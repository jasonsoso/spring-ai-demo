package com.jason.demo.demo2.product.service.infrastructure.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jason.demo.demo2.product.service.common.ProductStatusEnum;
import com.jason.demo.demo2.product.service.core.domain.Product;
import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import com.jason.demo.demo2.product.service.core.domain.ProductWithStock;
import com.jason.demo.demo2.product.service.infrastructure.dao.entity.ProductDO;
import com.jason.demo.demo2.product.service.infrastructure.dao.entity.ProductStockDO;
import com.jason.demo.demo2.product.service.infrastructure.dao.mapper.ProductMapper;
import com.jason.demo.demo2.product.service.infrastructure.repository.convert.ProductDoConvert;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
public class ProductRepository {

    private final ProductMapper productMapper;
    private final ProductStockRepository productStockRepository;
    private final ProductDoConvert productDoConvert;

    public ProductRepository(
            ProductMapper productMapper,
            ProductStockRepository productStockRepository,
            ProductDoConvert productDoConvert) {
        this.productMapper = productMapper;
        this.productStockRepository = productStockRepository;
        this.productDoConvert = productDoConvert;
    }

    public List<ProductWithStock> listOnShelfWithStock() {
        List<ProductDO> products = productMapper.selectList(new LambdaQueryWrapper<ProductDO>()
                .eq(ProductDO::getStatus, ProductStatusEnum.ON_SHELF.name())
                .orderByDesc(ProductDO::getSort)
                .orderByAsc(ProductDO::getProductId));
        if (products.isEmpty()) {
            return List.of();
        }
        List<Long> productIds = products.stream().map(ProductDO::getProductId).toList();
        Map<Long, ProductStock> stockByProductId = productStockRepository.findByProductIds(productIds).stream()
                .collect(Collectors.toMap(ProductStock::getProductId, Function.identity()));

        List<ProductWithStock> rows = new ArrayList<>();
        for (ProductDO productDO : products) {
            ProductStock stock = stockByProductId.get(productDO.getProductId());
            if (stock == null) {
                continue;
            }
            rows.add(new ProductWithStock(productDoConvert.toDomain(productDO), stock));
        }
        rows.sort(Comparator
                .comparing((ProductWithStock row) -> row.getProduct().getSort(), Comparator.reverseOrder())
                .thenComparing(row -> row.getStock().getSellStock(), Comparator.reverseOrder())
                .thenComparing(row -> row.getProduct().getProductId()));
        return rows;
    }

    public Optional<ProductWithStock> findOnShelfWithStock(long productId) {
        ProductDO productDO = productMapper.selectOne(new LambdaQueryWrapper<ProductDO>()
                .eq(ProductDO::getProductId, productId));
        if (productDO == null) {
            return Optional.empty();
        }
        return productStockRepository.findByProductId(productId)
                .map(stock -> new ProductWithStock(productDoConvert.toDomain(productDO), stock));
    }

    public Optional<Product> findByProductId(long productId) {
        ProductDO productDO = productMapper.selectOne(new LambdaQueryWrapper<ProductDO>()
                .eq(ProductDO::getProductId, productId));
        return Optional.ofNullable(productDoConvert.toDomain(productDO));
    }
}
