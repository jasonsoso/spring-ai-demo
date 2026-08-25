package com.jason.demo.demo2.order.app.executor;

import com.jason.demo.demo2.framework.delay.DelayTaskService;
import com.jason.demo.demo2.framework.delay.DelayTaskType;
import com.jason.demo.demo2.framework.auth.context.LoginContextHolder;
import com.jason.demo.demo2.order.service.core.OrderDomainService;
import com.jason.demo.demo2.order.service.core.domain.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderCancelCmdExe {

    private final OrderDomainService orderDomainService;
    private final DelayTaskService delayTaskService;

    public OrderCancelCmdExe(OrderDomainService orderDomainService, DelayTaskService delayTaskService) {
        this.orderDomainService = orderDomainService;
        this.delayTaskService = delayTaskService;
    }

    @Transactional
    public Order execute(long orderId) {
        long memberId = LoginContextHolder.require().memberId();
        orderDomainService.manualCancel(orderId, memberId);
        delayTaskService.cancelByBizKey(DelayTaskType.ORDER_CANCEL, String.valueOf(orderId));
        return orderDomainService.requireOrder(orderId, memberId);
    }
}
