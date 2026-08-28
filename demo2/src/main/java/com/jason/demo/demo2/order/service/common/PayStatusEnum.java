package com.jason.demo.demo2.order.service.common;

/**
 * 支付伴随字段（库列 {@code pay_status}），不是第二条状态机。
 * 随订单事件写入：SUBMIT→WAIT_PAY，COMPLETED→PAY_SUCCESS，CANCEL→CLOSE。
 */
public enum PayStatusEnum {

    /** 待支付。随 SUBMIT_ORDER 写入。 */
    WAIT_PAY,

    /** 支付成功。随 PAY_SUCCESS 写入，同时记 {@code pay_time}。 */
    PAY_SUCCESS,

    /** 关闭。随 CANCEL_ORDER / ORDER_EXPIRE 写入，同时记 {@code cancel_time}。 */
    CLOSE
}
