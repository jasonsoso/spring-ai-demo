package com.jason.demo.demo2.order.service.infrastructure.dao.entity;

import lombok.Data;

@Data
public class OrderStatusCountDO {

    private String orderStatus;
    private Long cnt;
}
