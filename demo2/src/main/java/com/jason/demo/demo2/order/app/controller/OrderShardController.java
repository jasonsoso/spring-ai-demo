package com.jason.demo.demo2.order.app.controller;

import com.jason.demo.demo2.framework.web.result.JsonResult;
import com.jason.demo.demo2.framework.web.result.JsonResults;
import com.jason.demo.demo2.order.app.executor.OrderShardExplainCmdExe;
import com.jason.demo.demo2.order.app.vo.req.OrderShardExplainReqVO;
import com.jason.demo.demo2.order.app.vo.res.OrderShardExplainResVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "订单分片")
@RestController
@RequestMapping("/demo/orders")
public class OrderShardController {

    private final OrderShardExplainCmdExe orderShardExplainCmdExe;

    public OrderShardController(OrderShardExplainCmdExe orderShardExplainCmdExe) {
        this.orderShardExplainCmdExe = orderShardExplainCmdExe;
    }

    @Operation(summary = "分片路由试算", description = "不登录、不查库。orderId 与 memberId 至少填一个")
    @PostMapping("/shardExplain")
    public JsonResult<OrderShardExplainResVO> shardExplain(
            @RequestBody(required = false) OrderShardExplainReqVO request) {
        return JsonResults.ok(orderShardExplainCmdExe.execute(request));
    }
}
