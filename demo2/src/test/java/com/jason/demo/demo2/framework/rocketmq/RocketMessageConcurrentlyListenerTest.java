package com.jason.demo.demo2.framework.rocketmq;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RocketMessageConcurrentlyListenerTest {

    static class Payload {
        public String id;
    }

    static class StubListener extends RocketMessageConcurrentlyListener<Payload> {
        StubListener(JsonMapper jsonMapper) {
            super(jsonMapper);
        }

        @Override
        protected ConsumeConcurrentlyStatus handleMessage(Payload payload, String message, MessageExt messageExt) {
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        }
    }

    @Test
    void invalidJson_returnsConsumeSuccess() {
        StubListener listener = new StubListener(JsonMapper.builder().build());
        MessageExt ext = new MessageExt();
        ext.setBody("not-json".getBytes(StandardCharsets.UTF_8));
        ConsumeConcurrentlyStatus status = listener.consumeMessage(List.of(ext), null);
        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
    }
}
