package com.jason.demo.demo2.order.app.executor;

import com.jason.demo.demo2.order.service.core.OrderDomainService;
import com.jason.demo.demo2.order.service.core.domain.Order;
import org.springframework.stereotype.Service;

@Service
public class OrderGetCmdExe {

    private final OrderDomainService orderDomainService;

    public OrderGetCmdExe(OrderDomainService orderDomainService) {
        this.orderDomainService = orderDomainService;
    }

    public Order execute(long orderId) {
        return orderDomainService.requireOrder(orderId);
    }
}
