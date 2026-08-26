package com.jason.demo.demo2.product.service.core.domain;

import com.jason.demo.demo2.product.service.infrastructure.dao.entity.ProductStockDO;

public class ProductStock extends ProductStockDO {

    public static ProductStock from(ProductStockDO source) {
        if (source == null) {
            return null;
        }
        ProductStock stock = new ProductStock();
        stock.setId(source.getId());
        stock.setStockId(source.getStockId());
        stock.setProductId(source.getProductId());
        stock.setActualStock(source.getActualStock());
        stock.setStock(source.getStock());
        stock.setWithholdStock(source.getWithholdStock());
        stock.setSellStock(source.getSellStock());
        stock.setUpdatedAt(source.getUpdatedAt());
        return stock;
    }

    public void assertBalance() {
        if (getStock() == null || getActualStock() == null || getWithholdStock() == null) {
            throw new IllegalStateException("stock fields must not be null");
        }
        if (!getStock().equals(getActualStock() - getWithholdStock())) {
            throw new IllegalStateException("stock balance violated: stock="
                    + getStock() + ", actual=" + getActualStock() + ", withhold=" + getWithholdStock());
        }
    }
}
