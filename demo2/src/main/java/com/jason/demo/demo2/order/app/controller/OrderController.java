package com.jason.demo.demo2.order.app.controller;

import com.jason.demo.demo2.framework.auth.annotation.LoginRequired;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.framework.web.exception.CommonErrorCodeEnum;
import com.jason.demo.demo2.framework.web.result.JsonResult;
import com.jason.demo.demo2.framework.web.result.JsonResults;
import com.jason.demo.demo2.order.app.executor.OrderCancelCmdExe;
import com.jason.demo.demo2.order.app.executor.OrderGetCmdExe;
import com.jason.demo.demo2.order.app.executor.OrderPaySuccessCmdExe;
import com.jason.demo.demo2.order.app.executor.OrderPlaceCmdExe;
import com.jason.demo.demo2.order.app.support.OrderDelayParser;
import com.jason.demo.demo2.order.app.vo.req.CancelOrderReqVO;
import com.jason.demo.demo2.order.app.vo.req.GetOrderReqVO;
import com.jason.demo.demo2.order.app.vo.req.OrderPlaceReqVO;
import com.jason.demo.demo2.order.app.vo.req.PayOrderReqVO;
import com.jason.demo.demo2.order.app.vo.res.CancelOrderResVO;
import com.jason.demo.demo2.order.app.vo.res.GetOrderResVO;
import com.jason.demo.demo2.order.app.vo.res.OrderPlaceResVO;
import com.jason.demo.demo2.order.app.vo.res.PayOrderResVO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/demo/orders")
public class OrderController {

    private final OrderPlaceCmdExe orderPlaceCmdExe;
    private final OrderPaySuccessCmdExe orderPaySuccessCmdExe;
    private final OrderGetCmdExe orderGetCmdExe;
    private final OrderCancelCmdExe orderCancelCmdExe;

    public OrderController(
            OrderPlaceCmdExe orderPlaceCmdExe,
            OrderPaySuccessCmdExe orderPaySuccessCmdExe,
            OrderGetCmdExe orderGetCmdExe,
            OrderCancelCmdExe orderCancelCmdExe) {
        this.orderPlaceCmdExe = orderPlaceCmdExe;
        this.orderPaySuccessCmdExe = orderPaySuccessCmdExe;
        this.orderGetCmdExe = orderGetCmdExe;
        this.orderCancelCmdExe = orderCancelCmdExe;
    }

    @LoginRequired
    @PostMapping("/orderPlace")
    public JsonResult<OrderPlaceResVO> orderPlace(@RequestBody OrderPlaceReqVO request) {
        if (request == null || request.getAmount() == null) {
            throw new BusinessException(CommonErrorCodeEnum.PARAM_MISSING, "amount is required");
        }
        Duration delay = OrderDelayParser.parseDelay(request.getDelay());
        return JsonResults.ok(orderPlaceCmdExe.execute(request.getAmount(), delay));
    }

    @LoginRequired
    @PostMapping("/pay")
    public JsonResult<PayOrderResVO> pay(@RequestBody PayOrderReqVO request) {
        long orderId = requireOrderId(request == null ? null : request.getOrderId());
        return JsonResults.ok(orderPaySuccessCmdExe.execute(orderId));
    }

    @LoginRequired
    @PostMapping("/get")
    public JsonResult<GetOrderResVO> get(@RequestBody GetOrderReqVO request) {
        long orderId = requireOrderId(request == null ? null : request.getOrderId());
        return JsonResults.ok(orderGetCmdExe.execute(orderId));
    }

    @LoginRequired
    @PostMapping("/cancel")
    public JsonResult<CancelOrderResVO> cancel(@RequestBody CancelOrderReqVO request) {
        long orderId = requireOrderId(request == null ? null : request.getOrderId());
        return JsonResults.ok(orderCancelCmdExe.execute(orderId));
    }

    private static long requireOrderId(Long orderId) {
        if (orderId == null) {
            throw new BusinessException(CommonErrorCodeEnum.PARAM_MISSING, "orderId is required");
        }
        return orderId;
    }
}
