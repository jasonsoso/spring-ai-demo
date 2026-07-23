package com.jason.demo.demo2.agentscope.observability;

import io.agentscope.core.agent.RuntimeContext;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public record AgentExecutionContext(String requestId, String traceId, String spanId) {

    public static final String REQUEST_ID_KEY = "observability.request_id";
    public static final String TRACE_ID_KEY = "observability.trace_id";
    public static final String SPAN_ID_KEY = "observability.span_id";
    public static final String REASONING_ROUND_KEY = "observability.reasoning_round";
    private static final String UNKNOWN = "-";

    public static AgentExecutionContext create(Tracer tracer) {
        TraceContext trace = currentTrace(tracer);
        return new AgentExecutionContext(
                UUID.randomUUID().toString().replace("-", ""),
                trace == null ? UNKNOWN : valueOrUnknown(trace.traceId()),
                trace == null ? UNKNOWN : valueOrUnknown(trace.spanId()));
    }

    public void writeTo(RuntimeContext context) {
        context.put(REQUEST_ID_KEY, requestId);
        context.put(TRACE_ID_KEY, traceId);
        context.put(SPAN_ID_KEY, spanId);
    }

    public static AgentExecutionContext from(RuntimeContext context) {
        return new AgentExecutionContext(
                valueOrUnknown(context.get(REQUEST_ID_KEY)),
                valueOrUnknown(context.get(TRACE_ID_KEY)),
                valueOrUnknown(context.get(SPAN_ID_KEY)));
    }

    public static int nextReasoningRound(RuntimeContext context) {
        Object value = context.getExtra().computeIfAbsent(
                REASONING_ROUND_KEY, ignored -> new AtomicInteger());
        if (!(value instanceof AtomicInteger counter)) {
            throw new IllegalStateException(REASONING_ROUND_KEY + " must contain AtomicInteger");
        }
        return counter.incrementAndGet();
    }

    private static TraceContext currentTrace(Tracer tracer) {
        if (tracer == null) {
            return null;
        }
        CurrentTraceContext current = tracer.currentTraceContext();
        return current == null ? null : current.context();
    }

    private static String valueOrUnknown(Object value) {
        if (value == null) {
            return UNKNOWN;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? UNKNOWN : text;
    }
}
