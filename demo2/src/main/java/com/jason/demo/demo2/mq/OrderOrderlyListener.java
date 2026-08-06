package com.jason.demo.demo2.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jason.demo.demo2.framework.rocketmq.RocketMessageOrderlyListener;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;

@Component("orderOrderlyListener")
public class OrderOrderlyListener extends RocketMessageOrderlyListener<OrderEvent> {

    private final InMemoryOrderEventStore store;

    public OrderOrderlyListener(ObjectMapper objectMapper, InMemoryOrderEventStore store) {
        super(objectMapper);
        this.store = store;
    }

    @Override
    protected ConsumeOrderlyStatus handleMessage(OrderEvent payload, String message, MessageExt messageExt) {
        store.append("orderly", payload);
        return ConsumeOrderlyStatus.SUCCESS;
    }
}
