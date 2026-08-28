package com.jason.demo.demo2.order.service.common;

import com.jason.demo.demo2.framework.web.exception.ErrorCode;

public enum OrderErrorCodeEnum implements ErrorCode {

    ORDER_NOT_FOUND(30001, "订单不存在"),
    ORDER_STATUS_CONFLICT(30002, "订单状态冲突"),
    AMOUNT_INVALID(30003, "订单金额必须大于 0"),
    ORDER_ID_REQUIRED(30004, "orderId 不能为空"),
    @Deprecated
    AMOUNT_REQUIRED(30005, "amount 不能为空"),
    @Deprecated
    INVALID_DELAY(30006, "delay 格式无效"),
    QTY_INVALID(30007, "qty 必须在 1~99999"),
    PRICE_CHANGED(30008, "商品售价已变动"),
    PLACE_TOKEN_INVALID(30009, "下单凭证无效"),
    ORDER_ITEMS_INVALID(30010, "订单商品行非法");

    private final int code;
    private final String desc;

    OrderErrorCodeEnum(int code, String desc) {
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
