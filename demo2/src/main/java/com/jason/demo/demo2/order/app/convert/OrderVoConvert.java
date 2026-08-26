package com.jason.demo.demo2.order.app.convert;

import com.jason.demo.demo2.order.app.vo.res.CancelOrderResVO;
import com.jason.demo.demo2.order.app.vo.res.GetOrderResVO;
import com.jason.demo.demo2.order.app.vo.res.PayOrderResVO;
import com.jason.demo.demo2.order.service.core.domain.Order;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface OrderVoConvert {

    PayOrderResVO toPayRes(Order order);

    GetOrderResVO toGetRes(Order order);

    CancelOrderResVO toCancelRes(Order order);
}
