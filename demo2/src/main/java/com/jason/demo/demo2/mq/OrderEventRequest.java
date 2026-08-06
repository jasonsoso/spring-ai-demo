package com.jason.demo.demo2.mq;

public record OrderEventRequest(String orderId, String type, String payload) {
}
