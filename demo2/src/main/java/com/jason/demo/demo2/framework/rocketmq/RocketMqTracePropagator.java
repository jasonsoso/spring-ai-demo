package com.jason.demo.demo2.framework.rocketmq;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RocketMqTracePropagator {

    private final Tracer tracer;
    private final Propagator propagator;

    public RocketMqTracePropagator(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public void inject(Message message) {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return;
        }
        try {
            propagator.inject(context, message, (carrier, key, value) -> {
                if (key != null && value != null) {
                    carrier.putUserProperty(key, value);
                }
            });
        } catch (Exception e) {
            log.warn("rocketmq trace inject failed", e);
        }
    }

    public void runWithExtractedOrNew(MessageExt message, String spanName, Runnable action) {
        Span span;
        try {
            Span.Builder builder = propagator.extract(message, (carrier, key) -> {
                if (carrier == null || key == null) {
                    return null;
                }
                return carrier.getUserProperty(key);
            });
            span = builder.name(spanName).start();
        } catch (Exception e) {
            log.warn("rocketmq trace extract failed, starting new root span", e);
            span = tracer.nextSpan().name(spanName).start();
        }
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            action.run();
        } finally {
            span.end();
        }
    }
}
