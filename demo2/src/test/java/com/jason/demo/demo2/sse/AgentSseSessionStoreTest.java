package com.jason.demo.demo2.sse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSseSessionStoreTest {

    private AgentSseSessionStore store;

    @BeforeEach
    void setUp() {
        store = new AgentSseSessionStore(new JsonMapper());
    }

    @Test
    void createAndFindSession() {
        AgentSseSession session = store.create("帮我选一个数据库");
        assertNotNull(session.getSessionId());
        assertEquals("帮我选一个数据库", session.getMessage());
        assertTrue(store.find(session.getSessionId()).isPresent());
    }

    @Test
    void buffersEventsUntilEmitterAttached() {
        AgentSseSession session = store.create("test");
        store.pushEvent(session.getSessionId(), AgentSseEvent.running());

        SseEmitter emitter = new SseEmitter(60_000L);
        store.attachEmitter(session.getSessionId(), emitter);
        assertNotNull(session.getSseEmitter());
    }

    @Test
    void completeAnswerResolvesFuture() throws Exception {
        AgentSseSession session = store.create("test");
        CompletableFuture<Map<String, String>> future = new CompletableFuture<>();
        session.setAnswerFuture(future);

        Map<String, String> answers = Map.of("你更倾向哪种数据库？", "PostgreSQL");
        store.completeAnswer(session.getSessionId(), answers);

        assertEquals("PostgreSQL", future.get(1, TimeUnit.SECONDS).get("你更倾向哪种数据库？"));
    }
}
