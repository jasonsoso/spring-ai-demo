package com.jason.demo.demo2.order;

import com.jason.demo.demo2.framework.delay.DelayTaskType;
import com.jason.demo.demo2.framework.delay.repository.DelayTaskEntity;
import com.jason.demo.demo2.order.app.executor.OrderExpireCmdExe;
import com.jason.demo.demo2.order.app.listener.OrderCancelHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OrderCancelHandlerTest {

    @Test
    void handle_delegatesToExpireExecutor() {
        OrderExpireCmdExe orderExpireCmdExe = mock(OrderExpireCmdExe.class);
        OrderCancelHandler handler = new OrderCancelHandler(orderExpireCmdExe);
        DelayTaskEntity task = new DelayTaskEntity();
        task.setTaskId(1L);
        task.setBizKey("100");

        handler.handle(task);

        verify(orderExpireCmdExe).execute(100L);
        assertEquals(DelayTaskType.ORDER_CANCEL, handler.taskType());
    }
}
