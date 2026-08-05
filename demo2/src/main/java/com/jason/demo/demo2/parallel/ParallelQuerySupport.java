package com.jason.demo.demo2.parallel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Component
public class ParallelQuerySupport {

    private static final Logger log = LoggerFactory.getLogger(ParallelQuerySupport.class);

    public Map<String, Object> run(
            Map<String, Supplier<?>> namedTasks,
            Duration timeout,
            Executor executor) {
        if (namedTasks == null || namedTasks.isEmpty()) {
            return Map.of();
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }

        Map<String, CompletableFuture<Object>> futures = new LinkedHashMap<>();
        namedTasks.forEach((name, supplier) -> {
            CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return supplier.get();
                } catch (RuntimeException ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }, executor);
            futures.put(name, future);
        });

        CompletableFuture<Void> all = CompletableFuture.allOf(
                futures.values().toArray(CompletableFuture[]::new));
        try {
            all.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            log.warn("Parallel query wall-clock timeout after {}", timeout);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Parallel query wait interrupted");
        } catch (Exception ex) {
            log.debug("Parallel allOf ended with exception (collecting per-task): {}",
                    ex.toString());
        }

        Map<String, Object> results = new LinkedHashMap<>();
        futures.forEach((name, future) -> results.put(name, resolve(name, future)));
        return results;
    }

    private static Object resolve(String name, CompletableFuture<Object> future) {
        if (!future.isDone()) {
            future.cancel(true);
            log.warn("Parallel task '{}' timed out; returning null", name);
            return null;
        }
        if (future.isCompletedExceptionally()) {
            try {
                future.join();
            } catch (Exception ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                log.warn("Parallel task '{}' failed; returning null: {}", name, cause.toString());
            }
            return null;
        }
        if (future.isCancelled()) {
            log.warn("Parallel task '{}' cancelled; returning null", name);
            return null;
        }
        return future.join();
    }
}
