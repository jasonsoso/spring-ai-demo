package com.jason.demo.demo2.member.service.core;

public class MemberDomainException extends RuntimeException {

    public enum Code {
        NOT_FOUND,
        CONFLICT,
        BAD_REQUEST
    }

    private final Code code;

    public MemberDomainException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
