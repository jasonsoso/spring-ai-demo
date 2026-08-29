package com.jason.demo.demo2.framework.delay;

import com.jason.demo.demo2.framework.delay.backend.DelayBackend;
import com.jason.demo.demo2.framework.delay.config.DelayProperties;
import com.jason.demo.demo2.framework.delay.repository.DelayTaskRepository;
import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class DelayTaskServiceTest {

    @Mock
    private DelayTaskRepository repository;
    @Mock
    private DelayBackend redisson;
    @Mock
    private SnowflakeIdGenerator idGenerator;

    private DelayTaskService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisson.name()).thenReturn("redisson");
        DelayProperties properties = new DelayProperties();
        properties.setBackend("redisson");
        properties.setDefaultDelay(Duration.ofSeconds(30));
        properties.setMaxRetry(3);
        DelayDispatcher dispatcher = new DelayDispatcher(properties, List.of(redisson));
        lenient().when(idGenerator.nextId()).thenReturn(1001L);
        service = new DelayTaskService(repository, dispatcher, idGenerator, properties);
    }

    @Test
    void schedule_returnsTaskIdEvenWhenDispatchFails() {
        doThrow(new RuntimeException("mq down")).when(redisson).schedule(anyLong(), any());

        long taskId = service.schedule("ORDER_CANCEL", "99", null, Duration.ofSeconds(10));

        assertEquals(1001L, taskId);
        verify(repository).insert(argThat(e -> e.getTaskId() == 1001L
                && "PENDING".equals(e.getStatus())
                && "ORDER_CANCEL".equals(e.getTaskType())));
    }

    @Test
    void cancelByBizKey_updatesAndDispatchesCancel() {
        var pending = new com.jason.demo.demo2.framework.delay.repository.DelayTaskEntity();
        pending.setTaskId(1001L);
        when(repository.findPendingByBizKey("ORDER_CANCEL", "99")).thenReturn(Optional.of(pending));
        when(repository.markCancelled("ORDER_CANCEL", "99")).thenReturn(true);

        assertTrue(service.cancelByBizKey("ORDER_CANCEL", "99"));
        verify(redisson).cancel(1001L);
    }

    @Test
    void findPendingExecuteAt_mapsLedgerTime() {
        var pending = new com.jason.demo.demo2.framework.delay.repository.DelayTaskEntity();
        java.time.LocalDateTime executeAt = java.time.LocalDateTime.of(2026, 8, 29, 16, 0, 0);
        pending.setExecuteAt(executeAt);
        when(repository.findPendingByBizKey("ORDER_CANCEL", "99")).thenReturn(Optional.of(pending));

        Optional<java.time.LocalDateTime> result = service.findPendingExecuteAt("ORDER_CANCEL", "99");

        assertEquals(executeAt, result.orElseThrow());
    }
}
