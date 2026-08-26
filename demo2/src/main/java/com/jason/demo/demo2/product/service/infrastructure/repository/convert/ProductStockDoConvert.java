package com.jason.demo.demo2.product.service.infrastructure.repository.convert;

import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import com.jason.demo.demo2.product.service.infrastructure.dao.entity.ProductStockDO;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ProductStockDoConvert {

    default ProductStock toDomain(ProductStockDO stockDO) {
        return ProductStock.from(stockDO);
    }
}
