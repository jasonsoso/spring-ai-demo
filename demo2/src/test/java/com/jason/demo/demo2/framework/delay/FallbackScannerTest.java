package com.jason.demo.demo2.framework.delay;

import com.baomidou.lock.LockInfo;
import com.baomidou.lock.LockTemplate;
import com.jason.demo.demo2.framework.delay.config.DelayProperties;
import com.jason.demo.demo2.framework.delay.repository.DelayTaskEntity;
import com.jason.demo.demo2.framework.delay.repository.DelayTaskRepository;
import com.jason.demo.demo2.lock.LockKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FallbackScannerTest {

    @Mock
    private DelayTaskRepository repository;
    @Mock
    private DelayTaskExecutor executor;
    @Mock
    private LockTemplate lockTemplate;
    @Mock
    private LockInfo lockInfo;

    private DelayProperties properties;

    @BeforeEach
    void setUp() {
        properties = new DelayProperties();
        properties.setScanBatchSize(50);
        properties.setScanLockEnabled(true);
        properties.setScanLockTimeout(Duration.ofSeconds(10));
    }

    private FallbackScanner newScanner() {
        return new FallbackScanner(repository, executor, properties, lockTemplate);
    }

    @Test
    void scan_lockDisabled_executesWithoutLockTemplate() {
        properties.setScanLockEnabled(false);
        DelayTaskEntity task = task(1L);
        when(repository.findDuePending(any(Instant.class), eq(50))).thenReturn(List.of(task));

        newScanner().scan();

        verifyNoInteractions(lockTemplate);
        verify(executor).execute(1L);
    }

    @Test
    void scan_lockAcquired_executesAllDueTasksAndReleasesLock() {
        when(lockTemplate.lock(eq(LockKeys.delayScannerFallbackKey()), eq(10_000L), eq(0L)))
                .thenReturn(lockInfo);
        DelayTaskEntity a = task(1L);
        DelayTaskEntity b = task(2L);
        when(repository.findDuePending(any(Instant.class), eq(50))).thenReturn(List.of(a, b));

        newScanner().scan();

        verify(executor).execute(1L);
        verify(executor).execute(2L);
        verify(lockTemplate).releaseLock(lockInfo);
    }

    @Test
    void scan_lockNotAcquired_skipsScan() {
        when(lockTemplate.lock(anyString(), anyLong(), anyLong())).thenReturn(null);

        newScanner().scan();

        verify(repository, never()).findDuePending(any(), anyInt());
        verify(executor, never()).execute(anyLong());
        verify(lockTemplate, never()).releaseLock(any());
    }

    @Test
    void scan_repositoryThrows_stillReleasesLock() {
        when(lockTemplate.lock(anyString(), anyLong(), anyLong())).thenReturn(lockInfo);
        when(repository.findDuePending(any(Instant.class), anyInt()))
                .thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> newScanner().scan());

        verify(lockTemplate).releaseLock(lockInfo);
    }

    private static DelayTaskEntity task(long taskId) {
        DelayTaskEntity entity = new DelayTaskEntity();
        entity.setTaskId(taskId);
        return entity;
    }
}
