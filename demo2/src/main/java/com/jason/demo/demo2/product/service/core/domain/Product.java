package com.jason.demo.demo2.product.service.core.domain;

import com.jason.demo.demo2.product.service.infrastructure.dao.entity.ProductDO;

public class Product extends ProductDO {

    public static Product from(ProductDO source) {
        if (source == null) {
            return null;
        }
        Product product = new Product();
        product.setId(source.getId());
        product.setProductId(source.getProductId());
        product.setProductName(source.getProductName());
        product.setSubtitle(source.getSubtitle());
        product.setCoverUrl(source.getCoverUrl());
        product.setSellPrice(source.getSellPrice());
        product.setMarketPrice(source.getMarketPrice());
        product.setDetailContent(source.getDetailContent());
        product.setStatus(source.getStatus());
        product.setSort(source.getSort());
        product.setCreatedAt(source.getCreatedAt());
        product.setUpdatedAt(source.getUpdatedAt());
        return product;
    }
}
