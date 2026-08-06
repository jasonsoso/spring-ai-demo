package com.jason.demo.demo2.framework.rocketmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

public abstract class RocketMessageOrderlyListener<T> extends AbstractOrderlyRocketListener {

    private static final Logger log = LoggerFactory.getLogger(RocketMessageOrderlyListener.class);

    private final ObjectMapper objectMapper;

    protected RocketMessageOrderlyListener(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected ConsumeOrderlyStatus doReceiveMessage(MessageExt messageExt) {
        String body = new String(messageExt.getBody(), StandardCharsets.UTF_8);
        T payload;
        try {
            payload = objectMapper.readValue(body, objectMapper.constructType(resolveMessageType()));
        } catch (Exception e) {
            log.error("parse messageBody error, body:{}", body, e);
            return ConsumeOrderlyStatus.SUCCESS;
        }
        return handleMessage(payload, body, messageExt);
    }

    protected abstract ConsumeOrderlyStatus handleMessage(T payload, String message, MessageExt messageExt);

    private Type resolveMessageType() {
        return ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
    }
}
