package com.jason.demo.demo2.order.app.executor;

import com.jason.demo.demo2.order.service.common.OrderEventEnum;
import com.jason.demo.demo2.order.service.common.OrderStatusEnum;
import com.jason.demo.demo2.order.service.core.OrderDomainService;
import com.jason.demo.demo2.order.service.core.domain.Order;
import com.jason.demo.demo2.order.service.core.statemachine.OrderContext;
import com.jason.demo.demo2.order.service.core.statemachine.OrderStateMachineExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class OrderExpireCmdExe {

    private final OrderDomainService orderDomainService;
    private final OrderStateMachineExecutor executor;

    public OrderExpireCmdExe(OrderDomainService orderDomainService, OrderStateMachineExecutor executor) {
        this.orderDomainService = orderDomainService;
        this.executor = executor;
    }

    public void execute(long orderId) {
        Order order = orderDomainService.findById(orderId).orElse(null);
        if (order == null || !OrderStatusEnum.SUBMIT.name().equals(order.getOrderStatus())) {
            log.info("skip expire cancel, orderId={}, status={}", orderId, order == null ? null : order.getOrderStatus());
            return;
        }
        OrderContext ctx = new OrderContext();
        ctx.setOrder(orderDomainService.requireOrderWithItems(orderId));
        executor.fireEvent(OrderStatusEnum.SUBMIT, OrderEventEnum.ORDER_EXPIRE, ctx);
    }
}
