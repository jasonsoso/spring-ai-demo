package com.jason.demo.demo2.order.app.executor;

import com.jason.demo.demo2.order.service.core.OrderDomainService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class OrderExpireCmdExe {

    private final OrderDomainService orderDomainService;

    public OrderExpireCmdExe(OrderDomainService orderDomainService) {
        this.orderDomainService = orderDomainService;
    }

    @Transactional
    public void execute(long orderId) {
        boolean cancelled = orderDomainService.expireCancel(orderId);
        if (!cancelled) {
            log.info("skip expire cancel, orderId={}", orderId);
        }
    }
}
