package com.jason.demo.demo2.framework.delay.repository;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("delay_task")
public class DelayTaskEntity {

    @TableId(value = "task_id", type = IdType.INPUT)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;
    private String taskType;
    private String bizKey;
    private String payload;
    private LocalDateTime executeAt;
    private String status;
    private Integer retryCount;
    private Integer maxRetry;
    private String backend;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
