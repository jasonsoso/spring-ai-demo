package com.jason.demo.demo2.framework.rocketmq;

import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;

public abstract class RocketMessageOrderlyListener<T> extends AbstractOrderlyRocketListener {

    private static final Logger log = LoggerFactory.getLogger(RocketMessageOrderlyListener.class);

    private final JsonMapper jsonMapper;

    protected RocketMessageOrderlyListener(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected ConsumeOrderlyStatus doReceiveMessage(MessageExt messageExt) {
        String body = new String(messageExt.getBody(), StandardCharsets.UTF_8);
        T payload;
        try {
            Class<T> messageClass = (Class<T>) ((ParameterizedType) getClass().getGenericSuperclass())
                    .getActualTypeArguments()[0];
            payload = jsonMapper.readValue(body, messageClass);
        } catch (Exception e) {
            log.error("parse messageBody error, body:{}", body, e);
            return ConsumeOrderlyStatus.SUCCESS;
        }
        return handleMessage(payload, body, messageExt);
    }

    protected abstract ConsumeOrderlyStatus handleMessage(T payload, String message, MessageExt messageExt);
}
