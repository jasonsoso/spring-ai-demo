package com.jason.demo.demo2.order.service.core.statemachine;

import com.jason.demo.demo2.order.service.core.domain.Order;
import lombok.Data;

/** 状态机上下文：Action 只从这里取聚合，不回头查 CmdExe。 */
@Data
public class OrderContext {

    private Order order;
}
