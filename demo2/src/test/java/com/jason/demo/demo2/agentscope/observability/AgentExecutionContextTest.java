package com.jason.demo.demo2.agentscope.observability;

import io.agentscope.core.agent.RuntimeContext;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentExecutionContextTest {

    @Test
    void create_capturesTraceAndGeneratesCompactUuid() {
        Tracer tracer = mock(Tracer.class);
        CurrentTraceContext current = mock(CurrentTraceContext.class);
        TraceContext trace = mock(TraceContext.class);
        when(tracer.currentTraceContext()).thenReturn(current);
        when(current.context()).thenReturn(trace);
        when(trace.traceId()).thenReturn("trace-1");
        when(trace.spanId()).thenReturn("span-1");

        AgentExecutionContext ids = AgentExecutionContext.create(tracer);

        assertThat(ids.requestId()).matches("[0-9a-f]{32}");
        assertThat(ids.traceId()).isEqualTo("trace-1");
        assertThat(ids.spanId()).isEqualTo("span-1");
    }

    @Test
    void writeAndRead_roundTripsAndKeepsRoundPerRuntimeContext() {
        RuntimeContext runtime = RuntimeContext.builder()
                .userId("u1")
                .sessionId("s1")
                .build();
        AgentExecutionContext ids =
                new AgentExecutionContext("request-1", "trace-1", "span-1");

        ids.writeTo(runtime);

        assertThat(AgentExecutionContext.from(runtime)).isEqualTo(ids);
        assertThat(AgentExecutionContext.nextReasoningRound(runtime)).isEqualTo(1);
        assertThat(AgentExecutionContext.nextReasoningRound(runtime)).isEqualTo(2);
    }

    @Test
    void create_withoutTraceUsesDash() {
        Tracer tracer = mock(Tracer.class);
        CurrentTraceContext current = mock(CurrentTraceContext.class);
        when(tracer.currentTraceContext()).thenReturn(current);
        when(current.context()).thenReturn(null);

        AgentExecutionContext ids = AgentExecutionContext.create(tracer);

        assertThat(ids.traceId()).isEqualTo("-");
        assertThat(ids.spanId()).isEqualTo("-");
    }
}
