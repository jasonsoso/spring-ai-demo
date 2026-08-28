package com.jason.demo.demo2.order.service.core;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.order.service.common.OrderErrorCodeEnum;
import com.jason.demo.demo2.order.service.core.domain.Order;
import com.jason.demo.demo2.order.service.infrastructure.repository.OrderItemRepository;
import com.jason.demo.demo2.order.service.infrastructure.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** 订单查询门面。写路径走状态机 Action，这里只读。 */
@Service
public class OrderDomainService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    public OrderDomainService(OrderRepository orderRepository, OrderItemRepository orderItemRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
    }

    public Optional<Order> findById(long orderId) {
        return orderRepository.findById(orderId);
    }

    public Order requireOrder(long orderId, long memberId) {
        return orderRepository.findByIdAndMemberId(orderId, memberId)
                .orElseThrow(() -> new BusinessException(OrderErrorCodeEnum.ORDER_NOT_FOUND));
    }

    public Order requireOrder(long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCodeEnum.ORDER_NOT_FOUND));
    }

    /** 详情/幂等回读：主表没有对应会员或明细缺失时由调用方按空 items 展示。 */
    public Order requireOrderWithItems(long orderId, long memberId) {
        Order order = requireOrder(orderId, memberId);
        order.setItems(orderItemRepository.listByOrderId(orderId));
        return order;
    }

    public Order requireOrderWithItems(long orderId) {
        Order order = requireOrder(orderId);
        order.setItems(orderItemRepository.listByOrderId(orderId));
        return order;
    }
}
