package com.jason.demo.demo2.product.app.vo.res;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "商品列表项")
public class ProductListItemResVO {

    @Schema(description = "商品业务 ID", example = "2085550503315509001")
    private Long productId;

    @Schema(description = "商品名称", example = "拿铁")
    private String productName;

    @Schema(description = "副标题", example = "经典意式")
    private String subtitle;

    @Schema(description = "封面图 URL")
    private String coverUrl;

    @Schema(description = "售价", example = "18.00")
    private BigDecimal sellPrice;

    @Schema(description = "划线价", example = "22.00")
    private BigDecimal marketPrice;

    @Schema(description = "可售库存", example = "100")
    private Integer availableStock;

    @Schema(description = "累计已售", example = "50")
    private Integer sellStock;
}
