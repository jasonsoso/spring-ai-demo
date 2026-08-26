package com.jason.demo.demo2.order.app.support;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.order.service.common.OrderErrorCode;

import java.time.Duration;

public final class OrderDelayParser {

    private OrderDelayParser() {
    }

    public static Duration parseDelay(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            if (value.startsWith("P") || value.startsWith("p")) {
                return Duration.parse(value);
            }
            if (value.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
            }
            if (value.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
            }
            if (value.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1)));
            }
            if (value.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(value.substring(0, value.length() - 1)));
            }
            return Duration.parse(value);
        } catch (Exception e) {
            throw new BusinessException(OrderErrorCode.INVALID_DELAY, "invalid delay: " + raw);
        }
    }
}
