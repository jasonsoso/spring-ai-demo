package com.jason.demo.demo2.order.app.vo.req;

import com.jason.demo.demo2.framework.validation.DelayFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "下单请求")
public class OrderPlaceReqVO {

    @NotNull(message = "不能为空")
    @DecimalMin(value = "0.01", message = "必须大于 0")
    @Digits(integer = 10, fraction = 2, message = "最多两位小数")
    @Schema(description = "订单金额", example = "18.00", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal amount;

    @DelayFormat
    @Schema(description = "可选超时延时，如 30s / PT30S；空则用配置默认", example = "30s")
    private String delay;
}
