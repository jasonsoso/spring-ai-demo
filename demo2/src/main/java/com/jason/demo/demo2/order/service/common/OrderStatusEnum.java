package com.jason.demo.demo2.order.service.common;

/**
 * 订单状态（COLA {@code S}）。库列 {@code order_status} 只存 SUBMIT / COMPLETED / CANCEL。
 */
public enum OrderStatusEnum {

    /** 状态机起点，不落库。下单 {@code fireEvent} 时作为 source。 */
    INIT,

    /** 已提交待支付。下单成功写入；列表「待支付」Tab / counts.pendingCount。 */
    SUBMIT,

    /** 已支付完成。终态；列表「已完成」Tab / counts.completedCount。 */
    COMPLETED,

    /** 已取消（手动或超时）。终态；只出现在「全部」Tab。 */
    CANCEL;

    /** 完成或取消后不可再流转。 */
    public boolean isFinalStatus() {
        return this == COMPLETED || this == CANCEL;
    }
}
