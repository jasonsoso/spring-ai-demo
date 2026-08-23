package com.jason.demo.demo2.framework.delay.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 延时任务框架配置，前缀 {@code app.delay}。
 */
@Data
@ConfigurationProperties(prefix = "app.delay")
public class DelayProperties {

    /** 主投递后端：{@code rocketmq}（默认）或 {@code redisson} */
    private String backend = "rocketmq";

    /** schedule 未传 delay 时的默认延时 */
    private Duration defaultDelay = Duration.ofSeconds(30);

    /** 台账扫描间隔（毫秒），对应 {@code @Scheduled fixedDelayString} */
    private long scanIntervalMs = 5000;

    /** 单任务最大重试次数 */
    private int maxRetry = 3;

    /** 执行时分布式锁持有超时 */
    private Duration lockTimeout = Duration.ofSeconds(10);

    /** 每次扫描最多捞取的到期任务数 */
    private int scanBatchSize = 50;

    /** 是否启用 FallbackScanner 扫描级分布式锁（多节点互斥） */
    private boolean scanLockEnabled = true;

    /** 扫描锁 TTL，应 ≥ 单次扫描最大耗时 */
    private Duration scanLockTimeout = Duration.ofSeconds(10);

    /** Redisson 到期后的目标阻塞队列名 */
    private String redissonQueueName = "demo2:delay:queue";

    /** 预留：独立延迟队列名（当前用 DelayedQueue 包装目标队列） */
    private String redissonDelayQueueName = "demo2:delay:delayed";
}
