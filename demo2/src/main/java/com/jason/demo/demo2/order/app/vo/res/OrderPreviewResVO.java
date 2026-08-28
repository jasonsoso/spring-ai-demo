package com.jason.demo.demo2.order.app.vo.res;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Schema(description = "预览下单响应")
public class OrderPreviewResVO {

    @Schema(description = "下单凭证", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
    private String placeToken;

    @Schema(description = "应付金额", example = "36.00")
    private BigDecimal amount;

    @Schema(description = "商品行快照")
    private List<OrderPreviewLineResVO> items;
}
