package com.jason.demo.demo2.order;

import com.jason.demo.demo2.framework.delay.DelayTaskHandler;
import com.jason.demo.demo2.framework.delay.DelayTaskType;
import com.jason.demo.demo2.framework.delay.repository.DelayTaskEntity;
import com.jason.demo.demo2.order.repository.OrderEntity;
import com.jason.demo.demo2.order.repository.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单超时取消：仅 {@link OrderStatus#PENDING_PAY} 可取消。
 */
@Slf4j
@Component
public class OrderCancelHandler implements DelayTaskHandler {

    private final OrderRepository orderRepository;

    public OrderCancelHandler(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public String taskType() {
        return DelayTaskType.ORDER_CANCEL;
    }

    @Override
    @Transactional
    public void handle(DelayTaskEntity task) {
        long orderId = Long.parseLong(task.getBizKey());
        OrderEntity order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("order not found for cancel task, orderId={}, taskId={}", orderId, task.getTaskId());
            return;
        }
        if (!OrderStatus.PENDING_PAY.name().equals(order.getStatus())) {
            log.info("skip cancel, order status={}, orderId={}", order.getStatus(), orderId);
            return;
        }
        boolean cancelled = orderRepository.markCancelled(orderId);
        log.info("order cancel result={}, orderId={}, taskId={}", cancelled, orderId, task.getTaskId());
    }
}
