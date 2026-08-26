package com.jason.demo.demo2.product.app.vo.res;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "商品详情响应")
public class ProductDetailResVO extends ProductListItemResVO {

    @Schema(description = "详情富文本/文案")
    private String detailContent;
}
