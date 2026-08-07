package com.jason.demo.demo2.framework.delay;

import com.jason.demo.demo2.framework.delay.config.DelayProperties;
import com.jason.demo.demo2.framework.delay.repository.DelayTaskEntity;
import com.jason.demo.demo2.framework.delay.repository.DelayTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * MySQL 台账扫描兜底：主路径（Redisson/RocketMQ）丢消息或投递失败时，仍能捞起到期 PENDING 任务执行。
 */
@Slf4j
@Component
public class FallbackScanner {

    private final DelayTaskRepository repository;
    private final DelayTaskExecutor executor;
    private final DelayProperties properties;

    public FallbackScanner(
            DelayTaskRepository repository,
            DelayTaskExecutor executor,
            DelayProperties properties) {
        this.repository = repository;
        this.executor = executor;
        this.properties = properties;
    }

    /** 固定间隔扫描到期 PENDING 任务，批量交给 {@link DelayTaskExecutor}。 */
    @Scheduled(fixedDelayString = "${app.delay.scan-interval-ms:5000}")
    public void scan() {
        List<DelayTaskEntity> due = repository.findDuePending(Instant.now(), properties.getScanBatchSize());
        for (DelayTaskEntity task : due) {
            try {
                executor.execute(task.getTaskId());
            } catch (Exception e) {
                log.error("fallback scan execute failed, taskId={}", task.getTaskId(), e);
            }
        }
    }
}
