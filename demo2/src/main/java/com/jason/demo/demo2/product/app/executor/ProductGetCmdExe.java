package com.jason.demo.demo2.product.app.executor;

import com.jason.demo.demo2.product.app.convert.ProductVoConvert;
import com.jason.demo.demo2.product.app.vo.res.ProductDetailResVO;
import com.jason.demo.demo2.product.service.core.ProductDomainService;
import com.jason.demo.demo2.product.service.core.ProductStockHotService;
import org.springframework.stereotype.Service;

@Service
public class ProductGetCmdExe {

    private final ProductDomainService productDomainService;
    private final ProductVoConvert productVoConvert;
    private final ProductStockHotService productStockHotService;

    public ProductGetCmdExe(
            ProductDomainService productDomainService,
            ProductVoConvert productVoConvert,
            ProductStockHotService productStockHotService) {
        this.productDomainService = productDomainService;
        this.productVoConvert = productVoConvert;
        this.productStockHotService = productStockHotService;
    }

    public ProductDetailResVO execute(long productId) {
        ProductDetailResVO detail = productVoConvert.toDetail(productDomainService.requireOnShelf(productId));
        // 与列表相同：只覆盖 availableStock
        productStockHotService.overlayAvail(productId).ifPresent(detail::setAvailableStock);
        return detail;
    }
}
