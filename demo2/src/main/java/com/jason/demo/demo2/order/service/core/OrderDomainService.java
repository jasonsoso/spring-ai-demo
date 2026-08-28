package com.jason.demo.demo2.order.service.core;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.order.service.common.OrderErrorCodeEnum;
import com.jason.demo.demo2.order.service.core.domain.Order;
import com.jason.demo.demo2.order.service.infrastructure.repository.OrderItemRepository;
import com.jason.demo.demo2.order.service.infrastructure.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class OrderDomainService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    public OrderDomainService(OrderRepository orderRepository, OrderItemRepository orderItemRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
    }

    public void place(Order order) {
        orderRepository.insert(order);
    }

    public Order requireOrder(long orderId, long memberId) {
        return orderRepository.findByIdAndMemberId(orderId, memberId)
                .orElseThrow(() -> new BusinessException(OrderErrorCodeEnum.ORDER_NOT_FOUND));
    }

    public Order requireOrder(long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCodeEnum.ORDER_NOT_FOUND));
    }

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

    public void payOrder(long orderId, long memberId) {
        Order order = requireOrder(orderId, memberId);
        order.pay();
        if (!orderRepository.markCompleted(orderId, memberId, LocalDateTime.now())) {
            Order latest = requireOrder(orderId, memberId);
            throw new BusinessException(OrderErrorCodeEnum.ORDER_STATUS_CONFLICT,
                    "cannot pay order in status " + latest.getOrderStatus());
        }
    }

    public boolean expireCancel(long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return false;
        }
        if (!order.cancel()) {
            return false;
        }
        return orderRepository.markCancelled(orderId, null, LocalDateTime.now());
    }

    public void manualCancel(long orderId, long memberId) {
        Order order = requireOrder(orderId, memberId);
        if (!order.cancel()) {
            throw new BusinessException(OrderErrorCodeEnum.ORDER_STATUS_CONFLICT,
                    "cannot cancel order in status " + order.getOrderStatus());
        }
        if (!orderRepository.markCancelled(orderId, memberId, LocalDateTime.now())) {
            Order latest = requireOrder(orderId, memberId);
            throw new BusinessException(OrderErrorCodeEnum.ORDER_STATUS_CONFLICT,
                    "cannot cancel order in status " + latest.getOrderStatus());
        }
    }
}
