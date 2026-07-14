package com.jason.demo.demo2.embabel.sse;

import com.embabel.agent.api.event.ActionExecutionResultEvent;
import com.embabel.agent.api.event.ActionExecutionStartEvent;
import com.embabel.agent.api.event.AgentProcessEvent;
import com.embabel.agent.api.event.AgenticEventListener;
import com.jason.demo.demo2.embabel.model.EmbabelSseEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * Per-request SSE bridge. Prefer {@link #create(SseEmitter, JsonMapper)} over a shared
 * ThreadLocal so events remain correct when Embabel fires callbacks off the request thread.
 */
@Component
public class EmbabelSseBridge {

    public RequestBridge create(SseEmitter emitter, JsonMapper jsonMapper) {
        return new RequestBridge(emitter, jsonMapper);
    }

    public static final class RequestBridge implements AgenticEventListener {

        private final SseEmitter emitter;
        private final JsonMapper jsonMapper;

        private RequestBridge(SseEmitter emitter, JsonMapper jsonMapper) {
            this.emitter = emitter;
            this.jsonMapper = jsonMapper;
        }

        public void send(EmbabelSseEvent event) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.event())
                        .data(jsonMapper.writeValueAsString(event.data())));
            } catch (IOException ignored) {
                // client disconnected
            }
        }

        @Override
        public void onProcessEvent(AgentProcessEvent event) {
            if (event instanceof ActionExecutionStartEvent start) {
                String action = start.getAction().shortName();
                send(EmbabelSseEvent.actionStart(action));
                send(EmbabelSseEvent.progress("开始执行 Action: " + action));
                return;
            }
            if (event instanceof ActionExecutionResultEvent result) {
                String action = result.getAction().shortName();
                String status = String.valueOf(result.getActionStatus());
                send(EmbabelSseEvent.actionComplete(action, status));
                send(EmbabelSseEvent.progress("完成 Action: " + action));
            }
        }
    }
}
