package com.jason.demo.demo2.mq.publisher;

import com.jason.demo.demo2.framework.rocketmq.DelayTimeLevel;
import com.jason.demo.demo2.framework.rocketmq.producer.BaseEventPublisher;
import com.jason.demo.demo2.mq.model.DelayTaskMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DelayTaskEventPublisher extends BaseEventPublisher {

    public static final String DELAY_TASK_PUBLISHER_ID = "delayTaskProducer";

    public DelayTaskEventPublisher() {
        super(DELAY_TASK_PUBLISHER_ID);
    }

    public void sendDelay(DelayTaskMessage message, DelayTimeLevel level) {
        super.sendDelay(message, level, String.valueOf(message.getTaskId()));
    }
}
