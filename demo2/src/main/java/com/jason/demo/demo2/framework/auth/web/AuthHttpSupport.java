package com.jason.demo.demo2.framework.auth.web;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.framework.web.exception.CommonErrorCode;

public final class AuthHttpSupport {

    private AuthHttpSupport() {
    }

    public static BusinessException unauthorized() {
        return new BusinessException(CommonErrorCode.UNAUTHORIZED);
    }

    public static BusinessException invalidToken() {
        return new BusinessException(CommonErrorCode.INVALID_TOKEN);
    }
}
