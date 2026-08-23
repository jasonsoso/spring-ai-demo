package com.jason.demo.demo2.order.app.vo.res;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class OrderPlaceResVO {

    private Long orderId;
    private String status;
    private BigDecimal amount;
    private Long taskId;
    private String delay;
}
