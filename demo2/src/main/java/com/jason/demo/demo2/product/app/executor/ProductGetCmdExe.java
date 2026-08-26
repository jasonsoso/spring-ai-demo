package com.jason.demo.demo2.product.app.executor;

import com.jason.demo.demo2.product.app.convert.ProductVoConvert;
import com.jason.demo.demo2.product.app.vo.res.ProductDetailResVO;
import com.jason.demo.demo2.product.service.core.ProductDomainService;
import org.springframework.stereotype.Service;

@Service
public class ProductGetCmdExe {

    private final ProductDomainService productDomainService;
    private final ProductVoConvert productVoConvert;

    public ProductGetCmdExe(ProductDomainService productDomainService, ProductVoConvert productVoConvert) {
        this.productDomainService = productDomainService;
        this.productVoConvert = productVoConvert;
    }

    public ProductDetailResVO execute(long productId) {
        return productVoConvert.toDetail(productDomainService.requireOnShelf(productId));
    }
}
