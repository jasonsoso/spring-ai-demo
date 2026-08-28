package com.jason.demo.demo2.order.app.executor;

import com.jason.demo.demo2.framework.auth.context.LoginContextHolder;
import com.jason.demo.demo2.framework.delay.DelayTaskService;
import com.jason.demo.demo2.framework.delay.DelayTaskType;
import com.jason.demo.demo2.order.app.convert.OrderVoConvert;
import com.jason.demo.demo2.order.app.vo.res.PayOrderResVO;
import com.jason.demo.demo2.order.service.common.OrderEventEnum;
import com.jason.demo.demo2.order.service.common.OrderStatusEnum;
import com.jason.demo.demo2.order.service.core.OrderDomainService;
import com.jason.demo.demo2.order.service.core.domain.Order;
import com.jason.demo.demo2.order.service.core.statemachine.OrderContext;
import com.jason.demo.demo2.order.service.core.statemachine.OrderStateMachineExecutor;
import org.springframework.stereotype.Service;

/** 模拟支付成功。fireEvent 成功后再撤延时关单，避免回滚后留下幽灵任务。 */
@Service
public class OrderPaySuccessCmdExe {

    private final OrderDomainService orderDomainService;
    private final OrderStateMachineExecutor executor;
    private final DelayTaskService delayTaskService;
    private final OrderVoConvert orderVoConvert;

    public OrderPaySuccessCmdExe(
            OrderDomainService orderDomainService,
            OrderStateMachineExecutor executor,
            DelayTaskService delayTaskService,
            OrderVoConvert orderVoConvert) {
        this.orderDomainService = orderDomainService;
        this.executor = executor;
        this.delayTaskService = delayTaskService;
        this.orderVoConvert = orderVoConvert;
    }

    public PayOrderResVO execute(long orderId) {
        long memberId = LoginContextHolder.require().memberId();
        Order order = orderDomainService.requireOrder(orderId, memberId);
        OrderContext ctx = new OrderContext();
        ctx.setOrder(order);
        executor.fireEvent(OrderStatusEnum.valueOf(order.getOrderStatus()), OrderEventEnum.PAY_SUCCESS, ctx);
        delayTaskService.cancelByBizKey(DelayTaskType.ORDER_CANCEL, String.valueOf(orderId));
        return orderVoConvert.toPayRes(order);
    }
}
