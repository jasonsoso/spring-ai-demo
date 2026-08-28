package com.jason.demo.demo2.order;

import com.jason.demo.demo2.order.app.controller.OrderController;
import com.jason.demo.demo2.order.app.vo.req.OrderListReqVO;
import com.jason.demo.demo2.order.app.vo.req.OrderPreviewReqVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class OrderControllerMappingTest {

    @Test
    void controller_exposesPreviewPlaceListCounts() throws Exception {
        assertNotNull(OrderController.class.getMethod("preview", OrderPreviewReqVO.class));
        assertNotNull(OrderController.class.getMethod("list", OrderListReqVO.class));
        assertNotNull(OrderController.class.getMethod("counts", Object.class));
    }
}
