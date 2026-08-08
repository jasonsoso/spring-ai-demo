package com.jason.demo.demo2.framework.rocketmq;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 并发消费模板：统一异常处理与前后置钩子。
 * <p>
 * 业务异常 → {@link ConsumeConcurrentlyStatus#RECONSUME_LATER}，由 Broker 稍后重投。
 * 子类实现 {@link #doReceiveMessage}；一般再继承 {@link RocketMessageConcurrentlyListener} 做 JSON 反序列化。
 */
@Slf4j
public abstract class AbstractConcurrentlyRocketListener implements MessageListenerConcurrently {

    private RocketMqTracePropagator tracePropagator;

    @Autowired(required = false)
    public void setTracePropagator(RocketMqTracePropagator tracePropagator) {
        this.tracePropagator = tracePropagator;
    }

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(
            List<MessageExt> msgs, ConsumeConcurrentlyContext context) {
        if (msgs == null || msgs.isEmpty()) {
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }
        MessageExt messageExt = msgs.getFirst();
        if (tracePropagator == null) {
            return consumeWithoutTrace(messageExt);
        }
        AtomicReference<ConsumeConcurrentlyStatus> status =
                new AtomicReference<>(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        tracePropagator.runWithExtractedOrNew(messageExt, "rocketmq.consume", () ->
                status.set(consumeWithoutTrace(messageExt)));
        return status.get();
    }

    private ConsumeConcurrentlyStatus consumeWithoutTrace(MessageExt messageExt) {
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
