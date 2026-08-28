package com.jason.demo.demo2.product.service.infrastructure.redis;

/** Redis 热库存 key。Hash 禁止放 actual/withhold/sell。 */
public final class RedisStockKeys {
    public static final String OUTBOX = "demo2:stock:outbox";

    private RedisStockKeys() {
    }

    public static String hash(long productId) {
        return "demo2:stock:" + productId;
    }

    /** CONFIRM/RELEASE 抢同一张票：谁先 DEL 谁赢。 */
    public static String ticket(long orderId, long productId) {
        return "demo2:stock:reserve:" + orderId + ":" + productId;
    }
}
