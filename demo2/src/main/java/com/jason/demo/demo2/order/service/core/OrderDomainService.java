package com.jason.demo.demo2.order.service.core;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.order.service.common.OrderErrorCodeEnum;
import com.jason.demo.demo2.order.service.core.domain.Order;
import com.jason.demo.demo2.order.service.infrastructure.repository.OrderRepository;
import org.springframework.stereotype.Service;

@Service
public class OrderDomainService {

    private final OrderRepository orderRepository;

    public OrderDomainService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public void place(Order order) {
        orderRepository.insert(order);
    }

    public Order requireOrder(long orderId, long memberId) {
        return orderRepository.findByIdAndMemberId(orderId, memberId)
                .orElseThrow(() -> new BusinessException(OrderErrorCodeEnum.ORDER_NOT_FOUND));
    }

    public void payOrder(long orderId, long memberId) {
        Order order = requireOrder(orderId, memberId);
        order.pay();
        if (!orderRepository.markPaid(orderId, memberId)) {
            Order latest = requireOrder(orderId, memberId);
            throw new BusinessException(OrderErrorCodeEnum.ORDER_STATUS_CONFLICT,
                    "cannot pay order in status " + latest.getStatus());
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
        return orderRepository.markCancelled(orderId);
    }

    public void manualCancel(long orderId, long memberId) {
        Order order = requireOrder(orderId, memberId);
        if (!order.cancel()) {
            throw new BusinessException(OrderErrorCodeEnum.ORDER_STATUS_CONFLICT,
                    "cannot cancel order in status " + order.getStatus());
        }
        if (!orderRepository.markCancelled(orderId, memberId)) {
            Order latest = requireOrder(orderId, memberId);
            throw new BusinessException(OrderErrorCodeEnum.ORDER_STATUS_CONFLICT,
                    "cannot cancel order in status " + latest.getStatus());
        }
    }
}
