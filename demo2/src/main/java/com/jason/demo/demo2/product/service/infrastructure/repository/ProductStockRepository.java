package com.jason.demo.demo2.product.service.infrastructure.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.product.service.common.ProductErrorCodeEnum;
import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import com.jason.demo.demo2.product.service.infrastructure.dao.entity.ProductStockDO;
import com.jason.demo.demo2.product.service.infrastructure.dao.mapper.ProductStockMapper;
import com.jason.demo.demo2.product.service.infrastructure.repository.convert.ProductStockDoConvert;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ProductStockRepository {

    private final ProductStockMapper productStockMapper;
    private final ProductStockDoConvert productStockDoConvert;

    public ProductStockRepository(ProductStockMapper productStockMapper, ProductStockDoConvert productStockDoConvert) {
        this.productStockMapper = productStockMapper;
        this.productStockDoConvert = productStockDoConvert;
    }

    public Optional<ProductStock> findByProductId(long productId) {
        ProductStockDO row = productStockMapper.selectOne(new LambdaQueryWrapper<ProductStockDO>()
                .eq(ProductStockDO::getProductId, productId));
        return Optional.ofNullable(productStockDoConvert.toDomain(row));
    }

    public List<ProductStock> findByProductIds(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        return productStockMapper.selectList(new LambdaQueryWrapper<ProductStockDO>()
                        .in(ProductStockDO::getProductId, productIds)).stream()
                .map(productStockDoConvert::toDomain)
                .toList();
    }

    public List<ProductStock> findAll() {
        return productStockMapper.selectList(null).stream()
                .map(productStockDoConvert::toDomain)
                .toList();
    }

    public ProductStock requireByProductId(long productId) {
        return findByProductId(productId)
                .orElseThrow(() -> new BusinessException(ProductErrorCodeEnum.STOCK_NOT_FOUND));
    }

    public ProductStock requireByProductIdForUpdate(long productId) {
        ProductStockDO row = productStockMapper.selectOne(new LambdaQueryWrapper<ProductStockDO>()
                .eq(ProductStockDO::getProductId, productId)
                .last("FOR UPDATE"));
        if (row == null) {
            throw new BusinessException(ProductErrorCodeEnum.STOCK_NOT_FOUND);
        }
        return productStockDoConvert.toDomain(row);
    }

    public boolean reserve(long productId, int qty) {
        return productStockMapper.reserve(productId, qty) > 0;
    }

    public boolean confirm(long productId, int qty) {
        return productStockMapper.confirm(productId, qty) > 0;
    }

    public boolean release(long productId, int qty) {
        return productStockMapper.release(productId, qty) > 0;
    }

    public boolean adjustActual(long productId, int targetActual) {
        return productStockMapper.adjustActual(productId, targetActual) > 0;
    }

    public boolean applyReserveDelta(long productId, int qty, long seq) {
        return productStockMapper.applyReserveDelta(productId, qty, seq) > 0;
    }

    public boolean applyConfirmDelta(long productId, int qty, long seq) {
        return productStockMapper.applyConfirmDelta(productId, qty, seq) > 0;
    }

    public boolean applyReleaseDelta(long productId, int qty, long seq) {
        return productStockMapper.applyReleaseDelta(productId, qty, seq) > 0;
    }
}
