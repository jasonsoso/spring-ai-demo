package com.jason.demo.demo2.order.service.core.domain;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.order.service.common.OrderErrorCodeEnum;
import com.jason.demo.demo2.order.service.common.OrderStatusEnum;
import com.jason.demo.demo2.order.service.common.PayStatusEnum;
import com.jason.demo.demo2.order.service.infrastructure.dao.entity.OrderDO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 订单聚合。继承 {@link OrderDO} 只为复用字段，不是表映射类；{@code items} 在明细表。
 * 落库走 {@code OrderDoConvert.toDo}，不要把本对象直接丢给 Mapper。
 */
public class Order extends OrderDO {

    private List<OrderItem> items = new ArrayList<>();

    public static Order create(long orderId, long memberId, List<OrderItem> items, LocalDateTime now) {
        List<OrderItem> lines = items == null ? List.of() : items;
        BigDecimal amount = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        for (OrderItem item : lines) {
            amount = amount.add(item.lineAmount());
        }
        if (amount.signum() <= 0) {
            throw new BusinessException(OrderErrorCodeEnum.AMOUNT_INVALID);
        }
        Order order = new Order();
        order.setOrderId(orderId);
        order.setMemberId(memberId);
        order.setOrderStatus(OrderStatusEnum.SUBMIT.name());
        order.setPayStatus(PayStatusEnum.WAIT_PAY.name());
        order.setAmount(amount);
        order.setItems(new ArrayList<>(lines));
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
        order.setOrderStatus(source.getOrderStatus());
        order.setPayStatus(source.getPayStatus());
        order.setAmount(source.getAmount());
        order.setPayTime(source.getPayTime());
        order.setCancelTime(source.getCancelTime());
        order.setCreatedAt(source.getCreatedAt());
        order.setUpdatedAt(source.getUpdatedAt());
        return order;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public void setItems(List<OrderItem> items) {
        this.items = items == null ? new ArrayList<>() : items;
    }
}
