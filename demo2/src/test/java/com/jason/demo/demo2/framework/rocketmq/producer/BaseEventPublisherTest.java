package com.jason.demo.demo2.framework.rocketmq.producer;

import com.jason.demo.demo2.framework.rocketmq.configuration.RocketMQProperties;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
        DemoPublisher(String producerId) {
            super(producerId);
        }

        void publish() {
            send(Map.of("orderId", "o1"), "o1");
        }
    }

    @Test
    void send_retriesThenSucceeds_withoutTag() throws Exception {
        RocketMQProperties properties = new RocketMQProperties();
        RocketMQProperties.ProducerConfig config = new RocketMQProperties.ProducerConfig();
        config.setTopic("DEMO_ORDER_TOPIC");
        // tag 不配
        properties.getProducers().put("orderProducer", config);

        when(applicationContext.getBean("orderProducer", DefaultMQProducer.class)).thenReturn(producer);
        when(applicationContext.getBean(JsonMapper.class)).thenReturn(JsonMapper.builder().build());
        when(applicationContext.getBean(RocketMQProperties.class)).thenReturn(properties);
        when(producer.send(any(Message.class)))
                .thenThrow(new RuntimeException("temp"))
                .thenReturn(new SendResult());

        DemoPublisher pub = new DemoPublisher("orderProducer");
        pub.setApplicationContext(applicationContext);
        pub.initialize();
        pub.publish();

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(producer, times(2)).send(captor.capture());
        Message last = captor.getValue();
        assertThat(last.getTopic()).isEqualTo("DEMO_ORDER_TOPIC");
        assertThat(last.getTags()).isNullOrEmpty();
    }
}
