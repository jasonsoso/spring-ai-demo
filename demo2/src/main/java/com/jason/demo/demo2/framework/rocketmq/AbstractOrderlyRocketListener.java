package com.jason.demo.demo2.framework.rocketmq;

import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.common.message.MessageExt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 顺序消费模板：统一异常处理与前后置钩子。
 * <p>
 * 业务异常 → {@link ConsumeOrderlyStatus#SUSPEND_CURRENT_QUEUE_A_MOMENT}，暂停当前队列片刻后重试，
 * 避免乱序。子类实现 {@link #doReceiveMessage}；一般再继承 {@link RocketMessageOrderlyListener}。
 */
@Slf4j
public abstract class AbstractOrderlyRocketListener implements MessageListenerOrderly {

    private RocketMqTracePropagator tracePropagator;

    @Autowired(required = false)
    public void setTracePropagator(RocketMqTracePropagator tracePropagator) {
        this.tracePropagator = tracePropagator;
    }

    @Override
    public ConsumeOrderlyStatus consumeMessage(List<MessageExt> msgs, ConsumeOrderlyContext context) {
        if (msgs == null || msgs.isEmpty()) {
            return ConsumeOrderlyStatus.SUCCESS;
        }
        MessageExt messageExt = msgs.getFirst();
        if (tracePropagator == null) {
            return consumeWithoutTrace(messageExt);
        }
        AtomicReference<ConsumeOrderlyStatus> status =
                new AtomicReference<>(ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT);
        tracePropagator.runWithExtractedOrNew(messageExt, "rocketmq.consume", () ->
                status.set(consumeWithoutTrace(messageExt)));
        return status.get();
    }

    private ConsumeOrderlyStatus consumeWithoutTrace(MessageExt messageExt) {
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

    /** 实际业务消费逻辑。 */
    protected abstract ConsumeOrderlyStatus doReceiveMessage(MessageExt message);

    /** 消费前钩子（可选覆盖）。 */
    protected void preReceiveMessage(MessageExt message) {
        // extension point
    }

    /** 消费后钩子，无论成功失败都会执行（可选覆盖）。 */
    protected void postReceiveMessage(MessageExt message) {
        // extension point
    }
}
