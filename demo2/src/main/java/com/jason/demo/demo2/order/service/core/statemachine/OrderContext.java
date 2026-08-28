package com.jason.demo.demo2.order.service.core.statemachine;

import com.jason.demo.demo2.order.service.core.domain.Order;
import lombok.Data;

@Data
public class OrderContext {

    private Order order;
}
