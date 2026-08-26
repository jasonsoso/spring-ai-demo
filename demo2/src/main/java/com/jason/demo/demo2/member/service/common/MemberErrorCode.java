package com.jason.demo.demo2.member.service.common;

import com.jason.demo.demo2.framework.web.exception.ErrorCode;

public enum MemberErrorCode implements ErrorCode {

    PHONE_ALREADY_REGISTERED(20001, "手机号已注册"),
    MEMBER_NOT_FOUND(20002, "会员不存在"),
    PASSWORD_ERROR(20003, "密码错误"),
    MEMBER_CANNOT_LOGIN(20004, "会员状态不可登录"),
    PHONE_REQUIRED(20005, "手机号不能为空"),
    PASSWORD_REQUIRED(20006, "密码不能为空");

    private final int code;
    private final String desc;

    MemberErrorCode(int code, String desc) {
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
