package com.jason.demo.demo2.order;

import com.jason.demo.demo2.framework.auth.context.LoginContextHolder;
import com.jason.demo.demo2.framework.auth.context.LoginPrincipal;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.order.app.convert.OrderVoConvert;
import com.jason.demo.demo2.order.app.convert.OrderVoConvertImpl;
import com.jason.demo.demo2.order.app.executor.OrderCountsCmdExe;
import com.jason.demo.demo2.order.app.executor.OrderGetCmdExe;
import com.jason.demo.demo2.order.app.executor.OrderListCmdExe;
import com.jason.demo.demo2.order.app.vo.req.OrderListReqVO;
import com.jason.demo.demo2.order.app.vo.res.GetOrderResVO;
import com.jason.demo.demo2.order.app.vo.res.OrderCountsResVO;
import com.jason.demo.demo2.order.app.vo.res.OrderListResVO;
import com.jason.demo.demo2.order.service.common.OrderErrorCodeEnum;
import com.jason.demo.demo2.order.service.common.OrderListTabEnum;
import com.jason.demo.demo2.order.service.common.OrderStatusEnum;
import com.jason.demo.demo2.order.service.common.PayStatusEnum;
import com.jason.demo.demo2.order.service.core.OrderDomainService;
import com.jason.demo.demo2.order.service.core.domain.Order;
import com.jason.demo.demo2.order.service.core.domain.OrderItem;
import com.jason.demo.demo2.order.service.infrastructure.repository.OrderItemRepository;
import com.jason.demo.demo2.order.service.infrastructure.repository.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderListCountsCmdExeTest {

    private static final long MEMBER_ID = 9001L;
    private static final long ORDER_ID = 55L;

    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock OrderDomainService orderDomainService;
    @Mock OrderVoConvert orderVoConvert;

    private OrderListCmdExe exe;
    private Order order;
    private OrderItem item;

    @BeforeEach
    void login() {
        LoginContextHolder.set(new LoginPrincipal(MEMBER_ID, "13888999999", "t1"));
        order = cancelOrder(ORDER_ID);
        item = latteLine(ORDER_ID);
        exe = new OrderListCmdExe(orderRepository, orderItemRepository, new OrderVoConvertImpl());
    }

    @AfterEach
    void clear() {
        LoginContextHolder.clear();
    }

    @Test
    void counts_mapsSubmitAndCompleted() {
        when(orderRepository.countByMemberAndStatus(9001L, "SUBMIT")).thenReturn(3L);
        when(orderRepository.countByMemberAndStatus(9001L, "COMPLETED")).thenReturn(11L);
        OrderCountsResVO vo = new OrderCountsCmdExe(orderRepository).execute();
        assertEquals(3L, vo.getPendingCount());
        assertEquals(11L, vo.getCompletedCount());
    }

    @Test
    void list_all_passesNullStatus() {
        when(orderRepository.countPageByMemberAndTab(9001L, null)).thenReturn(1L);
        when(orderRepository.pageByMemberAndTab(eq(9001L), isNull(), eq(0), eq(20)))
                .thenReturn(List.of(order));
        when(orderItemRepository.listByOrderIds(List.of(55L))).thenReturn(Map.of(55L, List.of(item)));
        OrderListReqVO req = new OrderListReqVO();
        req.setTab(OrderListTabEnum.ALL);
        OrderListResVO vo = exe.execute(req);
        assertEquals(1L, vo.getTotal());
        assertEquals("CANCEL", vo.getItems().get(0).getOrderStatus()); // 构造一条 CANCEL 证明 ALL 可含取消
        assertEquals(1, vo.getPageNo());
        assertEquals(20, vo.getPageSize());
        assertEquals("拿铁", vo.getItems().get(0).getItems().get(0).getProductName());
        assertEquals(2, vo.getItems().get(0).getItems().get(0).getQty());
        assertEquals(new BigDecimal("18.00"), vo.getItems().get(0).getItems().get(0).getSellPrice());
        assertNull(vo.getItems().get(0).getItems().get(0).getProductId());
        verify(orderItemRepository).listByOrderIds(List.of(55L));
    }

    @Test
    void list_submit_passesSubmitNameAndOffset() {
        when(orderRepository.countPageByMemberAndTab(9001L, "SUBMIT")).thenReturn(21L);
        when(orderRepository.pageByMemberAndTab(eq(9001L), eq("SUBMIT"), eq(20), eq(20)))
                .thenReturn(List.of());
        when(orderItemRepository.listByOrderIds(List.of())).thenReturn(Map.of());
        OrderListReqVO req = new OrderListReqVO();
        req.setTab(OrderListTabEnum.SUBMIT);
        req.setPageNo(2);
        req.setPageSize(20);
        OrderListResVO vo = exe.execute(req);
        assertEquals(21L, vo.getTotal());
        assertEquals(2, vo.getPageNo());
        assertTrue(vo.getItems().isEmpty());
        verify(orderRepository).pageByMemberAndTab(9001L, "SUBMIT", 20, 20);
    }

    @Test
    void get_othersOrder_throws30001() {
        when(orderDomainService.requireOrder(ORDER_ID, MEMBER_ID))
                .thenThrow(new BusinessException(OrderErrorCodeEnum.ORDER_NOT_FOUND));
        OrderGetCmdExe getExe = new OrderGetCmdExe(orderDomainService, orderItemRepository, orderVoConvert);

        BusinessException ex = assertThrows(BusinessException.class, () -> getExe.execute(ORDER_ID));

        assertEquals(OrderErrorCodeEnum.ORDER_NOT_FOUND.getCode(), ex.getCode());
        verify(orderItemRepository, never()).listByOrderId(anyLong());
        verify(orderVoConvert, never()).toGetRes(any());
    }

    @Test
    void get_attachesFullSnapshotItems() {
        Order owned = submitOrder(ORDER_ID);
        when(orderDomainService.requireOrder(ORDER_ID, MEMBER_ID)).thenReturn(owned);
        when(orderItemRepository.listByOrderId(ORDER_ID)).thenReturn(List.of(item));
        when(orderVoConvert.toGetRes(any())).thenAnswer(inv -> {
            GetOrderResVO vo = new GetOrderResVO();
            Order source = inv.getArgument(0);
            vo.setOrderId(source.getOrderId());
            vo.setOrderStatus(source.getOrderStatus());
            return vo;
        });
        OrderGetCmdExe getExe = new OrderGetCmdExe(orderDomainService, orderItemRepository, orderVoConvert);

        GetOrderResVO vo = getExe.execute(ORDER_ID);

        ArgumentCaptor<Order> cap = ArgumentCaptor.forClass(Order.class);
        verify(orderVoConvert).toGetRes(cap.capture());
        assertEquals(ORDER_ID, vo.getOrderId());
        assertEquals(1, cap.getValue().getItems().size());
        OrderItem snapshot = cap.getValue().getItems().get(0);
        assertEquals("拿铁", snapshot.getProductName());
        assertEquals("经典浓郁", snapshot.getSubtitle());
        assertEquals(new BigDecimal("20.00"), snapshot.getMarketPrice());
        assertNotNull(snapshot.getProductId());
    }

    @Test
    void get_missingItems_emptyArray() {
        Order owned = submitOrder(ORDER_ID);
        when(orderDomainService.requireOrder(ORDER_ID, MEMBER_ID)).thenReturn(owned);
        when(orderItemRepository.listByOrderId(ORDER_ID)).thenReturn(List.of());
        when(orderVoConvert.toGetRes(any())).thenAnswer(inv -> {
            GetOrderResVO vo = new GetOrderResVO();
            Order source = inv.getArgument(0);
            vo.setOrderId(source.getOrderId());
            return vo;
        });
        OrderGetCmdExe getExe = new OrderGetCmdExe(orderDomainService, orderItemRepository, orderVoConvert);

        getExe.execute(ORDER_ID);

        ArgumentCaptor<Order> cap = ArgumentCaptor.forClass(Order.class);
        verify(orderVoConvert).toGetRes(cap.capture());
        assertNotNull(cap.getValue().getItems());
        assertTrue(cap.getValue().getItems().isEmpty());
    }

    private static Order cancelOrder(long orderId) {
        Order row = new Order();
        row.setOrderId(orderId);
        row.setMemberId(MEMBER_ID);
        row.setOrderStatus(OrderStatusEnum.CANCEL.name());
        row.setPayStatus(PayStatusEnum.CLOSE.name());
        row.setAmount(new BigDecimal("36.00"));
        row.setCancelTime(LocalDateTime.now());
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    private static Order submitOrder(long orderId) {
        Order row = new Order();
        row.setOrderId(orderId);
        row.setMemberId(MEMBER_ID);
        row.setOrderStatus(OrderStatusEnum.SUBMIT.name());
        row.setPayStatus(PayStatusEnum.WAIT_PAY.name());
        row.setAmount(new BigDecimal("36.00"));
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    private static OrderItem latteLine(long orderId) {
        OrderItem line = new OrderItem();
        line.setItemId(orderId + 1);
        line.setOrderId(orderId);
        line.setProductId(2085550503315509001L);
        line.setProductName("拿铁");
        line.setSubtitle("经典浓郁");
        line.setCoverUrl(null);
        line.setSellPrice(new BigDecimal("18.00"));
        line.setMarketPrice(new BigDecimal("20.00"));
        line.setQty(2);
        return line;
    }
}
