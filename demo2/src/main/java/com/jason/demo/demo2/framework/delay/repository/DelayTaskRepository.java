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

/**
 * 延时任务台账仓储：封装 MyBatis-Plus 查询与状态变更（含 CAS / 取消 / 重试回写）。
 */
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

    /** 扫描到期 PENDING，按 executeAt 升序，LIMIT batch。 */
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

    /** 按业务键将 PENDING → CANCELLED。 */
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

    /** 按 taskId 将 PENDING → CANCELLED。 */
    public boolean markCancelledById(long taskId) {
        LocalDateTime now = LocalDateTime.now();
        int rows = mapper.update(null, new LambdaUpdateWrapper<DelayTaskEntity>()
                .eq(DelayTaskEntity::getTaskId, taskId)
                .eq(DelayTaskEntity::getStatus, DelayTaskStatus.PENDING.name())
                .set(DelayTaskEntity::getStatus, DelayTaskStatus.CANCELLED.name())
                .set(DelayTaskEntity::getUpdatedAt, now));
        return rows > 0;
    }

    /** 乐观 CAS：仅当当前状态为 from 时改为 to。 */
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

    /** 失败重试：回 PENDING，更新 retryCount 与下次 executeAt。 */
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
