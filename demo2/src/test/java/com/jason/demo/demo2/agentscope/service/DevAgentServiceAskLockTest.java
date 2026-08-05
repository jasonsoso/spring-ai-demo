package com.jason.demo.demo2.agentscope.service;

import com.baomidou.lock.LockInfo;
import com.baomidou.lock.LockTemplate;
import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import com.jason.demo.demo2.agentscope.model.DevAgentEvent;
import com.jason.demo.demo2.agentscope.model.DevAgentEventType;
import com.jason.demo.demo2.agentscope.model.DevAgentRequest;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DevAgentServiceAskLockTest {

    @Mock
    HarnessAgent harnessAgent;

    @Mock
    AgentStateStore agentStateStore;

    @Mock
    Tracer tracer;

    @Mock
    CurrentTraceContext currentTraceContext;

    @Mock
    TraceContext traceContext;

    @Mock
    LockTemplate lockTemplate;

    DevAgentProperties properties;
    DevAgentService service;

    @BeforeEach
    void setUp() {
        properties = new DevAgentProperties(
                "dev-task-agent",
                "prompt",
                ".",
                "workspace",
                new DevAgentProperties.Compaction(6, 2, "请整理会话：{messages}"),
                new DevAgentProperties.Model("sk-test", "https://api.deepseek.com", "deepseek-v4-pro"),
                null,
                new DevAgentProperties.McpSettings(false, java.util.List.of()),
                null,
                null);
        lenient().when(tracer.currentTraceContext()).thenReturn(currentTraceContext);
        lenient().when(currentTraceContext.context()).thenReturn(traceContext);
        lenient().when(traceContext.traceId()).thenReturn("trace-test");
        lenient().when(traceContext.spanId()).thenReturn("span-test");
        lenient()
                .when(agentStateStore.get(any(), any(), eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.empty());
        service = new DevAgentService(
                harnessAgent, properties, agentStateStore, tracer, null, null, lockTemplate);
    }

    @Test
    void ask_whenLockBusy_emitsDuplicateError() {
        when(lockTemplate.lock(anyString(), anyLong(), anyLong())).thenReturn(null);

        StepVerifier.create(service.ask(new DevAgentRequest("u1", "sid", "same-msg")))
                .recordWith(java.util.ArrayList::new)
                .thenConsumeWhile(e -> true)
                .consumeRecordedWith(events -> {
                    boolean found = events.stream()
                            .anyMatch(e -> e.type() == DevAgentEventType.ERROR
                                    && "duplicate_in_progress".equals(e.content()));
                    org.assertj.core.api.Assertions.assertThat(found).isTrue();
                })
                .verifyComplete();

        verify(lockTemplate, never()).releaseLock(any());
        verify(harnessAgent, never()).streamEvents(anyString(), any());
    }

    @Test
    void ask_whenLockAcquired_releasesOnComplete() {
        LockInfo lockInfo = mock(LockInfo.class);
        when(lockTemplate.lock(anyString(), anyLong(), anyLong())).thenReturn(lockInfo);
        when(lockTemplate.releaseLock(lockInfo)).thenReturn(true);
        when(harnessAgent.streamEvents(anyString(), any())).thenReturn(Flux.empty());

        StepVerifier.create(service.ask(new DevAgentRequest("u1", "sid", "msg")))
                .expectNextMatches(e -> e.type() == DevAgentEventType.SESSION)
                .expectNextMatches(e -> e.type() == DevAgentEventType.REQUEST_CONTEXT)
                .expectNextMatches(e -> e.type() == DevAgentEventType.DONE)
                .verifyComplete();

        verify(lockTemplate).releaseLock(lockInfo);
    }
}