package com.jason.demo.demo2.product.app.controller;

import com.jason.demo.demo2.framework.web.result.JsonResult;
import com.jason.demo.demo2.framework.web.result.JsonResults;
import com.jason.demo.demo2.product.app.executor.ProductAdjustStockCmdExe;
import com.jason.demo.demo2.product.app.executor.ProductGetCmdExe;
import com.jason.demo.demo2.product.app.executor.ProductListCmdExe;
import com.jason.demo.demo2.product.app.executor.ProductOffShelfCmdExe;
import com.jason.demo.demo2.product.app.executor.ProductOnShelfCmdExe;
import com.jason.demo.demo2.product.app.vo.req.AdjustStockReqVO;
import com.jason.demo.demo2.product.app.vo.req.GetProductReqVO;
import com.jason.demo.demo2.product.app.vo.res.AdjustStockResVO;
import com.jason.demo.demo2.product.app.vo.res.ProductDetailResVO;
import com.jason.demo.demo2.product.app.vo.res.ProductListResVO;
import com.jason.demo.demo2.product.app.vo.res.ProductShelfResVO;
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
    private final ProductOffShelfCmdExe productOffShelfCmdExe;
    private final ProductOnShelfCmdExe productOnShelfCmdExe;
    private final ProductAdjustStockCmdExe productAdjustStockCmdExe;

    public ProductController(
            ProductListCmdExe productListCmdExe,
            ProductGetCmdExe productGetCmdExe,
            ProductOffShelfCmdExe productOffShelfCmdExe,
            ProductOnShelfCmdExe productOnShelfCmdExe,
            ProductAdjustStockCmdExe productAdjustStockCmdExe) {
        this.productListCmdExe = productListCmdExe;
        this.productGetCmdExe = productGetCmdExe;
        this.productOffShelfCmdExe = productOffShelfCmdExe;
        this.productOnShelfCmdExe = productOnShelfCmdExe;
        this.productAdjustStockCmdExe = productAdjustStockCmdExe;
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

    @Operation(summary = "下架商品", description = "将商品改为下架。不改 Redis 可售。")
    @PostMapping("/offShelf")
    public JsonResult<ProductShelfResVO> offShelf(@Valid @RequestBody GetProductReqVO request) {
        return JsonResults.ok(productOffShelfCmdExe.execute(request.getProductId()));
    }

    @Operation(summary = "上架商品", description = "Hash 不存在时 HSETNX 灌入可售，再上架。已有 Hash 不覆盖。")
    @PostMapping("/onShelf")
    public JsonResult<ProductShelfResVO> onShelf(@Valid @RequestBody GetProductReqVO request) {
        return JsonResults.ok(productOnShelfCmdExe.execute(request.getProductId()));
    }

    @Operation(summary = "调整现货库存", description = "必须先下架。targetActual 为新的 actual_stock。")
    @PostMapping("/adjustStock")
    public JsonResult<AdjustStockResVO> adjustStock(@Valid @RequestBody AdjustStockReqVO request) {
        return JsonResults.ok(productAdjustStockCmdExe.execute(request.getProductId(), request.getTargetActual()));
    }
}
