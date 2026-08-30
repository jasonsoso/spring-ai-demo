package com.jason.demo.demo2.order.app.executor;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.framework.web.exception.CommonErrorCodeEnum;
import com.jason.demo.demo2.order.app.vo.req.OrderShardExplainReqVO;
import com.jason.demo.demo2.order.app.vo.res.OrderShardExplainResVO;
import com.jason.demo.demo2.order.service.common.OrderShardSourceEnum;
import com.jason.demo.demo2.order.service.infrastructure.shard.OrderShardGene;
import org.springframework.stereotype.Service;

@Service
public class OrderShardExplainCmdExe {

    public OrderShardExplainResVO execute(OrderShardExplainReqVO req) {
        Long memberId = req == null ? null : req.getMemberId();
        Long orderId = req == null ? null : req.getOrderId();
        boolean hasMember = memberId != null;
        boolean hasOrder = orderId != null;
        if (!hasMember && !hasOrder) {
            throw new BusinessException(CommonErrorCodeEnum.PARAM_MISSING);
        }
        Long memberVirtual = hasMember ? OrderShardGene.virtualOfMember(memberId) : null;
        Long orderVirtual = hasOrder ? OrderShardGene.virtualOfOrderId(orderId) : null;
        long virtual = hasMember ? memberVirtual : orderVirtual;
        OrderShardSourceEnum source = hasMember
                ? OrderShardSourceEnum.MEMBER_ID
                : OrderShardSourceEnum.ORDER_ID;
        OrderShardExplainResVO res = new OrderShardExplainResVO();
        res.setVirtual(virtual);
        res.setGeneBits(OrderShardGene.geneBits(virtual));
        res.setDs(OrderShardGene.dsName(virtual));
        res.setTable(OrderShardGene.orderTableName(virtual));
        res.setItemTable(OrderShardGene.itemTableName(virtual));
        res.setSource(source.name());
        res.setMemberVirtual(memberVirtual);
        res.setOrderVirtual(orderVirtual);
        if (hasMember && hasOrder) {
            res.setGeneMatch(memberVirtual.equals(orderVirtual));
        }
        return res;
    }
}
