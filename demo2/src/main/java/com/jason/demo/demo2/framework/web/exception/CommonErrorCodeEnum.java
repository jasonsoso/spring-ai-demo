package com.jason.demo.demo2.framework.web.exception;

public enum CommonErrorCodeEnum implements ErrorCode {

    SUCCESS(0, "success"),
    BAD_REQUEST(10001, "请求参数错误"),
    PARAM_MISSING(10002, "缺少必填参数"),
    UNAUTHORIZED(10003, "未登录或登录已失效"),
    INVALID_TOKEN(10004, "token 无效"),
    INTERNAL_ERROR(10999, "系统繁忙，请稍后重试");

    private final int code;
    private final String desc;

    CommonErrorCodeEnum(int code, String desc) {
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
