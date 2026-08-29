package com.jason.demo.demo2.product.service.core.domain;

public class ProductWithStock {

    private Product product;
    private ProductStock stock;

    public ProductWithStock() {
    }

    public ProductWithStock(Product product, ProductStock stock) {
        this.product = product;
        this.stock = stock;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public ProductStock getStock() {
        return stock;
    }

    public void setStock(ProductStock stock) {
        this.stock = stock;
    }
}
