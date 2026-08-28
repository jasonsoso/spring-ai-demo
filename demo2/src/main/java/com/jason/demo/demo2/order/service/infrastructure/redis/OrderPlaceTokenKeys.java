package com.jason.demo.demo2.order.service.infrastructure.redis;

/** Redis key：preview 凭证、下单互斥锁、同一 token 已生成的 orderId。 */
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
