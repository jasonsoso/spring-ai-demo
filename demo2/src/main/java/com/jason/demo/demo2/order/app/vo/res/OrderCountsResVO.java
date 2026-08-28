package com.jason.demo.demo2.order.app.vo.res;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "订单数量冒泡")
public class OrderCountsResVO {

    @Schema(description = "待支付数量（SUBMIT）", example = "3")
    private Long pendingCount;

    @Schema(description = "已完成数量（COMPLETED）", example = "11")
    private Long completedCount;
}
