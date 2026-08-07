package com.jason.demo.demo2.order;

import com.jason.demo.demo2.framework.delay.DelayTaskService;
import com.jason.demo.demo2.framework.delay.DelayTaskType;
import com.jason.demo.demo2.framework.delay.config.DelayProperties;
import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import com.jason.demo.demo2.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private DelayTaskService delayTaskService;
    @Mock
    private SnowflakeIdGenerator idGenerator;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        DelayProperties properties = new DelayProperties();
        properties.setDefaultDelay(Duration.ofSeconds(30));
        orderService = new OrderService(orderRepository, delayTaskService, idGenerator, properties);
    }

    @Test
    void create_schedulesCancelTask() {
        when(idGenerator.nextId()).thenReturn(55L);
        when(delayTaskService.schedule(eq(DelayTaskType.ORDER_CANCEL), eq("55"), isNull(), any()))
                .thenReturn(77L);

        Map<String, Object> result = orderService.create(new BigDecimal("9.90"), Duration.ofSeconds(10));

        assertEquals(55L, result.get("orderId"));
        assertEquals(77L, result.get("taskId"));
        verify(orderRepository).insert(argThat(o -> o.getOrderId() == 55L
                && OrderStatus.PENDING_PAY.name().equals(o.getStatus())));
        verify(delayTaskService).schedule(DelayTaskType.ORDER_CANCEL, "55", null, Duration.ofSeconds(10));
    }

    @Test
    void pay_cancelsDelayTask() {
        when(orderRepository.markPaid(55L)).thenReturn(true);

        Map<String, Object> result = orderService.pay(55L);

        assertEquals(OrderStatus.PAID.name(), result.get("status"));
        assertEquals(55L, result.get("orderId"));
        verify(delayTaskService).cancelByBizKey(DelayTaskType.ORDER_CANCEL, "55");
    }
}
