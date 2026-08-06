package com.jason.demo.demo2.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jason.demo.demo2.framework.rocketmq.RocketMessageConcurrentlyListener;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;

@Component("orderConcurrentListener")
public class OrderConcurrentListener extends RocketMessageConcurrentlyListener<OrderEvent> {

    private final InMemoryOrderEventStore store;

    public OrderConcurrentListener(ObjectMapper objectMapper, InMemoryOrderEventStore store) {
        super(objectMapper);
        this.store = store;
    }

    @Override
    protected ConsumeConcurrentlyStatus handleMessage(OrderEvent payload, String message, MessageExt messageExt) {
        store.append("concurrent", payload);
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}
