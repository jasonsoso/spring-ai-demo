package com.jason.demo.demo2.framework.rocketmq.producer;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BaseEventPublisherTest {

    @Mock
    ApplicationContext applicationContext;

    @Mock
    DefaultMQProducer producer;

    static class DemoPublisher extends BaseEventPublisher {
        DemoPublisher(String producerId, JsonMapper jsonMapper) {
            super(producerId, jsonMapper);
        }

        void publish(String tag) {
            send(Map.of("orderId", "o1"), tag);
        }
    }

    @Test
    void send_retriesThenSucceeds() throws Exception {
        when(applicationContext.getBean("orderProducer", DefaultMQProducer.class)).thenReturn(producer);
        when(producer.send(any(Message.class)))
                .thenThrow(new RuntimeException("temp"))
                .thenReturn(new SendResult());

        DemoPublisher pub = new DemoPublisher("orderProducer", JsonMapper.builder().build());
        pub.setApplicationContext(applicationContext);
        pub.setTopic("DEMO_ORDER_TOPIC");
        pub.setMaxTryTimes(2);
        pub.initialize();
        pub.publish("CONCURRENT");

        verify(producer, times(2)).send(any(Message.class));
    }
}
