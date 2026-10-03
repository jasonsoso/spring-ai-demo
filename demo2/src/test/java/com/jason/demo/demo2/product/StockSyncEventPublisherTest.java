package com.jason.demo.demo2.product;

import com.jason.demo.demo2.framework.rocketmq.RocketMqTracePropagator;
import com.jason.demo.demo2.framework.rocketmq.configuration.RocketMQProperties;
import com.jason.demo.demo2.product.service.infrastructure.publisher.StockSyncEvent;
import com.jason.demo.demo2.product.service.infrastructure.publisher.StockSyncEventPublisher;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockSyncEventPublisherTest {

    @Mock
    ApplicationContext applicationContext;
    @Mock
    DefaultMQProducer producer;
    @Mock
    ObjectProvider<RocketMqTracePropagator> tracePropagatorProvider;

    @Test
    void sendNow_selectsQueueByProductId() throws Exception {
        RocketMQProperties properties = new RocketMQProperties();
        RocketMQProperties.ProducerConfig config = new RocketMQProperties.ProducerConfig();
        config.setTopic("DEMO_STOCK_TOPIC");
        properties.getProducers().put(StockSyncEventPublisher.PRODUCER_ID, config);
        when(applicationContext.getBean(StockSyncEventPublisher.PRODUCER_ID, DefaultMQProducer.class))
                .thenReturn(producer);
        when(applicationContext.getBean(JsonMapper.class)).thenReturn(JsonMapper.builder().build());
        when(applicationContext.getBean(RocketMQProperties.class)).thenReturn(properties);
        when(applicationContext.getBeanProvider(RocketMqTracePropagator.class))
                .thenReturn(tracePropagatorProvider);

        StockSyncEventPublisher publisher = new StockSyncEventPublisher();
        publisher.setApplicationContext(applicationContext);
        publisher.initialize();
        publisher.sendNow(new StockSyncEvent(9001L, 100L, "RESERVE", 2, "100:9001:RESERVE", 4L));

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<Object> argCaptor = ArgumentCaptor.forClass(Object.class);
        verify(producer).send(messageCaptor.capture(), any(MessageQueueSelector.class), argCaptor.capture());
        verify(producer, never()).send(any(Message.class));
        assertThat(argCaptor.getValue()).isEqualTo("9001");
        assertThat(messageCaptor.getValue().getKeys()).isEqualTo("9001 100:9001:RESERVE");
    }
}
