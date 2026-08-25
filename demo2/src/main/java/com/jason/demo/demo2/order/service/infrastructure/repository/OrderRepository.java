package com.jason.demo.demo2.order.service.infrastructure.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jason.demo.demo2.order.service.common.OrderStatus;
import com.jason.demo.demo2.order.service.core.domain.Order;
import com.jason.demo.demo2.order.service.infrastructure.dao.entity.OrderDO;
import com.jason.demo.demo2.order.service.infrastructure.dao.mapper.OrderMapper;
import com.jason.demo.demo2.order.service.infrastructure.repository.convert.OrderDoConvert;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class OrderRepository {

    private final OrderMapper orderMapper;
    private final OrderDoConvert orderDoConvert;

    public OrderRepository(OrderMapper orderMapper, OrderDoConvert orderDoConvert) {
        this.orderMapper = orderMapper;
        this.orderDoConvert = orderDoConvert;
    }

    public void insert(Order order) {
        orderMapper.insert(orderDoConvert.toDo(order));
    }

    public Optional<Order> findById(long orderId) {
        return Optional.ofNullable(orderDoConvert.toDomain(orderMapper.selectById(orderId)));
    }

    public Optional<Order> findByIdAndMemberId(long orderId, long memberId) {
        OrderDO row = orderMapper.selectOne(new LambdaQueryWrapper<OrderDO>()
                .eq(OrderDO::getOrderId, orderId)
                .eq(OrderDO::getMemberId, memberId));
        return Optional.ofNullable(orderDoConvert.toDomain(row));
    }

    public boolean markPaid(long orderId, long memberId) {
        LocalDateTime now = LocalDateTime.now();
        int rows = orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>()
                .eq(OrderDO::getOrderId, orderId)
                .eq(OrderDO::getMemberId, memberId)
                .eq(OrderDO::getStatus, OrderStatus.PENDING_PAY.name())
                .set(OrderDO::getStatus, OrderStatus.PAID.name())
                .set(OrderDO::getUpdatedAt, now));
        return rows > 0;
    }

    public boolean markCancelled(long orderId, long memberId) {
        LocalDateTime now = LocalDateTime.now();
        int rows = orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>()
                .eq(OrderDO::getOrderId, orderId)
                .eq(OrderDO::getMemberId, memberId)
                .eq(OrderDO::getStatus, OrderStatus.PENDING_PAY.name())
                .set(OrderDO::getStatus, OrderStatus.CANCELLED.name())
                .set(OrderDO::getUpdatedAt, now));
        return rows > 0;
    }

    public boolean markCancelled(long orderId) {
        LocalDateTime now = LocalDateTime.now();
        int rows = orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>()
                .eq(OrderDO::getOrderId, orderId)
                .eq(OrderDO::getStatus, OrderStatus.PENDING_PAY.name())
                .set(OrderDO::getStatus, OrderStatus.CANCELLED.name())
                .set(OrderDO::getUpdatedAt, now));
        return rows > 0;
    }
}
