package com.jason.demo.demo2.product.app.executor;

import com.jason.demo.demo2.product.app.convert.ProductVoConvert;
import com.jason.demo.demo2.product.app.vo.res.ProductListItemResVO;
import com.jason.demo.demo2.product.app.vo.res.ProductListResVO;
import com.jason.demo.demo2.product.service.core.ProductDomainService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductListCmdExe {

    private final ProductDomainService productDomainService;
    private final ProductVoConvert productVoConvert;

    public ProductListCmdExe(ProductDomainService productDomainService, ProductVoConvert productVoConvert) {
        this.productDomainService = productDomainService;
        this.productVoConvert = productVoConvert;
    }

    public ProductListResVO execute() {
        List<ProductListItemResVO> items = productDomainService.listOnShelf().stream()
                .map(productVoConvert::toListItem)
                .toList();
        ProductListResVO res = new ProductListResVO();
        res.setItems(items);
        return res;
    }
}
