package com.jason.demo.demo2.agentscope.service;

import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import com.jason.demo.demo2.agentscope.model.DevAgentEventType;
import com.jason.demo.demo2.agentscope.model.DevAgentRequest;
import com.jason.demo.demo2.agentscope.plan.PlanHostSyncService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DevAgentServicePlanHostSyncTest {

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
    PlanHostSyncService planHostSyncService;

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
                new DevAgentProperties.McpSettings(false, java.util.List.of()),
                null,
                new DevAgentProperties.Sandbox(
                        true,
                        "agentscope-java-sandbox:17",
                        "none",
                        "/workspace",
                        ".agentscope/sandbox-snapshots",
                        536870912L,
                        1L));
        lenient().when(tracer.currentTraceContext()).thenReturn(currentTraceContext);
        lenient().when(currentTraceContext.context()).thenReturn(traceContext);
        lenient().when(traceContext.traceId()).thenReturn("trace-test");
        lenient().when(traceContext.spanId()).thenReturn("span-test");
        lenient()
                .when(agentStateStore.get(any(), any(), eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.empty());
        service = new DevAgentService(
                harnessAgent, properties, agentStateStore, tracer, null, planHostSyncService);
    }

    @Test
    void ask_syncsHostPlanAfterSuccessfulPlanWrite() {
        ToolResultEndEvent planWriteOk = mock(ToolResultEndEvent.class);
        when(planWriteOk.getType()).thenReturn(AgentEventType.TOOL_RESULT_END);
        when(planWriteOk.getId()).thenReturn("e1");
        when(planWriteOk.getSource()).thenReturn("agent");
        when(planWriteOk.getToolCallId()).thenReturn("tc1");
        when(planWriteOk.getToolCallName()).thenReturn("plan_write");
        when(planWriteOk.getState()).thenReturn(ToolResultState.SUCCESS);
        when(harnessAgent.streamEvents(eq("hi"), any(RuntimeContext.class)))
                .thenReturn(Flux.just(planWriteOk));

        StepVerifier.create(service.ask(new DevAgentRequest("u1", "s1", "hi")))
                .expectNextMatches(e -> e.type() == DevAgentEventType.SESSION)
                .expectNextMatches(e -> e.type() == DevAgentEventType.REQUEST_CONTEXT)
                .expectNextMatches(e -> e.type() == DevAgentEventType.TOOL_RESULT_END)
                .expectNextMatches(e -> e.type() == DevAgentEventType.DONE)
                .verifyComplete();

        verify(planHostSyncService).syncAfterPlanWrite("u1", "s1");
    }

    @Test
    void ask_doesNotSyncOnOtherToolsOrErrors() {
        ToolResultEndEvent readOk = mock(ToolResultEndEvent.class);
        when(readOk.getType()).thenReturn(AgentEventType.TOOL_RESULT_END);
        when(readOk.getId()).thenReturn("e1");
        when(readOk.getSource()).thenReturn("agent");
        when(readOk.getToolCallId()).thenReturn("tc1");
        when(readOk.getToolCallName()).thenReturn("read_file");
        when(readOk.getState()).thenReturn(ToolResultState.SUCCESS);

        ToolResultEndEvent planErr = mock(ToolResultEndEvent.class);
        when(planErr.getType()).thenReturn(AgentEventType.TOOL_RESULT_END);
        when(planErr.getId()).thenReturn("e2");
        when(planErr.getSource()).thenReturn("agent");
        when(planErr.getToolCallId()).thenReturn("tc2");
        when(planErr.getToolCallName()).thenReturn("plan_write");
        when(planErr.getState()).thenReturn(ToolResultState.ERROR);

        when(harnessAgent.streamEvents(eq("hi"), any(RuntimeContext.class)))
                .thenReturn(Flux.just(readOk, planErr));

        StepVerifier.create(service.ask(new DevAgentRequest("u1", "s1", "hi")))
                .thenConsumeWhile(e -> true)
                .verifyComplete();

        verify(planHostSyncService, never()).syncAfterPlanWrite(any(), any());
    }
}
