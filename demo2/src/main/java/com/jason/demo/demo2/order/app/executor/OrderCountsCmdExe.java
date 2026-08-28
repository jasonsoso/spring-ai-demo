package com.jason.demo.demo2.order.app.executor;

import com.jason.demo.demo2.framework.auth.context.LoginContextHolder;
import com.jason.demo.demo2.order.app.vo.res.OrderCountsResVO;
import com.jason.demo.demo2.order.service.common.OrderStatusEnum;
import com.jason.demo.demo2.order.service.infrastructure.repository.OrderRepository;
import org.springframework.stereotype.Service;

@Service
public class OrderCountsCmdExe {

    private final OrderRepository orderRepository;

    public OrderCountsCmdExe(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public OrderCountsResVO execute() {
        long memberId = LoginContextHolder.require().memberId();
        OrderCountsResVO vo = new OrderCountsResVO();
        vo.setPendingCount(orderRepository.countByMemberAndStatus(memberId, OrderStatusEnum.SUBMIT.name()));
        vo.setCompletedCount(orderRepository.countByMemberAndStatus(memberId, OrderStatusEnum.COMPLETED.name()));
        return vo;
    }
}
