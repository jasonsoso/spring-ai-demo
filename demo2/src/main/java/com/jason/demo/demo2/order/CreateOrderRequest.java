package com.jason.demo.demo2.order;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateOrderRequest {

    private BigDecimal amount;
    /** 可选，如 {@code 30s}、{@code PT30S}；空则用配置默认延时 */
    private String delay;
}
