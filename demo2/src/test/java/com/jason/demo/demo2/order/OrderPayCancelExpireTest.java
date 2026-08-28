package com.jason.demo.demo2.order;

import com.jason.demo.demo2.framework.auth.context.LoginContextHolder;
import com.jason.demo.demo2.framework.auth.context.LoginPrincipal;
import com.jason.demo.demo2.framework.delay.DelayTaskService;
import com.jason.demo.demo2.framework.delay.DelayTaskType;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.order.app.convert.OrderVoConvert;
import com.jason.demo.demo2.order.app.executor.OrderCancelCmdExe;
import com.jason.demo.demo2.order.app.executor.OrderExpireCmdExe;
import com.jason.demo.demo2.order.app.executor.OrderPaySuccessCmdExe;
import com.jason.demo.demo2.order.app.vo.res.CancelOrderResVO;
import com.jason.demo.demo2.order.app.vo.res.PayOrderResVO;
import com.jason.demo.demo2.order.service.common.OrderErrorCodeEnum;
import com.jason.demo.demo2.order.service.common.OrderEventEnum;
import com.jason.demo.demo2.order.service.common.OrderStatusEnum;
import com.jason.demo.demo2.order.service.common.PayStatusEnum;
import com.jason.demo.demo2.order.service.core.OrderDomainService;
import com.jason.demo.demo2.order.service.core.domain.Order;
import com.jason.demo.demo2.order.service.core.domain.OrderItem;
import com.jason.demo.demo2.order.service.core.statemachine.OrderContext;
import com.jason.demo.demo2.order.service.core.statemachine.OrderStateMachineExecutor;
import com.jason.demo.demo2.order.service.core.statemachine.action.OrderCancelAction;
import com.jason.demo.demo2.order.service.core.statemachine.action.OrderExpireAction;
import com.jason.demo.demo2.order.service.core.statemachine.action.OrderPaySuccessAction;
import com.jason.demo.demo2.order.service.core.statemachine.action.OrderPlaceAction;
import com.jason.demo.demo2.order.service.infrastructure.repository.OrderItemRepository;
import com.jason.demo.demo2.order.service.infrastructure.repository.OrderRepository;
import com.jason.demo.demo2.product.service.core.ProductStockHotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPayCancelExpireTest {

    private static final long ORDER_ID = 55L;
    private static final long MEMBER_ID = 9001L;
    private static final long PRODUCT_ID = 2085550503315509001L;

    @Mock OrderDomainService orderDomainService;
    @Mock OrderStateMachineExecutor executor;
    @Mock DelayTaskService delayTaskService;
    @Mock OrderVoConvert orderVoConvert;
    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock ProductStockHotService productStockHotService;

    @BeforeEach
    void login() {
        LoginContextHolder.set(new LoginPrincipal(MEMBER_ID, "13888999999", "t1"));
    }

    @AfterEach
    void clear() {
        LoginContextHolder.clear();
    }

    @Test
    void expire_skipsWhenCompleted_noRelease() {
        Order order = new Order();
        order.setOrderId(8L);
        order.setOrderStatus(OrderStatusEnum.COMPLETED.name());
        when(orderDomainService.findById(8L)).thenReturn(Optional.of(order));
        new OrderExpireCmdExe(orderDomainService, executor).execute(8L);
        verify(executor, never()).fireEvent(any(), any(), any());
        verify(productStockHotService, never()).release(anyLong(), anyLong());
    }

    @Test
    void expire_skipsWhenMissing_noFire() {
        when(orderDomainService.findById(8L)).thenReturn(Optional.empty());
        new OrderExpireCmdExe(orderDomainService, executor).execute(8L);
        verify(executor, never()).fireEvent(any(), any(), any());
        verify(productStockHotService, never()).release(anyLong(), anyLong());
    }

    @Test
    void expire_submit_firesExpireEvent() {
        Order order = submitOrder(8L);
        when(orderDomainService.findById(8L)).thenReturn(Optional.of(order));
        when(orderDomainService.requireOrderWithItems(8L)).thenReturn(order);
        new OrderExpireCmdExe(orderDomainService, executor).execute(8L);
        verify(executor).fireEvent(eq(OrderStatusEnum.SUBMIT), eq(OrderEventEnum.ORDER_EXPIRE), any());
    }

    @Test
    void payAction_casTrue_confirmsOnce() {
        OrderPaySuccessAction action = newPayAction();
        Order order = orderWithItem(ORDER_ID, PRODUCT_ID, 2);
        OrderContext ctx = context(order);
        when(orderRepository.markCompleted(eq(ORDER_ID), eq(MEMBER_ID), any())).thenReturn(true);

        action.execute(OrderStatusEnum.SUBMIT, OrderStatusEnum.COMPLETED, OrderEventEnum.PAY_SUCCESS, ctx);

        assertEquals(OrderStatusEnum.COMPLETED.name(), order.getOrderStatus());
        assertEquals(PayStatusEnum.PAY_SUCCESS.name(), order.getPayStatus());
        InOrder inOrder = inOrder(orderRepository, productStockHotService);
        inOrder.verify(orderRepository).markCompleted(eq(ORDER_ID), eq(MEMBER_ID), any());
        inOrder.verify(productStockHotService).confirm(PRODUCT_ID, ORDER_ID, 2);
    }

    @Test
    void payAction_casFalse_throwsConflictNeverConfirm() {
        OrderPaySuccessAction action = newPayAction();
        OrderContext ctx = context(orderWithItem(ORDER_ID, PRODUCT_ID, 2));
        when(orderRepository.markCompleted(eq(ORDER_ID), eq(MEMBER_ID), any())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                action.execute(OrderStatusEnum.SUBMIT, OrderStatusEnum.COMPLETED, OrderEventEnum.PAY_SUCCESS, ctx));

        assertEquals(OrderErrorCodeEnum.ORDER_STATUS_CONFLICT.getCode(), ex.getCode());
        verify(productStockHotService, never()).confirm(anyLong(), anyLong(), anyInt());
    }

    @Test
    void payAction_emptyItems_loadsFromRepository() {
        OrderPaySuccessAction action = newPayAction();
        Order order = new Order();
        order.setOrderId(ORDER_ID);
        order.setMemberId(MEMBER_ID);
        order.setItems(List.of());
        OrderContext ctx = context(order);
        when(orderRepository.markCompleted(eq(ORDER_ID), eq(MEMBER_ID), any())).thenReturn(true);
        when(orderItemRepository.listByOrderId(ORDER_ID)).thenReturn(List.of(item(ORDER_ID, PRODUCT_ID, 2)));

        action.execute(OrderStatusEnum.SUBMIT, OrderStatusEnum.COMPLETED, OrderEventEnum.PAY_SUCCESS, ctx);

        verify(orderItemRepository).listByOrderId(ORDER_ID);
        verify(productStockHotService).confirm(PRODUCT_ID, ORDER_ID, 2);
    }

    @Test
    void cancelAction_casTrue_releasesOnce() {
        OrderCancelAction action = newCancelAction();
        Order order = orderWithItem(ORDER_ID, PRODUCT_ID, 2);
        OrderContext ctx = context(order);
        when(orderRepository.markCancelled(eq(ORDER_ID), eq(MEMBER_ID), any())).thenReturn(true);

        action.execute(OrderStatusEnum.SUBMIT, OrderStatusEnum.CANCEL, OrderEventEnum.CANCEL_ORDER, ctx);

        assertEquals(OrderStatusEnum.CANCEL.name(), order.getOrderStatus());
        assertEquals(PayStatusEnum.CLOSE.name(), order.getPayStatus());
        InOrder inOrder = inOrder(orderRepository, productStockHotService);
        inOrder.verify(orderRepository).markCancelled(eq(ORDER_ID), eq(MEMBER_ID), any());
        inOrder.verify(productStockHotService).release(PRODUCT_ID, ORDER_ID);
    }

    @Test
    void cancelAction_casFalse_throwsConflictNeverRelease() {
        OrderCancelAction action = newCancelAction();
        OrderContext ctx = context(orderWithItem(ORDER_ID, PRODUCT_ID, 2));
        when(orderRepository.markCancelled(eq(ORDER_ID), eq(MEMBER_ID), any())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                action.execute(OrderStatusEnum.SUBMIT, OrderStatusEnum.CANCEL, OrderEventEnum.CANCEL_ORDER, ctx));

        assertEquals(OrderErrorCodeEnum.ORDER_STATUS_CONFLICT.getCode(), ex.getCode());
        verify(productStockHotService, never()).release(anyLong(), anyLong());
    }

    @Test
    void expireAction_casFalse_returnsWithoutRelease() {
        OrderExpireAction action = newExpireAction();
        OrderContext ctx = context(orderWithItem(ORDER_ID, PRODUCT_ID, 2));
        when(orderRepository.markCancelled(eq(ORDER_ID), isNull(), any())).thenReturn(false);

        assertDoesNotThrow(() ->
                action.execute(OrderStatusEnum.SUBMIT, OrderStatusEnum.CANCEL, OrderEventEnum.ORDER_EXPIRE, ctx));

        verify(productStockHotService, never()).release(anyLong(), anyLong());
    }

    @Test
    void expireAction_casTrue_releasesOnce() {
        OrderExpireAction action = newExpireAction();
        OrderContext ctx = context(orderWithItem(ORDER_ID, PRODUCT_ID, 2));
        when(orderRepository.markCancelled(eq(ORDER_ID), isNull(), any())).thenReturn(true);

        action.execute(OrderStatusEnum.SUBMIT, OrderStatusEnum.CANCEL, OrderEventEnum.ORDER_EXPIRE, ctx);

        verify(productStockHotService).release(PRODUCT_ID, ORDER_ID);
    }

    @Test
    void pay_confirmsEachLine_andCancelsDelay() {
        Order order = orderWithItem(ORDER_ID, PRODUCT_ID, 2);
        order.setOrderStatus(OrderStatusEnum.SUBMIT.name());
        when(orderDomainService.requireOrder(ORDER_ID, MEMBER_ID)).thenReturn(order);
        PayOrderResVO vo = new PayOrderResVO();
        vo.setOrderId(ORDER_ID);
        vo.setOrderStatus(OrderStatusEnum.COMPLETED.name());
        vo.setPayStatus(PayStatusEnum.PAY_SUCCESS.name());
        when(orderVoConvert.toPayRes(order)).thenReturn(vo);
        OrderPaySuccessCmdExe exe = new OrderPaySuccessCmdExe(
                orderDomainService, executor, delayTaskService, orderVoConvert);

        PayOrderResVO result = exe.execute(ORDER_ID);

        assertEquals(OrderStatusEnum.COMPLETED.name(), result.getOrderStatus());
        assertEquals(PayStatusEnum.PAY_SUCCESS.name(), result.getPayStatus());
        InOrder inOrder = inOrder(executor, delayTaskService);
        inOrder.verify(executor).fireEvent(eq(OrderStatusEnum.SUBMIT), eq(OrderEventEnum.PAY_SUCCESS), any());
        inOrder.verify(delayTaskService).cancelByBizKey(DelayTaskType.ORDER_CANCEL, String.valueOf(ORDER_ID));
    }

    @Test
    void pay_missingOrder_neverFires() {
        when(orderDomainService.requireOrder(ORDER_ID, MEMBER_ID))
                .thenThrow(new BusinessException(OrderErrorCodeEnum.ORDER_NOT_FOUND));
        OrderPaySuccessCmdExe exe = new OrderPaySuccessCmdExe(
                orderDomainService, executor, delayTaskService, orderVoConvert);

        BusinessException ex = assertThrows(BusinessException.class, () -> exe.execute(ORDER_ID));

        assertEquals(OrderErrorCodeEnum.ORDER_NOT_FOUND.getCode(), ex.getCode());
        verify(executor, never()).fireEvent(any(), any(), any());
        verify(delayTaskService, never()).cancelByBizKey(any(), any());
    }

    @Test
    void cancel_cancelStatus_throwsConflictViaFailCallback() {
        Order order = new Order();
        order.setOrderId(ORDER_ID);
        order.setMemberId(MEMBER_ID);
        order.setOrderStatus(OrderStatusEnum.CANCEL.name());
        when(orderDomainService.requireOrder(ORDER_ID, MEMBER_ID)).thenReturn(order);
        OrderStateMachineExecutor realExecutor = new OrderStateMachineExecutor(
                new OrderPlaceAction(orderRepository, productStockHotService),
                newPayAction(),
                newCancelAction(),
                newExpireAction());
        OrderCancelCmdExe exe = new OrderCancelCmdExe(
                orderDomainService, realExecutor, delayTaskService, orderVoConvert);

        BusinessException ex = assertThrows(BusinessException.class, () -> exe.execute(ORDER_ID));

        assertEquals(OrderErrorCodeEnum.ORDER_STATUS_CONFLICT.getCode(), ex.getCode());
        verify(delayTaskService, never()).cancelByBizKey(any(), any());
        verify(productStockHotService, never()).release(anyLong(), anyLong());
    }

    @Test
    void cancel_success_cancelsDelay() {
        Order order = orderWithItem(ORDER_ID, PRODUCT_ID, 2);
        order.setOrderStatus(OrderStatusEnum.SUBMIT.name());
        when(orderDomainService.requireOrder(ORDER_ID, MEMBER_ID)).thenReturn(order);
        CancelOrderResVO vo = new CancelOrderResVO();
        vo.setOrderId(ORDER_ID);
        vo.setOrderStatus(OrderStatusEnum.CANCEL.name());
        vo.setPayStatus(PayStatusEnum.CLOSE.name());
        when(orderVoConvert.toCancelRes(order)).thenReturn(vo);
        OrderCancelCmdExe exe = new OrderCancelCmdExe(
                orderDomainService, executor, delayTaskService, orderVoConvert);

        CancelOrderResVO result = exe.execute(ORDER_ID);

        assertEquals(OrderStatusEnum.CANCEL.name(), result.getOrderStatus());
        assertEquals(PayStatusEnum.CLOSE.name(), result.getPayStatus());
        InOrder inOrder = inOrder(executor, delayTaskService);
        inOrder.verify(executor).fireEvent(eq(OrderStatusEnum.SUBMIT), eq(OrderEventEnum.CANCEL_ORDER), any());
        inOrder.verify(delayTaskService).cancelByBizKey(DelayTaskType.ORDER_CANCEL, String.valueOf(ORDER_ID));
    }

    private OrderPaySuccessAction newPayAction() {
        return new OrderPaySuccessAction(orderRepository, productStockHotService, orderItemRepository);
    }

    private OrderCancelAction newCancelAction() {
        return new OrderCancelAction(orderRepository, productStockHotService, orderItemRepository);
    }

    private OrderExpireAction newExpireAction() {
        return new OrderExpireAction(orderRepository, productStockHotService, orderItemRepository);
    }

    private static OrderContext context(Order order) {
        OrderContext ctx = new OrderContext();
        ctx.setOrder(order);
        return ctx;
    }

    private static Order submitOrder(long orderId) {
        Order order = new Order();
        order.setOrderId(orderId);
        order.setMemberId(MEMBER_ID);
        order.setOrderStatus(OrderStatusEnum.SUBMIT.name());
        return order;
    }

    private static Order orderWithItem(long orderId, long productId, int qty) {
        Order order = new Order();
        order.setOrderId(orderId);
        order.setMemberId(MEMBER_ID);
        order.setOrderStatus(OrderStatusEnum.SUBMIT.name());
        order.setItems(List.of(item(orderId, productId, qty)));
        return order;
    }

    private static OrderItem item(long orderId, long productId, int qty) {
        OrderItem line = new OrderItem();
        line.setItemId(orderId + 1);
        line.setOrderId(orderId);
        line.setProductId(productId);
        line.setQty(qty);
        line.setSellPrice(new BigDecimal("18.00"));
        return line;
    }
}
