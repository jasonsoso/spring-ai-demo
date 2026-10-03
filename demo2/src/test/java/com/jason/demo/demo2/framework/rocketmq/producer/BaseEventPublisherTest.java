package com.jason.demo.demo2.framework.rocketmq.producer;

import com.jason.demo.demo2.framework.rocketmq.RocketMqTracePropagator;
import com.jason.demo.demo2.framework.rocketmq.configuration.RocketMQProperties;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BaseEventPublisherTest {

    @Mock
    ApplicationContext applicationContext;

    @Mock
    DefaultMQProducer producer;

    @Mock
    ObjectProvider<RocketMqTracePropagator> tracePropagatorProvider;

    static class DemoPublisher extends BaseEventPublisher {
        DemoPublisher(String producerId) {
            super(producerId);
        }

        void publish() {
            send(Map.of("orderId", "o1"), "o1");
        }

        void publishByKey(String shardingKey) {
            sendImmediateByKey(Map.of("productId", shardingKey), shardingKey, shardingKey, "idem");
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
        when(applicationContext.getBeanProvider(RocketMqTracePropagator.class))
                .thenReturn(tracePropagatorProvider);
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

    @Test
    void sendImmediateByKey_selectsStableQueueAndSkipsPlainSend() throws Exception {
        DemoPublisher pub = ready("stockSyncProducer", "DEMO_STOCK_TOPIC");
        when(producer.send(any(Message.class), any(MessageQueueSelector.class), any()))
                .thenReturn(new SendResult());

        pub.publishByKey("1001");

        ArgumentCaptor<MessageQueueSelector> selectorCaptor = ArgumentCaptor.forClass(MessageQueueSelector.class);
        ArgumentCaptor<Object> argCaptor = ArgumentCaptor.forClass(Object.class);
        verify(producer).send(any(Message.class), selectorCaptor.capture(), argCaptor.capture());
        verify(producer, never()).send(any(Message.class));
        assertThat(argCaptor.getValue()).isEqualTo("1001");

        List<MessageQueue> queues = List.of(
                new MessageQueue("DEMO_STOCK_TOPIC", "broker-a", 0),
                new MessageQueue("DEMO_STOCK_TOPIC", "broker-a", 1),
                new MessageQueue("DEMO_STOCK_TOPIC", "broker-a", 2),
                new MessageQueue("DEMO_STOCK_TOPIC", "broker-a", 3));
        Message probe = new Message("DEMO_STOCK_TOPIC", new byte[0]);
        MessageQueue first = selectorCaptor.getValue().select(queues, probe, "1001");
        MessageQueue second = selectorCaptor.getValue().select(queues, probe, "1001");
        assertThat(first.getQueueId()).isEqualTo(Math.floorMod("1001".hashCode(), 4));
        assertThat(second.getQueueId()).isEqualTo(first.getQueueId());
    }

    @Test
    void sendImmediateByKey_retriesThenThrows() throws Exception {
        DemoPublisher pub = ready("stockSyncProducer", "DEMO_STOCK_TOPIC");
        when(producer.send(any(Message.class), any(MessageQueueSelector.class), any()))
                .thenThrow(new RuntimeException("temp"));

        assertThatThrownBy(() -> pub.publishByKey("1001"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rocketmq immediate send failed after retries");
        verify(producer, times(2)).send(any(Message.class), any(MessageQueueSelector.class), eq("1001"));
        verify(producer, never()).send(any(Message.class));
    }

    private DemoPublisher ready(String producerId, String topic) {
        RocketMQProperties properties = new RocketMQProperties();
        RocketMQProperties.ProducerConfig config = new RocketMQProperties.ProducerConfig();
        config.setTopic(topic);
        properties.getProducers().put(producerId, config);
        when(applicationContext.getBean(producerId, DefaultMQProducer.class)).thenReturn(producer);
        when(applicationContext.getBean(JsonMapper.class)).thenReturn(JsonMapper.builder().build());
        when(applicationContext.getBean(RocketMQProperties.class)).thenReturn(properties);
        when(applicationContext.getBeanProvider(RocketMqTracePropagator.class))
                .thenReturn(tracePropagatorProvider);
        DemoPublisher pub = new DemoPublisher(producerId);
        pub.setApplicationContext(applicationContext);
        pub.initialize();
        return pub;
    }
}
