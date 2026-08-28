package com.jason.demo.demo2.product.service.common;

import com.jason.demo.demo2.framework.web.exception.ErrorCode;

public enum ProductErrorCodeEnum implements ErrorCode {

    PRODUCT_NOT_FOUND(40001, "商品不存在"),
    PRODUCT_OFF_SHELF(40002, "商品已下架"),
    STOCK_INSUFFICIENT(40003, "可售库存不足"),
    RESERVE_LOG_NOT_FOUND(40004, "无待释放的预占流水"),
    STOCK_CONFLICT(40005, "库存状态冲突"),
    STOCK_NOT_FOUND(40007, "库存记录不存在"),
    ADJUST_REQUIRES_OFF_SHELF(40008, "调整库存前必须先下架"),
    ADJUST_INVALID_TARGET(40009, "目标现货非法"),
    STOCK_SYNC_LAG(40010, "库存同步未追上");

    private final int code;
    private final String desc;

    ProductErrorCodeEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getDesc() {
        return desc;
    }
}
