package com.jason.demo.demo2.order.app.vo.res;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "分片路由试算结果")
public class OrderShardExplainResVO {

    @Schema(description = "用于展示的 virtual（两边都有时取 member）")
    private Long virtual;

    @Schema(description = "9 位二进制")
    private String geneBits;

    @Schema(description = "目标库", example = "order_ds_0")
    private String ds;

    @Schema(description = "主表", example = "demo_order_18")
    private String table;

    @Schema(description = "明细表", example = "demo_order_item_18")
    private String itemTable;

    @Schema(description = "MEMBER_ID 或 ORDER_ID")
    private String source;

    @Schema(description = "由 memberId 算出的 virtual")
    private Long memberVirtual;

    @Schema(description = "由 orderId 拆出的 virtual")
    private Long orderVirtual;

    @Schema(description = "两边都有时是否同一 virtual")
    private Boolean geneMatch;
}
