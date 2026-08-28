package com.jason.demo.demo2.order;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.order.service.common.OrderEventEnum;
import com.jason.demo.demo2.order.service.common.OrderStatusEnum;
import com.jason.demo.demo2.order.service.common.PayStatusEnum;
import com.jason.demo.demo2.order.service.core.domain.Order;
import com.jason.demo.demo2.order.service.core.domain.OrderItem;
import com.jason.demo.demo2.order.service.core.statemachine.OrderContext;
import com.jason.demo.demo2.order.service.core.statemachine.action.OrderPlaceAction;
import com.jason.demo.demo2.order.service.infrastructure.repository.OrderRepository;
import com.jason.demo.demo2.product.service.common.ProductErrorCodeEnum;
import com.jason.demo.demo2.product.service.core.ProductStockHotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderPlaceActionTest {

    @Mock OrderRepository orderRepository;
    @Mock ProductStockHotService productStockHotService;

    @BeforeEach
    void initTx() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.initSynchronization();
        }
    }

    @AfterEach
    void clearTx() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void execute_insertsBeforeReserve() {
        OrderPlaceAction action = new OrderPlaceAction(orderRepository, productStockHotService);
        Order order = orderWithItem(55L, 2085550503315509001L, 2);
        OrderContext ctx = new OrderContext();
        ctx.setOrder(order);

        action.execute(OrderStatusEnum.INIT, OrderStatusEnum.SUBMIT, OrderEventEnum.SUBMIT_ORDER, ctx);

        assertEquals(OrderStatusEnum.SUBMIT.name(), order.getOrderStatus());
        assertEquals(PayStatusEnum.WAIT_PAY.name(), order.getPayStatus());
        InOrder inOrder = inOrder(orderRepository, productStockHotService);
        inOrder.verify(orderRepository).insertWithItems(order);
        inOrder.verify(productStockHotService).reserve(2085550503315509001L, 55L, 2);
    }

    @Test
    void execute_reserveInsufficient_stillInserted() {
        OrderPlaceAction action = new OrderPlaceAction(orderRepository, productStockHotService);
        Order order = orderWithItem(55L, 2085550503315509001L, 2);
        OrderContext ctx = new OrderContext();
        ctx.setOrder(order);
        doThrow(new BusinessException(ProductErrorCodeEnum.STOCK_INSUFFICIENT))
                .when(productStockHotService).reserve(anyLong(), anyLong(), anyInt());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                action.execute(OrderStatusEnum.INIT, OrderStatusEnum.SUBMIT, OrderEventEnum.SUBMIT_ORDER, ctx));

        assertEquals(ProductErrorCodeEnum.STOCK_INSUFFICIENT.getCode(), ex.getCode());
        verify(orderRepository).insertWithItems(order);
        verify(productStockHotService).reserve(2085550503315509001L, 55L, 2);
    }

    private static Order orderWithItem(long orderId, long productId, int qty) {
        OrderItem item = new OrderItem();
        item.setItemId(orderId + 1);
        item.setOrderId(orderId);
        item.setProductId(productId);
        item.setQty(qty);
        item.setSellPrice(new BigDecimal("18.00"));
        Order order = new Order();
        order.setOrderId(orderId);
        order.setMemberId(9001L);
        order.setItems(List.of(item));
        return order;
    }
}
