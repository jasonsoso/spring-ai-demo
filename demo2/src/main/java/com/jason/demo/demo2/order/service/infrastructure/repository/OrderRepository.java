package com.jason.demo.demo2.order.service.infrastructure.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jason.demo.demo2.order.service.core.domain.Order;
import com.jason.demo.demo2.order.service.infrastructure.dao.entity.OrderDO;
import com.jason.demo.demo2.order.service.infrastructure.dao.mapper.OrderMapper;
import com.jason.demo.demo2.order.service.infrastructure.repository.convert.OrderDoConvert;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class OrderRepository {

    private final OrderMapper orderMapper;
    private final OrderItemRepository orderItemRepository;
    private final OrderDoConvert orderDoConvert;

    public OrderRepository(
            OrderMapper orderMapper,
            OrderItemRepository orderItemRepository,
            OrderDoConvert orderDoConvert) {
        this.orderMapper = orderMapper;
        this.orderItemRepository = orderItemRepository;
        this.orderDoConvert = orderDoConvert;
    }

    public void insert(Order order) {
        orderMapper.insert(orderDoConvert.toDo(order));
    }

    public void insertWithItems(Order order) {
        insert(order);
        orderItemRepository.insertAll(order.getItems());
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

    public boolean markCompleted(long orderId, Long memberId, LocalDateTime payTime) {
        return orderMapper.markCompleted(orderId, memberId, payTime) > 0;
    }

    public boolean markCancelled(long orderId, Long memberId, LocalDateTime cancelTime) {
        return orderMapper.markCancelled(orderId, memberId, cancelTime) > 0;
    }

    public long countByMemberAndStatus(long memberId, String orderStatus) {
        return orderMapper.countByMemberAndStatus(memberId, orderStatus);
    }

    public long countPageByMemberAndTab(long memberId, String orderStatusOrNull) {
        return orderMapper.countPageByMemberAndTab(memberId, orderStatusOrNull);
    }

    public List<Order> pageByMemberAndTab(long memberId, String orderStatusOrNull, int offset, int pageSize) {
        return orderMapper.pageByMemberAndTab(memberId, orderStatusOrNull, offset, pageSize).stream()
                .map(orderDoConvert::toDomain)
                .toList();
    }
}
