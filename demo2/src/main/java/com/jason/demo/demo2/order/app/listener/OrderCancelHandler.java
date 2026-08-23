package com.jason.demo.demo2.order.app.listener;

import com.jason.demo.demo2.framework.delay.DelayTaskHandler;
import com.jason.demo.demo2.framework.delay.DelayTaskType;
import com.jason.demo.demo2.framework.delay.repository.DelayTaskEntity;
import com.jason.demo.demo2.order.app.executor.OrderExpireCmdExe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 订单超时取消延时任务适配器，业务逻辑委托给 {@link OrderExpireCmdExe}。
 */
@Slf4j
@Component
public class OrderCancelHandler implements DelayTaskHandler {

    private final OrderExpireCmdExe orderExpireCmdExe;

    public OrderCancelHandler(OrderExpireCmdExe orderExpireCmdExe) {
        this.orderExpireCmdExe = orderExpireCmdExe;
    }

    @Override
    public String taskType() {
        return DelayTaskType.ORDER_CANCEL;
    }

    @Override
    public void handle(DelayTaskEntity task) {
        long orderId = Long.parseLong(task.getBizKey());
        log.info("handle order cancel delay task, orderId={}, taskId={}", orderId, task.getTaskId());
        orderExpireCmdExe.execute(orderId);
    }
}
