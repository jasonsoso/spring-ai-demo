package com.jason.demo.demo2.product.service.core;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.product.service.common.ProductErrorCodeEnum;
import com.jason.demo.demo2.product.service.common.ProductStatusEnum;
import com.jason.demo.demo2.product.service.core.domain.Product;
import com.jason.demo.demo2.product.service.core.domain.ProductWithStock;
import com.jason.demo.demo2.product.service.infrastructure.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductDomainService {

    private final ProductRepository productRepository;

    public ProductDomainService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public List<ProductWithStock> listOnShelf() {
        return productRepository.listOnShelfWithStock();
    }

    public ProductWithStock requireOnShelf(long productId) {
        ProductWithStock row = productRepository.findOnShelfWithStock(productId)
                .orElseThrow(() -> new BusinessException(ProductErrorCodeEnum.PRODUCT_NOT_FOUND));
        if (!ProductStatusEnum.ON_SHELF.name().equals(row.getProduct().getStatus())) {
            throw new BusinessException(ProductErrorCodeEnum.PRODUCT_OFF_SHELF);
        }
        return row;
    }

    public Product requireProduct(long productId) {
        return productRepository.requireByProductId(productId);
    }

    public Product offShelf(long productId) {
        Product product = productRepository.requireByProductId(productId);
        productRepository.updateStatus(productId, ProductStatusEnum.OFF_SHELF);
        product.setStatus(ProductStatusEnum.OFF_SHELF.name());
        return product;
    }

    public Product onShelf(long productId) {
        Product product = productRepository.requireByProductId(productId);
        productRepository.updateStatus(productId, ProductStatusEnum.ON_SHELF);
        product.setStatus(ProductStatusEnum.ON_SHELF.name());
        return product;
    }
}
