package com.jason.demo.demo2.order.app.executor;

import com.jason.demo.demo2.framework.auth.context.LoginContextHolder;
import com.jason.demo.demo2.framework.delay.DelayTaskService;
import com.jason.demo.demo2.framework.delay.DelayTaskType;
import com.jason.demo.demo2.framework.delay.config.DelayProperties;
import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import com.jason.demo.demo2.order.app.vo.res.OrderPlaceResVO;
import com.jason.demo.demo2.order.service.core.OrderDomainService;
import com.jason.demo.demo2.order.service.core.domain.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class OrderPlaceCmdExe {

    private final OrderDomainService orderDomainService;
    private final DelayTaskService delayTaskService;
    private final SnowflakeIdGenerator idGenerator;
    private final DelayProperties delayProperties;

    public OrderPlaceCmdExe(
            OrderDomainService orderDomainService,
            DelayTaskService delayTaskService,
            SnowflakeIdGenerator idGenerator,
            DelayProperties delayProperties) {
        this.orderDomainService = orderDomainService;
        this.delayTaskService = delayTaskService;
        this.idGenerator = idGenerator;
        this.delayProperties = delayProperties;
    }

    @Transactional
    public OrderPlaceResVO execute(BigDecimal amount, Duration delay) {
        long memberId = LoginContextHolder.require().memberId();
        long orderId = idGenerator.nextId();
        Order order = Order.create(orderId, memberId, amount, LocalDateTime.now());
        orderDomainService.place(order);

        Duration effectiveDelay = delay == null ? delayProperties.getDefaultDelay() : delay;
        long taskId = delayTaskService.schedule(
                DelayTaskType.ORDER_CANCEL,
                String.valueOf(orderId),
                null,
                effectiveDelay);

        OrderPlaceResVO res = new OrderPlaceResVO();
        res.setOrderId(orderId);
        res.setStatus(order.getOrderStatus());
        res.setAmount(order.getAmount());
        res.setTaskId(taskId);
        res.setDelay(effectiveDelay.toString());
        return res;
    }
}
