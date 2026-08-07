package com.jason.demo.demo2.framework.delay;

/**
 * 延时任务台账状态。
 */
public enum DelayTaskStatus {
    /** 待执行（含重试回写后） */
    PENDING,
    /** 执行中（CAS 抢占后） */
    RUNNING,
    /** 业务处理成功 */
    SUCCESS,
    /** 超过最大重试或无 Handler */
    FAILED,
    /** 业务主动取消或支付后撤销 */
    CANCELLED
}
