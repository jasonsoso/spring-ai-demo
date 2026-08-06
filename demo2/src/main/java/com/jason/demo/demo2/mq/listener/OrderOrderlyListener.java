package com.jason.demo.demo2.mq.listener;

import com.jason.demo.demo2.framework.rocketmq.RocketMessageOrderlyListener;
import com.jason.demo.demo2.mq.model.OrderEvent;
import com.jason.demo.demo2.mq.store.InMemoryOrderEventStore;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component("orderOrderlyListener")
public class OrderOrderlyListener extends RocketMessageOrderlyListener<OrderEvent> {

    private final InMemoryOrderEventStore store;

    public OrderOrderlyListener(JsonMapper jsonMapper, InMemoryOrderEventStore store) {
        super(jsonMapper);
        this.store = store;
    }

    @Override
    protected ConsumeOrderlyStatus handleMessage(OrderEvent payload, String message, MessageExt messageExt) {
        store.append("orderly", payload);
        return ConsumeOrderlyStatus.SUCCESS;
    }
}
