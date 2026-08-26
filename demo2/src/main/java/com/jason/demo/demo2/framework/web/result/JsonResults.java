package com.jason.demo.demo2.framework.web.result;

import com.jason.demo.demo2.framework.web.exception.CommonErrorCodeEnum;
import com.jason.demo.demo2.framework.web.exception.ErrorCode;

public final class JsonResults {

    private JsonResults() {
    }

    public static <T> JsonResult<T> ok(T data) {
        JsonResult<T> result = new JsonResult<>();
        result.setCode(CommonErrorCodeEnum.SUCCESS.getCode());
        result.setMessage(CommonErrorCodeEnum.SUCCESS.getDesc());
        result.setData(data);
        return result;
    }

    public static <T> JsonResult<T> fail(ErrorCode errorCode) {
        return fail(errorCode.getCode(), errorCode.getDesc());
    }

    public static <T> JsonResult<T> fail(int code, String message) {
        JsonResult<T> result = new JsonResult<>();
        result.setCode(code);
        result.setMessage(message);
        result.setData(null);
        return result;
    }
}
