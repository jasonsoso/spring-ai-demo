package com.jason.demo.demo2.product.app.vo.res;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "调整库存结果")
public class AdjustStockResVO {

    @Schema(description = "商品业务 ID")
    private Long productId;

    @Schema(description = "现货库存")
    private Integer actualStock;

    @Schema(description = "可售库存")
    private Integer stock;

    @Schema(description = "预占库存")
    private Integer withholdStock;

    @Schema(description = "已投影序号")
    private Long stockSeq;
}
