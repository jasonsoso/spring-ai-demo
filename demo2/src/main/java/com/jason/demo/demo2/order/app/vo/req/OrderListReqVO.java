package com.jason.demo.demo2.order.app.vo.req;

import com.jason.demo.demo2.order.service.common.OrderListTabEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "订单列表请求")
public class OrderListReqVO {

    @NotNull(message = "不能为空")
    @Schema(description = "列表 Tab：ALL / SUBMIT / COMPLETED", requiredMode = Schema.RequiredMode.REQUIRED)
    private OrderListTabEnum tab;

    @Min(value = 1, message = "必须大于等于 1")
    @Schema(description = "页码，从 1 开始", example = "1")
    private Integer pageNo;

    @Min(value = 1, message = "必须大于等于 1")
    @Max(value = 50, message = "不能超过 50")
    @Schema(description = "每页条数，最大 50", example = "10")
    private Integer pageSize;
}
