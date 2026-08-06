package com.jason.demo.demo2.framework.rocketmq.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RocketMQPropertiesBindingTest {

    @Test
    void bindsConsumersAndProducers() {
        Map<String, Object> map = new HashMap<>();
        map.put("rocketmq.consumers.orderConcurrent.enabled", "true");
        map.put("rocketmq.consumers.orderConcurrent.namesrvAddr", "127.0.0.1:9876");
        map.put("rocketmq.consumers.orderConcurrent.topic", "DEMO_ORDER_TOPIC");
        map.put("rocketmq.consumers.orderConcurrent.tags", "CONCURRENT");
        map.put("rocketmq.consumers.orderConcurrent.consumerGroup", "demo-order-concurrent-group");
        map.put("rocketmq.consumers.orderConcurrent.listenerBeanName", "orderConcurrentListener");
        map.put("rocketmq.producers.orderProducer.enabled", "true");
        map.put("rocketmq.producers.orderProducer.namesrvAddr", "127.0.0.1:9876");
        map.put("rocketmq.producers.orderProducer.producerGroup", "demo-order-producer-group");
        map.put("rocketmq.producers.orderProducer.topic", "DEMO_ORDER_TOPIC");
        map.put("rocketmq.producers.orderProducer.tag", "CONCURRENT");

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", map));

        RocketMQProperties props = Binder.get(environment)
                .bind("rocketmq", RocketMQProperties.class)
                .get();
        assertThat(props.getConsumers()).containsKey("orderConcurrent");
        assertThat(props.getConsumers().get("orderConcurrent").getListenerBeanName())
                .isEqualTo("orderConcurrentListener");
        assertThat(props.getProducers().get("orderProducer").getTopic())
                .isEqualTo("DEMO_ORDER_TOPIC");
    }
}
