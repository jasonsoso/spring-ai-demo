package com.jason.demo.demo2.order.app.vo.res;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "支付订单响应")
public class PayOrderResVO {

    @Schema(description = "订单 ID")
    private Long orderId;

    @Schema(description = "订单状态", example = "COMPLETED")
    private String orderStatus;

    @Schema(description = "支付状态", example = "PAY_SUCCESS")
    private String payStatus;
}
