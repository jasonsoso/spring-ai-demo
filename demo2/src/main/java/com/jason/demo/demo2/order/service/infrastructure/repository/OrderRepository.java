package com.jason.demo.demo2.order.service.infrastructure.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jason.demo.demo2.order.service.core.domain.Order;
import com.jason.demo.demo2.order.service.infrastructure.dao.entity.OrderDO;
import com.jason.demo.demo2.order.service.infrastructure.dao.entity.OrderStatusCountDO;
import com.jason.demo.demo2.order.service.infrastructure.dao.mapper.OrderMapper;
import com.jason.demo.demo2.order.service.infrastructure.repository.convert.OrderDoConvert;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 订单持久化出口。对外只暴露领域 {@link Order}，表映射走 DO + Convert。 */
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

    /** SUBMIT/COMPLETED 数量，key 为状态名；缺的状态不在 map 里。 */
    public Map<String, Long> countSubmitAndCompletedByMember(long memberId) {
        Map<String, Long> counts = new HashMap<>();
        for (OrderStatusCountDO row : orderMapper.countSubmitAndCompletedByMember(memberId)) {
            if (row.getOrderStatus() == null) {
                continue;
            }
            counts.put(row.getOrderStatus(), row.getCnt() == null ? 0L : row.getCnt());
        }
        return counts;
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
