package com.jason.demo.demo2.order;

import com.jason.demo.demo2.framework.delay.DelayTaskType;
import com.jason.demo.demo2.framework.delay.repository.DelayTaskEntity;
import com.jason.demo.demo2.order.repository.OrderEntity;
import com.jason.demo.demo2.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderCancelHandlerTest {

    @Mock
    private OrderRepository orderRepository;
    @InjectMocks
    private OrderCancelHandler handler;

    @Test
    void pendingPay_cancelsOrder() {
        DelayTaskEntity task = new DelayTaskEntity();
        task.setTaskId(1L);
        task.setBizKey("100");
        OrderEntity order = new OrderEntity();
        order.setOrderId(100L);
        order.setStatus(OrderStatus.PENDING_PAY.name());
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));
        when(orderRepository.markCancelled(100L)).thenReturn(true);

        handler.handle(task);

        verify(orderRepository).markCancelled(100L);
        assert handler.taskType().equals(DelayTaskType.ORDER_CANCEL);
    }

    @Test
    void paid_skipsCancel() {
        DelayTaskEntity task = new DelayTaskEntity();
        task.setBizKey("100");
        OrderEntity order = new OrderEntity();
        order.setOrderId(100L);
        order.setStatus(OrderStatus.PAID.name());
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));

        handler.handle(task);

        verify(orderRepository, never()).markCancelled(anyLong());
    }
}
