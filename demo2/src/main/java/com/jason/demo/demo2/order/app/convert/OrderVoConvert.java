package com.jason.demo.demo2.order.app.convert;

import com.jason.demo.demo2.order.app.vo.res.CancelOrderResVO;
import com.jason.demo.demo2.order.app.vo.res.GetOrderResVO;
import com.jason.demo.demo2.order.app.vo.res.PayOrderResVO;
import com.jason.demo.demo2.order.service.core.domain.Order;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface OrderVoConvert {

    @Mapping(source = "orderStatus", target = "status")
    PayOrderResVO toPayRes(Order order);

    @Mapping(source = "orderStatus", target = "status")
    GetOrderResVO toGetRes(Order order);

    @Mapping(source = "orderStatus", target = "status")
    CancelOrderResVO toCancelRes(Order order);
}
