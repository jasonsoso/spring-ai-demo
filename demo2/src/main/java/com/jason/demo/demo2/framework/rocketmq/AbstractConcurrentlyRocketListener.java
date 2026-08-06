package com.jason.demo.demo2.framework.rocketmq;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public abstract class AbstractConcurrentlyRocketListener implements MessageListenerConcurrently {

    private static final Logger log = LoggerFactory.getLogger(AbstractConcurrentlyRocketListener.class);

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(
            List<MessageExt> msgs, ConsumeConcurrentlyContext context) {
        if (msgs == null || msgs.isEmpty()) {
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }
        MessageExt messageExt = msgs.getFirst();
        try {
            preReceiveMessage(messageExt);
            return doReceiveMessage(messageExt);
        } catch (RuntimeException e) {
            log.error("接收消息异常", e);
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        } catch (Exception e) {
            log.error("处理消息异常", e);
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        } finally {
            postReceiveMessage(messageExt);
        }
    }

    protected abstract ConsumeConcurrentlyStatus doReceiveMessage(MessageExt message);

    protected void preReceiveMessage(MessageExt message) {
        // extension point
    }

    protected void postReceiveMessage(MessageExt message) {
        // extension point
    }
}
