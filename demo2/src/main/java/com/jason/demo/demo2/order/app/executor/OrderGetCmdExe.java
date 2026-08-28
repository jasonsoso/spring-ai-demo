package com.jason.demo.demo2.order.app.executor;

import com.jason.demo.demo2.framework.auth.context.LoginContextHolder;
import com.jason.demo.demo2.order.app.convert.OrderVoConvert;
import com.jason.demo.demo2.order.app.vo.res.GetOrderResVO;
import com.jason.demo.demo2.order.service.core.OrderDomainService;
import com.jason.demo.demo2.order.service.core.domain.Order;
import org.springframework.stereotype.Service;

/** 订单详情：主表 + 明细快照。 */
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
        Order order = orderDomainService.requireOrderWithItems(orderId, memberId);
        return orderVoConvert.toGetRes(order);
    }
}
