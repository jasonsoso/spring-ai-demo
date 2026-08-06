package com.jason.demo.demo2.framework.rocketmq;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;

public abstract class RocketMessageConcurrentlyListener<T> extends AbstractConcurrentlyRocketListener {

    private static final Logger log = LoggerFactory.getLogger(RocketMessageConcurrentlyListener.class);

    private final JsonMapper jsonMapper;

    protected RocketMessageConcurrentlyListener(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected ConsumeConcurrentlyStatus doReceiveMessage(MessageExt messageExt) {
        String body = new String(messageExt.getBody(), StandardCharsets.UTF_8);
        T payload;
        try {
            Class<T> messageClass = (Class<T>) ((ParameterizedType) getClass().getGenericSuperclass())
                    .getActualTypeArguments()[0];
            payload = jsonMapper.readValue(body, messageClass);
        } catch (Exception e) {
            log.error("parse messageBody error, body:{}", body, e);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }
        return handleMessage(payload, body, messageExt);
    }

    protected abstract ConsumeConcurrentlyStatus handleMessage(T payload, String message, MessageExt messageExt);
}
