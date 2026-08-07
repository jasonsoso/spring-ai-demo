package com.jason.demo.demo2.framework.delay;

import com.jason.demo.demo2.framework.delay.config.DelayProperties;
import com.jason.demo.demo2.framework.delay.repository.DelayTaskEntity;
import com.jason.demo.demo2.framework.delay.repository.DelayTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FallbackScannerTest {

    @Mock
    private DelayTaskRepository repository;
    @Mock
    private DelayTaskExecutor executor;

    @Test
    void scan_executesAllDueTasks() {
        DelayProperties properties = new DelayProperties();
        properties.setScanBatchSize(50);
        FallbackScanner scanner = new FallbackScanner(repository, executor, properties);

        DelayTaskEntity a = new DelayTaskEntity();
        a.setTaskId(1L);
        DelayTaskEntity b = new DelayTaskEntity();
        b.setTaskId(2L);
        when(repository.findDuePending(any(Instant.class), eq(50))).thenReturn(List.of(a, b));

        scanner.scan();

        verify(executor).execute(1L);
        verify(executor).execute(2L);
    }
}
