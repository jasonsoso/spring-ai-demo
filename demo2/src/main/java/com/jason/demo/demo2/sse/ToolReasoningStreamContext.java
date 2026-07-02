package com.jason.demo.demo2.sse;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.model.tool.internal.ToolCallReactiveContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.util.context.ContextView;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class ToolReasoningStreamContext {

    public record Holder(
            SseEmitter emitter,
            JsonMapper jsonMapper,
            AtomicInteger callIndex,
            Consumer<String> sender
    ) {}

    private static final Map<String, Holder> HOLDERS = new ConcurrentHashMap<>();

    private ToolReasoningStreamContext() {
    }

    public static void bind(String sessionId, SseEmitter emitter, JsonMapper jsonMapper, AtomicInteger callIndex) {
        bind(sessionId, emitter, jsonMapper, callIndex, json -> send(emitter, json));
    }

    static void bind(String sessionId, SseEmitter emitter, JsonMapper jsonMapper, AtomicInteger callIndex,
                     Consumer<String> sender) {
        HOLDERS.put(sessionId, new Holder(emitter, jsonMapper, callIndex, sender));
    }

    public static Optional<Holder> currentHolder() {
        return resolveSessionId()
                .map(HOLDERS::get)
                .filter(holder -> holder != null)
                .or(() -> HOLDERS.size() == 1
                        ? Optional.of(HOLDERS.values().iterator().next())
                        : Optional.empty());
    }

    public static void clear(String sessionId) {
        if (sessionId != null) {
            HOLDERS.remove(sessionId);
        }
    }

    /** @deprecated tests only — prefer {@link #clear(String)} */
    @Deprecated
    static void clearAll() {
        HOLDERS.clear();
    }

    private static Optional<String> resolveSessionId() {
        try {
            ContextView context = ToolCallReactiveContextHolder.getContext();
            if (context != null && context.hasKey(ChatMemory.CONVERSATION_ID)) {
                return Optional.of(context.get(ChatMemory.CONVERSATION_ID));
            }
        } catch (Exception ignored) {
            // outside reactive tool-call thread
        }
        return Optional.empty();
    }

    private static void send(SseEmitter emitter, String json) {
        try {
            emitter.send(SseEmitter.event().data(json).build());
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }
}
