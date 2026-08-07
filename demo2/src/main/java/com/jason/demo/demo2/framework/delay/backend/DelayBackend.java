package com.jason.demo.demo2.framework.delay.backend;

import java.time.Duration;

/**
 * 延时投递后端抽象：当前实现 Redisson DelayedQueue / RocketMQ 固定延时级别。
 */
public interface DelayBackend {

    /** 后端标识，写入台账并与 {@code app.delay.backend} 对应。 */
    String name();

    /** 按延时投递 taskId，到期后由本后端触发执行。 */
    void schedule(long taskId, Duration delay);

    /**
     * 尽量撤回未到期消息。
     * <p>Redisson 可从延迟队列移除；RocketMQ 固定延时无法撤回，实现可为 no-op。
     */
    void cancel(long taskId);
}
