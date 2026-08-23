package com.jason.demo.demo2.order.service.core;

public class OrderDomainException extends RuntimeException {

    public enum Code {
        NOT_FOUND,
        CONFLICT,
        BAD_REQUEST
    }

    private final Code code;

    public OrderDomainException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
