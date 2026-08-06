package com.jason.demo.demo2.framework.rocketmq.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * RocketMQ 配置绑定：{@code rocketmq.consumers.*} / {@code rocketmq.producers.*}。
 * <p>
 * Map 的 key 即为逻辑名称；生产者侧还作为 {@link org.apache.rocketmq.client.producer.DefaultMQProducer}
 * Bean 名，供 {@code BaseEventPublisher} 按 {@code producerId} 查找。
 */
@Data
@ConfigurationProperties(prefix = RocketMQProperties.PREFIX)
public class RocketMQProperties {

    public static final String PREFIX = "rocketmq";

    /** 消费者配置，key = 逻辑名（注册 Bean 时拼成 {@code <key>_rocketmq_consumer}） */
    private Map<String, ConsumerConfig> consumers = new HashMap<>();

    /** 生产者配置，key = 逻辑名 / 默认 Bean 名（可被 {@link ProducerConfig#beanName} 覆盖） */
    private Map<String, ProducerConfig> producers = new HashMap<>();

    /**
     * 单个 PushConsumer 配置。
     * <ul>
     *   <li>{@code tags} 默认 {@code *}，订阅 Topic 下全部 Tag</li>
     *   <li>{@code listenerBeanName} 必须指向已存在的 Listener Bean</li>
     *   <li>{@code consumeQps} 仅占位，当前未实现限流</li>
     * </ul>
     */
    @Data
    public static class ConsumerConfig {
        /** 是否启用；false 时跳过注册并打 warn */
        private boolean enabled = true;
        private String namesrvAddr;
        private String topic;
        /** 订阅 Tag 表达式，如 {@code CONCURRENT} 或 {@code *} */
        private String tags = "*";
        private String consumerGroup;
        /** 预留：消费 QPS 限流（未实现） */
        private Long consumeQps;
        /** Spring Bean 名，须为并发或顺序 Listener */
        private String listenerBeanName;
        /** 预留扩展属性 */
        private Map<String, Object> props = new HashMap<>();
    }

    /**
     * 单个 Producer 配置。
     * <p>
     * {@code topic}/{@code tag} 由 {@code BaseEventPublisher} 在启动时按 producerId 读取，
     * 业务发送时无需再传 Tag；未配置 tag 则消息不带 Tag。
     */
    @Data
    public static class ProducerConfig {
        /** 是否启用；false 时跳过注册并打 warn */
        private boolean enabled = true;
        /** 可选：覆盖默认 Bean 名（默认用 Map key） */
        private String beanName;
        private String namesrvAddr;
        private String producerGroup;
        /** 发送目标 Topic（Publisher 必填） */
        private String topic;
        /** 默认 Tag；可空 */
        private String tag;
        /** 预留扩展属性 */
        private Map<String, Object> props = new HashMap<>();
    }
}
