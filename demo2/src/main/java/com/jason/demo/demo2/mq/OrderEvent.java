package com.jason.demo.demo2.mq;

import java.time.Instant;

public record OrderEvent(String orderId, String type, String payload, Instant createdAt) {
}
