package com.jason.demo.demo2.framework.delay;

import com.baomidou.lock.LockInfo;
import com.baomidou.lock.LockTemplate;
import com.jason.demo.demo2.framework.delay.config.DelayProperties;
import com.jason.demo.demo2.framework.delay.repository.DelayTaskEntity;
import com.jason.demo.demo2.framework.delay.repository.DelayTaskRepository;
import com.jason.demo.demo2.lock.LockKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * MySQL 台账扫描兜底：主路径（Redisson/RocketMQ）丢消息或投递失败时，仍能捞起到期 PENDING 任务执行。
 * <p>多节点下通过扫描级分布式锁避免重复扫 DB；单任务执行仍由 {@link DelayTaskExecutor} 按 taskId 防重。
 */
@Slf4j
@Component
public class FallbackScanner {

    private final DelayTaskRepository repository;
    private final DelayTaskExecutor executor;
    private final DelayProperties properties;
    private final LockTemplate lockTemplate;

    public FallbackScanner(
            DelayTaskRepository repository,
            DelayTaskExecutor executor,
            DelayProperties properties,
            LockTemplate lockTemplate) {
        this.repository = repository;
        this.executor = executor;
        this.properties = properties;
        this.lockTemplate = lockTemplate;
    }

    /** 固定间隔扫描到期 PENDING 任务，批量交给 {@link DelayTaskExecutor}。 */
    @Scheduled(fixedDelayString = "${app.delay.scan-interval-ms:5000}")
    public void scan() {
        if (!properties.isScanLockEnabled()) {
            doScan();
            return;
        }
        LockInfo lockInfo = lockTemplate.lock(
                LockKeys.delayScannerFallbackKey(),
                properties.getScanLockTimeout().toMillis(),
                0L);
        if (lockInfo == null) {
            log.debug("skip fallback scan, scanner lock not acquired");
            return;
        }
        try {
            doScan();
        } finally {
            releaseQuietly(lockInfo);
        }
    }

    private void doScan() {
        List<DelayTaskEntity> due = repository.findDuePending(Instant.now(), properties.getScanBatchSize());
        for (DelayTaskEntity task : due) {
            try {
                log.info("calling DelayTaskExecutor#execute from FallbackScanner, taskId={}",
                        task.getTaskId());
                executor.execute(task.getTaskId());
            } catch (Exception e) {
                log.error("fallback scan execute failed, taskId={}", task.getTaskId(), e);
            }
        }
    }

    private void releaseQuietly(LockInfo lockInfo) {
        try {
            lockTemplate.releaseLock(lockInfo);
        } catch (Exception e) {
            log.warn("release fallback scanner lock failed", e);
        }
    }
}
