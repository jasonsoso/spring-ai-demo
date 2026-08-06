package com.jason.demo.demo2.framework.rocketmq;

import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public abstract class AbstractOrderlyRocketListener implements MessageListenerOrderly {

    private static final Logger log = LoggerFactory.getLogger(AbstractOrderlyRocketListener.class);

    @Override
    public ConsumeOrderlyStatus consumeMessage(List<MessageExt> msgs, ConsumeOrderlyContext context) {
        if (msgs == null || msgs.isEmpty()) {
            return ConsumeOrderlyStatus.SUCCESS;
        }
        MessageExt messageExt = msgs.getFirst();
        try {
            preReceiveMessage(messageExt);
            return doReceiveMessage(messageExt);
        } catch (RuntimeException e) {
            log.error("接收消息异常", e);
            return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
        } catch (Exception e) {
            log.error("处理消息异常", e);
            return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
        } finally {
            postReceiveMessage(messageExt);
        }
    }

    protected abstract ConsumeOrderlyStatus doReceiveMessage(MessageExt message);

    protected void preReceiveMessage(MessageExt message) {
        // extension point
    }

    protected void postReceiveMessage(MessageExt message) {
        // extension point
    }
}
