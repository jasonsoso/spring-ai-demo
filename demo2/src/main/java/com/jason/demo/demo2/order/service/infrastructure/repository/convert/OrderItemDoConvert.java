package com.jason.demo.demo2.order.service.infrastructure.repository.convert;

import com.jason.demo.demo2.order.service.core.domain.OrderItem;
import com.jason.demo.demo2.order.service.infrastructure.dao.entity.OrderItemDO;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface OrderItemDoConvert {

    OrderItemDO toDo(OrderItem item);

    default OrderItem toDomain(OrderItemDO itemDO) {
        return OrderItem.from(itemDO);
    }
}
