package com.jason.demo.demo2.product.service.infrastructure.repository.convert;

import com.jason.demo.demo2.product.service.core.domain.Product;
import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import com.jason.demo.demo2.product.service.infrastructure.dao.entity.ProductDO;
import com.jason.demo.demo2.product.service.infrastructure.dao.entity.ProductStockDO;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ProductDoConvert {

    default Product toDomain(ProductDO productDO) {
        return Product.from(productDO);
    }
}
