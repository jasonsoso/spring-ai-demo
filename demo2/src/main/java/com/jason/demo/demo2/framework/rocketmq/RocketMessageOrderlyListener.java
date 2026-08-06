package com.jason.demo.demo2.framework.rocketmq;

import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;

/**
 * 顺序消费 + Jackson 反序列化。
 * <p>
 * 行为对齐 {@link RocketMessageConcurrentlyListener}：解析失败返回 {@code SUCCESS} 跳过毒消息。
 * 业务实现 {@link #handleMessage}。
 *
 * @param <T> 消息体类型
 */
@Slf4j
public abstract class RocketMessageOrderlyListener<T> extends AbstractOrderlyRocketListener {

    private final JsonMapper jsonMapper;

    protected RocketMessageOrderlyListener(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected ConsumeOrderlyStatus doReceiveMessage(MessageExt messageExt) {
        String body = new String(messageExt.getBody(), StandardCharsets.UTF_8);
        log.info("received orderly message, msgId={}, topic={}, tags={}, keys={}, queueId={}, "
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
            return ConsumeOrderlyStatus.SUCCESS;
        }
        return handleMessage(payload, body, messageExt);
    }

    /**
     * @param payload    反序列化后的业务对象
     * @param message    原始 UTF-8 字符串
     * @param messageExt RocketMQ 原始消息（含 keys/tags 等）
     */
    protected abstract ConsumeOrderlyStatus handleMessage(T payload, String message, MessageExt messageExt);
}
