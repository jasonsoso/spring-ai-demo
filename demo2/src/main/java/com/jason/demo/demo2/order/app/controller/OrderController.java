package com.jason.demo.demo2.order.app.controller;

import com.jason.demo.demo2.framework.auth.annotation.LoginRequired;
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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@Tag(name = "订单")
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
    @Operation(summary = "下单", description = "创建待支付订单并注册超时延时任务")
    @PostMapping("/orderPlace")
    public JsonResult<OrderPlaceResVO> orderPlace(@Valid @RequestBody OrderPlaceReqVO request) {
        Duration delay = OrderDelayParser.parseDelay(request.getDelay());
        return JsonResults.ok(orderPlaceCmdExe.execute(request, delay));
    }

    @LoginRequired
    @Operation(summary = "支付成功", description = "将订单置为已支付")
    @PostMapping("/pay")
    public JsonResult<PayOrderResVO> pay(@Valid @RequestBody PayOrderReqVO request) {
        return JsonResults.ok(orderPaySuccessCmdExe.execute(request.getOrderId()));
    }

    @LoginRequired
    @Operation(summary = "查询订单", description = "按 orderId 查询订单")
    @PostMapping("/get")
    public JsonResult<GetOrderResVO> get(@Valid @RequestBody GetOrderReqVO request) {
        return JsonResults.ok(orderGetCmdExe.execute(request.getOrderId()));
    }

    @LoginRequired
    @Operation(summary = "取消订单", description = "取消待支付订单")
    @PostMapping("/cancel")
    public JsonResult<CancelOrderResVO> cancel(@Valid @RequestBody CancelOrderReqVO request) {
        return JsonResults.ok(orderCancelCmdExe.execute(request.getOrderId()));
    }
}
