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

/**
 * COLA {@link StateMachine} 的装配与触发入口。
 *
 * <p>只负责「能不能转」：合法流转才进 Action；落库、CAS、热库存都在 Action 里。
 * 非法转移（无匹配 {@code from + event}）走 FailCallback，抛 {@code 30002}，不会落到 Action。
 *
 * <p>生产态由 {@link OrderStateMachineConfiguration} {@code build} 一次并交给本类持有。
 * 不要用 {@code StateMachineFactory.get(machineId)} 按同一 ID 重复注册。
 */
public class OrderStateMachineExecutor {

    /**
     * COLA 状态机实例 {@code StateMachine<S, E, C>}。
     * {@link StateMachine#fireEvent} 按 source + event 查转移表并执行对应 {@link com.alibaba.cola.statemachine.Action}。
     */
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

    /**
     * 声明四条外部转移并 {@link StateMachineBuilder#build} 出 {@link StateMachine}。
     * {@code machineId} 全局唯一；COLA 会按 ID 登记，同 ID 二次 build 会失败。
     */
    private static StateMachine<OrderStatusEnum, OrderEventEnum, OrderContext> buildMachine(
            String machineId,
            OrderPlaceAction placeAction,
            OrderPaySuccessAction payAction,
            OrderCancelAction cancelAction,
            OrderExpireAction expireAction) {
        StateMachineBuilder<OrderStatusEnum, OrderEventEnum, OrderContext> builder =
                StateMachineBuilderFactory.create();
        // from + on(event) → to + perform(Action)；同一 from 可挂多条 event
        // INIT → SUBMIT：下单、落库并预占库存
        builder.externalTransition()
                .from(OrderStatusEnum.INIT)
                .to(OrderStatusEnum.SUBMIT)
                .on(OrderEventEnum.SUBMIT_ORDER)
                .perform(placeAction);
        // SUBMIT → COMPLETED：支付成功，订单已完成
        builder.externalTransition()
                .from(OrderStatusEnum.SUBMIT)
                .to(OrderStatusEnum.COMPLETED)
                .on(OrderEventEnum.PAY_SUCCESS)
                .perform(payAction);
        // SUBMIT → CANCEL：手动取消订单
        builder.externalTransition()
                .from(OrderStatusEnum.SUBMIT)
                .to(OrderStatusEnum.CANCEL)
                .on(OrderEventEnum.CANCEL_ORDER)
                .perform(cancelAction);
        // SUBMIT → CANCEL：延时超时关单
        builder.externalTransition()
                .from(OrderStatusEnum.SUBMIT)
                .to(OrderStatusEnum.CANCEL)
                .on(OrderEventEnum.ORDER_EXPIRE)
                .perform(expireAction);

        // 无匹配转移（如已完成再支付）不进 Action，统一 30002
        builder.setFailCallback((from, event, ctx) -> {
            throw new BusinessException(OrderErrorCodeEnum.ORDER_STATUS_CONFLICT);
        });
        return builder.build(machineId);
    }

    /**
     * 委托 {@link StateMachine#fireEvent(Object, Object, Object)}。
     *
     * @param source  当前状态；下单尚无库行时传 {@link OrderStatusEnum#INIT}
     * @param event   要触发的事件
     * @param context Action 从这里取 {@link OrderContext#getOrder()}
     * @return 转移后的目标状态
     */
    public OrderStatusEnum fireEvent(OrderStatusEnum source, OrderEventEnum event, OrderContext context) {
        return stateMachine.fireEvent(source, event, context);
    }
}
