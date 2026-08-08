package com.jason.demo.demo2.framework.rocketmq;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RocketMqTracePropagatorTest {

    @Test
    void inject_writesViaPropagator_whenContextPresent() {
        SimpleTracer tracer = new SimpleTracer();
        Propagator propagator = mock(Propagator.class);
        RocketMqTracePropagator mq = new RocketMqTracePropagator(tracer, propagator);

        Span parent = tracer.nextSpan().name("parent").start();
        try (Tracer.SpanInScope scope = tracer.withSpan(parent)) {
            Message message = new Message("t", "body".getBytes());
            mq.inject(message);
            verify(propagator).inject(eq(parent.context()), eq(message), any());
        } finally {
            parent.end();
        }
    }

    @Test
    void inject_noop_whenNoContext() {
        SimpleTracer tracer = new SimpleTracer();
        Propagator propagator = mock(Propagator.class);
        RocketMqTracePropagator mq = new RocketMqTracePropagator(tracer, propagator);

        mq.inject(new Message("t", "body".getBytes()));
        verifyNoInteractions(propagator);
    }

    @Test
    void runWithExtractedOrNew_withoutHeaders_stillRunsUnderSpan() {
        SimpleTracer tracer = new SimpleTracer();
        Propagator propagator = mock(Propagator.class);
        when(propagator.extract(any(), any())).thenReturn(tracer.spanBuilder());

        RocketMqTracePropagator mq = new RocketMqTracePropagator(tracer, propagator);
        MessageExt ext = new MessageExt();
        AtomicBoolean ran = new AtomicBoolean();
        AtomicReference<Span> inside = new AtomicReference<>();

        mq.runWithExtractedOrNew(ext, "rocketmq.consume", () -> {
            ran.set(true);
            inside.set(tracer.currentSpan());
        });

        assertThat(ran.get()).isTrue();
        assertThat(inside.get()).isNotNull();
        assertThat(tracer.currentSpan()).isNull();
    }
}
