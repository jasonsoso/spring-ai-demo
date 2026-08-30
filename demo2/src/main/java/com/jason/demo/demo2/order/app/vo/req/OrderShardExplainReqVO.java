package com.jason.demo.demo2.order.app.vo.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "分片路由试算，orderId 与 memberId 至少填一个")
public class OrderShardExplainReqVO {

    @Schema(description = "订单 ID（拆低 9 位基因）")
    private Long orderId;

    @Schema(description = "会员 ID")
    private Long memberId;
}
