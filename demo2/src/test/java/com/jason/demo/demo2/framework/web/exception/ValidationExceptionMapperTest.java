package com.jason.demo.demo2.framework.web.exception;

import com.jason.demo.demo2.framework.web.result.JsonResult;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationExceptionMapperTest {

    @Test
    void notNullOnly_mapsToParamMissing() {
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "request");
        br.addError(new FieldError("request", "productId", null, false,
                new String[]{"NotNull.productId", "NotNull"}, null, "不能为空"));

        JsonResult<Void> result = ValidationExceptionMapper.fromBindingResult(br);

        assertEquals(CommonErrorCodeEnum.PARAM_MISSING.getCode(), result.getCode());
        assertTrue(result.getMessage().contains("productId"));
    }

    @Test
    void patternOnly_mapsToBadRequest() {
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "request");
        br.addError(new FieldError("request", "phone", "bad", false,
                new String[]{"Pattern.phone", "Pattern"}, null, "格式不正确"));

        JsonResult<Void> result = ValidationExceptionMapper.fromBindingResult(br);

        assertEquals(CommonErrorCodeEnum.BAD_REQUEST.getCode(), result.getCode());
        assertTrue(result.getMessage().contains("phone"));
    }

    @Test
    void mixedWithNotNull_prefersParamMissing() {
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "request");
        br.addError(new FieldError("request", "phone", "bad", false,
                new String[]{"Pattern.phone", "Pattern"}, null, "格式不正确"));
        br.addError(new FieldError("request", "password", null, false,
                new String[]{"NotBlank.password", "NotBlank"}, null, "不能为空"));

        JsonResult<Void> result = ValidationExceptionMapper.fromBindingResult(br);

        assertEquals(CommonErrorCodeEnum.PARAM_MISSING.getCode(), result.getCode());
        assertTrue(result.getMessage().contains("phone"));
        assertTrue(result.getMessage().contains("password"));
    }

    @Test
    void bindException_handler_delegates() {
        Demo2GlobalExceptionHandler handler = new Demo2GlobalExceptionHandler();
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "request");
        br.addError(new FieldError("request", "orderId", null, false,
                new String[]{"NotNull"}, null, "不能为空"));

        JsonResult<Void> result = handler.handleBindException(new BindException(br));

        assertEquals(CommonErrorCodeEnum.PARAM_MISSING.getCode(), result.getCode());
    }
}
