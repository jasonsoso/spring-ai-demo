package com.jason.demo.demo2.framework.delay;

import com.jason.demo.demo2.framework.delay.repository.DelayTaskEntity;

/**
 * 业务侧延时任务处理器：实现并注册为 Spring Bean，由 {@link DelayTaskExecutor} 按 {@link #taskType()} 路由。
 */
public interface DelayTaskHandler {

    /** 任务类型常量，与台账 {@code task_type}、调度入参一致。 */
    String taskType();

    /**
     * 到期执行逻辑；抛异常将触发重试或最终 FAILED。
     */
    void handle(DelayTaskEntity task);
}
