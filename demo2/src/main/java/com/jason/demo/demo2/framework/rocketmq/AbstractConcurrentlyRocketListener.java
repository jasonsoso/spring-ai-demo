package com.jason.demo.demo2.framework.rocketmq;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 并发消费模板：统一异常处理与前后置钩子。
 * <p>
 * 业务异常 → {@link ConsumeConcurrentlyStatus#RECONSUME_LATER}，由 Broker 稍后重投。
 * 子类实现 {@link #doReceiveMessage}；一般再继承 {@link RocketMessageConcurrentlyListener} 做 JSON 反序列化。
 */
@Slf4j
public abstract class AbstractConcurrentlyRocketListener implements MessageListenerConcurrently {

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(
            List<MessageExt> msgs, ConsumeConcurrentlyContext context) {
        if (msgs == null || msgs.isEmpty()) {
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }
        // PushConsumer 默认每次回调一批，本框架按首条处理（与 Demo 单条投递场景对齐）
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

    /** 实际业务消费逻辑。 */
    protected abstract ConsumeConcurrentlyStatus doReceiveMessage(MessageExt message);

    /** 消费前钩子（可选覆盖）。 */
    protected void preReceiveMessage(MessageExt message) {
        // extension point
    }

    /** 消费后钩子，无论成功失败都会执行（可选覆盖）。 */
    protected void postReceiveMessage(MessageExt message) {
        // extension point
    }
}
