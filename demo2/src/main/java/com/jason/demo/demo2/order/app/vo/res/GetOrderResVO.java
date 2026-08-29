package com.jason.demo.demo2.order.app.vo.res;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "订单详情响应")
public class GetOrderResVO {

    @Schema(description = "订单 ID")
    private Long orderId;

    @Schema(description = "订单状态", example = "SUBMIT")
    private String orderStatus;

    @Schema(description = "支付状态", example = "WAIT_PAY")
    private String payStatus;

    @Schema(description = "订单金额", example = "18.00")
    private BigDecimal amount;

    @Schema(description = "支付时间")
    private LocalDateTime payTime;

    @Schema(description = "取消时间")
    private LocalDateTime cancelTime;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @Schema(description = "待支付关单截止时间；非待支付或无延时任务时为空")
    private LocalDateTime payDeadline;

    @Schema(description = "商品行快照；无明细时为空数组")
    private List<OrderLineResVO> items;
}
