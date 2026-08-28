package com.jason.demo.demo2.order.app.vo.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "预览下单请求")
public class OrderPreviewReqVO {

    @NotEmpty(message = "不能为空")
    @Valid
    @Size(min = 1, max = 1, message = "本版仅支持 1 行商品")
    @Schema(description = "商品行；本版仅 1 行", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<OrderLineReqVO> items;
}
