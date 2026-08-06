package com.jason.demo.demo2.mq.listener;

import com.jason.demo.demo2.framework.rocketmq.RocketMessageConcurrentlyListener;
import com.jason.demo.demo2.mq.model.OrderEvent;
import com.jason.demo.demo2.mq.store.InMemoryOrderEventStore;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 同 Topic 旁路消费者示例：{@code tags=*}，与并发/顺序消费者各自独立 consumerGroup，
 * 因此每条消息都会再收到一份（fan-out）。
 */
@Component("orderAuditListener")
public class OrderAuditListener extends RocketMessageConcurrentlyListener<OrderEvent> {

    private final InMemoryOrderEventStore store;

    public OrderAuditListener(JsonMapper jsonMapper, InMemoryOrderEventStore store) {
        super(jsonMapper);
        this.store = store;
    }

    @Override
    protected ConsumeConcurrentlyStatus handleMessage(OrderEvent payload, String message, MessageExt messageExt) {
        store.append("audit", payload);
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}
