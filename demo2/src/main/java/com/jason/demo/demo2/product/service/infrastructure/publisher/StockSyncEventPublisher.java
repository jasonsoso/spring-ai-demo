package com.jason.demo.demo2.product.service.infrastructure.publisher;

import com.jason.demo.demo2.framework.rocketmq.producer.BaseEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class StockSyncEventPublisher extends BaseEventPublisher {

    public static final String PRODUCER_ID = "stockSyncProducer";

    public StockSyncEventPublisher() {
        super(PRODUCER_ID);
    }

    /** 出箱专用：按 productId 选队列同步发送。失败抛给 Relay，以便不 XACK。 */
    public void sendNow(StockSyncEvent event) {
        String productId = String.valueOf(event.getProductId());
        sendImmediateByKey(event, productId, productId, event.getIdempotentKey());
    }
}
