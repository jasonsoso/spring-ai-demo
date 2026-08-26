package com.jason.demo.demo2.framework.web.result;

import com.jason.demo.demo2.framework.web.exception.CommonErrorCodeEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JsonResultsTest {

    @Test
    void ok_wrapsDataWithSuccessCode() {
        JsonResult<String> result = JsonResults.ok("hello");

        assertEquals(0, result.getCode());
        assertEquals("success", result.getMessage());
        assertEquals("hello", result.getData());
    }

    @Test
    void fail_fromErrorCode() {
        JsonResult<Void> result = JsonResults.fail(CommonErrorCodeEnum.UNAUTHORIZED);

        assertEquals(10003, result.getCode());
        assertEquals(CommonErrorCodeEnum.UNAUTHORIZED.getDesc(), result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void fail_fromCustomCodeAndMessage() {
        JsonResult<Void> result = JsonResults.fail(10002, "amount is required");

        assertEquals(10002, result.getCode());
        assertEquals("amount is required", result.getMessage());
        assertNull(result.getData());
    }
}
