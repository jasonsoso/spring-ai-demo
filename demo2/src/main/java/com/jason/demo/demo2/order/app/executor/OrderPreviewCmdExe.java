package com.jason.demo.demo2.order.app.executor;

import com.jason.demo.demo2.framework.auth.context.LoginContextHolder;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.order.app.vo.req.OrderLineReqVO;
import com.jason.demo.demo2.order.app.vo.req.OrderPreviewReqVO;
import com.jason.demo.demo2.order.app.vo.res.OrderPreviewLineResVO;
import com.jason.demo.demo2.order.app.vo.res.OrderPreviewResVO;
import com.jason.demo.demo2.order.config.OrderProperties;
import com.jason.demo.demo2.order.service.common.OrderItemsRules;
import com.jason.demo.demo2.order.service.infrastructure.redis.OrderPlaceTokenPayload;
import com.jason.demo.demo2.order.service.infrastructure.redis.OrderPlaceTokenStore;
import com.jason.demo.demo2.product.service.common.ProductErrorCodeEnum;
import com.jason.demo.demo2.product.service.core.ProductDomainService;
import com.jason.demo.demo2.product.service.core.ProductStockHotService;
import com.jason.demo.demo2.product.service.core.domain.Product;
import com.jason.demo.demo2.product.service.core.domain.ProductWithStock;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 预览：不落库、不占库存。签发 Redis placeToken，改 qty 必须重新 preview。
 */
@Service
public class OrderPreviewCmdExe {

    private final ProductDomainService productDomainService;
    private final ProductStockHotService productStockHotService;
    private final OrderPlaceTokenStore tokenStore;
    private final OrderProperties orderProperties;

    public OrderPreviewCmdExe(
            ProductDomainService productDomainService,
            ProductStockHotService productStockHotService,
            OrderPlaceTokenStore tokenStore,
            OrderProperties orderProperties) {
        this.productDomainService = productDomainService;
        this.productStockHotService = productStockHotService;
        this.tokenStore = tokenStore;
        this.orderProperties = orderProperties;
    }

    public OrderPreviewResVO execute(OrderPreviewReqVO req) {
        long memberId = LoginContextHolder.require().memberId();
        List<OrderLineReqVO> reqItems = req.getItems();
        List<Long> productIds = reqItems == null
                ? List.of()
                : reqItems.stream().map(OrderLineReqVO::getProductId).toList();
        OrderItemsRules.requireOneDistinctProduct(productIds);

        List<OrderPreviewLineResVO> lines = new ArrayList<>();
        List<OrderPlaceTokenPayload.Item> tokenItems = new ArrayList<>();
        BigDecimal amount = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);

        for (OrderLineReqVO reqLine : reqItems) {
            ProductWithStock row = productDomainService.requireOnShelf(reqLine.getProductId());
            Product product = row.getProduct();
            int available = productStockHotService.overlayAvail(reqLine.getProductId())
                    .orElse(row.getStock().getStock());
            if (available < reqLine.getQty()) {
                throw new BusinessException(ProductErrorCodeEnum.STOCK_INSUFFICIENT);
            }
            BigDecimal lineAmount = product.getSellPrice()
                    .multiply(BigDecimal.valueOf(reqLine.getQty()))
                    .setScale(2, RoundingMode.UNNECESSARY);
            amount = amount.add(lineAmount);

            OrderPreviewLineResVO line = new OrderPreviewLineResVO();
            line.setProductId(product.getProductId());
            line.setProductName(product.getProductName());
            line.setSubtitle(product.getSubtitle());
            line.setCoverUrl(product.getCoverUrl());
            line.setSellPrice(product.getSellPrice());
            line.setMarketPrice(product.getMarketPrice());
            line.setQty(reqLine.getQty());
            line.setLineAmount(lineAmount);
            line.setAvailableStock(available);
            lines.add(line);

            tokenItems.add(new OrderPlaceTokenPayload.Item(
                    product.getProductId(), reqLine.getQty(), product.getSellPrice()));
        }

        String placeToken = UUID.randomUUID().toString();
        tokenStore.savePreview(
                placeToken,
                new OrderPlaceTokenPayload(memberId, List.copyOf(tokenItems)),
                orderProperties.getPlaceTokenTtl());

        OrderPreviewResVO res = new OrderPreviewResVO();
        res.setPlaceToken(placeToken);
        res.setAmount(amount);
        res.setItems(lines);
        return res;
    }
}
