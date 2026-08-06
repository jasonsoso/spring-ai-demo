package com.jason.demo.demo2.framework.rocketmq;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;

/**
 * 并发消费 + Jackson 反序列化。
 * <p>
 * 通过泛型参数 {@code T} 解析消息体；反序列化失败记日志并返回 {@code CONSUME_SUCCESS}，
 * 避免坏消息无限重试。业务实现 {@link #handleMessage}。
 *
 * @param <T> 消息体类型
 */
@Slf4j
public abstract class RocketMessageConcurrentlyListener<T> extends AbstractConcurrentlyRocketListener {

    private final JsonMapper jsonMapper;

    protected RocketMessageConcurrentlyListener(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected ConsumeConcurrentlyStatus doReceiveMessage(MessageExt messageExt) {
        String body = new String(messageExt.getBody(), StandardCharsets.UTF_8);
        log.info("received message, msgId={}, topic={}, tags={}, keys={}, queueId={}, "
                        + "reconsumeTimes={}, bornTimestamp={}, body={}",
                messageExt.getMsgId(),
                messageExt.getTopic(),
                messageExt.getTags(),
                messageExt.getKeys(),
                messageExt.getQueueId(),
                messageExt.getReconsumeTimes(),
                messageExt.getBornTimestamp(),
                body);
        T payload;
        try {
            Class<T> messageClass = (Class<T>) ((ParameterizedType) getClass().getGenericSuperclass())
                    .getActualTypeArguments()[0];
            payload = jsonMapper.readValue(body, messageClass);
        } catch (Exception e) {
            log.error("parse messageBody error, msgId={}, body:{}", messageExt.getMsgId(), body, e);
            // 解析失败视为毒消息：确认消费，防止反复重投
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }
        return handleMessage(payload, body, messageExt);
    }

    /**
     * @param payload    反序列化后的业务对象
     * @param message    原始 UTF-8 字符串
     * @param messageExt RocketMQ 原始消息（含 keys/tags 等）
     */
    protected abstract ConsumeConcurrentlyStatus handleMessage(T payload, String message, MessageExt messageExt);
}
