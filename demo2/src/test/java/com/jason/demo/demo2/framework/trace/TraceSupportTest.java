package com.jason.demo.demo2.framework.trace;

import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceSupportTest {

    @Test
    void runInSpan_setsCurrentTraceAndClearsAfter() {
        SimpleTracer tracer = new SimpleTracer();
        TraceSupport support = new TraceSupport(tracer);
        AtomicReference<String> inside = new AtomicReference<>();

        support.runInSpan("test.span", () -> {
            assertThat(tracer.currentSpan()).isNotNull();
            inside.set(tracer.currentSpan().context().traceId());
        });

        assertThat(inside.get()).isNotBlank();
        assertThat(tracer.currentSpan()).isNull();
    }

    @Test
    void runInSpan_rethrowsAndStillEndsSpan() {
        SimpleTracer tracer = new SimpleTracer();
        TraceSupport support = new TraceSupport(tracer);

        assertThatThrownBy(() -> support.runInSpan("boom", () -> {
            throw new IllegalStateException("x");
        })).isInstanceOf(IllegalStateException.class).hasMessage("x");

        assertThat(tracer.currentSpan()).isNull();
    }

    @Test
    void runInSpan_withParent_createsChildSameTraceId() {
        SimpleTracer tracer = new SimpleTracer();
        TraceSupport support = new TraceSupport(tracer);
        AtomicReference<String> parentTrace = new AtomicReference<>();
        AtomicReference<String> childTrace = new AtomicReference<>();

        support.runInSpan("parent", () -> {
            parentTrace.set(tracer.currentSpan().context().traceId());
            support.runInSpan("child", () ->
                    childTrace.set(tracer.currentSpan().context().traceId()));
        });

        assertThat(childTrace.get()).isEqualTo(parentTrace.get());
    }
}
