package com.jason.demo.demo2.order;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.order.service.common.OrderErrorCodeEnum;
import com.jason.demo.demo2.order.service.common.OrderEventEnum;
import com.jason.demo.demo2.order.service.common.OrderStatusEnum;
import com.jason.demo.demo2.order.service.common.PayStatusEnum;
import com.jason.demo.demo2.order.service.core.domain.Order;
import com.jason.demo.demo2.order.service.core.statemachine.OrderContext;
import com.jason.demo.demo2.order.service.core.statemachine.OrderStateMachineExecutor;
import com.jason.demo.demo2.order.service.core.statemachine.action.OrderCancelAction;
import com.jason.demo.demo2.order.service.core.statemachine.action.OrderExpireAction;
import com.jason.demo.demo2.order.service.core.statemachine.action.OrderPaySuccessAction;
import com.jason.demo.demo2.order.service.core.statemachine.action.OrderPlaceAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderStateMachineExecutorTest {

    private OrderStateMachineExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new OrderStateMachineExecutor(
                new OrderPlaceAction(null, null, null),
                new OrderPaySuccessAction(null, null),
                new OrderCancelAction(null, null),
                new OrderExpireAction(null, null));
    }

    @Test
    void submitThenPay_reachesCompleted() {
        Order order = Order.create(1L, 9L, new BigDecimal("18.00"), LocalDateTime.now());
        OrderContext ctx = new OrderContext();
        ctx.setOrder(order);
        assertEquals(OrderStatusEnum.SUBMIT, executor.fireEvent(OrderStatusEnum.INIT, OrderEventEnum.SUBMIT_ORDER, ctx));
        assertEquals(OrderStatusEnum.SUBMIT.name(), order.getOrderStatus());
        assertEquals(PayStatusEnum.WAIT_PAY.name(), order.getPayStatus());
        assertEquals(OrderStatusEnum.COMPLETED, executor.fireEvent(OrderStatusEnum.SUBMIT, OrderEventEnum.PAY_SUCCESS, ctx));
        assertEquals(PayStatusEnum.PAY_SUCCESS.name(), order.getPayStatus());
    }

    @Test
    void submitThenCancel_andExpire_reachCancel() {
        Order order = Order.create(2L, 9L, new BigDecimal("18.00"), LocalDateTime.now());
        OrderContext ctx = new OrderContext();
        ctx.setOrder(order);
        executor.fireEvent(OrderStatusEnum.INIT, OrderEventEnum.SUBMIT_ORDER, ctx);
        assertEquals(OrderStatusEnum.CANCEL, executor.fireEvent(OrderStatusEnum.SUBMIT, OrderEventEnum.CANCEL_ORDER, ctx));
        Order order2 = Order.create(3L, 9L, new BigDecimal("18.00"), LocalDateTime.now());
        OrderContext ctx2 = new OrderContext();
        ctx2.setOrder(order2);
        executor.fireEvent(OrderStatusEnum.INIT, OrderEventEnum.SUBMIT_ORDER, ctx2);
        assertEquals(OrderStatusEnum.CANCEL, executor.fireEvent(OrderStatusEnum.SUBMIT, OrderEventEnum.ORDER_EXPIRE, ctx2));
        assertEquals(PayStatusEnum.CLOSE.name(), order2.getPayStatus());
    }

    @Test
    void payFromCompleted_throwsConflict() {
        Order order = Order.create(4L, 9L, new BigDecimal("18.00"), LocalDateTime.now());
        OrderContext ctx = new OrderContext();
        ctx.setOrder(order);
        executor.fireEvent(OrderStatusEnum.INIT, OrderEventEnum.SUBMIT_ORDER, ctx);
        executor.fireEvent(OrderStatusEnum.SUBMIT, OrderEventEnum.PAY_SUCCESS, ctx);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> executor.fireEvent(OrderStatusEnum.COMPLETED, OrderEventEnum.PAY_SUCCESS, ctx));
        assertEquals(OrderErrorCodeEnum.ORDER_STATUS_CONFLICT.getCode(), ex.getCode());
    }
}
