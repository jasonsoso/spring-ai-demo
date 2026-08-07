package com.jason.demo.demo2.framework.delay;

import com.jason.demo.demo2.framework.delay.backend.DelayBackend;
import com.jason.demo.demo2.framework.delay.config.DelayProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DelayDispatcherTest {

    @Mock
    private DelayBackend redisson;
    @Mock
    private DelayBackend rocketmq;

    @Test
    void schedule_usesConfiguredBackendOnly() {
        when(redisson.name()).thenReturn("redisson");
        when(rocketmq.name()).thenReturn("rocketmq");
        DelayProperties properties = new DelayProperties();
        properties.setBackend("redisson");
        DelayDispatcher dispatcher = new DelayDispatcher(properties, List.of(redisson, rocketmq));

        dispatcher.schedule(9L, Duration.ofSeconds(5));

        verify(redisson).schedule(9L, Duration.ofSeconds(5));
        verify(rocketmq, never()).schedule(anyLong(), any());
    }
}
