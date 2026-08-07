package com.jason.demo.demo2.framework.delay;

/**
 * 内置延时任务类型常量（业务可自行扩展字符串，不必全放此处）。
 */
public final class DelayTaskType {

    /** 订单超时未支付取消 */
    public static final String ORDER_CANCEL = "ORDER_CANCEL";

    private DelayTaskType() {
    }
}
