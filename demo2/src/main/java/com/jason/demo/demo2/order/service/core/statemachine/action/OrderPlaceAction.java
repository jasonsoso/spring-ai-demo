package com.jason.demo.demo2.order.service.core.statemachine.action;

import com.alibaba.cola.statemachine.Action;
import com.jason.demo.demo2.order.service.common.OrderEventEnum;
import com.jason.demo.demo2.order.service.common.OrderStatusEnum;
import com.jason.demo.demo2.order.service.common.PayStatusEnum;
import com.jason.demo.demo2.order.service.core.domain.Order;
import com.jason.demo.demo2.order.service.core.domain.OrderItem;
import com.jason.demo.demo2.order.service.core.statemachine.OrderContext;
import com.jason.demo.demo2.order.service.infrastructure.repository.OrderRepository;
import com.jason.demo.demo2.product.service.core.ProductStockHotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

/**
 * INIT → SUBMIT：先写主表+明细，再逐行 {@code reserve}。
 * Redis 热库存不在 JDBC 事务里，本地回滚时按已预占商品补偿 {@code release}。
 */
@Slf4j
@Component
public class OrderPlaceAction implements Action<OrderStatusEnum, OrderEventEnum, OrderContext> {

    private final OrderRepository orderRepository;
    private final ProductStockHotService productStockHotService;

    public OrderPlaceAction(OrderRepository orderRepository, ProductStockHotService productStockHotService) {
        this.orderRepository = orderRepository;
        this.productStockHotService = productStockHotService;
    }

    @Override
    @Transactional
    public void execute(OrderStatusEnum from, OrderStatusEnum to, OrderEventEnum event, OrderContext ctx) {
        applyTransition(to, event, ctx);
        orderRepository.insertWithItems(ctx.getOrder());
        List<Long> reserved = new ArrayList<>();
        // reserve 成功后若 insert 之后的步骤失败，JDBC 回滚订单行，Redis 票需补偿释放
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        for (Long productId : reserved) {
                            try {
                                productStockHotService.release(productId, ctx.getOrder().getOrderId());
                            } catch (RuntimeException ex) {
                                log.warn("compensate release failed, orderId={}, productId={}",
                                        ctx.getOrder().getOrderId(), productId, ex);
                            }
                        }
                    }
                }
            });
        }
        for (OrderItem item : ctx.getOrder().getItems()) {
            productStockHotService.reserve(item.getProductId(), ctx.getOrder().getOrderId(), item.getQty());
            reserved.add(item.getProductId());
        }
    }

    private void applyTransition(OrderStatusEnum to, OrderEventEnum event, OrderContext ctx) {
        Order order = ctx.getOrder();
        order.setOrderStatus(to.name());
        order.setPayStatus(PayStatusEnum.WAIT_PAY.name());
    }
}
