package com.jason.demo.demo2.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

@Slf4j
public abstract class AbstractSseAgentService {

    protected static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    protected final AgentSseSessionStore sessionStore;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    protected AbstractSseAgentService(AgentSseSessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    public String startChat(String message) {
        AgentSseSession session = sessionStore.create(message);
        String sessionId = session.getSessionId();
        executor.submit(() -> runAgent(sessionId, message));
        return sessionId;
    }

    public SseEmitter connectSse(String sessionId) {
        AgentSseSession session = sessionStore.find(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        sessionStore.attachEmitter(sessionId, emitter);
        if (session.getEventBuffer().isEmpty() && session.getStatus() == AgentSessionStatus.RUNNING) {
            sessionStore.pushEvent(sessionId, AgentSseEvent.running());
        }
        return emitter;
    }

    protected abstract void runAgent(String sessionId, String message);

    protected void runWithSession(String sessionId, Supplier<String> agentCall) {
        AgentSessionHolder.setSessionId(sessionId);
        try {
            sessionStore.pushEvent(sessionId, AgentSseEvent.running());
            String response = agentCall.get();
            sessionStore.find(sessionId).ifPresent(s -> s.setStatus(AgentSessionStatus.COMPLETED));
            sessionStore.pushEvent(sessionId, AgentSseEvent.completed(response));
        } catch (Exception e) {
            log.error("Agent failed: {}", sessionId, e);
            sessionStore.find(sessionId).ifPresent(s -> s.setStatus(AgentSessionStatus.FAILED));
            sessionStore.pushEvent(sessionId, AgentSseEvent.failed("Agent 执行失败: " + e.getMessage()));
        } finally {
            AgentSessionHolder.clear();
        }
    }
}
