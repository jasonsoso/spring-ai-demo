package com.jason.demo.demo2.product.service.common;

/** 对账分流。IN_FLIGHT 不是故障；AVAIL_MISMATCH 仅在 seq 已齐时才有意义。 */
public enum ReconcileKindEnum {
    OK,
    IN_FLIGHT,
    IN_FLIGHT_SLOW,
    AVAIL_MISMATCH,
    MYSQL_AHEAD,
    REDIS_MISSING
}
