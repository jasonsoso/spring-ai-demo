package com.jason.demo.demo2.framework.delay;

import com.jason.demo.demo2.framework.delay.config.DelayProperties;
import com.jason.demo.demo2.framework.delay.repository.DelayTaskEntity;
import com.jason.demo.demo2.framework.delay.repository.DelayTaskRepository;
import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class DelayTaskService {

    private final DelayTaskRepository repository;
    private final DelayDispatcher dispatcher;
    private final SnowflakeIdGenerator idGenerator;
    private final DelayProperties properties;

    public DelayTaskService(
            DelayTaskRepository repository,
            DelayDispatcher dispatcher,
            SnowflakeIdGenerator idGenerator,
            DelayProperties properties) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.idGenerator = idGenerator;
        this.properties = properties;
    }

    public long schedule(String taskType, String bizKey, String payload, Duration delay) {
        Duration effectiveDelay = delay == null ? properties.getDefaultDelay() : delay;
        long taskId = idGenerator.nextId();
        LocalDateTime now = LocalDateTime.now();
        DelayTaskEntity entity = new DelayTaskEntity();
        entity.setTaskId(taskId);
        entity.setTaskType(taskType);
        entity.setBizKey(bizKey);
        entity.setPayload(payload);
        entity.setExecuteAt(now.plus(effectiveDelay));
        entity.setStatus(DelayTaskStatus.PENDING.name());
        entity.setRetryCount(0);
        entity.setMaxRetry(properties.getMaxRetry());
        entity.setBackend(dispatcher.primaryBackendName());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        repository.insert(entity);

        try {
            dispatcher.schedule(taskId, effectiveDelay);
        } catch (Exception e) {
            log.warn("primary delay dispatch failed, rely on scanner, taskId={}", taskId, e);
        }
        return taskId;
    }

    public boolean cancelByBizKey(String taskType, String bizKey) {
        Optional<DelayTaskEntity> pending = repository.findPendingByBizKey(taskType, bizKey);
        boolean updated = repository.markCancelled(taskType, bizKey);
        pending.ifPresent(task -> dispatcher.cancel(task.getTaskId()));
        return updated;
    }

    public boolean cancelById(long taskId) {
        boolean updated = repository.markCancelledById(taskId);
        if (updated) {
            dispatcher.cancel(taskId);
        }
        return updated;
    }

    public Optional<DelayTaskEntity> get(long taskId) {
        return repository.findById(taskId);
    }

    public List<DelayTaskEntity> listByBizKey(String bizKey) {
        return repository.findByBizKey(bizKey);
    }
}
