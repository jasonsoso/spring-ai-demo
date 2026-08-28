package com.jason.demo.demo2.product;

import com.jason.demo.demo2.product.service.common.ProductStockOptTypeEnum;
import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductStockTest {

    @Test
    void from_copiesStockSeq() {
        ProductStock source = base(100, 0, 100, 10);
        source.setStockSeq(7L);
        ProductStock copy = ProductStock.from(source);
        assertEquals(7L, copy.getStockSeq());
        assertEquals(100, copy.getStock());
    }

    @Test
    void applyReserve_thenReverse_restores() {
        ProductStock before = base(100, 0, 100, 10);
        ProductStock after = before.copy().applyReserve(3);
        after.assertBalance();
        assertEquals(97, after.getStock());
        assertEquals(3, after.getWithholdStock());
        ProductStock restored = ProductStock.reverse(after, ProductStockOptTypeEnum.RESERVE, 3);
        assertEquals(100, restored.getStock());
        assertEquals(0, restored.getWithholdStock());
        assertNotSame(after, restored);
    }

    @Test
    void applyConfirm_doesNotChangeAvail() {
        ProductStock after = base(97, 3, 100, 10).applyConfirm(3);
        after.assertBalance();
        assertEquals(97, after.getStock());
        assertEquals(0, after.getWithholdStock());
        assertEquals(97, after.getActualStock());
        assertEquals(13, after.getSellStock());
        ProductStock restored = ProductStock.reverse(after, ProductStockOptTypeEnum.CONFIRM, 3);
        assertEquals(100, restored.getActualStock());
        assertEquals(3, restored.getWithholdStock());
        assertEquals(10, restored.getSellStock());
    }

    @Test
    void applyRelease_restoresAvail() {
        ProductStock after = base(97, 3, 100, 10).applyRelease(3);
        after.assertBalance();
        assertEquals(100, after.getStock());
        assertEquals(0, after.getWithholdStock());
    }

    @Test
    void applyAdjust_setsActualAndAvail() {
        ProductStock after = base(90, 10, 100, 5).applyAdjust(80);
        after.assertBalance();
        assertEquals(80, after.getActualStock());
        assertEquals(70, after.getStock());
        assertEquals(10, after.getWithholdStock());
    }

    @Test
    void applyAdjust_rejectsBelowWithhold() {
        assertThrows(IllegalArgumentException.class, () -> base(90, 10, 100, 5).applyAdjust(9));
    }

    private static ProductStock base(int stock, int withhold, int actual, int sell) {
        ProductStock s = new ProductStock();
        s.setStockId(1L);
        s.setProductId(9L);
        s.setStock(stock);
        s.setWithholdStock(withhold);
        s.setActualStock(actual);
        s.setSellStock(sell);
        s.setStockSeq(0L);
        return s;
    }
}
