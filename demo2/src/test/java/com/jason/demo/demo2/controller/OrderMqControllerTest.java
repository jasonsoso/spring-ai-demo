package com.jason.demo.demo2.controller;

import com.jason.demo.demo2.mq.InMemoryOrderEventStore;
import com.jason.demo.demo2.mq.OrderEvent;
import com.jason.demo.demo2.mq.OrderEventPublisher;
import com.jason.demo.demo2.mq.OrderEventRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderMqControllerTest {

    @Mock
    OrderEventPublisher publisher;

    @Mock
    InMemoryOrderEventStore store;

    @InjectMocks
    OrderMqController controller;

    @Test
    void sync_callsPublisher() {
        Map<String, Object> resp = controller.sync(new OrderEventRequest("o-1", "CREATED", "demo"));
        assertThat(resp.get("ok")).isEqualTo(true);
        assertThat(resp.get("mode")).isEqualTo("sync");

        ArgumentCaptor<OrderEvent> captor = ArgumentCaptor.forClass(OrderEvent.class);
        verify(publisher).sendSync(captor.capture());
        assertThat(captor.getValue().orderId()).isEqualTo("o-1");
    }
}
