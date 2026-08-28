package com.jason.demo.demo2.order.service.infrastructure.redis;

public final class OrderPlaceTokenKeys {

    private OrderPlaceTokenKeys() {
    }

    public static String preview(String token) {
        return "demo:order:preview:" + token;
    }

    public static String lock(String token) {
        return "demo:order:place:lock:" + token;
    }

    public static String result(String token) {
        return "demo:order:place:result:" + token;
    }
}
