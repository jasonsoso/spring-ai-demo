package com.jason.demo.demo2.order.service.infrastructure.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jason.demo.demo2.order.service.core.domain.OrderItem;
import com.jason.demo.demo2.order.service.infrastructure.dao.entity.OrderItemDO;
import com.jason.demo.demo2.order.service.infrastructure.dao.mapper.OrderItemMapper;
import com.jason.demo.demo2.order.service.infrastructure.repository.convert.OrderItemDoConvert;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class OrderItemRepository {

    private final OrderItemMapper orderItemMapper;
    private final OrderItemDoConvert orderItemDoConvert;

    public OrderItemRepository(OrderItemMapper orderItemMapper, OrderItemDoConvert orderItemDoConvert) {
        this.orderItemMapper = orderItemMapper;
        this.orderItemDoConvert = orderItemDoConvert;
    }

    public void insertAll(List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (OrderItem item : items) {
            orderItemMapper.insert(orderItemDoConvert.toDo(item));
        }
    }

    public List<OrderItem> listByOrderId(long orderId) {
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItemDO>()
                        .eq(OrderItemDO::getOrderId, orderId))
                .stream()
                .map(orderItemDoConvert::toDomain)
                .toList();
    }

    public Map<Long, List<OrderItem>> listByOrderIds(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItemDO>()
                        .in(OrderItemDO::getOrderId, orderIds))
                .stream()
                .map(orderItemDoConvert::toDomain)
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
    }
}
