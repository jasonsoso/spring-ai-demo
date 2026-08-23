package com.jason.demo.demo2.order.app.controller;

import com.jason.demo.demo2.order.app.convert.OrderVoConvert;
import com.jason.demo.demo2.order.app.executor.OrderCancelCmdExe;
import com.jason.demo.demo2.order.app.executor.OrderGetCmdExe;
import com.jason.demo.demo2.order.app.executor.OrderPaySuccessCmdExe;
import com.jason.demo.demo2.order.app.executor.OrderPlaceCmdExe;
import com.jason.demo.demo2.order.app.support.OrderDelayParser;
import com.jason.demo.demo2.order.app.support.OrderHttpSupport;
import com.jason.demo.demo2.order.app.vo.req.CancelOrderReqVO;
import com.jason.demo.demo2.order.app.vo.res.CancelOrderResVO;
import com.jason.demo.demo2.order.app.vo.req.GetOrderReqVO;
import com.jason.demo.demo2.order.app.vo.res.GetOrderResVO;
import com.jason.demo.demo2.order.app.vo.req.OrderPlaceReqVO;
import com.jason.demo.demo2.order.app.vo.res.OrderPlaceResVO;
import com.jason.demo.demo2.order.app.vo.req.PayOrderReqVO;
import com.jason.demo.demo2.order.app.vo.res.PayOrderResVO;
import com.jason.demo.demo2.order.service.core.OrderDomainException;
import com.jason.demo.demo2.order.service.core.domain.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

@RestController
@RequestMapping("/demo/orders")
public class OrderController {

    private final OrderPlaceCmdExe orderPlaceCmdExe;
    private final OrderPaySuccessCmdExe orderPaySuccessCmdExe;
    private final OrderGetCmdExe orderGetCmdExe;
    private final OrderCancelCmdExe orderCancelCmdExe;
    private final OrderVoConvert orderVoConvert;

    public OrderController(
            OrderPlaceCmdExe orderPlaceCmdExe,
            OrderPaySuccessCmdExe orderPaySuccessCmdExe,
            OrderGetCmdExe orderGetCmdExe,
            OrderCancelCmdExe orderCancelCmdExe,
            OrderVoConvert orderVoConvert) {
        this.orderPlaceCmdExe = orderPlaceCmdExe;
        this.orderPaySuccessCmdExe = orderPaySuccessCmdExe;
        this.orderGetCmdExe = orderGetCmdExe;
        this.orderCancelCmdExe = orderCancelCmdExe;
        this.orderVoConvert = orderVoConvert;
    }

    @PostMapping("/orderPlace")
    public OrderPlaceResVO orderPlace(@RequestBody OrderPlaceReqVO request) {
        if (request == null || request.getAmount() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount is required");
        }
        Duration delay = OrderDelayParser.parseDelay(request.getDelay());
        try {
            return orderVoConvert.toPlaceRes(orderPlaceCmdExe.execute(request.getAmount(), delay));
        } catch (OrderDomainException e) {
            throw OrderHttpSupport.toHttpException(e);
        }
    }

    @PostMapping("/pay")
    public PayOrderResVO pay(@RequestBody PayOrderReqVO request) {
        long orderId = requireOrderId(request == null ? null : request.getOrderId());
        try {
            Order order = orderPaySuccessCmdExe.execute(orderId);
            return orderVoConvert.toPayRes(order);
        } catch (OrderDomainException e) {
            throw OrderHttpSupport.toHttpException(e);
        }
    }

    @PostMapping("/get")
    public GetOrderResVO get(@RequestBody GetOrderReqVO request) {
        long orderId = requireOrderId(request == null ? null : request.getOrderId());
        try {
            return orderVoConvert.toGetRes(orderGetCmdExe.execute(orderId));
        } catch (OrderDomainException e) {
            throw OrderHttpSupport.toHttpException(e);
        }
    }

    @PostMapping("/cancel")
    public CancelOrderResVO cancel(@RequestBody CancelOrderReqVO request) {
        long orderId = requireOrderId(request == null ? null : request.getOrderId());
        try {
            return orderVoConvert.toCancelRes(orderCancelCmdExe.execute(orderId));
        } catch (OrderDomainException e) {
            throw OrderHttpSupport.toHttpException(e);
        }
    }

    private static long requireOrderId(Long orderId) {
        if (orderId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "orderId is required");
        }
        return orderId;
    }
}
