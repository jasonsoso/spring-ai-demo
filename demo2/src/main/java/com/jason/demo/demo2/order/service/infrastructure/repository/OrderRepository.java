package com.jason.demo.demo2.order.service.infrastructure.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jason.demo.demo2.order.service.common.OrderStatusEnum;
import com.jason.demo.demo2.order.service.common.PayStatusEnum;
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

    public boolean markCompleted(long orderId, long memberId) {
        LocalDateTime now = LocalDateTime.now();
        int rows = orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>()
                .eq(OrderDO::getOrderId, orderId)
                .eq(OrderDO::getMemberId, memberId)
                .eq(OrderDO::getOrderStatus, OrderStatusEnum.SUBMIT.name())
                .set(OrderDO::getOrderStatus, OrderStatusEnum.COMPLETED.name())
                .set(OrderDO::getPayStatus, PayStatusEnum.PAY_SUCCESS.name())
                .set(OrderDO::getPayTime, now)
                .set(OrderDO::getUpdatedAt, now));
        return rows > 0;
    }

    public boolean markCancelled(long orderId, long memberId) {
        LocalDateTime now = LocalDateTime.now();
        int rows = orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>()
                .eq(OrderDO::getOrderId, orderId)
                .eq(OrderDO::getMemberId, memberId)
                .eq(OrderDO::getOrderStatus, OrderStatusEnum.SUBMIT.name())
                .set(OrderDO::getOrderStatus, OrderStatusEnum.CANCEL.name())
                .set(OrderDO::getPayStatus, PayStatusEnum.CLOSE.name())
                .set(OrderDO::getCancelTime, now)
                .set(OrderDO::getUpdatedAt, now));
        return rows > 0;
    }

    public boolean markCancelled(long orderId) {
        LocalDateTime now = LocalDateTime.now();
        int rows = orderMapper.update(null, new LambdaUpdateWrapper<OrderDO>()
                .eq(OrderDO::getOrderId, orderId)
                .eq(OrderDO::getOrderStatus, OrderStatusEnum.SUBMIT.name())
                .set(OrderDO::getOrderStatus, OrderStatusEnum.CANCEL.name())
                .set(OrderDO::getPayStatus, PayStatusEnum.CLOSE.name())
                .set(OrderDO::getCancelTime, now)
                .set(OrderDO::getUpdatedAt, now));
        return rows > 0;
    }
}
