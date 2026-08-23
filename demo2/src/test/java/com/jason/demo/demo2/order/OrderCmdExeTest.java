package com.jason.demo.demo2.order;

import com.jason.demo.demo2.framework.delay.DelayTaskService;
import com.jason.demo.demo2.framework.delay.DelayTaskType;
import com.jason.demo.demo2.framework.delay.config.DelayProperties;
import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import com.jason.demo.demo2.order.app.vo.OrderPlaceResult;
import com.jason.demo.demo2.order.app.executor.OrderCancelCmdExe;
import com.jason.demo.demo2.order.app.executor.OrderPaySuccessCmdExe;
import com.jason.demo.demo2.order.app.executor.OrderPlaceCmdExe;
import com.jason.demo.demo2.order.service.common.OrderStatus;
import com.jason.demo.demo2.order.service.core.OrderDomainService;
import com.jason.demo.demo2.order.service.core.domain.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderCmdExeTest {

    @Mock
    private OrderDomainService orderDomainService;
    @Mock
    private DelayTaskService delayTaskService;
    @Mock
    private SnowflakeIdGenerator idGenerator;

    private DelayProperties delayProperties;

    @BeforeEach
    void setUp() {
        delayProperties = new DelayProperties();
        delayProperties.setDefaultDelay(Duration.ofSeconds(30));
    }

    @Test
    void orderPlace_schedulesCancelTask() {
        when(idGenerator.nextId()).thenReturn(55L);
        when(delayTaskService.schedule(eq(DelayTaskType.ORDER_CANCEL), eq("55"), isNull(), any()))
                .thenReturn(77L);
        OrderPlaceCmdExe exe = new OrderPlaceCmdExe(orderDomainService, delayTaskService, idGenerator, delayProperties);

        OrderPlaceResult result = exe.execute(new BigDecimal("9.90"), Duration.ofSeconds(10));

        assertEquals(55L, result.getOrderId());
        assertEquals(77L, result.getTaskId());
        assertEquals(OrderStatus.PENDING_PAY.name(), result.getStatus());
        verify(orderDomainService).place(argThat(o -> o.getOrderId() == 55L
                && OrderStatus.PENDING_PAY.name().equals(o.getStatus())));
        verify(delayTaskService).schedule(DelayTaskType.ORDER_CANCEL, "55", null, Duration.ofSeconds(10));
    }

    @Test
    void pay_cancelsDelayTask() {
        Order paid = order(55L, OrderStatus.PAID);
        when(orderDomainService.requireOrder(55L)).thenReturn(paid);
        OrderPaySuccessCmdExe exe = new OrderPaySuccessCmdExe(orderDomainService, delayTaskService);

        Order result = exe.execute(55L);

        assertEquals(OrderStatus.PAID.name(), result.getStatus());
        verify(orderDomainService).payOrder(55L);
        verify(delayTaskService).cancelByBizKey(DelayTaskType.ORDER_CANCEL, "55");
    }

    @Test
    void cancel_cancelsDelayTask() {
        Order cancelled = order(55L, OrderStatus.CANCELLED);
        when(orderDomainService.requireOrder(55L)).thenReturn(cancelled);
        OrderCancelCmdExe exe = new OrderCancelCmdExe(orderDomainService, delayTaskService);

        Order result = exe.execute(55L);

        assertEquals(OrderStatus.CANCELLED.name(), result.getStatus());
        verify(orderDomainService).manualCancel(55L);
        verify(delayTaskService).cancelByBizKey(DelayTaskType.ORDER_CANCEL, "55");
    }

    private static Order order(long orderId, OrderStatus status) {
        Order order = new Order();
        order.setOrderId(orderId);
        order.setStatus(status.name());
        order.setAmount(new BigDecimal("9.90"));
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        return order;
    }
}
