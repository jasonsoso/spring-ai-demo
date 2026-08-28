package com.jason.demo.demo2.framework.rocketmq.producer;

import com.jason.demo.demo2.framework.rocketmq.DelayTimeLevel;
import com.jason.demo.demo2.framework.rocketmq.RocketMqTracePropagator;
import com.jason.demo.demo2.framework.rocketmq.configuration.RocketMQProperties;
import com.jason.demo.demo2.framework.rocketmq.util.TransactionUtils;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import jakarta.annotation.PostConstruct;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.jspecify.annotations.NonNull;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 业务事件发布基类。
 * <p>
 * 子类仅需 {@code super(producerId)}，启动时自动：
 * <ol>
 *   <li>按 producerId 获取 {@link DefaultMQProducer} Bean</li>
 *   <li>从 {@code rocketmq.producers.<id>} 读取 topic / tag</li>
 *   <li>注入 {@link JsonMapper} 做消息体序列化</li>
 * </ol>
 * 发送均经 {@link TransactionUtils#afterCommitSyncExecute}：有事务则提交后再发，无事务则立即发。
 * 调用方无需再传 tag（使用配置中的默认 tag；未配置则消息不带 tag）。
 */
@Slf4j
public class BaseEventPublisher implements ApplicationContextAware {

    /** 对应 {@code rocketmq.producers.<producerId>} 与 Producer Bean 名 */
    private final String producerId;

    private ApplicationContext applicationContext;
    private DefaultMQProducer producer;
    private JsonMapper jsonMapper;
    private String topic;
    private String tag;
    private RocketMqTracePropagator tracePropagator;
    /** 同步 / 顺序发送失败时的最大尝试次数 */
    private int maxTryTimes = 2;

    public BaseEventPublisher(String producerId) {
        this.producerId = producerId;
    }

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /** 解析 Producer Bean 与 topic/tag 配置，缺配置则启动失败。 */
    @PostConstruct
    public void initialize() {
        this.producer = applicationContext.getBean(producerId, DefaultMQProducer.class);
        this.jsonMapper = applicationContext.getBean(JsonMapper.class);
        RocketMQProperties properties = applicationContext.getBean(RocketMQProperties.class);
        RocketMQProperties.ProducerConfig config = properties.getProducers().get(producerId);
        if (config == null) {
            throw new IllegalStateException("rocketmq producer config not found: " + producerId);
        }
        if (config.getTopic() == null || config.getTopic().isBlank()) {
            throw new IllegalStateException("rocketmq.producers." + producerId + ".topic is required");
        }
        this.topic = config.getTopic();
        this.tag = config.getTag();
        this.tracePropagator = applicationContext.getBeanProvider(RocketMqTracePropagator.class).getIfAvailable();
        log.info("rocketmq publisher initialized, producerId={}, publisherClass={}, topic={}, tag={}, maxTryTimes={}",
                producerId, getClass().getName(), topic, tag, maxTryTimes);
    }

    /** 同步发送（事务提交后执行，带重试）。 */
    protected void send(Object messageBodyObj, String... keys) {
        Message message = buildMessage(messageBodyObj, keys);
        doSend(message, messageBodyObj);
    }

    /** 异步发送（事务提交后提交回调，不在此重试）。 */
    protected void sendAsync(Object messageBodyObj, String... keys) {
        Message message = buildMessage(messageBodyObj, keys);
        TransactionUtils.afterCommitSyncExecute(() -> {
            ContextSnapshot snapshot = ContextSnapshotFactory.builder().build().captureAll();
            try {
                producer.send(message, new SendCallback() {
                    @Override
                    public void onSuccess(SendResult sendResult) {
                        try (ContextSnapshot.Scope scope = snapshot.setThreadLocals()) {
                            log.info("async send success, result:{}", sendResult);
                        }
                    }

                    @Override
                    public void onException(Throwable e) {
                        try (ContextSnapshot.Scope scope = snapshot.setThreadLocals()) {
                            log.error("async send error, message:{}", messageBodyObj, e);
                        }
                    }
                });
            } catch (Exception e) {
                log.error("async send submit error, message:{}", messageBodyObj, e);
            }
        });
    }

    /**
     * 顺序发送：按 {@code shardingKey} 哈希选队列，保证同 key 落同一队列。
     *
     * @param shardingKey 选队列依据，同时会并入 message keys
     */
    protected void sendOrderly(Object messageBodyObj, String shardingKey, String... keys) {
        String[] mergedKeys = buildMessageKeys(keys, shardingKey);
        Message message = buildMessage(messageBodyObj, mergedKeys);
        TransactionUtils.afterCommitSyncExecute(() -> {
            for (int i = 0; i < maxTryTimes; i++) {
                try {
                    SendResult sendResult = producer.send(message, (List<MessageQueue> mqs, Message msg, Object arg) -> {
                        int index = Math.floorMod(arg.hashCode(), mqs.size());
                        return mqs.get(index);
                    }, shardingKey);
                    log.info("orderly send success, attempt:{}, result:{}", i + 1, sendResult);
                    return;
                } catch (Exception e) {
                    log.error("orderly send error, attempt:{}, message:{}", i + 1, messageBodyObj, e);
                    if (i < maxTryTimes - 1) {
                        sleepQuietly(100L * (i + 1));
                    }
                }
            }
            log.error("orderly send failed after retries, message:{}", messageBodyObj);
        });
    }

    /** 延迟发送：使用 RocketMQ 固定 18 档延迟级别。 */
    protected void sendDelay(Object messageBodyObj, DelayTimeLevel level, String... keys) {
        Message message = buildMessage(messageBodyObj, keys);
        message.setDelayTimeLevel(level.getLevel());
        doSend(message, messageBodyObj);
    }

    /**
     * 立即同步发送：不走 afterCommit。重试耗尽后抛异常，供出箱 Relay 据此不 XACK。
     */
    protected void sendImmediate(Object messageBodyObj, String... keys) {
        Message message = buildMessage(messageBodyObj, keys);
        Exception last = null;
        for (int i = 0; i < maxTryTimes; i++) {
            try {
                SendResult sendResult = producer.send(message);
                log.info("immediate send success, attempt:{}, result:{}", i + 1, sendResult);
                return;
            } catch (Exception e) {
                last = e;
                log.error("immediate send error, attempt:{}, message:{}", i + 1, messageBodyObj, e);
                if (i < maxTryTimes - 1) {
                    sleepQuietly(100L * (i + 1));
                }
            }
        }
        throw new IllegalStateException("rocketmq immediate send failed after retries", last);
    }

    /** 同步发送核心：事务后提交 + 有限次重试。 */
    private void doSend(Message message, Object messageBodyObj) {
        TransactionUtils.afterCommitSyncExecute(() -> {
            for (int i = 0; i < maxTryTimes; i++) {
                try {
                    SendResult sendResult = producer.send(message);
                    log.info("send success, attempt:{}, result:{}", i + 1, sendResult);
                    return;
                } catch (Exception e) {
                    log.error("send message error, attempt:{}, message:{}", i + 1, messageBodyObj, e);
                    if (i < maxTryTimes - 1) {
                        sleepQuietly(100L * (i + 1));
                    }
                }
            }
            log.error("send failed after retries, message:{}", messageBodyObj);
        });
    }

    /**
     * 序列化 body，写入配置中的 topic/tag 与业务 keys。
     * tag 未配置时不调用 {@code setTags}，消息以无 Tag 形式发出。
     */
    private Message buildMessage(Object messageBodyObj, String... keys) {
        try {
            byte[] body = jsonMapper.writeValueAsBytes(messageBodyObj);
            Message message = new Message(topic, body);
            if (tag != null && !tag.isBlank()) {
                message.setTags(tag);
            }
            if (keys != null && keys.length > 0) {
                message.setKeys(String.join(" ", keys));
            }
            if (tracePropagator != null) {
                tracePropagator.inject(message);
            }
            return message;
        } catch (Exception e) {
            throw new IllegalStateException("serialize rocketmq message failed", e);
        }
    }

    /** 将 shardingKey 并入 keys（去重保序），便于控制台检索。 */
    private String[] buildMessageKeys(String[] keys, String shardingKey) {
        if (shardingKey == null || shardingKey.isBlank()) {
            return keys;
        }
        if (keys != null && keys.length > 0) {
            Set<String> set = new LinkedHashSet<>(Arrays.asList(keys));
            set.add(shardingKey);
            return set.toArray(String[]::new);
        }
        return new String[]{shardingKey};
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
