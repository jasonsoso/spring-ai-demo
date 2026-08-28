/**
 * 订单 COLA 状态机（{@code cola-component-statemachine} 5.0.0）。
 *
 * <p>核心类型 {@link com.alibaba.cola.statemachine.StateMachine}{@code <S, E, C>}：
 * <ul>
 *   <li>{@code S} = {@link com.jason.demo.demo2.order.service.common.OrderStatusEnum} 状态</li>
 *   <li>{@code E} = {@link com.jason.demo.demo2.order.service.common.OrderEventEnum} 事件</li>
 *   <li>{@code C} = {@link com.jason.demo.demo2.order.service.core.statemachine.OrderContext} 上下文</li>
 * </ul>
 *
 * <p>{@link com.alibaba.cola.statemachine.StateMachine#fireEvent} 用当前状态 + 事件匹配
 * {@code externalTransition}。命中则调用 {@link com.alibaba.cola.statemachine.Action#execute}
 * （from / to / event / context），返回目标态；未命中走 {@code FailCallback}。
 * 组件本身无 Spring 依赖，事务靠我们把 Action 做成 Spring Bean。
 */
package com.jason.demo.demo2.order.service.core.statemachine;
