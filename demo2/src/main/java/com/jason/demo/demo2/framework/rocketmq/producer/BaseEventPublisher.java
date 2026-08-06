package com.jason.demo.demo2.framework.rocketmq.producer;

import com.jason.demo.demo2.framework.rocketmq.DelayTimeLevel;
import com.jason.demo.demo2.framework.rocketmq.util.TransactionUtils;
import jakarta.annotation.PostConstruct;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.lang.NonNull;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class BaseEventPublisher implements ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(BaseEventPublisher.class);

    private final String producerId;
    private final JsonMapper jsonMapper;

    private ApplicationContext applicationContext;
    private DefaultMQProducer producer;
    private String topic;
    private String tag;
    private int maxTryTimes = 2;

    public BaseEventPublisher(String producerId, JsonMapper jsonMapper) {
        this.producerId = producerId;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void initialize() {
        this.producer = applicationContext.getBean(producerId, DefaultMQProducer.class);
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public void setMaxTryTimes(int maxTryTimes) {
        this.maxTryTimes = maxTryTimes;
    }

    protected void send(Object messageBodyObj, String messageTag, String... keys) {
        Message message = buildMessage(messageBodyObj, messageTag, keys);
        doSend(message, messageBodyObj);
    }

    protected void sendAsync(Object messageBodyObj, String messageTag, String... keys) {
        Message message = buildMessage(messageBodyObj, messageTag, keys);
        TransactionUtils.afterCommitSyncExecute(() -> {
            try {
                producer.send(message, new SendCallback() {
                    @Override
                    public void onSuccess(SendResult sendResult) {
                        log.info("async send success, result:{}", sendResult);
                    }

                    @Override
                    public void onException(Throwable e) {
                        log.error("async send error, message:{}", messageBodyObj, e);
                    }
                });
            } catch (Exception e) {
                log.error("async send submit error, message:{}", messageBodyObj, e);
            }
        });
    }

    protected void sendOrderly(Object messageBodyObj, String messageTag, String shardingKey, String... keys) {
        String[] mergedKeys = buildMessageKeys(keys, shardingKey);
        Message message = buildMessage(messageBodyObj, messageTag, mergedKeys);
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

    protected void sendDelay(Object messageBodyObj, String messageTag, DelayTimeLevel level, String... keys) {
        Message message = buildMessage(messageBodyObj, messageTag, keys);
        message.setDelayTimeLevel(level.getLevel());
        doSend(message, messageBodyObj);
    }

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

    private Message buildMessage(Object messageBodyObj, String messageTag, String... keys) {
        try {
            byte[] body = jsonMapper.writeValueAsBytes(messageBodyObj);
            Message message = new Message(topic, body);
            String effectiveTag = messageTag != null ? messageTag : tag;
            if (effectiveTag != null && !effectiveTag.isBlank()) {
                message.setTags(effectiveTag);
            }
            if (keys != null && keys.length > 0) {
                message.setKeys(String.join(" ", keys));
            }
            return message;
        } catch (Exception e) {
            throw new IllegalStateException("serialize rocketmq message failed", e);
        }
    }

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
