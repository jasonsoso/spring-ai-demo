package com.jason.demo.demo2.order.service.core.statemachine.action;

import com.alibaba.cola.statemachine.Action;
import com.jason.demo.demo2.order.service.common.OrderEventEnum;
import com.jason.demo.demo2.order.service.common.OrderStatusEnum;
import com.jason.demo.demo2.order.service.common.PayStatusEnum;
import com.jason.demo.demo2.order.service.core.domain.Order;
import com.jason.demo.demo2.order.service.core.domain.OrderItem;
import com.jason.demo.demo2.order.service.core.statemachine.OrderContext;
import com.jason.demo.demo2.order.service.infrastructure.repository.OrderRepository;
import com.jason.demo.demo2.product.service.core.ProductStockHotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

/**
 * INIT → SUBMIT：先写主表+明细，再逐行 {@code reserve}。
 * Redis 热库存不在 JDBC 事务里，本地回滚时按已预占商品补偿 {@code release}。
 */
@Slf4j
@Component
public class OrderPlaceAction implements Action<OrderStatusEnum, OrderEventEnum, OrderContext> {

    private final OrderRepository orderRepository;
    private final ProductStockHotService productStockHotService;

    public OrderPlaceAction(OrderRepository orderRepository, ProductStockHotService productStockHotService) {
        this.orderRepository = orderRepository;
        this.productStockHotService = productStockHotService;
    }

    /**
     * COLA 已判定 {@code INIT + SUBMIT_ORDER → SUBMIT} 合法，这里只做副作用。
     * {@code from}/{@code event} 由状态机传入，本方法不重复校验转移。
     */
    @Override
    @Transactional
    public void execute(OrderStatusEnum from, OrderStatusEnum to, OrderEventEnum event, OrderContext ctx) {
        // 先改内存里的订单状态，再和主表、明细一起 insert
        applyTransition(to, event, ctx);
        // insert 若失败会直接抛出；此时还没动 Redis，不需要补偿
        orderRepository.insertWithItems(ctx.getOrder());
        // 回调捕获的是这个 List 引用。后面循环里 add 的商品，回滚时回调也能看到
        List<Long> reserved = new ArrayList<>();
        // Redis 不在 JDBC 事务里。必须在 reserve 之前登记回调：
        // 循环中途失败时，Spring 会回滚订单行，但已扣的 Redis 票不会自动归还
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    // 提交成功保留预占；只有整笔事务回滚才把已预占的票还回去
                    if (status == STATUS_ROLLED_BACK) {
                        for (Long productId : reserved) {
                            try {
                                productStockHotService.release(productId, ctx.getOrder().getOrderId());
                            } catch (RuntimeException ex) {
                                // 一行补偿失败只记日志，继续释放后面的商品
                                log.warn("compensate release failed, orderId={}, productId={}",
                                        ctx.getOrder().getOrderId(), productId, ex);
                            }
                        }
                    }
                }
            });
        }
        for (OrderItem item : ctx.getOrder().getItems()) {
            // 本行成功才记入 reserved；本行抛异常时，前面已成功的商品留在列表里等回滚回调 release
            productStockHotService.reserve(item.getProductId(), ctx.getOrder().getOrderId(), item.getQty());
            reserved.add(item.getProductId());
        }
    }

    /** 把状态机给出的目标态写回订单对象，支付态固定为待支付。 */
    private void applyTransition(OrderStatusEnum to, OrderEventEnum event, OrderContext ctx) {
        Order order = ctx.getOrder();
        order.setOrderStatus(to.name());
        order.setPayStatus(PayStatusEnum.WAIT_PAY.name());
    }
}
