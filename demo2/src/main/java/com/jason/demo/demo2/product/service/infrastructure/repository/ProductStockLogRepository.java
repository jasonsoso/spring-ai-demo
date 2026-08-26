package com.jason.demo.demo2.product.service.infrastructure.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jason.demo.demo2.product.service.common.ProductStockOptTypeEnum;
import com.jason.demo.demo2.product.service.infrastructure.dao.entity.ProductStockLogDO;
import com.jason.demo.demo2.product.service.infrastructure.dao.mapper.ProductStockLogMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class ProductStockLogRepository {

    private final ProductStockLogMapper productStockLogMapper;

    public ProductStockLogRepository(ProductStockLogMapper productStockLogMapper) {
        this.productStockLogMapper = productStockLogMapper;
    }

    public void insertLog(ProductStockLogDO log) {
        productStockLogMapper.insert(log);
    }

    public Optional<ProductStockLogDO> findPendingReserve(long orderId, long productId) {
        ProductStockLogDO reserve = productStockLogMapper.selectOne(new LambdaQueryWrapper<ProductStockLogDO>()
                .eq(ProductStockLogDO::getOrderId, orderId)
                .eq(ProductStockLogDO::getProductId, productId)
                .eq(ProductStockLogDO::getOptType, ProductStockOptTypeEnum.RESERVE.name())
                .orderByDesc(ProductStockLogDO::getCreatedAt)
                .last("LIMIT 1"));
        if (reserve == null || existsRelease(orderId, productId)) {
            return Optional.empty();
        }
        return Optional.of(reserve);
    }

    public boolean existsRelease(long orderId, long productId) {
        return productStockLogMapper.selectCount(new LambdaQueryWrapper<ProductStockLogDO>()
                .eq(ProductStockLogDO::getOrderId, orderId)
                .eq(ProductStockLogDO::getProductId, productId)
                .eq(ProductStockLogDO::getOptType, ProductStockOptTypeEnum.RELEASE.name())) > 0;
    }
}
