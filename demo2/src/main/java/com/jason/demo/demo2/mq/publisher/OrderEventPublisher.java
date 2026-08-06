package com.jason.demo.demo2.mq.publisher;

import com.jason.demo.demo2.framework.rocketmq.DelayTimeLevel;
import com.jason.demo.demo2.framework.rocketmq.producer.BaseEventPublisher;
import com.jason.demo.demo2.mq.model.OrderEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 单一生产者 Demo：不配 tag，同一 Topic 上的多个消费者（tags=*）均可收到。
 */
@Slf4j
@Component
public class OrderEventPublisher extends BaseEventPublisher {

    public static final String ORDER_EVENT_PUBLISHER_ID = "orderProducer";

    public OrderEventPublisher() {
        super(ORDER_EVENT_PUBLISHER_ID);
    }

    public void sendSync(OrderEvent event) {
        send(event, event.getOrderId());
    }

    public void sendAsync(OrderEvent event) {
        super.sendAsync(event, event.getOrderId());
    }

    public void sendOrderly(OrderEvent event) {
        super.sendOrderly(event, event.getOrderId(), event.getOrderId());
    }

    public void sendDelay(OrderEvent event, DelayTimeLevel level) {
        super.sendDelay(event, level, event.getOrderId());
    }
}
