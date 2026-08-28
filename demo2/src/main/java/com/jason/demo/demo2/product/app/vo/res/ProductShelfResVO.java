package com.jason.demo.demo2.product.app.vo.res;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "商品上下架结果")
public class ProductShelfResVO {

    @Schema(description = "商品业务 ID")
    private Long productId;

    @Schema(description = "商品状态 ON_SHELF / OFF_SHELF")
    private String status;
}
