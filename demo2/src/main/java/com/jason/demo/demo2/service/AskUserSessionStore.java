package com.jason.demo.demo2.service;

import com.jason.demo.demo2.model.AskUserSession;
import com.jason.demo.demo2.model.AskUserSseEvent;
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
public class AskUserSessionStore {

    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(10);

    private final ConcurrentHashMap<String, AskUserSession> sessions = new ConcurrentHashMap<>();
    private final JsonMapper jsonMapper;

    public AskUserSessionStore(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public AskUserSession create(String message) {
        expireIdleSessions();
        String sessionId = UUID.randomUUID().toString();
        AskUserSession session = new AskUserSession(sessionId, message);
        sessions.put(sessionId, session);
        return session;
    }

    public Optional<AskUserSession> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public void remove(String sessionId) {
        AskUserSession session = sessions.remove(sessionId);
        if (session != null && session.getSseEmitter() != null) {
            session.getSseEmitter().complete();
        }
    }

    public void pushEvent(String sessionId, AskUserSseEvent event) {
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
        AskUserSession session = sessions.get(sessionId);
        if (session == null) {
            emitter.completeWithError(new IllegalArgumentException("Session not found: " + sessionId));
            return;
        }
        session.setSseEmitter(emitter);
        session.touch();
        emitter.onCompletion(() -> log.debug("SSE completed: {}", sessionId));
        emitter.onTimeout(() -> {
            pushEvent(sessionId, AskUserSseEvent.failed("SSE 连接超时"));
            remove(sessionId);
        });
        AskUserSseEvent buffered;
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

    public void expireIdleSessions() {
        Instant cutoff = Instant.now().minus(IDLE_TIMEOUT);
        sessions.entrySet().removeIf(entry -> {
            AskUserSession session = entry.getValue();
            if (session.getLastActivityAt().isBefore(cutoff)) {
                log.info("AskUser session expired: {}", entry.getKey());
                if (session.getSseEmitter() != null) {
                    session.getSseEmitter().complete();
                }
                return true;
            }
            return false;
        });
    }

    private void sendToEmitter(AskUserSession session, SseEmitter emitter, AskUserSseEvent event) {
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
