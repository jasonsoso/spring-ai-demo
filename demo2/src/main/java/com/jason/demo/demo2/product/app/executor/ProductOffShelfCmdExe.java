package com.jason.demo.demo2.product.app.executor;

import com.jason.demo.demo2.product.app.vo.res.ProductShelfResVO;
import com.jason.demo.demo2.product.service.core.ProductDomainService;
import com.jason.demo.demo2.product.service.core.domain.Product;
import org.springframework.stereotype.Service;

/** 下架只改 status。Redis 可售留给在途订单继续 confirm/release，禁止在这里 DEL Hash。 */
@Service
public class ProductOffShelfCmdExe {

    private final ProductDomainService productDomainService;

    public ProductOffShelfCmdExe(ProductDomainService productDomainService) {
        this.productDomainService = productDomainService;
    }

    public ProductShelfResVO execute(long productId) {
        Product product = productDomainService.offShelf(productId);
        ProductShelfResVO res = new ProductShelfResVO();
        res.setProductId(product.getProductId());
        res.setStatus(product.getStatus());
        return res;
    }
}
