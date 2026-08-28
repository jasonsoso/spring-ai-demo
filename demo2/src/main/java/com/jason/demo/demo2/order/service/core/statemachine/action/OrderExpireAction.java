package com.jason.demo.demo2.order.service.core.statemachine.action;

import com.alibaba.cola.statemachine.Action;
import com.jason.demo.demo2.order.service.common.OrderEventEnum;
import com.jason.demo.demo2.order.service.common.OrderStatusEnum;
import com.jason.demo.demo2.order.service.common.PayStatusEnum;
import com.jason.demo.demo2.order.service.core.domain.Order;
import com.jason.demo.demo2.order.service.core.domain.OrderItem;
import com.jason.demo.demo2.order.service.core.statemachine.OrderContext;
import com.jason.demo.demo2.order.service.infrastructure.repository.OrderItemRepository;
import com.jason.demo.demo2.order.service.infrastructure.repository.OrderRepository;
import com.jason.demo.demo2.product.service.core.ProductStockHotService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class OrderExpireAction implements Action<OrderStatusEnum, OrderEventEnum, OrderContext> {

    private final OrderRepository orderRepository;
    private final ProductStockHotService productStockHotService;
    private final OrderItemRepository orderItemRepository;

    public OrderExpireAction(
            OrderRepository orderRepository,
            ProductStockHotService productStockHotService,
            OrderItemRepository orderItemRepository) {
        this.orderRepository = orderRepository;
        this.productStockHotService = productStockHotService;
        this.orderItemRepository = orderItemRepository;
    }

    @Override
    @Transactional
    public void execute(OrderStatusEnum from, OrderStatusEnum to, OrderEventEnum event, OrderContext ctx) {
        Order order = ctx.getOrder();
        LocalDateTime now = LocalDateTime.now();
        applyTransition(to, order, now);
        if (!orderRepository.markCancelled(order.getOrderId(), null, now)) {
            return;
        }
        for (OrderItem item : itemsOf(ctx)) {
            productStockHotService.release(item.getProductId(), order.getOrderId());
        }
    }

    private void applyTransition(OrderStatusEnum to, Order order, LocalDateTime now) {
        order.setOrderStatus(to.name());
        order.setPayStatus(PayStatusEnum.CLOSE.name());
        order.setCancelTime(now);
    }

    private List<OrderItem> itemsOf(OrderContext ctx) {
        Order order = ctx.getOrder();
        List<OrderItem> items = order.getItems();
        if (items == null || items.isEmpty()) {
            items = orderItemRepository.listByOrderId(order.getOrderId());
            order.setItems(items);
        }
        return items;
    }
}
