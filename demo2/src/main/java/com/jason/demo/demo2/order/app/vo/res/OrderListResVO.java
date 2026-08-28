package com.jason.demo.demo2.order.app.vo.res;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "订单列表响应")
public class OrderListResVO {

    @Schema(description = "页码", example = "1")
    private Integer pageNo;

    @Schema(description = "每页条数", example = "20")
    private Integer pageSize;

    @Schema(description = "总条数", example = "3")
    private Long total;

    @Schema(description = "当前页订单")
    private List<OrderListItemResVO> items;
}
