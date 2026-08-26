package com.jason.demo.demo2.product.service.core.domain;

public class ProductWithStock {

    private Product product;
    private ProductStock stock;

    public ProductWithStock(Product product, ProductStock stock) {
        this.product = product;
        this.stock = stock;
    }

    public Product getProduct() {
        return product;
    }

    public ProductStock getStock() {
        return stock;
    }
}
