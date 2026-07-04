package com.jason.demo.demo2.sse;

import com.jason.demo.demo2.model.LkCoffeeSseEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public final class LkCoffeeStreamContext {

    private static final ThreadLocal<Context> CTX = new ThreadLocal<>();

    private record Context(SseEmitter emitter, JsonMapper jsonMapper, AtomicInteger callIndex) {}

    private LkCoffeeStreamContext() {}

    public static void bind(SseEmitter emitter, JsonMapper jsonMapper) {
        CTX.set(new Context(emitter, jsonMapper, new AtomicInteger(0)));
    }

    public static AtomicInteger callIndex() {
        Context c = CTX.get();
        return c != null ? c.callIndex : null;
    }

    public static void emit(LkCoffeeSseEvent event) {
        Context c = CTX.get();
        if (c == null) {
            return;
        }
        try {
            c.emitter.send(SseEmitter.event()
                    .data(c.jsonMapper.writeValueAsString(event))
                    .build());
        } catch (IOException e) {
            c.emitter.completeWithError(e);
        }
    }

    public static void clear() {
        CTX.remove();
    }
}
