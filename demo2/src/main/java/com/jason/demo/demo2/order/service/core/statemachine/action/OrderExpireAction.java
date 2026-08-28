package com.jason.demo.demo2.order.service.core.statemachine.action;

import com.alibaba.cola.statemachine.Action;
import com.jason.demo.demo2.order.service.common.OrderEventEnum;
import com.jason.demo.demo2.order.service.common.OrderStatusEnum;
import com.jason.demo.demo2.order.service.common.PayStatusEnum;
import com.jason.demo.demo2.order.service.core.domain.Order;
import com.jason.demo.demo2.order.service.core.statemachine.OrderContext;
import com.jason.demo.demo2.order.service.infrastructure.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
public class OrderExpireAction implements Action<OrderStatusEnum, OrderEventEnum, OrderContext> {

    @SuppressWarnings("unused")
    private final OrderRepository orderRepository;

    @Autowired
    public OrderExpireAction(OrderRepository orderRepository) {
        this(orderRepository, null);
    }

    public OrderExpireAction(OrderRepository orderRepository, Object productStockHotService) {
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional
    public void execute(OrderStatusEnum from, OrderStatusEnum to, OrderEventEnum event, OrderContext ctx) {
        applyTransition(to, event, ctx);
    }

    private void applyTransition(OrderStatusEnum to, OrderEventEnum event, OrderContext ctx) {
        Order order = ctx.getOrder();
        order.setOrderStatus(to.name());
        order.setPayStatus(PayStatusEnum.CLOSE.name());
        order.setCancelTime(LocalDateTime.now());
    }
}
