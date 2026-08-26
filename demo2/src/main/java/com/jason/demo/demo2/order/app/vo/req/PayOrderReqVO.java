package com.jason.demo.demo2.order.app.vo.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "支付订单请求")
public class PayOrderReqVO {

    @NotNull(message = "不能为空")
    @Min(value = 1, message = "必须大于 0")
    @Schema(description = "订单 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long orderId;
}
