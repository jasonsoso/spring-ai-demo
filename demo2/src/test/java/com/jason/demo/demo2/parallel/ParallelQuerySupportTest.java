package com.jason.demo.demo2.parallel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ParallelQuerySupportTest {

    private ExecutorService executor;
    private ParallelQuerySupport support;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        support = new ParallelQuerySupport();
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void run_bothSucceed_returnsBothValues() {
        Map<String, Supplier<?>> tasks = new LinkedHashMap<>();
        tasks.put("user", () -> "alice");
        tasks.put("orders", () -> java.util.List.of("o1"));

        Map<String, Object> result = support.run(tasks, Duration.ofSeconds(3), executor);

        assertThat(result.get("user")).isEqualTo("alice");
        assertThat(result.get("orders")).isEqualTo(java.util.List.of("o1"));
    }

    @Test
    void run_oneThrows_returnsNullForThatKeyOnly() {
        Map<String, Supplier<?>> tasks = new LinkedHashMap<>();
        tasks.put("user", () -> "alice");
        tasks.put("orders", () -> {
            throw new IllegalStateException("order-down");
        });

        Map<String, Object> result = support.run(tasks, Duration.ofSeconds(3), executor);

        assertThat(result.get("user")).isEqualTo("alice");
        assertThat(result.get("orders")).isNull();
    }

    @Test
    void run_oneExceedsBudget_returnsNullForSlowKey() {
        AtomicBoolean slowStarted = new AtomicBoolean();
        Map<String, Supplier<?>> tasks = new LinkedHashMap<>();
        tasks.put("user", () -> {
            sleep(200);
            return "alice";
        });
        tasks.put("orders", () -> {
            slowStarted.set(true);
            sleep(5_000);
            return java.util.List.of("o1");
        });

        long start = System.nanoTime();
        Map<String, Object> result = support.run(tasks, Duration.ofMillis(800), executor);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(slowStarted).isTrue();
        assertThat(result.get("user")).isEqualTo("alice");
        assertThat(result.get("orders")).isNull();
        assertThat(elapsedMs).isLessThan(3_000L);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
