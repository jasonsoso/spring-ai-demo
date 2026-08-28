package com.jason.demo.demo2.order.app.vo.req;

import com.jason.demo.demo2.framework.validation.DelayFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "下单请求")
public class OrderPlaceReqVO {

    @NotBlank(message = "不能为空")
    @Schema(description = "预览签发的下单凭证", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String placeToken;

    @NotEmpty(message = "不能为空")
    @Valid
    @Size(min = 1, max = 1, message = "本版仅支持 1 行商品")
    @Schema(description = "商品行；本版仅 1 行", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<OrderLineReqVO> items;

    @DelayFormat
    @Schema(description = "可选超时延时，如 30s / PT30S；空则用配置默认", example = "30s")
    private String delay;
}
