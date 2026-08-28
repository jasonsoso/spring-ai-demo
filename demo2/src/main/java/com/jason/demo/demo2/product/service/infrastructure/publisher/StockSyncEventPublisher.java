package com.jason.demo.demo2.product.service.infrastructure.publisher;

import com.jason.demo.demo2.framework.rocketmq.producer.BaseEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class StockSyncEventPublisher extends BaseEventPublisher {

    public static final String PRODUCER_ID = "stockSyncProducer";

    public StockSyncEventPublisher() {
        super(PRODUCER_ID);
    }

    /** 出箱专用：同步发送、不走 afterCommit，失败抛给 Relay 以便不 XACK。 */
    public void sendNow(StockSyncEvent event) {
        sendImmediate(event, String.valueOf(event.getProductId()), event.getIdempotentKey());
    }
}
