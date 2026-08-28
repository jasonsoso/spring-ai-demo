package com.jason.demo.demo2.product.service.common;

/** 幂等键与 Lua XADD / 流水 uk 对齐：orderId:productId:OPT；ADJUST 用独立 adjustId。 */
public final class ProductStockIdempotentKeys {

    private ProductStockIdempotentKeys() {
    }

    public static String of(long orderId, long productId, ProductStockOptTypeEnum optType) {
        return orderId + ":" + productId + ":" + optType.name();
    }

    public static String ofAdjust(long adjustId) {
        return "ADJUST:" + adjustId;
    }
}
