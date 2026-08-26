package com.jason.demo.demo2.product.app.vo.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "查询商品详情请求")
public class GetProductReqVO {

    @NotNull(message = "不能为空")
    @Min(value = 1, message = "必须大于 0")
    @Schema(description = "商品业务 ID", example = "2085550503315509001", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long productId;
}
