package com.jason.demo.demo2.order.app.vo.res;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "预览商品行快照")
public class OrderPreviewLineResVO {

    @Schema(description = "商品业务 ID", example = "2085550503315509001")
    private Long productId;

    @Schema(description = "商品名称快照", example = "拿铁")
    private String productName;

    @Schema(description = "封面快照")
    private String coverUrl;

    @Schema(description = "售价快照", example = "18.00")
    private BigDecimal sellPrice;

    @Schema(description = "购买数量", example = "2")
    private Integer qty;

    @Schema(description = "行金额", example = "36.00")
    private BigDecimal lineAmount;

    @Schema(description = "可售库存", example = "100")
    private Integer availableStock;
}
