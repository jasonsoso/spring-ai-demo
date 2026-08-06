package com.jason.demo.demo2.framework.rocketmq.configuration;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.lang.NonNull;
import org.springframework.util.StringUtils;

import java.util.Map;

@Configuration
@EnableConfigurationProperties(RocketMQProperties.class)
public class RocketMQConfiguration
        implements BeanDefinitionRegistryPostProcessor, ApplicationContextAware, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(RocketMQConfiguration.class);

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

    static void requireProducerFields(String producerName, RocketMQProperties.ProducerConfig config) {
        if (!StringUtils.hasText(config.getProducerGroup())) {
            throw new IllegalArgumentException("rocketmq 配置错误, producerGroup 不能为空: " + producerName);
        }
        if (!StringUtils.hasText(config.getNamesrvAddr())) {
            throw new IllegalArgumentException("rocketmq 配置错误, namesrvAddr 不能为空: " + producerName);
        }
    }

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
            bd.setInstanceSupplier(() -> createConsumer(config));
            registry.registerBeanDefinition(consumerName + "_rocketmq_consumer", bd);
        }
    }

    private DefaultMQPushConsumer createConsumer(RocketMQProperties.ConsumerConfig config) {
        try {
            DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(config.getConsumerGroup());
            consumer.setNamesrvAddr(config.getNamesrvAddr());
            consumer.subscribe(config.getTopic(), config.getTags());
            Object listener = applicationContext.getBean(config.getListenerBeanName());
            if (listener instanceof MessageListenerOrderly orderly) {
                consumer.registerMessageListener(orderly);
            } else if (listener instanceof MessageListenerConcurrently concurrently) {
                consumer.registerMessageListener(concurrently);
            } else {
                throw new IllegalStateException(
                        "listener must be MessageListenerOrderly or MessageListenerConcurrently: "
                                + config.getListenerBeanName());
            }
            return consumer;
        } catch (Exception e) {
            throw new IllegalStateException("create rocketmq consumer failed: " + config.getConsumerGroup(), e);
        }
    }

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
            bd.setInstanceSupplier(() -> createProducer(producerName, config));
            registry.registerBeanDefinition(beanName, bd);
        }
    }

    private DefaultMQProducer createProducer(String producerName, RocketMQProperties.ProducerConfig config) {
        try {
            DefaultMQProducer producer = new DefaultMQProducer(config.getProducerGroup());
            producer.setNamesrvAddr(config.getNamesrvAddr());
            producer.setInstanceName(producerName);
            return producer;
        } catch (Exception e) {
            throw new IllegalStateException("create rocketmq producer failed: " + producerName, e);
        }
    }
}
