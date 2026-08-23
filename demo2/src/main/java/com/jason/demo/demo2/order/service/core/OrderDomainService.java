package com.jason.demo.demo2.order.service.core;

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

    public Order requireOrder(long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderDomainException(
                        OrderDomainException.Code.NOT_FOUND,
                        "order not found"));
    }

    public void payOrder(long orderId) {
        Order order = requireOrder(orderId);
        order.pay();
        if (!orderRepository.markPaid(orderId)) {
            Order latest = requireOrder(orderId);
            throw new OrderDomainException(
                    OrderDomainException.Code.CONFLICT,
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

    public void manualCancel(long orderId) {
        Order order = requireOrder(orderId);
        if (!order.cancel()) {
            throw new OrderDomainException(
                    OrderDomainException.Code.CONFLICT,
                    "cannot cancel order in status " + order.getStatus());
        }
        if (!orderRepository.markCancelled(orderId)) {
            Order latest = requireOrder(orderId);
            throw new OrderDomainException(
                    OrderDomainException.Code.CONFLICT,
                    "cannot cancel order in status " + latest.getStatus());
        }
    }
}
