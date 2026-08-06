package com.jason.demo.demo2.mq;

import com.jason.demo.demo2.framework.rocketmq.DelayTimeLevel;
import com.jason.demo.demo2.framework.rocketmq.producer.BaseEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class OrderEventPublisher extends BaseEventPublisher {

    public OrderEventPublisher(
            JsonMapper jsonMapper,
            @Value("${rocketmq.producers.orderProducer.topic}") String topic) {
        super("orderProducer", jsonMapper);
        setTopic(topic);
    }

    public void sendSync(OrderEvent event) {
        send(event, "CONCURRENT", event.orderId());
    }

    public void sendAsync(OrderEvent event) {
        super.sendAsync(event, "CONCURRENT", event.orderId());
    }

    public void sendOrderly(OrderEvent event) {
        super.sendOrderly(event, "ORDERLY", event.orderId(), event.orderId());
    }

    public void sendDelay(OrderEvent event, DelayTimeLevel level) {
        super.sendDelay(event, "CONCURRENT", level, event.orderId());
    }
}
