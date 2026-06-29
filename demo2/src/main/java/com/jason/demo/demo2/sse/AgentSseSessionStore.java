package com.jason.demo.demo2.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class AgentSseSessionStore {

    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(10);

    private final ConcurrentHashMap<String, AgentSseSession> sessions = new ConcurrentHashMap<>();
    private final JsonMapper jsonMapper;

    public AgentSseSessionStore(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public AgentSseSession create(String message) {
        expireIdleSessions();
        String sessionId = UUID.randomUUID().toString();
        AgentSseSession session = new AgentSseSession(sessionId, message);
        sessions.put(sessionId, session);
        return session;
    }

    public Optional<AgentSseSession> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public void remove(String sessionId) {
        AgentSseSession session = sessions.remove(sessionId);
        if (session != null && session.getSseEmitter() != null) {
            session.getSseEmitter().complete();
        }
    }

    public void pushEvent(String sessionId, AgentSseEvent event) {
        find(sessionId).ifPresent(session -> {
            session.touch();
            SseEmitter emitter = session.getSseEmitter();
            if (emitter == null) {
                session.getEventBuffer().add(event);
                return;
            }
            sendToEmitter(session, emitter, event);
        });
    }

    public void attachEmitter(String sessionId, SseEmitter emitter) {
        AgentSseSession session = sessions.get(sessionId);
        if (session == null) {
            emitter.completeWithError(new IllegalArgumentException("Session not found: " + sessionId));
            return;
        }
        session.setSseEmitter(emitter);
        session.touch();
        emitter.onCompletion(() -> log.debug("SSE completed: {}", sessionId));
        emitter.onTimeout(() -> {
            pushEvent(sessionId, AgentSseEvent.failed("SSE 连接超时"));
            remove(sessionId);
        });
        AgentSseEvent buffered;
        while ((buffered = session.getEventBuffer().poll()) != null) {
            sendToEmitter(session, emitter, buffered);
        }
    }

    public void completeAnswer(String sessionId, Map<String, String> answers) {
        find(sessionId).ifPresent(session -> {
            session.touch();
            CompletableFuture<Map<String, String>> future = session.getAnswerFuture();
            if (future != null && !future.isDone()) {
                future.complete(answers);
            }
        });
    }

    private void expireIdleSessions() {
        Instant cutoff = Instant.now().minus(IDLE_TIMEOUT);
        sessions.entrySet().removeIf(entry -> {
            AgentSseSession session = entry.getValue();
            if (session.getLastActivityAt().isBefore(cutoff)) {
                log.info("Agent SSE session expired: {}", entry.getKey());
                if (session.getSseEmitter() != null) {
                    session.getSseEmitter().complete();
                }
                return true;
            }
            return false;
        });
    }

    private void sendToEmitter(AgentSseSession session, SseEmitter emitter, AgentSseEvent event) {
        try {
            String json = jsonMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event().data(json).build());
            if ("COMPLETED".equals(event.getType()) || "FAILED".equals(event.getType())) {
                emitter.complete();
                sessions.remove(session.getSessionId());
            }
        } catch (IOException e) {
            log.error("SSE send failed: {}", session.getSessionId(), e);
            emitter.completeWithError(e);
        }
    }
}
