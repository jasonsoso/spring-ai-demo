package com.jason.demo.demo2.framework.rocketmq.configuration;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.jspecify.annotations.NonNull;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * RocketMQ 自动装配入口。
 * <p>
 * 在 BeanDefinition 注册阶段根据 {@link RocketMQProperties} 动态注册：
 * <ul>
 *   <li>{@link DefaultMQPushConsumer}：订阅 topic/tags，绑定业务 Listener</li>
 *   <li>{@link DefaultMQProducer}：按配置创建，{@code initMethod=start} / {@code destroyMethod=shutdown}</li>
 * </ul>
 * 行为约定：{@code enabled=false}、空 consumers/producers、Listener Bean 缺失 → warn 并跳过；
 * 必填字段为空 → 抛异常导致启动失败。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RocketMQProperties.class)
public class RocketMQConfiguration
        implements BeanDefinitionRegistryPostProcessor, ApplicationContextAware, EnvironmentAware {

    private Environment environment;
    private ApplicationContext applicationContext;

    @Override
    public void setEnvironment(@NonNull Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * 尽早绑定配置并注册 Consumer / Producer Bean，保证后续 {@code BaseEventPublisher} 能按名注入 Producer。
     */
    @Override
    public void postProcessBeanDefinitionRegistry(@NonNull BeanDefinitionRegistry registry) throws BeansException {
        RocketMQProperties props = Binder.get(environment)
                .bind(RocketMQProperties.PREFIX, RocketMQProperties.class)
                .orElseGet(RocketMQProperties::new);
        registerConsumers(registry, props);
        registerProducers(registry, props);
    }

    @Override
    public void postProcessBeanFactory(@NonNull ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // no-op
    }

    /** 校验消费者必填项：consumerGroup / namesrvAddr / topic */
    static void requireConsumerFields(String consumerName, RocketMQProperties.ConsumerConfig config) {
        if (!StringUtils.hasText(config.getConsumerGroup())) {
            throw new IllegalArgumentException("rocketmq 配置错误, consumerGroup 不能为空: " + consumerName);
        }
        if (!StringUtils.hasText(config.getNamesrvAddr())) {
            throw new IllegalArgumentException("rocketmq 配置错误, namesrvAddr 不能为空: " + consumerName);
        }
        if (!StringUtils.hasText(config.getTopic())) {
            throw new IllegalArgumentException("rocketmq 配置错误, topic 不能为空: " + consumerName);
        }
    }

    /** 校验生产者必填项：producerGroup / namesrvAddr（topic 由 Publisher 启动时再校验） */
    static void requireProducerFields(String producerName, RocketMQProperties.ProducerConfig config) {
        if (!StringUtils.hasText(config.getProducerGroup())) {
            throw new IllegalArgumentException("rocketmq 配置错误, producerGroup 不能为空: " + producerName);
        }
        if (!StringUtils.hasText(config.getNamesrvAddr())) {
            throw new IllegalArgumentException("rocketmq 配置错误, namesrvAddr 不能为空: " + producerName);
        }
    }

    /**
     * 注册 PushConsumer：Listener 缺失或未启用则跳过；Bean 名 = {@code <consumerName>_rocketmq_consumer}。
     */
    private void registerConsumers(BeanDefinitionRegistry registry, RocketMQProperties props) {
        Map<String, RocketMQProperties.ConsumerConfig> consumers = props.getConsumers();
        if (consumers == null || consumers.isEmpty()) {
            log.warn("consumers is empty:{}", props);
            return;
        }
        for (Map.Entry<String, RocketMQProperties.ConsumerConfig> entry : consumers.entrySet()) {
            String consumerName = entry.getKey();
            RocketMQProperties.ConsumerConfig config = entry.getValue();
            if (!config.isEnabled()) {
                log.warn("rocketmq consumer disabled, consumerName: {}", consumerName);
                continue;
            }
            requireConsumerFields(consumerName, config);
            String listenerBeanName = config.getListenerBeanName();
            if (!StringUtils.hasText(listenerBeanName) || !registry.containsBeanDefinition(listenerBeanName)) {
                log.warn("bean名称不存在跳过:{}", listenerBeanName);
                continue;
            }
            GenericBeanDefinition bd = new GenericBeanDefinition();
            bd.setBeanClass(DefaultMQPushConsumer.class);
            bd.setInitMethodName("start");
            bd.setDestroyMethodName("shutdown");
            String consumerBeanName = consumerName + "_rocketmq_consumer";
            bd.setInstanceSupplier(() -> createConsumer(consumerName, config));
            registry.registerBeanDefinition(consumerBeanName, bd);
            log.info("rocketmq consumer bean registered, name={}, consumerGroup={}, topic={}, tags={}, listener={}",
                    consumerBeanName, config.getConsumerGroup(), config.getTopic(), config.getTags(),
                    listenerBeanName);
        }
    }

    /** 创建并订阅：按 Listener 类型注册并发或顺序消费回调。 */
    private DefaultMQPushConsumer createConsumer(String consumerName, RocketMQProperties.ConsumerConfig config) {
        try {
            DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(config.getConsumerGroup());
            consumer.setNamesrvAddr(config.getNamesrvAddr());
            consumer.subscribe(config.getTopic(), config.getTags());
            Object listener = applicationContext.getBean(config.getListenerBeanName());
            String listenerType;
            if (listener instanceof MessageListenerOrderly orderly) {
                consumer.registerMessageListener(orderly);
                listenerType = "orderly";
            } else if (listener instanceof MessageListenerConcurrently concurrently) {
                consumer.registerMessageListener(concurrently);
                listenerType = "concurrently";
            } else {
                throw new IllegalStateException(
                        "listener must be MessageListenerOrderly or MessageListenerConcurrently: "
                                + config.getListenerBeanName());
            }
            log.info("rocketmq consumer instantiated, name={}, consumerGroup={}, topic={}, tags={}, "
                            + "listener={}, listenerType={}, listenerClass={}",
                    consumerName, config.getConsumerGroup(), config.getTopic(), config.getTags(),
                    config.getListenerBeanName(), listenerType, listener.getClass().getName());
            return consumer;
        } catch (Exception e) {
            throw new IllegalStateException("create rocketmq consumer failed: " + config.getConsumerGroup(), e);
        }
    }

    /**
     * 注册 Producer：Bean 名优先用 {@code beanName}，否则用 Map key（即业务 Publisher 的 producerId）。
     */
    private void registerProducers(BeanDefinitionRegistry registry, RocketMQProperties props) {
        Map<String, RocketMQProperties.ProducerConfig> producers = props.getProducers();
        if (producers == null || producers.isEmpty()) {
            log.warn("producers is empty:{}", props);
            return;
        }
        for (Map.Entry<String, RocketMQProperties.ProducerConfig> entry : producers.entrySet()) {
            String producerName = entry.getKey();
            RocketMQProperties.ProducerConfig config = entry.getValue();
            if (!config.isEnabled()) {
                log.warn("rocketmq producer disabled, producerName: {}", producerName);
                continue;
            }
            requireProducerFields(producerName, config);
            String beanName = StringUtils.hasText(config.getBeanName()) ? config.getBeanName() : producerName;
            GenericBeanDefinition bd = new GenericBeanDefinition();
            bd.setBeanClass(DefaultMQProducer.class);
            bd.setInitMethodName("start");
            bd.setDestroyMethodName("shutdown");
            bd.setInstanceSupplier(() -> createProducer(producerName, beanName, config));
            registry.registerBeanDefinition(beanName, bd);
            log.info("rocketmq producer bean registered, beanName={}, producerGroup={}, namesrvAddr={}, topic={}, tag={}",
                    beanName, config.getProducerGroup(), config.getNamesrvAddr(), config.getTopic(), config.getTag());
        }
    }

    private DefaultMQProducer createProducer(
            String producerName, String beanName, RocketMQProperties.ProducerConfig config) {
        try {
            DefaultMQProducer producer = new DefaultMQProducer(config.getProducerGroup());
            producer.setNamesrvAddr(config.getNamesrvAddr());
            producer.setInstanceName(producerName);
            log.info("rocketmq producer instantiated, beanName={}, producerGroup={}, namesrvAddr={}, topic={}, tag={}",
                    beanName, config.getProducerGroup(), config.getNamesrvAddr(), config.getTopic(), config.getTag());
            return producer;
        } catch (Exception e) {
            throw new IllegalStateException("create rocketmq producer failed: " + producerName, e);
        }
    }
}
