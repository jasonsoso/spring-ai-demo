package com.jason.demo.demo2.order.service.common;

import com.jason.demo.demo2.framework.web.exception.BusinessException;

import java.util.HashSet;
import java.util.List;

/** 本版只允许 1 行且 productId 不重复；下版放宽 size 即可一单多商品。 */
public final class OrderItemsRules {

    private OrderItemsRules() {
    }

    public static void requireOneDistinctProduct(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty() || productIds.size() > 1
                || new HashSet<>(productIds).size() != productIds.size()) {
            throw new BusinessException(OrderErrorCodeEnum.ORDER_ITEMS_INVALID);
        }
    }
}
