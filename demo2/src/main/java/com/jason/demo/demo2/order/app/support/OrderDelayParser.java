package com.jason.demo.demo2.order.app.support;

import com.jason.demo.demo2.framework.validation.DelayFormats;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.order.service.common.OrderErrorCodeEnum;

import java.time.Duration;

public final class OrderDelayParser {

    private OrderDelayParser() {
    }

    public static Duration parseDelay(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return DelayFormats.tryParse(raw)
                .orElseThrow(() -> new BusinessException(OrderErrorCodeEnum.INVALID_DELAY, "invalid delay: " + raw));
    }
}
