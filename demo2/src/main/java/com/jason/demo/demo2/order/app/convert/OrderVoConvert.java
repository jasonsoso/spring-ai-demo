package com.jason.demo.demo2.order.app.convert;

import com.jason.demo.demo2.order.app.vo.res.CancelOrderResVO;
import com.jason.demo.demo2.order.app.vo.res.GetOrderResVO;
import com.jason.demo.demo2.order.app.vo.res.OrderLineResVO;
import com.jason.demo.demo2.order.app.vo.res.OrderListItemResVO;
import com.jason.demo.demo2.order.app.vo.res.PayOrderResVO;
import com.jason.demo.demo2.order.service.core.domain.Order;
import com.jason.demo.demo2.order.service.core.domain.OrderItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface OrderVoConvert {

    @Mapping(target = "orderStatus", source = "orderStatus")
    PayOrderResVO toPayRes(Order order);

    @Mapping(target = "orderStatus", source = "orderStatus")
    @Mapping(target = "items", source = "items", qualifiedByName = "fullLine")
    @Mapping(target = "payDeadline", ignore = true)
    GetOrderResVO toGetRes(Order order);

    @Mapping(target = "orderStatus", source = "orderStatus")
    CancelOrderResVO toCancelRes(Order order);

    @Mapping(target = "orderStatus", source = "orderStatus")
    @Mapping(target = "items", ignore = true)
    OrderListItemResVO toListItem(Order order);

    @Named("fullLine")
    OrderLineResVO toLine(OrderItem item);

    @Named("listLine")
    @Mapping(target = "productId", ignore = true)
    OrderLineResVO toListLine(OrderItem item);
}
