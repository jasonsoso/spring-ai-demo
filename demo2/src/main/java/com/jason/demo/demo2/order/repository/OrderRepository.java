package com.jason.demo.demo2.order.repository;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jason.demo.demo2.order.OrderStatus;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class OrderRepository {

    private final OrderMapper mapper;

    public OrderRepository(OrderMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(OrderEntity entity) {
        mapper.insert(entity);
    }

    public Optional<OrderEntity> findById(long orderId) {
        return Optional.ofNullable(mapper.selectById(orderId));
    }

    public boolean markPaid(long orderId) {
        LocalDateTime now = LocalDateTime.now();
        int rows = mapper.update(null, new LambdaUpdateWrapper<OrderEntity>()
                .eq(OrderEntity::getOrderId, orderId)
                .eq(OrderEntity::getStatus, OrderStatus.PENDING_PAY.name())
                .set(OrderEntity::getStatus, OrderStatus.PAID.name())
                .set(OrderEntity::getUpdatedAt, now));
        return rows > 0;
    }

    public boolean markCancelled(long orderId) {
        LocalDateTime now = LocalDateTime.now();
        int rows = mapper.update(null, new LambdaUpdateWrapper<OrderEntity>()
                .eq(OrderEntity::getOrderId, orderId)
                .eq(OrderEntity::getStatus, OrderStatus.PENDING_PAY.name())
                .set(OrderEntity::getStatus, OrderStatus.CANCELLED.name())
                .set(OrderEntity::getUpdatedAt, now));
        return rows > 0;
    }
}
