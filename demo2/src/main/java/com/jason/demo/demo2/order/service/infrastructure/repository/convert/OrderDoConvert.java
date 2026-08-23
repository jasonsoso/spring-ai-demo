package com.jason.demo.demo2.order.service.infrastructure.repository.convert;

import com.jason.demo.demo2.order.service.core.domain.Order;
import com.jason.demo.demo2.order.service.infrastructure.dao.entity.OrderDO;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface OrderDoConvert {

    OrderDO toDo(Order order);

    default Order toDomain(OrderDO orderDO) {
        return Order.from(orderDO);
    }
}
