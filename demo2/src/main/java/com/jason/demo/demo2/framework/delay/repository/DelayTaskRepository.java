package com.jason.demo.demo2.framework.delay.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jason.demo.demo2.framework.delay.DelayTaskStatus;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Repository
public class DelayTaskRepository {

    private final DelayTaskMapper mapper;

    public DelayTaskRepository(DelayTaskMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(DelayTaskEntity entity) {
        mapper.insert(entity);
    }

    public Optional<DelayTaskEntity> findById(long taskId) {
        return Optional.ofNullable(mapper.selectById(taskId));
    }

    public List<DelayTaskEntity> findDuePending(Instant now, int limit) {
        LocalDateTime deadline = LocalDateTime.ofInstant(now, ZoneId.systemDefault());
        return mapper.selectList(new LambdaQueryWrapper<DelayTaskEntity>()
                .eq(DelayTaskEntity::getStatus, DelayTaskStatus.PENDING.name())
                .le(DelayTaskEntity::getExecuteAt, deadline)
                .orderByAsc(DelayTaskEntity::getExecuteAt)
                .last("LIMIT " + Math.max(1, limit)));
    }

    public List<DelayTaskEntity> findByBizKey(String bizKey) {
        return mapper.selectList(new LambdaQueryWrapper<DelayTaskEntity>()
                .eq(DelayTaskEntity::getBizKey, bizKey)
                .orderByDesc(DelayTaskEntity::getCreatedAt));
    }

    public Optional<DelayTaskEntity> findPendingByBizKey(String taskType, String bizKey) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<DelayTaskEntity>()
                .eq(DelayTaskEntity::getTaskType, taskType)
                .eq(DelayTaskEntity::getBizKey, bizKey)
                .eq(DelayTaskEntity::getStatus, DelayTaskStatus.PENDING.name())
                .last("LIMIT 1")));
    }

    public boolean markCancelled(String taskType, String bizKey) {
        LocalDateTime now = LocalDateTime.now();
        int rows = mapper.update(null, new LambdaUpdateWrapper<DelayTaskEntity>()
                .eq(DelayTaskEntity::getTaskType, taskType)
                .eq(DelayTaskEntity::getBizKey, bizKey)
                .eq(DelayTaskEntity::getStatus, DelayTaskStatus.PENDING.name())
                .set(DelayTaskEntity::getStatus, DelayTaskStatus.CANCELLED.name())
                .set(DelayTaskEntity::getUpdatedAt, now));
        return rows > 0;
    }

    public boolean markCancelledById(long taskId) {
        LocalDateTime now = LocalDateTime.now();
        int rows = mapper.update(null, new LambdaUpdateWrapper<DelayTaskEntity>()
                .eq(DelayTaskEntity::getTaskId, taskId)
                .eq(DelayTaskEntity::getStatus, DelayTaskStatus.PENDING.name())
                .set(DelayTaskEntity::getStatus, DelayTaskStatus.CANCELLED.name())
                .set(DelayTaskEntity::getUpdatedAt, now));
        return rows > 0;
    }

    public boolean casStatus(long taskId, String from, String to) {
        LocalDateTime now = LocalDateTime.now();
        int rows = mapper.update(null, new LambdaUpdateWrapper<DelayTaskEntity>()
                .eq(DelayTaskEntity::getTaskId, taskId)
                .eq(DelayTaskEntity::getStatus, from)
                .set(DelayTaskEntity::getStatus, to)
                .set(DelayTaskEntity::getUpdatedAt, now));
        return rows > 0;
    }

    public void markSuccess(long taskId) {
        LocalDateTime now = LocalDateTime.now();
        mapper.update(null, new LambdaUpdateWrapper<DelayTaskEntity>()
                .eq(DelayTaskEntity::getTaskId, taskId)
                .set(DelayTaskEntity::getStatus, DelayTaskStatus.SUCCESS.name())
                .set(DelayTaskEntity::getUpdatedAt, now));
    }

    public void scheduleRetry(long taskId, int newRetry, Instant newExecuteAt) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime executeAt = LocalDateTime.ofInstant(newExecuteAt, ZoneId.systemDefault());
        mapper.update(null, new LambdaUpdateWrapper<DelayTaskEntity>()
                .eq(DelayTaskEntity::getTaskId, taskId)
                .set(DelayTaskEntity::getStatus, DelayTaskStatus.PENDING.name())
                .set(DelayTaskEntity::getRetryCount, newRetry)
                .set(DelayTaskEntity::getExecuteAt, executeAt)
                .set(DelayTaskEntity::getUpdatedAt, now));
    }

    public void markFailed(long taskId) {
        LocalDateTime now = LocalDateTime.now();
        mapper.update(null, new LambdaUpdateWrapper<DelayTaskEntity>()
                .eq(DelayTaskEntity::getTaskId, taskId)
                .set(DelayTaskEntity::getStatus, DelayTaskStatus.FAILED.name())
                .set(DelayTaskEntity::getUpdatedAt, now));
    }
}
