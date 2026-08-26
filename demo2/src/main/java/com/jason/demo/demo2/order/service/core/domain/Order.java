package com.jason.demo.demo2.order.service.core.domain;

import com.jason.demo.demo2.order.service.common.OrderStatusEnum;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.order.service.common.OrderErrorCodeEnum;
import com.jason.demo.demo2.order.service.infrastructure.dao.entity.OrderDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class Order extends OrderDO {

    public static Order create(long orderId, long memberId, BigDecimal amount, LocalDateTime now) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(OrderErrorCodeEnum.AMOUNT_INVALID);
        }
        Order order = new Order();
        order.setOrderId(orderId);
        order.setMemberId(memberId);
        order.setStatus(OrderStatusEnum.PENDING_PAY.name());
        order.setAmount(amount);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        return order;
    }

    public static Order from(OrderDO source) {
        if (source == null) {
            return null;
        }
        Order order = new Order();
        order.setOrderId(source.getOrderId());
        order.setMemberId(source.getMemberId());
        order.setStatus(source.getStatus());
        order.setAmount(source.getAmount());
        order.setCreatedAt(source.getCreatedAt());
        order.setUpdatedAt(source.getUpdatedAt());
        return order;
    }

    public void pay() {
        if (!OrderStatusEnum.PENDING_PAY.name().equals(getStatus())) {
            throw new BusinessException(OrderErrorCodeEnum.ORDER_STATUS_CONFLICT,
                    "cannot pay order in status " + getStatus());
        }
        setStatus(OrderStatusEnum.PAID.name());
        setUpdatedAt(LocalDateTime.now());
    }

    public boolean cancel() {
        if (!OrderStatusEnum.PENDING_PAY.name().equals(getStatus())) {
            return false;
        }
        setStatus(OrderStatusEnum.CANCELLED.name());
        setUpdatedAt(LocalDateTime.now());
        return true;
    }
}
