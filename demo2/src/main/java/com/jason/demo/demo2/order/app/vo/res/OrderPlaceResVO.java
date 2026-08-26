package com.jason.demo.demo2.order.app.vo.res;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "下单响应")
public class OrderPlaceResVO {

    @Schema(description = "订单 ID")
    private Long orderId;

    @Schema(description = "订单状态", example = "PENDING")
    private String status;

    @Schema(description = "订单金额", example = "18.00")
    private BigDecimal amount;

    @Schema(description = "超时延时任务 ID")
    private Long taskId;

    @Schema(description = "实际延时描述", example = "PT30S")
    private String delay;
}
