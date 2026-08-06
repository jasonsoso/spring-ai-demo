package com.jason.demo.demo2.framework.rocketmq.configuration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RocketMQConfigurationValidationTest {

    @Test
    void requireConsumerFields_rejectsBlankTopic() {
        var cfg = new RocketMQProperties.ConsumerConfig();
        cfg.setConsumerGroup("g");
        cfg.setNamesrvAddr("127.0.0.1:9876");
        cfg.setTopic(" ");
        assertThatThrownBy(() -> RocketMQConfiguration.requireConsumerFields("c1", cfg))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topic");
    }
}
