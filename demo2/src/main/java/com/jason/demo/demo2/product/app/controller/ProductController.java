package com.jason.demo.demo2.product.app.controller;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.framework.web.result.JsonResult;
import com.jason.demo.demo2.framework.web.result.JsonResults;
import com.jason.demo.demo2.product.app.executor.ProductGetCmdExe;
import com.jason.demo.demo2.product.app.executor.ProductListCmdExe;
import com.jason.demo.demo2.product.app.vo.req.GetProductReqVO;
import com.jason.demo.demo2.product.app.vo.res.ProductDetailResVO;
import com.jason.demo.demo2.product.app.vo.res.ProductListResVO;
import com.jason.demo.demo2.product.service.common.ProductErrorCodeEnum;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/demo/products")
public class ProductController {

    private final ProductListCmdExe productListCmdExe;
    private final ProductGetCmdExe productGetCmdExe;

    public ProductController(ProductListCmdExe productListCmdExe, ProductGetCmdExe productGetCmdExe) {
        this.productListCmdExe = productListCmdExe;
        this.productGetCmdExe = productGetCmdExe;
    }

    @PostMapping("/listProducts")
    public JsonResult<ProductListResVO> listProducts(@RequestBody(required = false) Object ignored) {
        return JsonResults.ok(productListCmdExe.execute());
    }

    @PostMapping("/getProduct")
    public JsonResult<ProductDetailResVO> getProduct(@RequestBody GetProductReqVO request) {
        if (request == null || request.getProductId() == null) {
            throw new BusinessException(ProductErrorCodeEnum.PRODUCT_ID_REQUIRED);
        }
        return JsonResults.ok(productGetCmdExe.execute(request.getProductId()));
    }
}
