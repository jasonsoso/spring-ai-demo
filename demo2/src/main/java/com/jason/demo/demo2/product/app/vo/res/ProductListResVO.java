package com.jason.demo.demo2.product.app.vo.res;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "商品列表响应")
public class ProductListResVO {

    @Schema(description = "上架商品列表")
    private List<ProductListItemResVO> items;
}
