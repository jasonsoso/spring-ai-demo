package com.jason.demo.demo2.order.app.executor;

import com.jason.demo.demo2.framework.auth.context.LoginContextHolder;
import com.jason.demo.demo2.framework.delay.DelayTaskService;
import com.jason.demo.demo2.framework.delay.DelayTaskType;
import com.jason.demo.demo2.order.app.convert.OrderVoConvert;
import com.jason.demo.demo2.order.app.vo.res.GetOrderResVO;
import com.jason.demo.demo2.order.service.common.OrderStatusEnum;
import com.jason.demo.demo2.order.service.core.OrderDomainService;
import com.jason.demo.demo2.order.service.core.domain.Order;
import org.springframework.stereotype.Service;

/** 订单详情：主表 + 明细快照；待支付时附关单截止时间。 */
@Service
public class OrderGetCmdExe {

    private final OrderDomainService orderDomainService;
    private final OrderVoConvert orderVoConvert;
    private final DelayTaskService delayTaskService;

    public OrderGetCmdExe(
            OrderDomainService orderDomainService,
            OrderVoConvert orderVoConvert,
            DelayTaskService delayTaskService) {
        this.orderDomainService = orderDomainService;
        this.orderVoConvert = orderVoConvert;
        this.delayTaskService = delayTaskService;
    }

    public GetOrderResVO execute(long orderId) {
        long memberId = LoginContextHolder.require().memberId();
        Order order = orderDomainService.requireOrderWithItems(orderId, memberId);
        GetOrderResVO vo = orderVoConvert.toGetRes(order);
        if (OrderStatusEnum.SUBMIT.name().equals(order.getOrderStatus())) {
            delayTaskService.findPendingExecuteAt(DelayTaskType.ORDER_CANCEL, String.valueOf(orderId))
                    .ifPresent(vo::setPayDeadline);
        }
        return vo;
    }
}
