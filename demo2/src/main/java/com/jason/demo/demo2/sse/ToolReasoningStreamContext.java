package com.jason.demo.demo2.sse;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class ToolReasoningStreamContext {

    public record Holder(
            SseEmitter emitter,
            JsonMapper jsonMapper,
            AtomicInteger callIndex,
            Consumer<String> sender
    ) {}

    private static final ThreadLocal<Holder> HOLDER = new ThreadLocal<>();

    private ToolReasoningStreamContext() {
    }

    public static void set(SseEmitter emitter, JsonMapper jsonMapper, AtomicInteger callIndex) {
        set(emitter, jsonMapper, callIndex, json -> send(emitter, json));
    }

    static void set(SseEmitter emitter, JsonMapper jsonMapper, AtomicInteger callIndex,
                    Consumer<String> sender) {
        HOLDER.set(new Holder(emitter, jsonMapper, callIndex, sender));
    }

    public static Optional<Holder> get() {
        return Optional.ofNullable(HOLDER.get());
    }

    public static void clear() {
        HOLDER.remove();
    }

    private static void send(SseEmitter emitter, String json) {
        try {
            emitter.send(SseEmitter.event().data(json).build());
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }
}
