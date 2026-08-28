package com.jason.demo.demo2.order.app.vo.res;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "订单列表项")
public class OrderListItemResVO {

    @Schema(description = "订单 ID")
    private Long orderId;

    @Schema(description = "订单状态", example = "SUBMIT")
    private String orderStatus;

    @Schema(description = "支付状态", example = "WAIT_PAY")
    private String payStatus;

    @Schema(description = "订单金额", example = "36.00")
    private BigDecimal amount;

    @Schema(description = "支付时间")
    private LocalDateTime payTime;

    @Schema(description = "取消时间")
    private LocalDateTime cancelTime;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "商品行；列表仅封面/名称/qty/售价")
    private List<OrderLineResVO> items;
}
