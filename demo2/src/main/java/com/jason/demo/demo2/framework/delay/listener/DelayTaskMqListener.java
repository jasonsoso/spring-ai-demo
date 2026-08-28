package com.jason.demo.demo2.framework.delay.listener;

import com.jason.demo.demo2.framework.delay.DelayTaskExecutor;
import com.jason.demo.demo2.framework.rocketmq.RocketMessageConcurrentlyListener;
import com.jason.demo.demo2.mq.model.DelayTaskMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component("delayTaskMqListener")
public class DelayTaskMqListener extends RocketMessageConcurrentlyListener<DelayTaskMessage> {

    private final DelayTaskExecutor executor;

    public DelayTaskMqListener(JsonMapper jsonMapper, DelayTaskExecutor executor) {
        super(jsonMapper);
        this.executor = executor;
    }

    @Override
    protected ConsumeConcurrentlyStatus handleMessage(
            DelayTaskMessage payload, String message, MessageExt messageExt) {
        if (payload == null || payload.getTaskId() == null) {
            log.warn("delay task mq payload missing taskId, msgId={}", messageExt.getMsgId());
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }
        log.info("calling DelayTaskExecutor#execute from DelayTaskMqListener, taskId={}, msgId={}",
                payload.getTaskId(), messageExt.getMsgId());
        executor.execute(payload.getTaskId());
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}
