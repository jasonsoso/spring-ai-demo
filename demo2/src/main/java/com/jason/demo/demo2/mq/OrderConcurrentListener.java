package com.jason.demo.demo2.mq;

import com.jason.demo.demo2.framework.rocketmq.RocketMessageConcurrentlyListener;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component("orderConcurrentListener")
public class OrderConcurrentListener extends RocketMessageConcurrentlyListener<OrderEvent> {

    private final InMemoryOrderEventStore store;

    public OrderConcurrentListener(JsonMapper jsonMapper, InMemoryOrderEventStore store) {
        super(jsonMapper);
        this.store = store;
    }

    @Override
    protected ConsumeConcurrentlyStatus handleMessage(OrderEvent payload, String message, MessageExt messageExt) {
        store.append("concurrent", payload);
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}
