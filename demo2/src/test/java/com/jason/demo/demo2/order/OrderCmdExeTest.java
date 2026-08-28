package com.jason.demo.demo2.order;

import com.jason.demo.demo2.framework.auth.context.LoginContextHolder;
import com.jason.demo.demo2.framework.auth.context.LoginPrincipal;
import com.jason.demo.demo2.framework.delay.DelayTaskService;
import com.jason.demo.demo2.framework.delay.DelayTaskType;
import com.jason.demo.demo2.framework.delay.config.DelayProperties;
import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import com.jason.demo.demo2.order.app.convert.OrderVoConvert;
import com.jason.demo.demo2.order.app.executor.OrderCancelCmdExe;
import com.jason.demo.demo2.order.app.executor.OrderGetCmdExe;
import com.jason.demo.demo2.order.app.executor.OrderPaySuccessCmdExe;
import com.jason.demo.demo2.order.app.executor.OrderPlaceCmdExe;
import com.jason.demo.demo2.order.app.vo.res.CancelOrderResVO;
import com.jason.demo.demo2.order.app.vo.res.GetOrderResVO;
import com.jason.demo.demo2.order.app.vo.res.OrderPlaceResVO;
import com.jason.demo.demo2.order.app.vo.res.PayOrderResVO;
import com.jason.demo.demo2.order.service.common.OrderStatusEnum;
import com.jason.demo.demo2.order.service.core.OrderDomainService;
import com.jason.demo.demo2.order.service.core.domain.Order;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
    @Mock
    private OrderVoConvert orderVoConvert;

    private DelayProperties delayProperties;

    @BeforeEach
    void setUp() {
        delayProperties = new DelayProperties();
        delayProperties.setDefaultDelay(Duration.ofSeconds(30));
        LoginContextHolder.set(new LoginPrincipal(9001L, "13888999999", "t1"));
    }

    @AfterEach
    void tearDown() {
        LoginContextHolder.clear();
    }

    @Test
    void orderPlace_schedulesCancelTask() {
        when(idGenerator.nextId()).thenReturn(55L);
        when(delayTaskService.schedule(eq(DelayTaskType.ORDER_CANCEL), eq("55"), isNull(), any()))
                .thenReturn(77L);
        OrderPlaceCmdExe exe = new OrderPlaceCmdExe(
                orderDomainService, delayTaskService, idGenerator, delayProperties);

        OrderPlaceResVO result = exe.execute(new BigDecimal("9.90"), Duration.ofSeconds(10));

        assertEquals(55L, result.getOrderId());
        assertEquals(77L, result.getTaskId());
        assertEquals(OrderStatusEnum.SUBMIT.name(), result.getStatus());
        assertEquals("PT10S", result.getDelay());
        verify(orderDomainService).place(argThat(o -> o.getOrderId() == 55L
                && o.getMemberId() == 9001L
                && OrderStatusEnum.SUBMIT.name().equals(o.getOrderStatus())));
        verify(delayTaskService).schedule(DelayTaskType.ORDER_CANCEL, "55", null, Duration.ofSeconds(10));
    }

    @Test
    void pay_cancelsDelayTask() {
        Order paid = order(55L, OrderStatusEnum.COMPLETED);
        when(orderDomainService.requireOrder(55L, 9001L)).thenReturn(paid);
        when(orderVoConvert.toPayRes(paid)).thenReturn(payRes(paid));
        OrderPaySuccessCmdExe exe = new OrderPaySuccessCmdExe(orderDomainService, delayTaskService, orderVoConvert);

        PayOrderResVO result = exe.execute(55L);

        assertEquals(OrderStatusEnum.COMPLETED.name(), result.getStatus());
        verify(orderDomainService).payOrder(55L, 9001L);
        verify(delayTaskService).cancelByBizKey(DelayTaskType.ORDER_CANCEL, "55");
    }

    @Test
    void get_usesCurrentMemberOwnership() {
        Order order = order(55L, OrderStatusEnum.SUBMIT);
        when(orderDomainService.requireOrder(55L, 9001L)).thenReturn(order);
        when(orderVoConvert.toGetRes(order)).thenReturn(getRes(order));
        OrderGetCmdExe exe = new OrderGetCmdExe(orderDomainService, orderVoConvert);

        GetOrderResVO result = exe.execute(55L);

        assertEquals(55L, result.getOrderId());
        verify(orderDomainService).requireOrder(55L, 9001L);
    }

    @Test
    void cancel_cancelsDelayTask() {
        Order cancelled = order(55L, OrderStatusEnum.CANCEL);
        when(orderDomainService.requireOrder(55L, 9001L)).thenReturn(cancelled);
        when(orderVoConvert.toCancelRes(cancelled)).thenReturn(cancelRes(cancelled));
        OrderCancelCmdExe exe = new OrderCancelCmdExe(orderDomainService, delayTaskService, orderVoConvert);

        CancelOrderResVO result = exe.execute(55L);

        assertEquals(OrderStatusEnum.CANCEL.name(), result.getStatus());
        verify(orderDomainService).manualCancel(55L, 9001L);
        verify(delayTaskService).cancelByBizKey(DelayTaskType.ORDER_CANCEL, "55");
    }

    private static Order order(long orderId, OrderStatusEnum status) {
        Order order = new Order();
        order.setOrderId(orderId);
        order.setOrderStatus(status.name());
        order.setAmount(new BigDecimal("9.90"));
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        return order;
    }

    private static PayOrderResVO payRes(Order order) {
        PayOrderResVO vo = new PayOrderResVO();
        vo.setOrderId(order.getOrderId());
        vo.setStatus(order.getOrderStatus());
        return vo;
    }

    private static GetOrderResVO getRes(Order order) {
        GetOrderResVO vo = new GetOrderResVO();
        vo.setOrderId(order.getOrderId());
        vo.setStatus(order.getOrderStatus());
        vo.setAmount(order.getAmount());
        return vo;
    }

    private static CancelOrderResVO cancelRes(Order order) {
        CancelOrderResVO vo = new CancelOrderResVO();
        vo.setOrderId(order.getOrderId());
        vo.setStatus(order.getOrderStatus());
        return vo;
    }
}
