package com.jason.demo.demo2.framework.web.exception;

public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode.getCode(), errorCode.getDesc());
    }

    public BusinessException(ErrorCode errorCode, String overrideMessage) {
        this(errorCode.getCode(), overrideMessage);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
