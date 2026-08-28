package com.jason.demo.demo2.order.app.controller;

import com.jason.demo.demo2.framework.auth.annotation.LoginRequired;
import com.jason.demo.demo2.framework.web.result.JsonResult;
import com.jason.demo.demo2.framework.web.result.JsonResults;
import com.jason.demo.demo2.order.app.executor.OrderCancelCmdExe;
import com.jason.demo.demo2.order.app.executor.OrderCountsCmdExe;
import com.jason.demo.demo2.order.app.executor.OrderGetCmdExe;
import com.jason.demo.demo2.order.app.executor.OrderListCmdExe;
import com.jason.demo.demo2.order.app.executor.OrderPaySuccessCmdExe;
import com.jason.demo.demo2.order.app.executor.OrderPlaceCmdExe;
import com.jason.demo.demo2.order.app.executor.OrderPreviewCmdExe;
import com.jason.demo.demo2.order.app.vo.req.CancelOrderReqVO;
import com.jason.demo.demo2.order.app.vo.req.GetOrderReqVO;
import com.jason.demo.demo2.order.app.vo.req.OrderListReqVO;
import com.jason.demo.demo2.order.app.vo.req.OrderPlaceReqVO;
import com.jason.demo.demo2.order.app.vo.req.OrderPreviewReqVO;
import com.jason.demo.demo2.order.app.vo.req.PayOrderReqVO;
import com.jason.demo.demo2.order.app.vo.res.CancelOrderResVO;
import com.jason.demo.demo2.order.app.vo.res.GetOrderResVO;
import com.jason.demo.demo2.order.app.vo.res.OrderCountsResVO;
import com.jason.demo.demo2.order.app.vo.res.OrderListResVO;
import com.jason.demo.demo2.order.app.vo.res.OrderPlaceResVO;
import com.jason.demo.demo2.order.app.vo.res.OrderPreviewResVO;
import com.jason.demo.demo2.order.app.vo.res.PayOrderResVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单 HTTP：全部 POST + JSON + 登录。薄转发，不注入 VoConvert。
 */
@Tag(name = "订单")
@RestController
@RequestMapping("/demo/orders")
public class OrderController {

    private final OrderPreviewCmdExe orderPreviewCmdExe;
    private final OrderPlaceCmdExe orderPlaceCmdExe;
    private final OrderPaySuccessCmdExe orderPaySuccessCmdExe;
    private final OrderGetCmdExe orderGetCmdExe;
    private final OrderCancelCmdExe orderCancelCmdExe;
    private final OrderListCmdExe orderListCmdExe;
    private final OrderCountsCmdExe orderCountsCmdExe;

    public OrderController(
            OrderPreviewCmdExe orderPreviewCmdExe,
            OrderPlaceCmdExe orderPlaceCmdExe,
            OrderPaySuccessCmdExe orderPaySuccessCmdExe,
            OrderGetCmdExe orderGetCmdExe,
            OrderCancelCmdExe orderCancelCmdExe,
            OrderListCmdExe orderListCmdExe,
            OrderCountsCmdExe orderCountsCmdExe) {
        this.orderPreviewCmdExe = orderPreviewCmdExe;
        this.orderPlaceCmdExe = orderPlaceCmdExe;
        this.orderPaySuccessCmdExe = orderPaySuccessCmdExe;
        this.orderGetCmdExe = orderGetCmdExe;
        this.orderCancelCmdExe = orderCancelCmdExe;
        this.orderListCmdExe = orderListCmdExe;
        this.orderCountsCmdExe = orderCountsCmdExe;
    }

    @LoginRequired
    @Operation(summary = "预览下单", description = "不落库、不占库存，签发 placeToken")
    @PostMapping("/preview")
    public JsonResult<OrderPreviewResVO> preview(@Valid @RequestBody OrderPreviewReqVO request) {
        return JsonResults.ok(orderPreviewCmdExe.execute(request));
    }

    @LoginRequired
    @Operation(summary = "下单", description = "校验 placeToken 后预占库存并创建 SUBMIT 订单")
    @PostMapping("/orderPlace")
    public JsonResult<OrderPlaceResVO> orderPlace(@Valid @RequestBody OrderPlaceReqVO request) {
        return JsonResults.ok(orderPlaceCmdExe.execute(request));
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

    @LoginRequired
    @Operation(summary = "订单列表")
    @PostMapping("/list")
    public JsonResult<OrderListResVO> list(@Valid @RequestBody OrderListReqVO request) {
        return JsonResults.ok(orderListCmdExe.execute(request));
    }

    @LoginRequired
    @Operation(summary = "订单数量", description = "无请求体。返回待支付/已完成数量供 Tab 冒泡")
    @PostMapping("/counts")
    public JsonResult<OrderCountsResVO> counts(@RequestBody(required = false) Object ignored) {
        return JsonResults.ok(orderCountsCmdExe.execute());
    }
}
