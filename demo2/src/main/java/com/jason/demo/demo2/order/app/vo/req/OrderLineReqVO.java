package com.jason.demo.demo2.order.app.vo.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "订单商品行")
public class OrderLineReqVO {

    @NotNull(message = "不能为空")
    @Schema(description = "商品业务 ID", example = "2085550503315509001", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long productId;

    @NotNull(message = "不能为空")
    @Min(value = 1, message = "必须大于等于 1")
    @Max(value = 99999, message = "不能超过 99999")
    @Schema(description = "购买数量", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer qty;

    @Schema(description = "售价快照；预览可不传，下单必填", example = "18.00")
    private BigDecimal sellPrice;
}
