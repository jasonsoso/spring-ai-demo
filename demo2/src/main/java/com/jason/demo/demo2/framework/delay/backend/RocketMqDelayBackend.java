package com.jason.demo.demo2.framework.delay.backend;

import com.jason.demo.demo2.framework.delay.support.DelayTimeLevelMapper;
import com.jason.demo.demo2.framework.rocketmq.DelayTimeLevel;
import com.jason.demo.demo2.mq.model.DelayTaskMessage;
import com.jason.demo.demo2.mq.publisher.DelayTaskEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * RocketMQ 延时后端：将 Duration 映射到固定 18 档 delayTimeLevel 后发送；取消为逻辑取消（no-op）。
 */
@Slf4j
@Component
public class RocketMqDelayBackend implements DelayBackend {

    public static final String NAME = "rocketmq";

    private final DelayTaskEventPublisher publisher;

    public RocketMqDelayBackend(DelayTaskEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void schedule(long taskId, Duration delay) {
        DelayTimeLevel level = DelayTimeLevelMapper.mapAtLeast(delay);
        publisher.sendDelay(new DelayTaskMessage(taskId), level);
        log.info("rocketmq delay scheduled, taskId={}, level={}", taskId, level.name());
    }

    @Override
    public void cancel(long taskId) {
        // RocketMQ 固定延时消息无法撤回；依赖台账 CANCELLED + 消费时校验
        log.debug("rocketmq delay cancel is no-op (logical cancel via ledger), taskId={}", taskId);
    }
}
