package com.jason.demo.demo2.product;

import com.alicp.jetcache.support.DecodeFilter;
import com.alicp.jetcache.support.Kryo5ValueDecoder;
import com.alicp.jetcache.support.Kryo5ValueEncoder;
import com.jason.demo.demo2.product.service.common.ProductStatusEnum;
import com.jason.demo.demo2.product.service.core.domain.Product;
import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import com.jason.demo.demo2.product.service.core.domain.ProductWithStock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ProductWithStockKryoTest {

    @BeforeAll
    static void allowProductTypes() {
        DecodeFilter.getDefault().addAllowPatterns("com.jason.demo.demo2.product.");
    }

    @Test
    void kryo5_roundTripsProductWithStockAndList() {
        Product product = new Product();
        product.setProductId(2085550503315509001L);
        product.setProductName("拿铁");
        product.setSellPrice(new BigDecimal("18.00"));
        product.setStatus(ProductStatusEnum.ON_SHELF.name());
        product.setCreatedAt(LocalDateTime.of(2026, 8, 26, 10, 0, 0));
        product.setUpdatedAt(LocalDateTime.of(2026, 8, 26, 10, 0, 0));

        ProductStock stock = new ProductStock();
        stock.setProductId(2085550503315509001L);
        stock.setStock(80);
        stock.setSellStock(12);
        stock.setActualStock(80);
        stock.setWithholdStock(0);
        stock.setUpdatedAt(LocalDateTime.of(2026, 8, 26, 10, 0, 0));

        ProductWithStock row = new ProductWithStock(product, stock);
        byte[] bytes = Kryo5ValueEncoder.INSTANCE.apply(row);
        ProductWithStock decoded = assertInstanceOf(
                ProductWithStock.class, Kryo5ValueDecoder.INSTANCE.apply(bytes));

        assertEquals(2085550503315509001L, decoded.getProduct().getProductId());
        assertEquals("拿铁", decoded.getProduct().getProductName());
        assertEquals(new BigDecimal("18.00"), decoded.getProduct().getSellPrice());
        assertEquals(80, decoded.getStock().getStock());
        assertEquals(12, decoded.getStock().getSellStock());

        ArrayList<ProductWithStock> list = new ArrayList<>(List.of(row));
        byte[] listBytes = Kryo5ValueEncoder.INSTANCE.apply(list);
        @SuppressWarnings("unchecked")
        List<ProductWithStock> decodedList =
                (List<ProductWithStock>) Kryo5ValueDecoder.INSTANCE.apply(listBytes);
        assertEquals(1, decodedList.size());
        assertEquals("拿铁", decodedList.get(0).getProduct().getProductName());
    }
}
