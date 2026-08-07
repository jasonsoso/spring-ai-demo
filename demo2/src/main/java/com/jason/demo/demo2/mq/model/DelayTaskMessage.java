package com.jason.demo.demo2.mq.model;

public class DelayTaskMessage {

    private Long taskId;

    public DelayTaskMessage() {
    }

    public DelayTaskMessage(Long taskId) {
        this.taskId = taskId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }
}
