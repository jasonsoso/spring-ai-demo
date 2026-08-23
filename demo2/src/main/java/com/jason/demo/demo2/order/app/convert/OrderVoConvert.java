package com.jason.demo.demo2.order.app.convert;

import com.jason.demo.demo2.order.app.vo.OrderPlaceResult;
import com.jason.demo.demo2.order.app.vo.res.CancelOrderResVO;
import com.jason.demo.demo2.order.app.vo.res.GetOrderResVO;
import com.jason.demo.demo2.order.app.vo.res.OrderPlaceResVO;
import com.jason.demo.demo2.order.app.vo.res.PayOrderResVO;
import com.jason.demo.demo2.order.service.core.domain.Order;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface OrderVoConvert {

    @Mapping(target = "delay", expression = "java(result.getDelay() == null ? null : result.getDelay().toString())")
    OrderPlaceResVO toPlaceRes(OrderPlaceResult result);

    PayOrderResVO toPayRes(Order order);

    GetOrderResVO toGetRes(Order order);

    CancelOrderResVO toCancelRes(Order order);
}
