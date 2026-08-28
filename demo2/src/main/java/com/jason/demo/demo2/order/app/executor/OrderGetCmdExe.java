package com.jason.demo.demo2.order.app.executor;

import com.jason.demo.demo2.framework.auth.context.LoginContextHolder;
import com.jason.demo.demo2.order.app.convert.OrderVoConvert;
import com.jason.demo.demo2.order.app.vo.res.GetOrderResVO;
import com.jason.demo.demo2.order.service.core.OrderDomainService;
import com.jason.demo.demo2.order.service.core.domain.Order;
import com.jason.demo.demo2.order.service.infrastructure.repository.OrderItemRepository;
import org.springframework.stereotype.Service;

@Service
public class OrderGetCmdExe {

    private final OrderDomainService orderDomainService;
    private final OrderItemRepository orderItemRepository;
    private final OrderVoConvert orderVoConvert;

    public OrderGetCmdExe(
            OrderDomainService orderDomainService,
            OrderItemRepository orderItemRepository,
            OrderVoConvert orderVoConvert) {
        this.orderDomainService = orderDomainService;
        this.orderItemRepository = orderItemRepository;
        this.orderVoConvert = orderVoConvert;
    }

    public GetOrderResVO execute(long orderId) {
        long memberId = LoginContextHolder.require().memberId();
        Order order = orderDomainService.requireOrder(orderId, memberId);
        order.setItems(orderItemRepository.listByOrderId(orderId));
        return orderVoConvert.toGetRes(order);
    }
}
