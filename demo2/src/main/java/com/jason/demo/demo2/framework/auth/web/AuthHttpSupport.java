package com.jason.demo.demo2.framework.auth.web;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.framework.web.exception.CommonErrorCodeEnum;

public final class AuthHttpSupport {

    private AuthHttpSupport() {
    }

    public static BusinessException unauthorized() {
        return new BusinessException(CommonErrorCodeEnum.UNAUTHORIZED);
    }

    public static BusinessException invalidToken() {
        return new BusinessException(CommonErrorCodeEnum.INVALID_TOKEN);
    }
}
