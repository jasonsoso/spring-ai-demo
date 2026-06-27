package com.jason.demo.demo2.model;

import lombok.Getter;
import lombok.Setter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

@Getter
public class AskUserSession {

    private final String sessionId;
    private final String message;
    private final Instant createdAt;
    private final Queue<AskUserSseEvent> eventBuffer = new ConcurrentLinkedQueue<>();

    @Setter
    private volatile AskUserSessionStatus status = AskUserSessionStatus.RUNNING;

    @Setter
    private volatile SseEmitter sseEmitter;

    @Setter
    private volatile CompletableFuture<Map<String, String>> answerFuture;

    @Setter
    private volatile Instant lastActivityAt;

    public AskUserSession(String sessionId, String message) {
        this.sessionId = sessionId;
        this.message = message;
        this.createdAt = Instant.now();
        this.lastActivityAt = this.createdAt;
    }

    public void touch() {
        this.lastActivityAt = Instant.now();
    }
}
