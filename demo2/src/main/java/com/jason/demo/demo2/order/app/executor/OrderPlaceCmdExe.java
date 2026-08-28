package com.jason.demo.demo2.order.app.executor;

import com.jason.demo.demo2.framework.auth.context.LoginContextHolder;
import com.jason.demo.demo2.framework.delay.DelayTaskService;
import com.jason.demo.demo2.framework.delay.DelayTaskType;
import com.jason.demo.demo2.framework.delay.config.DelayProperties;
import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.framework.web.exception.CommonErrorCodeEnum;
import com.jason.demo.demo2.order.app.vo.req.OrderLineReqVO;
import com.jason.demo.demo2.order.app.vo.req.OrderPlaceReqVO;
import com.jason.demo.demo2.order.app.vo.res.OrderPlaceResVO;
import com.jason.demo.demo2.order.service.common.OrderErrorCodeEnum;
import com.jason.demo.demo2.order.service.common.OrderEventEnum;
import com.jason.demo.demo2.order.service.common.OrderItemsRules;
import com.jason.demo.demo2.order.service.common.OrderStatusEnum;
import com.jason.demo.demo2.order.service.core.OrderDomainService;
import com.jason.demo.demo2.order.service.core.domain.Order;
import com.jason.demo.demo2.order.service.core.domain.OrderItem;
import com.jason.demo.demo2.order.service.core.statemachine.OrderContext;
import com.jason.demo.demo2.order.service.core.statemachine.OrderStateMachineExecutor;
import com.jason.demo.demo2.order.service.infrastructure.redis.OrderPlaceTokenPayload;
import com.jason.demo.demo2.order.service.infrastructure.redis.OrderPlaceTokenStore;
import com.jason.demo.demo2.product.service.common.ProductErrorCodeEnum;
import com.jason.demo.demo2.product.service.core.ProductDomainService;
import com.jason.demo.demo2.product.service.core.ProductStockHotService;
import com.jason.demo.demo2.product.service.core.domain.Product;
import com.jason.demo.demo2.product.service.core.domain.ProductWithStock;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class OrderPlaceCmdExe {

    private final OrderPlaceTokenStore tokenStore;
    private final ProductDomainService productDomainService;
    private final ProductStockHotService hotService;
    private final OrderStateMachineExecutor executor;
    private final DelayTaskService delayTaskService;
    private final SnowflakeIdGenerator idGenerator;
    private final DelayProperties delayProperties;
    private final OrderDomainService orderDomainService;

    public OrderPlaceCmdExe(
            OrderPlaceTokenStore tokenStore,
            ProductDomainService productDomainService,
            ProductStockHotService hotService,
            OrderStateMachineExecutor executor,
            DelayTaskService delayTaskService,
            SnowflakeIdGenerator idGenerator,
            DelayProperties delayProperties,
            OrderDomainService orderDomainService) {
        this.tokenStore = tokenStore;
        this.productDomainService = productDomainService;
        this.hotService = hotService;
        this.executor = executor;
        this.delayTaskService = delayTaskService;
        this.idGenerator = idGenerator;
        this.delayProperties = delayProperties;
        this.orderDomainService = orderDomainService;
    }

    public OrderPlaceResVO execute(OrderPlaceReqVO req, Duration delay) {
        long memberId = LoginContextHolder.require().memberId();
        List<OrderLineReqVO> reqItems = req.getItems();
        List<Long> productIds = reqItems == null
                ? List.of()
                : reqItems.stream().map(OrderLineReqVO::getProductId).toList();
        OrderItemsRules.requireOneDistinctProduct(productIds);
        for (OrderLineReqVO line : reqItems) {
            if (line.getSellPrice() == null) {
                throw new BusinessException(CommonErrorCodeEnum.PARAM_MISSING);
            }
        }

        String token = req.getPlaceToken();
        Optional<OrderPlaceTokenPayload> preview = tokenStore.getPreview(token);
        if (preview.isEmpty() || !matchesPreview(preview.get(), memberId, reqItems)) {
            throw new BusinessException(OrderErrorCodeEnum.PLACE_TOKEN_INVALID);
        }

        Duration effectiveDelay = delay == null ? delayProperties.getDefaultDelay() : delay;
        if (!tokenStore.tryLock(token, Duration.ofSeconds(30))) {
            Optional<Long> raced = tokenStore.getResult(token);
            if (raced.isPresent()) {
                return toRes(orderDomainService.requireOrderWithItems(raced.get(), memberId), null, effectiveDelay);
            }
            throw new BusinessException(CommonErrorCodeEnum.INTERNAL_ERROR);
        }
        try {
            Optional<Long> existing = tokenStore.getResult(token);
            if (existing.isPresent()) {
                return toRes(orderDomainService.requireOrderWithItems(existing.get(), memberId), null, effectiveDelay);
            }

            long orderId = idGenerator.nextId();
            List<OrderItem> items = new ArrayList<>();
            for (OrderLineReqVO reqLine : reqItems) {
                ProductWithStock row = productDomainService.requireOnShelf(reqLine.getProductId());
                Product product = row.getProduct();
                int available = hotService.overlayAvail(reqLine.getProductId())
                        .orElse(row.getStock().getStock());
                if (available < reqLine.getQty()) {
                    throw new BusinessException(ProductErrorCodeEnum.STOCK_INSUFFICIENT);
                }
                if (product.getSellPrice().compareTo(reqLine.getSellPrice()) != 0) {
                    throw new BusinessException(OrderErrorCodeEnum.PRICE_CHANGED);
                }
                items.add(OrderItem.create(
                        idGenerator.nextId(),
                        orderId,
                        memberId,
                        product.getProductId(),
                        product.getProductName(),
                        product.getSubtitle(),
                        product.getCoverUrl(),
                        product.getSellPrice(),
                        product.getMarketPrice(),
                        reqLine.getQty()));
            }

            Order order = Order.create(orderId, memberId, items, LocalDateTime.now());
            OrderContext ctx = new OrderContext();
            ctx.setOrder(order);
            executor.fireEvent(OrderStatusEnum.INIT, OrderEventEnum.SUBMIT_ORDER, ctx);

            tokenStore.saveResult(token, orderId, Duration.ofHours(24));
            long taskId = delayTaskService.schedule(
                    DelayTaskType.ORDER_CANCEL,
                    String.valueOf(orderId),
                    null,
                    effectiveDelay);
            return toRes(order, taskId, effectiveDelay);
        } finally {
            tokenStore.unlock(token);
        }
    }

    private static boolean matchesPreview(
            OrderPlaceTokenPayload preview, long memberId, List<OrderLineReqVO> reqItems) {
        if (preview.memberId() == null || preview.memberId() != memberId) {
            return false;
        }
        List<OrderPlaceTokenPayload.Item> previewItems = preview.items();
        if (previewItems == null || previewItems.size() != reqItems.size()) {
            return false;
        }
        for (int i = 0; i < reqItems.size(); i++) {
            OrderLineReqVO reqLine = reqItems.get(i);
            OrderPlaceTokenPayload.Item previewLine = previewItems.get(i);
            if (!Objects.equals(previewLine.productId(), reqLine.getProductId())
                    || !Objects.equals(previewLine.qty(), reqLine.getQty())
                    || previewLine.sellPrice() == null
                    || previewLine.sellPrice().compareTo(reqLine.getSellPrice()) != 0) {
                return false;
            }
        }
        return true;
    }

    private static OrderPlaceResVO toRes(Order order, Long taskId, Duration effectiveDelay) {
        OrderPlaceResVO res = new OrderPlaceResVO();
        res.setOrderId(order.getOrderId());
        res.setOrderStatus(order.getOrderStatus());
        res.setPayStatus(order.getPayStatus());
        res.setAmount(order.getAmount());
        res.setTaskId(taskId);
        res.setDelay(effectiveDelay.toString());
        return res;
    }
}
