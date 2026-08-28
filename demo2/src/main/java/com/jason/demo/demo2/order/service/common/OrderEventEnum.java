package com.jason.demo.demo2.order.service.common;

/**
 * 驱动 COLA 转移的事件。支付状态不单独做状态机，由对应 Action 写入 {@link PayStatusEnum}。
 */
public enum OrderEventEnum {

    /** INIT → SUBMIT：预览 token 校验通过后下单、落库并预占库存。 */
    SUBMIT_ORDER,

    /** SUBMIT → COMPLETED：模拟支付成功，CAS 后实扣库存。 */
    PAY_SUCCESS,

    /** SUBMIT → CANCEL：会员手动取消，CAS 后释放预占。 */
    CANCEL_ORDER,

    /** SUBMIT → CANCEL：延时超时关单，无登录态；CAS 失败则静默。 */
    ORDER_EXPIRE
}
