package com.jason.demo.demo2.order.app.executor;

import com.jason.demo.demo2.framework.auth.context.LoginContextHolder;
import com.jason.demo.demo2.order.app.vo.res.OrderCountsResVO;
import com.jason.demo.demo2.order.service.common.OrderStatusEnum;
import com.jason.demo.demo2.order.service.infrastructure.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Tab 冒泡：一条 GROUP BY 取 SUBMIT/COMPLETED。某状态无单时结果集没有该行，这里补 0。
 */
@Service
public class OrderCountsCmdExe {

    private final OrderRepository orderRepository;

    public OrderCountsCmdExe(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public OrderCountsResVO execute() {
        long memberId = LoginContextHolder.require().memberId();
        Map<String, Long> counts = orderRepository.countSubmitAndCompletedByMember(memberId);
        OrderCountsResVO vo = new OrderCountsResVO();
        vo.setPendingCount(counts.getOrDefault(OrderStatusEnum.SUBMIT.name(), 0L));
        vo.setCompletedCount(counts.getOrDefault(OrderStatusEnum.COMPLETED.name(), 0L));
        return vo;
    }
}
