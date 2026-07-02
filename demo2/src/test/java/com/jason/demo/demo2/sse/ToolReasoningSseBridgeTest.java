package com.jason.demo.demo2.sse;

import com.jason.demo.demo2.model.AgentThinking;
import com.jason.demo.demo2.model.ToolReasoningSseEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolReasoningSseBridgeTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final List<String> sentPayloads = new ArrayList<>();

    @AfterEach
    void tearDown() {
        ToolReasoningStreamContext.clear();
    }

    @Test
    void onToolReasoning_incrementsCallIndexAndSendsEvent() throws Exception {
        SseEmitter emitter = new SseEmitter();
        ToolReasoningStreamContext.set(emitter, jsonMapper, new AtomicInteger(0), sentPayloads::add);

        ToolReasoningSseBridge.onToolReasoning("getWeather",
                new AgentThinking("需要北京实时天气", "high"));
        ToolReasoningSseBridge.onToolReasoning("recommendAttractions",
                new AgentThinking("推荐人文景点", "medium"));

        assertEquals(2, sentPayloads.size());
        ToolReasoningSseEvent first = jsonMapper.readValue(sentPayloads.get(0), ToolReasoningSseEvent.class);
        ToolReasoningSseEvent second = jsonMapper.readValue(sentPayloads.get(1), ToolReasoningSseEvent.class);
        assertEquals("TOOL_REASONING", first.getType());
        assertEquals("getWeather", first.getToolName());
        assertEquals(1, first.getCallIndex());
        assertEquals(2, second.getCallIndex());
    }

    @Test
    void onToolReasoning_withoutContext_isNoOp() {
        assertTrue(ToolReasoningStreamContext.get().isEmpty());
        ToolReasoningSseBridge.onToolReasoning("getWeather",
                new AgentThinking("x", "low"));
        assertEquals(0, sentPayloads.size());
    }
}
