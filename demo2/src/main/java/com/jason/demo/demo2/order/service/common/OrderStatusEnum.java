package com.jason.demo.demo2.order.service.common;

public enum OrderStatusEnum {
    INIT,
    SUBMIT,
    COMPLETED,
    CANCEL;

    public boolean isFinalStatus() {
        return this == COMPLETED || this == CANCEL;
    }
}
