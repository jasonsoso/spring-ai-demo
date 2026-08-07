package com.jason.demo.demo2.order;

import java.math.BigDecimal;

public class CreateOrderRequest {

    private BigDecimal amount;
    /** 可选，如 {@code 30s}、{@code PT30S}；空则用配置默认延时 */
    private String delay;

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getDelay() {
        return delay;
    }

    public void setDelay(String delay) {
        this.delay = delay;
    }
}
