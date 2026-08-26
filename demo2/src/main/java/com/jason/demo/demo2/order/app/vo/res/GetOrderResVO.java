package com.jason.demo.demo2.order.app.vo.res;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "订单详情响应")
public class GetOrderResVO {

    @Schema(description = "订单 ID")
    private Long orderId;

    @Schema(description = "订单状态", example = "PENDING")
    private String status;

    @Schema(description = "订单金额", example = "18.00")
    private BigDecimal amount;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
