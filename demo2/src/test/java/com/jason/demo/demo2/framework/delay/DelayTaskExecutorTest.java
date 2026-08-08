package com.jason.demo.demo2.framework.delay;

import com.baomidou.lock.LockInfo;
import com.baomidou.lock.LockTemplate;
import com.jason.demo.demo2.framework.delay.config.DelayProperties;
import com.jason.demo.demo2.framework.delay.repository.DelayTaskEntity;
import com.jason.demo.demo2.framework.delay.repository.DelayTaskRepository;
import com.jason.demo.demo2.framework.trace.TraceSupport;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DelayTaskExecutorTest {

    @Mock
    private DelayTaskRepository repository;
    @Mock
    private LockTemplate lockTemplate;
    @Mock
    private DelayTaskHandler handler;
    @Mock
    private LockInfo lockInfo;

    private DelayProperties properties;
    private DelayTaskExecutor executor;

    @BeforeEach
    void setUp() {
        properties = new DelayProperties();
        properties.setLockTimeout(Duration.ofSeconds(10));
        properties.setMaxRetry(3);
        when(handler.taskType()).thenReturn(DelayTaskType.ORDER_CANCEL);
        TraceSupport traceSupport = new TraceSupport(new SimpleTracer());
        executor = new DelayTaskExecutor(repository, lockTemplate, properties, List.of(handler), traceSupport);
    }

    @Test
    void nonPending_skipsHandler() {
        when(lockTemplate.lock(anyString(), anyLong(), anyLong())).thenReturn(lockInfo);
        DelayTaskEntity task = baseTask();
        task.setStatus(DelayTaskStatus.CANCELLED.name());
        when(repository.findById(1L)).thenReturn(Optional.of(task));

        executor.execute(1L);

        verify(handler, never()).handle(any());
        verify(lockTemplate).releaseLock(lockInfo);
    }

    @Test
    void handlerSuccess_marksSuccess() {
        when(lockTemplate.lock(anyString(), anyLong(), anyLong())).thenReturn(lockInfo);
        DelayTaskEntity task = baseTask();
        when(repository.findById(1L)).thenReturn(Optional.of(task));
        when(repository.casStatus(1L, DelayTaskStatus.PENDING.name(), DelayTaskStatus.RUNNING.name()))
                .thenReturn(true);

        executor.execute(1L);

        verify(handler).handle(task);
        verify(repository).markSuccess(1L);
    }

    @Test
    void handlerFailure_schedulesRetry() {
        when(lockTemplate.lock(anyString(), anyLong(), anyLong())).thenReturn(lockInfo);
        DelayTaskEntity task = baseTask();
        when(repository.findById(1L)).thenReturn(Optional.of(task));
        when(repository.casStatus(1L, DelayTaskStatus.PENDING.name(), DelayTaskStatus.RUNNING.name()))
                .thenReturn(true);
        doThrow(new RuntimeException("boom")).when(handler).handle(task);

        executor.execute(1L);

        ArgumentCaptor<Instant> executeAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).scheduleRetry(eq(1L), eq(1), executeAt.capture());
        assertTrue(executeAt.getValue().isAfter(Instant.now().minusSeconds(1)));
        assertEquals(1, task.getRetryCount() + 1);
    }

    @Test
    void lockFailed_skips() {
        when(lockTemplate.lock(anyString(), anyLong(), anyLong())).thenReturn(null);

        executor.execute(1L);

        verify(repository, never()).findById(anyLong());
        verify(handler, never()).handle(any());
    }

    private static DelayTaskEntity baseTask() {
        DelayTaskEntity task = new DelayTaskEntity();
        task.setTaskId(1L);
        task.setTaskType(DelayTaskType.ORDER_CANCEL);
        task.setBizKey("100");
        task.setStatus(DelayTaskStatus.PENDING.name());
        task.setExecuteAt(LocalDateTime.now().minusSeconds(1));
        task.setRetryCount(0);
        task.setMaxRetry(3);
        return task;
    }
}
