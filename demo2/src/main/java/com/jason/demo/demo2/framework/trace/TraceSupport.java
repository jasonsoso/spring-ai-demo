package com.jason.demo.demo2.framework.trace;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;

/**
 * 统一开 span：已有父上下文时为 child，否则为新根；保证 finally end，避免线程 MDC 泄漏。
 */
@Component
public class TraceSupport {

    private final Tracer tracer;

    public TraceSupport(Tracer tracer) {
        this.tracer = tracer;
    }

    public void runInSpan(String name, Runnable action) {
        Span span = tracer.nextSpan().name(name).start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            action.run();
        } finally {
            span.end();
        }
    }
}
