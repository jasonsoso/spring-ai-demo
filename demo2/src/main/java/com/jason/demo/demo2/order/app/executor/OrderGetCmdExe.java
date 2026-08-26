package com.jason.demo.demo2.order.app.executor;

import com.jason.demo.demo2.framework.auth.context.LoginContextHolder;
import com.jason.demo.demo2.order.app.convert.OrderVoConvert;
import com.jason.demo.demo2.order.app.vo.res.GetOrderResVO;
import com.jason.demo.demo2.order.service.core.OrderDomainService;
import org.springframework.stereotype.Service;

@Service
public class OrderGetCmdExe {

    private final OrderDomainService orderDomainService;
    private final OrderVoConvert orderVoConvert;

    public OrderGetCmdExe(OrderDomainService orderDomainService, OrderVoConvert orderVoConvert) {
        this.orderDomainService = orderDomainService;
        this.orderVoConvert = orderVoConvert;
    }

    public GetOrderResVO execute(long orderId) {
        long memberId = LoginContextHolder.require().memberId();
        return orderVoConvert.toGetRes(orderDomainService.requireOrder(orderId, memberId));
    }
}
