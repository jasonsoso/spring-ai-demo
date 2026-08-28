package com.jason.demo.demo2.order;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.order.service.common.OrderErrorCodeEnum;
import com.jason.demo.demo2.order.service.common.OrderEventEnum;
import com.jason.demo.demo2.order.service.common.OrderStatusEnum;
import com.jason.demo.demo2.order.service.common.PayStatusEnum;
import com.jason.demo.demo2.order.service.core.domain.Order;
import com.jason.demo.demo2.order.service.core.domain.OrderItem;
import com.jason.demo.demo2.order.service.core.statemachine.OrderContext;
import com.jason.demo.demo2.order.service.core.statemachine.OrderStateMachineExecutor;
import com.jason.demo.demo2.order.service.core.statemachine.action.OrderCancelAction;
import com.jason.demo.demo2.order.service.core.statemachine.action.OrderExpireAction;
import com.jason.demo.demo2.order.service.core.statemachine.action.OrderPaySuccessAction;
import com.jason.demo.demo2.order.service.core.statemachine.action.OrderPlaceAction;
import com.jason.demo.demo2.order.service.infrastructure.repository.OrderRepository;
import com.jason.demo.demo2.product.service.core.ProductStockHotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class OrderStateMachineExecutorTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProductStockHotService productStockHotService;

    private OrderStateMachineExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new OrderStateMachineExecutor(
                new OrderPlaceAction(orderRepository, productStockHotService),
                new OrderPaySuccessAction(null, null),
                new OrderCancelAction(null, null),
                new OrderExpireAction(null, null));
    }

    @Test
    void submitThenPay_reachesCompleted() {
        Order order = sampleOrder(1L);
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
        Order order = sampleOrder(2L);
        OrderContext ctx = new OrderContext();
        ctx.setOrder(order);
        executor.fireEvent(OrderStatusEnum.INIT, OrderEventEnum.SUBMIT_ORDER, ctx);
        assertEquals(OrderStatusEnum.CANCEL, executor.fireEvent(OrderStatusEnum.SUBMIT, OrderEventEnum.CANCEL_ORDER, ctx));
        Order order2 = sampleOrder(3L);
        OrderContext ctx2 = new OrderContext();
        ctx2.setOrder(order2);
        executor.fireEvent(OrderStatusEnum.INIT, OrderEventEnum.SUBMIT_ORDER, ctx2);
        assertEquals(OrderStatusEnum.CANCEL, executor.fireEvent(OrderStatusEnum.SUBMIT, OrderEventEnum.ORDER_EXPIRE, ctx2));
        assertEquals(PayStatusEnum.CLOSE.name(), order2.getPayStatus());
    }

    @Test
    void payFromCompleted_throwsConflict() {
        Order order = sampleOrder(4L);
        OrderContext ctx = new OrderContext();
        ctx.setOrder(order);
        executor.fireEvent(OrderStatusEnum.INIT, OrderEventEnum.SUBMIT_ORDER, ctx);
        executor.fireEvent(OrderStatusEnum.SUBMIT, OrderEventEnum.PAY_SUCCESS, ctx);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> executor.fireEvent(OrderStatusEnum.COMPLETED, OrderEventEnum.PAY_SUCCESS, ctx));
        assertEquals(OrderErrorCodeEnum.ORDER_STATUS_CONFLICT.getCode(), ex.getCode());
    }

    private static Order sampleOrder(long orderId) {
        OrderItem item = OrderItem.create(
                orderId + 100,
                orderId,
                9L,
                1L,
                "拿铁",
                "x",
                null,
                new BigDecimal("18.00"),
                new BigDecimal("20.00"),
                1);
        return Order.create(orderId, 9L, List.of(item), LocalDateTime.now());
    }
}
