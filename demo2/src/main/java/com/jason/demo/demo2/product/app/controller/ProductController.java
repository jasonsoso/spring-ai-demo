package com.jason.demo.demo2.product.app.controller;

import com.jason.demo.demo2.framework.web.result.JsonResult;
import com.jason.demo.demo2.framework.web.result.JsonResults;
import com.jason.demo.demo2.product.app.executor.ProductGetCmdExe;
import com.jason.demo.demo2.product.app.executor.ProductListCmdExe;
import com.jason.demo.demo2.product.app.vo.req.GetProductReqVO;
import com.jason.demo.demo2.product.app.vo.res.ProductDetailResVO;
import com.jason.demo.demo2.product.app.vo.res.ProductListResVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "商品")
@RestController
@RequestMapping("/demo/products")
public class ProductController {

    private final ProductListCmdExe productListCmdExe;
    private final ProductGetCmdExe productGetCmdExe;

    public ProductController(ProductListCmdExe productListCmdExe, ProductGetCmdExe productGetCmdExe) {
        this.productListCmdExe = productListCmdExe;
        this.productGetCmdExe = productGetCmdExe;
    }

    @Operation(summary = "商品列表", description = "查询上架商品列表（含库存摘要）。无请求体。")
    @PostMapping("/listProducts")
    public JsonResult<ProductListResVO> listProducts(@RequestBody(required = false) Object ignored) {
        return JsonResults.ok(productListCmdExe.execute());
    }

    @Operation(summary = "商品详情", description = "按 productId 查询上架商品详情")
    @PostMapping("/getProduct")
    public JsonResult<ProductDetailResVO> getProduct(@Valid @RequestBody GetProductReqVO request) {
        return JsonResults.ok(productGetCmdExe.execute(request.getProductId()));
    }
}
