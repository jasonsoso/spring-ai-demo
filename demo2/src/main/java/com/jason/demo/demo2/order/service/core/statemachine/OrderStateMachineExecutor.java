package com.jason.demo.demo2.order.service.core.statemachine;

import com.alibaba.cola.statemachine.StateMachine;
import com.alibaba.cola.statemachine.builder.StateMachineBuilder;
import com.alibaba.cola.statemachine.builder.StateMachineBuilderFactory;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.order.service.common.OrderErrorCodeEnum;
import com.jason.demo.demo2.order.service.common.OrderEventEnum;
import com.jason.demo.demo2.order.service.common.OrderStatusEnum;
import com.jason.demo.demo2.order.service.core.statemachine.action.OrderCancelAction;
import com.jason.demo.demo2.order.service.core.statemachine.action.OrderExpireAction;
import com.jason.demo.demo2.order.service.core.statemachine.action.OrderPaySuccessAction;
import com.jason.demo.demo2.order.service.core.statemachine.action.OrderPlaceAction;

import java.util.UUID;

public class OrderStateMachineExecutor {

    private final StateMachine<OrderStatusEnum, OrderEventEnum, OrderContext> stateMachine;

    public OrderStateMachineExecutor(StateMachine<OrderStatusEnum, OrderEventEnum, OrderContext> stateMachine) {
        this.stateMachine = stateMachine;
    }

    public OrderStateMachineExecutor(
            OrderPlaceAction placeAction,
            OrderPaySuccessAction payAction,
            OrderCancelAction cancelAction,
            OrderExpireAction expireAction) {
        this(buildMachine("order-ut-" + UUID.randomUUID(), placeAction, payAction, cancelAction, expireAction));
    }

    public static OrderStateMachineExecutor build(
            String machineId,
            OrderPlaceAction placeAction,
            OrderPaySuccessAction payAction,
            OrderCancelAction cancelAction,
            OrderExpireAction expireAction) {
        return new OrderStateMachineExecutor(
                buildMachine(machineId, placeAction, payAction, cancelAction, expireAction));
    }

    private static StateMachine<OrderStatusEnum, OrderEventEnum, OrderContext> buildMachine(
            String machineId,
            OrderPlaceAction placeAction,
            OrderPaySuccessAction payAction,
            OrderCancelAction cancelAction,
            OrderExpireAction expireAction) {
        StateMachineBuilder<OrderStatusEnum, OrderEventEnum, OrderContext> builder =
                StateMachineBuilderFactory.create();
        builder.externalTransition()
                .from(OrderStatusEnum.INIT)
                .to(OrderStatusEnum.SUBMIT)
                .on(OrderEventEnum.SUBMIT_ORDER)
                .perform(placeAction);
        builder.externalTransition()
                .from(OrderStatusEnum.SUBMIT)
                .to(OrderStatusEnum.COMPLETED)
                .on(OrderEventEnum.PAY_SUCCESS)
                .perform(payAction);
        builder.externalTransition()
                .from(OrderStatusEnum.SUBMIT)
                .to(OrderStatusEnum.CANCEL)
                .on(OrderEventEnum.CANCEL_ORDER)
                .perform(cancelAction);
        builder.externalTransition()
                .from(OrderStatusEnum.SUBMIT)
                .to(OrderStatusEnum.CANCEL)
                .on(OrderEventEnum.ORDER_EXPIRE)
                .perform(expireAction);
        builder.setFailCallback((from, event, ctx) -> {
            throw new BusinessException(OrderErrorCodeEnum.ORDER_STATUS_CONFLICT);
        });
        return builder.build(machineId);
    }

    public OrderStatusEnum fireEvent(OrderStatusEnum source, OrderEventEnum event, OrderContext context) {
        return stateMachine.fireEvent(source, event, context);
    }
}
