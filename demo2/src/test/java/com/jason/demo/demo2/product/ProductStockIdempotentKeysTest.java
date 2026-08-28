package com.jason.demo.demo2.product;

import com.jason.demo.demo2.product.service.common.ProductErrorCodeEnum;
import com.jason.demo.demo2.product.service.common.ProductStockIdempotentKeys;
import com.jason.demo.demo2.product.service.common.ProductStockOptTypeEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductStockIdempotentKeysTest {

    @Test
    void of_joinsOrderProductOpt() {
        assertEquals("100:9001:RESERVE",
                ProductStockIdempotentKeys.of(100L, 9001L, ProductStockOptTypeEnum.RESERVE));
        assertEquals("ADJUST:55", ProductStockIdempotentKeys.ofAdjust(55L));
    }

    @Test
    void newErrorCodes_areStable() {
        assertEquals(40008, ProductErrorCodeEnum.ADJUST_REQUIRES_OFF_SHELF.getCode());
        assertEquals(40009, ProductErrorCodeEnum.ADJUST_INVALID_TARGET.getCode());
        assertEquals(40010, ProductErrorCodeEnum.STOCK_SYNC_LAG.getCode());
    }
}
