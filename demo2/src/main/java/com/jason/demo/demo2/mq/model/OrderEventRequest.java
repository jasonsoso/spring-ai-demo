package com.jason.demo.demo2.mq.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderEventRequest {
    private String orderId;
    private String type;
    private String payload;
}
