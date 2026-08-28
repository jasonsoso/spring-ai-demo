package com.jason.demo.demo2.order.service.core.domain;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.order.service.common.OrderErrorCodeEnum;
import com.jason.demo.demo2.order.service.infrastructure.dao.entity.OrderItemDO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

public class OrderItem extends OrderItemDO {

    public static OrderItem create(
            long itemId,
            long orderId,
            long memberId,
            long productId,
            String productName,
            String subtitle,
            String coverUrl,
            BigDecimal sellPrice,
            BigDecimal marketPrice,
            int qty) {
        if (qty < 1 || qty > 99999) {
            throw new BusinessException(OrderErrorCodeEnum.QTY_INVALID);
        }
        OrderItem item = new OrderItem();
        item.setItemId(itemId);
        item.setOrderId(orderId);
        item.setMemberId(memberId);
        item.setProductId(productId);
        item.setProductName(productName);
        item.setSubtitle(subtitle);
        item.setCoverUrl(coverUrl);
        item.setSellPrice(sellPrice);
        item.setMarketPrice(marketPrice);
        item.setQty(qty);
        item.setCreatedAt(LocalDateTime.now());
        return item;
    }

    public static OrderItem from(OrderItemDO source) {
        if (source == null) {
            return null;
        }
        OrderItem item = new OrderItem();
        item.setId(source.getId());
        item.setItemId(source.getItemId());
        item.setOrderId(source.getOrderId());
        item.setMemberId(source.getMemberId());
        item.setProductId(source.getProductId());
        item.setProductName(source.getProductName());
        item.setSubtitle(source.getSubtitle());
        item.setCoverUrl(source.getCoverUrl());
        item.setSellPrice(source.getSellPrice());
        item.setMarketPrice(source.getMarketPrice());
        item.setQty(source.getQty());
        item.setCreatedAt(source.getCreatedAt());
        return item;
    }

    public BigDecimal lineAmount() {
        return getSellPrice().multiply(BigDecimal.valueOf(getQty())).setScale(2, RoundingMode.UNNECESSARY);
    }
}
