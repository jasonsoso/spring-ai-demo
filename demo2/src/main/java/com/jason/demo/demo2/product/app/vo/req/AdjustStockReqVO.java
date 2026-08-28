package com.jason.demo.demo2.product.app.vo.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "调整现货库存请求")
public class AdjustStockReqVO {

    @NotNull(message = "不能为空")
    @Min(value = 1, message = "必须大于 0")
    @Schema(description = "商品业务 ID", example = "2085550503315509001", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long productId;

    @NotNull(message = "不能为空")
    @Min(value = 0, message = "必须大于等于 0")
    @Schema(description = "目标现货库存", example = "80", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer targetActual;
}
