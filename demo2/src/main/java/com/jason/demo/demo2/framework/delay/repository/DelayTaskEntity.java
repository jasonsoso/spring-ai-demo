package com.jason.demo.demo2.framework.delay.repository;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 延时任务台账实体，表 {@code delay_task}。
 */
@Data
@TableName("delay_task")
public class DelayTaskEntity {

    /** 雪花任务 ID（业务输入，非库自增） */
    @TableId(value = "task_id", type = IdType.INPUT)
    private Long taskId;

    /** 任务类型，路由 {@link com.jason.demo.demo2.framework.delay.DelayTaskHandler} */
    private String taskType;

    /** 业务键（如 orderId），用于取消与查询 */
    private String bizKey;

    /** 可选 JSON/文本载荷 */
    private String payload;

    /** 计划执行时间 */
    private LocalDateTime executeAt;

    /** 见 {@link com.jason.demo.demo2.framework.delay.DelayTaskStatus} */
    private String status;

    /** 已重试次数 */
    private Integer retryCount;

    /** 允许的最大重试次数 */
    private Integer maxRetry;

    /** 调度时主后端名（redisson / rocketmq） */
    private String backend;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
