package com.jason.demo.demo2.order.app.vo.res;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "取消订单响应")
public class CancelOrderResVO {

    @Schema(description = "订单 ID")
    private Long orderId;

    @Schema(description = "订单状态", example = "CANCELLED")
    private String status;
}
