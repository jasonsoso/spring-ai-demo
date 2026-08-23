package com.jason.demo.demo2.order.app.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Duration;

@Data
public class OrderPlaceResult {

    private long orderId;
    private String status;
    private BigDecimal amount;
    private long taskId;
    private Duration delay;
}
