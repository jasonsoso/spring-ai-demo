package com.jason.demo.demo2.framework.rocketmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

public abstract class RocketMessageConcurrentlyListener<T> extends AbstractConcurrentlyRocketListener {

    private static final Logger log = LoggerFactory.getLogger(RocketMessageConcurrentlyListener.class);

    private final ObjectMapper objectMapper;

    protected RocketMessageConcurrentlyListener(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected ConsumeConcurrentlyStatus doReceiveMessage(MessageExt messageExt) {
        String body = new String(messageExt.getBody(), StandardCharsets.UTF_8);
        T payload;
        try {
            payload = objectMapper.readValue(body, objectMapper.constructType(resolveMessageType()));
        } catch (Exception e) {
            log.error("parse messageBody error, body:{}", body, e);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }
        return handleMessage(payload, body, messageExt);
    }

    protected abstract ConsumeConcurrentlyStatus handleMessage(T payload, String message, MessageExt messageExt);

    private Type resolveMessageType() {
        return ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
    }
}
