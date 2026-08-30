package com.jason.demo.demo2.order;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.framework.web.exception.CommonErrorCodeEnum;
import com.jason.demo.demo2.order.app.executor.OrderShardExplainCmdExe;
import com.jason.demo.demo2.order.app.vo.req.OrderShardExplainReqVO;
import com.jason.demo.demo2.order.app.vo.res.OrderShardExplainResVO;
import com.jason.demo.demo2.order.service.common.OrderShardSourceEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderShardExplainCmdExeTest {

    private final OrderShardExplainCmdExe exe = new OrderShardExplainCmdExe();

    @Test
    void empty_throwsParamMissing() {
        BusinessException ex = assertThrows(BusinessException.class, () -> exe.execute(new OrderShardExplainReqVO()));
        assertEquals(CommonErrorCodeEnum.PARAM_MISSING.getCode(), ex.getCode());
    }

    @Test
    void memberOnly() {
        OrderShardExplainReqVO req = new OrderShardExplainReqVO();
        req.setMemberId(612L);
        OrderShardExplainResVO res = exe.execute(req);
        assertEquals(100L, res.getVirtual());
        assertEquals("001100100", res.getGeneBits());
        assertEquals("order_ds_0", res.getDs());
        assertEquals("demo_order_18", res.getTable());
        assertEquals("demo_order_item_18", res.getItemTable());
        assertEquals(OrderShardSourceEnum.MEMBER_ID.name(), res.getSource());
        assertEquals(100L, res.getMemberVirtual());
        assertNull(res.getOrderVirtual());
        assertNull(res.getGeneMatch());
    }

    @Test
    void orderOnly() {
        OrderShardExplainReqVO req = new OrderShardExplainReqVO();
        req.setOrderId((99L << 9) | 100L);
        OrderShardExplainResVO res = exe.execute(req);
        assertEquals(OrderShardSourceEnum.ORDER_ID.name(), res.getSource());
        assertEquals(100L, res.getOrderVirtual());
        assertNull(res.getMemberVirtual());
        assertEquals("demo_order_18", res.getTable());
    }

    @Test
    void both_matchAndMismatch() {
        OrderShardExplainReqVO match = new OrderShardExplainReqVO();
        match.setMemberId(612L);
        match.setOrderId((1L << 9) | 100L);
        OrderShardExplainResVO ok = exe.execute(match);
        assertEquals(OrderShardSourceEnum.MEMBER_ID.name(), ok.getSource());
        assertTrue(ok.getGeneMatch());
        assertEquals("demo_order_18", ok.getTable());

        OrderShardExplainReqVO bad = new OrderShardExplainReqVO();
        bad.setMemberId(612L);
        bad.setOrderId((1L << 9) | 7L);
        OrderShardExplainResVO res = exe.execute(bad);
        assertEquals(Boolean.FALSE, res.getGeneMatch());
        assertEquals("demo_order_18", res.getTable());
    }
}
