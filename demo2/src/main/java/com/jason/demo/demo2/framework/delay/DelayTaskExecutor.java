package com.jason.demo.demo2.framework.delay;

import com.baomidou.lock.LockInfo;
import com.baomidou.lock.LockTemplate;
import com.jason.demo.demo2.framework.delay.config.DelayProperties;
import com.jason.demo.demo2.framework.delay.repository.DelayTaskEntity;
import com.jason.demo.demo2.framework.delay.repository.DelayTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 延时任务执行器：分布式锁防重，CAS 推进状态，按 taskType 路由 {@link DelayTaskHandler}，失败按退避重试。
 */
@Slf4j
@Component
public class DelayTaskExecutor {

    /** 第 1/2/3 次重试间隔：5s / 15s / 30s */
    private static final Duration[] RETRY_BACKOFF = {
            Duration.ofSeconds(5),
            Duration.ofSeconds(15),
            Duration.ofSeconds(30)
    };

    private final DelayTaskRepository repository;
    private final LockTemplate lockTemplate;
    private final DelayProperties properties;
    /** key = {@link DelayTaskHandler#taskType()} */
    private final Map<String, DelayTaskHandler> handlers;

    public DelayTaskExecutor(
            DelayTaskRepository repository,
            LockTemplate lockTemplate,
            DelayProperties properties,
            List<DelayTaskHandler> handlerList) {
        this.repository = repository;
        this.lockTemplate = lockTemplate;
        this.properties = properties;
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(DelayTaskHandler::taskType, Function.identity(), (a, b) -> a));
    }

    /**
     * 执行指定任务（主路径到期或扫描兜底均可调用）。
     * 抢不到锁则直接返回，由其他持锁方或下次扫描再试。
     */
    public void execute(long taskId) {
        String lockKey = "delay:task:" + taskId;
        long expireMs = properties.getLockTimeout().toMillis();
        LockInfo lockInfo = lockTemplate.lock(lockKey, expireMs, 0L);
        if (lockInfo == null) {
            log.debug("skip delay task, lock not acquired, taskId={}", taskId);
            return;
        }
        try {
            doExecute(taskId);
        } finally {
            try {
                lockTemplate.releaseLock(lockInfo);
            } catch (Exception e) {
                log.warn("release delay task lock failed, taskId={}", taskId, e);
            }
        }
    }

    private void doExecute(long taskId) {
        DelayTaskEntity task = repository.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("delay task not found, taskId={}", taskId);
            return;
        }
        if (!DelayTaskStatus.PENDING.name().equals(task.getStatus())) {
            log.debug("skip delay task, status={}, taskId={}", task.getStatus(), taskId);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (task.getExecuteAt() != null && task.getExecuteAt().isAfter(now)) {
            log.debug("skip delay task, not due yet, taskId={}, executeAt={}", taskId, task.getExecuteAt());
            return;
        }
        if (!repository.casStatus(taskId, DelayTaskStatus.PENDING.name(), DelayTaskStatus.RUNNING.name())) {
            return;
        }

        DelayTaskHandler handler = handlers.get(task.getTaskType());
        if (handler == null) {
            log.error("no DelayTaskHandler for taskType={}, taskId={}", task.getTaskType(), taskId);
            repository.markFailed(taskId);
            return;
        }

        try {
            handler.handle(task);
            repository.markSuccess(taskId);
        } catch (Exception e) {
            int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
            int maxRetry = task.getMaxRetry() == null ? properties.getMaxRetry() : task.getMaxRetry();
            int nextRetry = retryCount + 1;
            if (nextRetry <= maxRetry) {
                Duration backoff = RETRY_BACKOFF[Math.min(nextRetry - 1, RETRY_BACKOFF.length - 1)];
                Instant nextExecuteAt = Instant.now().plus(backoff);
                repository.scheduleRetry(taskId, nextRetry, nextExecuteAt);
                log.warn("delay task failed, will retry, taskId={}, retryCount={}, nextExecuteAt={}",
                        taskId, nextRetry, LocalDateTime.ofInstant(nextExecuteAt, ZoneId.systemDefault()), e);
            } else {
                repository.markFailed(taskId);
                log.error("delay task failed permanently, taskId={}", taskId, e);
            }
        }
    }
}
