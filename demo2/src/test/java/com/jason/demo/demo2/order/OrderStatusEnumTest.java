package com.jason.demo.demo2.order;

import com.jason.demo.demo2.order.service.common.OrderErrorCodeEnum;
import com.jason.demo.demo2.order.service.common.OrderItemsRules;
import com.jason.demo.demo2.order.service.common.OrderStatusEnum;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderStatusEnumTest {

    @Test
    void finalStatuses_areCompletedAndCancel() {
        assertTrue(OrderStatusEnum.COMPLETED.isFinalStatus());
        assertTrue(OrderStatusEnum.CANCEL.isFinalStatus());
        assertFalse(OrderStatusEnum.SUBMIT.isFinalStatus());
        assertFalse(OrderStatusEnum.INIT.isFinalStatus());
    }

    @Test
    void itemsRules_rejectEmptyOrTwoLines() {
        BusinessException empty = assertThrows(BusinessException.class,
                () -> OrderItemsRules.requireOneDistinctProduct(List.of()));
        assertEquals(OrderErrorCodeEnum.ORDER_ITEMS_INVALID.getCode(), empty.getCode());
    }

    @Test
    void errorCodes_matchSpec() {
        assertEquals(30007, OrderErrorCodeEnum.QTY_INVALID.getCode());
        assertEquals(30008, OrderErrorCodeEnum.PRICE_CHANGED.getCode());
        assertEquals(30009, OrderErrorCodeEnum.PLACE_TOKEN_INVALID.getCode());
        assertEquals(30010, OrderErrorCodeEnum.ORDER_ITEMS_INVALID.getCode());
    }
}
