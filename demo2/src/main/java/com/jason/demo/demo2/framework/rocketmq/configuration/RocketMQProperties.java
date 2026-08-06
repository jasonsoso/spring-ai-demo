package com.jason.demo.demo2.framework.rocketmq.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = RocketMQProperties.PREFIX)
public class RocketMQProperties {

    public static final String PREFIX = "rocketmq";

    private Map<String, ConsumerConfig> consumers = new HashMap<>();
    private Map<String, ProducerConfig> producers = new HashMap<>();

    public Map<String, ConsumerConfig> getConsumers() {
        return consumers;
    }

    public void setConsumers(Map<String, ConsumerConfig> consumers) {
        this.consumers = consumers;
    }

    public Map<String, ProducerConfig> getProducers() {
        return producers;
    }

    public void setProducers(Map<String, ProducerConfig> producers) {
        this.producers = producers;
    }

    public static class ConsumerConfig {
        private boolean enabled = true;
        private String namesrvAddr;
        private String topic;
        private String tags = "*";
        private String consumerGroup;
        private Long consumeQps;
        private String listenerBeanName;
        private Map<String, Object> props = new HashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getNamesrvAddr() {
            return namesrvAddr;
        }

        public void setNamesrvAddr(String namesrvAddr) {
            this.namesrvAddr = namesrvAddr;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getTags() {
            return tags;
        }

        public void setTags(String tags) {
            this.tags = tags;
        }

        public String getConsumerGroup() {
            return consumerGroup;
        }

        public void setConsumerGroup(String consumerGroup) {
            this.consumerGroup = consumerGroup;
        }

        public Long getConsumeQps() {
            return consumeQps;
        }

        public void setConsumeQps(Long consumeQps) {
            this.consumeQps = consumeQps;
        }

        public String getListenerBeanName() {
            return listenerBeanName;
        }

        public void setListenerBeanName(String listenerBeanName) {
            this.listenerBeanName = listenerBeanName;
        }

        public Map<String, Object> getProps() {
            return props;
        }

        public void setProps(Map<String, Object> props) {
            this.props = props;
        }
    }

    public static class ProducerConfig {
        private boolean enabled = true;
        private String beanName;
        private String namesrvAddr;
        private String producerGroup;
        private String topic;
        private String tag;
        private Map<String, Object> props = new HashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBeanName() {
            return beanName;
        }

        public void setBeanName(String beanName) {
            this.beanName = beanName;
        }

        public String getNamesrvAddr() {
            return namesrvAddr;
        }

        public void setNamesrvAddr(String namesrvAddr) {
            this.namesrvAddr = namesrvAddr;
        }

        public String getProducerGroup() {
            return producerGroup;
        }

        public void setProducerGroup(String producerGroup) {
            this.producerGroup = producerGroup;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getTag() {
            return tag;
        }

        public void setTag(String tag) {
            this.tag = tag;
        }

        public Map<String, Object> getProps() {
            return props;
        }

        public void setProps(Map<String, Object> props) {
            this.props = props;
        }
    }
}
