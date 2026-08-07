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

/**
 * 按 {@code app.delay.backend} 选择主投递后端（默认 rocketmq）；取消时对所有已注册后端尽力尝试。
 */
@Slf4j
@Component
public class DelayDispatcher {

    private final DelayProperties properties;
    /** key = backend.name() 小写 */
    private final Map<String, DelayBackend> backends;

    public DelayDispatcher(DelayProperties properties, List<DelayBackend> backendList) {
        this.properties = properties;
        this.backends = backendList.stream()
                .collect(Collectors.toMap(
                        b -> b.name().toLowerCase(Locale.ROOT),
                        Function.identity(),
                        (a, b) -> a));
    }

    /** 投递到配置的主后端。 */
    public void schedule(long taskId, Duration delay) {
        resolvePrimary().schedule(taskId, delay);
    }

    /**
     * 取消：两个 backend 都尝试（Redisson 可撤队；RocketMQ 为 no-op，依赖台账状态）。
     */
    public void cancel(long taskId) {
        for (DelayBackend backend : backends.values()) {
            try {
                backend.cancel(taskId);
            } catch (Exception e) {
                log.warn("delay backend cancel failed, backend={}, taskId={}", backend.name(), taskId, e);
            }
        }
    }

    /** 当前主后端名称（写入台账 backend 字段）。 */
    public String primaryBackendName() {
        return resolvePrimary().name();
    }

    private DelayBackend resolvePrimary() {
        String name = properties.getBackend() == null
                ? "rocketmq"
                : properties.getBackend().trim().toLowerCase(Locale.ROOT);
        DelayBackend backend = backends.get(name);
        if (backend == null) {
            throw new IllegalStateException("unknown app.delay.backend=" + properties.getBackend()
                    + ", available=" + backends.keySet());
        }
        return backend;
    }
}
