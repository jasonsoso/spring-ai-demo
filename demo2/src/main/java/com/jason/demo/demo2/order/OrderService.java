package com.jason.demo.demo2.order;

import com.jason.demo.demo2.framework.delay.DelayTaskService;
import com.jason.demo.demo2.framework.delay.DelayTaskType;
import com.jason.demo.demo2.framework.delay.config.DelayProperties;
import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import com.jason.demo.demo2.order.repository.OrderEntity;
import com.jason.demo.demo2.order.repository.OrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 订单 Demo：下单/支付与延时台账同事务。
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final DelayTaskService delayTaskService;
    private final SnowflakeIdGenerator idGenerator;
    private final DelayProperties delayProperties;

    public OrderService(
            OrderRepository orderRepository,
            DelayTaskService delayTaskService,
            SnowflakeIdGenerator idGenerator,
            DelayProperties delayProperties) {
        this.orderRepository = orderRepository;
        this.delayTaskService = delayTaskService;
        this.idGenerator = idGenerator;
        this.delayProperties = delayProperties;
    }

    /**
     * 创建待支付订单并注册超时取消任务（同一本地事务）。
     */
    @Transactional
    public Map<String, Object> create(BigDecimal amount, Duration delay) {
        if (amount == null || amount.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must be positive");
        }
        long orderId = idGenerator.nextId();
        LocalDateTime now = LocalDateTime.now();
        OrderEntity order = new OrderEntity();
        order.setOrderId(orderId);
        order.setStatus(OrderStatus.PENDING_PAY.name());
        order.setAmount(amount);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        orderRepository.insert(order);

        Duration effective = delay == null ? delayProperties.getDefaultDelay() : delay;
        long taskId = delayTaskService.schedule(
                DelayTaskType.ORDER_CANCEL,
                String.valueOf(orderId),
                null,
                effective);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("status", order.getStatus());
        result.put("amount", amount);
        result.put("taskId", taskId);
        result.put("delay", effective.toString());
        return result;
    }

    /**
     * 支付成功：先校验待支付，再置 PAID，并取消对应延时任务（同一本地事务）。
     */
    @Transactional
    public Map<String, Object> pay(long orderId) {
        OrderEntity existing = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found"));
        if (!OrderStatus.PENDING_PAY.name().equals(existing.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "cannot pay order in status " + existing.getStatus());
        }
        boolean paid = orderRepository.markPaid(orderId);
        if (!paid) {
            OrderEntity latest = orderRepository.findById(orderId).orElse(existing);
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "cannot pay order in status " + latest.getStatus());
        }
        delayTaskService.cancelByBizKey(DelayTaskType.ORDER_CANCEL, String.valueOf(orderId));
        return Map.of("orderId", orderId, "status", OrderStatus.PAID.name());
    }

    public OrderEntity get(long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found"));
    }
}
