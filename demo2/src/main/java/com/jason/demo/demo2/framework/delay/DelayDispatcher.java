package com.jason.demo.demo2.framework.delay;

import com.jason.demo.demo2.framework.delay.backend.DelayBackend;
import com.jason.demo.demo2.framework.delay.config.DelayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DelayDispatcher {

    private final DelayProperties properties;
    private final Map<String, DelayBackend> backends;

    public DelayDispatcher(DelayProperties properties, List<DelayBackend> backendList) {
        this.properties = properties;
        this.backends = backendList.stream()
                .collect(Collectors.toMap(
                        b -> b.name().toLowerCase(Locale.ROOT),
                        Function.identity(),
                        (a, b) -> a));
    }

    public void schedule(long taskId, Duration delay) {
        resolvePrimary().schedule(taskId, delay);
    }

    public void cancel(long taskId) {
        // 两个 backend 都尝试：Redisson 撤队；RocketMQ no-op
        for (DelayBackend backend : backends.values()) {
            try {
                backend.cancel(taskId);
            } catch (Exception e) {
                log.warn("delay backend cancel failed, backend={}, taskId={}", backend.name(), taskId, e);
            }
        }
    }

    public String primaryBackendName() {
        return resolvePrimary().name();
    }

    private DelayBackend resolvePrimary() {
        String name = properties.getBackend() == null
                ? "redisson"
                : properties.getBackend().trim().toLowerCase(Locale.ROOT);
        DelayBackend backend = backends.get(name);
        if (backend == null) {
            throw new IllegalStateException("unknown app.delay.backend=" + properties.getBackend()
                    + ", available=" + backends.keySet());
        }
        return backend;
    }
}
