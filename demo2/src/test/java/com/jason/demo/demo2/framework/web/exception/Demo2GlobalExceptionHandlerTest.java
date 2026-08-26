package com.jason.demo.demo2.framework.web.exception;

import com.jason.demo.demo2.framework.web.result.JsonResult;
import com.jason.demo.demo2.order.service.common.OrderErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class Demo2GlobalExceptionHandlerTest {

    private final Demo2GlobalExceptionHandler handler = new Demo2GlobalExceptionHandler();

    @Test
    void handleBusinessException_returnsOkJsonResult() {
        JsonResult<Void> result = handler.handleBusinessException(
                new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));

        assertEquals(30001, result.getCode());
        assertEquals("订单不存在", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void handleBusinessException_preservesOverrideMessage() {
        JsonResult<Void> result = handler.handleBusinessException(
                new BusinessException(OrderErrorCode.ORDER_STATUS_CONFLICT,
                        "cannot pay order in status PAID"));

        assertEquals(30002, result.getCode());
        assertEquals("cannot pay order in status PAID", result.getMessage());
    }
}
