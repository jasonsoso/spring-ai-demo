package com.jason.demo.demo2.product.app.executor;

import com.jason.demo.demo2.product.app.convert.ProductVoConvert;
import com.jason.demo.demo2.product.app.vo.res.ProductListItemResVO;
import com.jason.demo.demo2.product.app.vo.res.ProductListResVO;
import com.jason.demo.demo2.product.service.core.ProductDomainService;
import com.jason.demo.demo2.product.service.core.ProductStockHotService;
import com.jason.demo.demo2.product.service.core.domain.ProductWithStock;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ProductListCmdExe {

    private final ProductDomainService productDomainService;
    private final ProductVoConvert productVoConvert;
    private final ProductStockHotService productStockHotService;

    public ProductListCmdExe(
            ProductDomainService productDomainService,
            ProductVoConvert productVoConvert,
            ProductStockHotService productStockHotService) {
        this.productDomainService = productDomainService;
        this.productVoConvert = productVoConvert;
        this.productStockHotService = productStockHotService;
    }

    public ProductListResVO execute() {
        List<ProductListItemResVO> items = new ArrayList<>();
        for (ProductWithStock row : productDomainService.listOnShelf()) {
            ProductListItemResVO item = productVoConvert.toListItem(row);
            // 只 overlay 可售；convert 里的 sellStock 保持 MySQL
            productStockHotService.overlayAvail(row.getProduct().getProductId())
                    .ifPresent(item::setAvailableStock);
            items.add(item);
        }
        ProductListResVO res = new ProductListResVO();
        res.setItems(items);
        return res;
    }
}
