package com.jason.demo.demo2.agentscope.service;

import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import com.jason.demo.demo2.agentscope.model.DevAgentConfirmRequest;
import com.jason.demo.demo2.agentscope.model.DevAgentEvent;
import com.jason.demo.demo2.agentscope.model.DevAgentEventType;
import com.jason.demo.demo2.agentscope.model.DevAgentRequest;
import com.jason.demo.demo2.agentscope.observability.AgentExecutionContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DevAgentServiceTest {

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
                new DevAgentProperties.Model("sk-test", "https://api.deepseek.com", "deepseek-v4-pro"));
        lenient().when(tracer.currentTraceContext()).thenReturn(currentTraceContext);
        lenient().when(currentTraceContext.context()).thenReturn(traceContext);
        lenient().when(traceContext.traceId()).thenReturn("trace-test");
        lenient().when(traceContext.spanId()).thenReturn("span-test");
        service = new DevAgentService(harnessAgent, properties, agentStateStore, tracer);
        lenient()
                .when(agentStateStore.get(any(), any(), eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.empty());
    }

    private static AgentState stateWithMessageCount(int n) {
        java.util.ArrayList<Msg> context = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            context.add(Msg.builder()
                    .role(i % 2 == 0 ? MsgRole.USER : MsgRole.ASSISTANT)
                    .textContent("m" + i)
                    .build());
        }
        return AgentState.builder().userId("u1").sessionId("s1").context(context).build();
    }

    private static boolean isRequestContext(DevAgentEvent event) {
        return event.type() == DevAgentEventType.REQUEST_CONTEXT
                && event.requestId() != null
                && event.requestId().matches("[0-9a-f]{32}");
    }

    @Test
    void ask_emitsRequestContextAndWritesItToRuntimeContext() {
        when(harnessAgent.streamEvents(eq("hi"), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());

        StepVerifier.create(service.ask(new DevAgentRequest("u1", "s1", "hi")))
                .expectNext(DevAgentEvent.session("s1"))
                .assertNext(event -> {
                    assertThat(event.type()).isEqualTo(DevAgentEventType.REQUEST_CONTEXT);
                    assertThat(event.requestId()).matches("[0-9a-f]{32}");
                    assertThat(event.traceId()).isEqualTo("trace-test");
                    assertThat(event.spanId()).isEqualTo("span-test");
                })
                .expectNext(DevAgentEvent.done("s1"))
                .verifyComplete();

        ArgumentCaptor<RuntimeContext> captor =
                ArgumentCaptor.forClass(RuntimeContext.class);
        verify(harnessAgent).streamEvents(eq("hi"), captor.capture());
        AgentExecutionContext stored = AgentExecutionContext.from(captor.getValue());
        assertThat(stored.traceId()).isEqualTo("trace-test");
        assertThat(stored.spanId()).isEqualTo("span-test");
        assertThat(stored.requestId()).matches("[0-9a-f]{32}");
    }

    @Test
    void ask_whenContextShrunk_emitsCompactionBeforeDone() {
        when(agentStateStore.get(eq("u1"), eq("s1"), eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.of(stateWithMessageCount(6)))
                .thenReturn(Optional.of(stateWithMessageCount(4)));

        TextBlockDeltaEvent d1 = mock(TextBlockDeltaEvent.class);
        when(d1.getType()).thenReturn(AgentEventType.TEXT_BLOCK_DELTA);
        when(d1.getDelta()).thenReturn("ok");
        when(harnessAgent.streamEvents(eq("汇总"), any(RuntimeContext.class)))
                .thenReturn(Flux.just(d1));

        StepVerifier.create(service.ask(new DevAgentRequest("u1", "s1", "汇总")))
                .expectNext(DevAgentEvent.session("s1"))
                .expectNextMatches(DevAgentServiceTest::isRequestContext)
                .expectNext(DevAgentEvent.message("s1", "ok"))
                .expectNextMatches(e ->
                        e.type() == DevAgentEventType.COMPACTION
                                && e.content().contains("7 条")
                                && e.content().contains("共 4 条")
                                && e.content().contains("2 条原文"))
                .expectNext(DevAgentEvent.done("s1"))
                .verifyComplete();
    }

    @Test
    void ask_whenContextGrew_doesNotEmitCompaction() {
        when(agentStateStore.get(eq("u1"), eq("s1"), eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.of(stateWithMessageCount(2)))
                .thenReturn(Optional.of(stateWithMessageCount(4)));

        TextBlockDeltaEvent d1 = mock(TextBlockDeltaEvent.class);
        when(d1.getType()).thenReturn(AgentEventType.TEXT_BLOCK_DELTA);
        when(d1.getDelta()).thenReturn("ack");
        when(harnessAgent.streamEvents(eq("hi"), any(RuntimeContext.class)))
                .thenReturn(Flux.just(d1));

        StepVerifier.create(service.ask(new DevAgentRequest("u1", "s1", "hi")))
                .expectNext(DevAgentEvent.session("s1"))
                .expectNextMatches(DevAgentServiceTest::isRequestContext)
                .expectNext(DevAgentEvent.message("s1", "ack"))
                .expectNext(DevAgentEvent.done("s1"))
                .verifyComplete();
    }

    @Test
    void ask_emitsSessionMessagesAndDone() {
        TextBlockDeltaEvent d1 = mock(TextBlockDeltaEvent.class);
        TextBlockDeltaEvent d2 = mock(TextBlockDeltaEvent.class);
        when(d1.getType()).thenReturn(AgentEventType.TEXT_BLOCK_DELTA);
        when(d2.getType()).thenReturn(AgentEventType.TEXT_BLOCK_DELTA);
        when(d1.getDelta()).thenReturn("1.");
        when(d2.getDelta()).thenReturn("确认超时时间段");
        when(harnessAgent.streamEvents(eq("帮我整理"), any(RuntimeContext.class)))
                .thenReturn(Flux.just(d1, d2));

        StepVerifier.create(service.ask(new DevAgentRequest("u1", "s1", "帮我整理")))
                .expectNext(DevAgentEvent.session("s1"))
                .expectNextMatches(DevAgentServiceTest::isRequestContext)
                .expectNext(DevAgentEvent.message("s1", "1."))
                .expectNext(DevAgentEvent.message("s1", "确认超时时间段"))
                .expectNext(DevAgentEvent.done("s1"))
                .verifyComplete();

        ArgumentCaptor<RuntimeContext> ctx = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(harnessAgent).streamEvents(eq("帮我整理"), ctx.capture());
        assertThat(ctx.getValue().getSessionId()).isEqualTo("s1");
        assertThat(ctx.getValue().getUserId()).isEqualTo("u1");
    }

    @Test
    void ask_blankApiKey_emitsErrorWithoutCallingAgent() {
        service = new DevAgentService(
                harnessAgent,
                new DevAgentProperties(
                        "dev-task-agent",
                        "prompt",
                        ".",
                        "workspace",
                        new DevAgentProperties.Compaction(6, 2, "请整理会话：{messages}"),
                        new DevAgentProperties.Model("  ", "https://api.deepseek.com", "deepseek-v4-pro")),
                agentStateStore,
                tracer);

        StepVerifier.create(service.ask(new DevAgentRequest(null, "s1", "hi")))
                .expectNext(DevAgentEvent.session("s1"))
                .expectNextMatches(DevAgentServiceTest::isRequestContext)
                .expectNextMatches(e -> e.type() == DevAgentEventType.ERROR && e.content().contains("DEEPSEEK_API_KEY"))
                .verifyComplete();
    }

    @Test
    void ask_streamFailure_emitsError() {
        when(harnessAgent.streamEvents(any(String.class), any(RuntimeContext.class)))
                .thenReturn(Flux.error(new RuntimeException("upstream down")));

        StepVerifier.create(service.ask(new DevAgentRequest("u1", "s1", "hi")))
                .expectNext(DevAgentEvent.session("s1"))
                .expectNextMatches(DevAgentServiceTest::isRequestContext)
                .expectNextMatches(e -> e.type() == DevAgentEventType.ERROR && e.content().contains("upstream down"))
                .verifyComplete();
    }

    @Test
    void ask_mapsToolAndLifecycleEvents() {
        AgentStartEvent agentStart = mock(AgentStartEvent.class);
        when(agentStart.getType()).thenReturn(AgentEventType.AGENT_START);
        when(agentStart.getId()).thenReturn("e-start");

        ToolCallStartEvent toolStart = mock(ToolCallStartEvent.class);
        when(toolStart.getType()).thenReturn(AgentEventType.TOOL_CALL_START);
        when(toolStart.getId()).thenReturn("e-ts");
        when(toolStart.getToolCallId()).thenReturn("call-1");
        when(toolStart.getToolCallName()).thenReturn("read_pom");

        ToolResultEndEvent toolEnd = mock(ToolResultEndEvent.class);
        when(toolEnd.getType()).thenReturn(AgentEventType.TOOL_RESULT_END);
        when(toolEnd.getId()).thenReturn("e-te");
        when(toolEnd.getToolCallId()).thenReturn("call-1");
        when(toolEnd.getToolCallName()).thenReturn("read_pom");
        when(toolEnd.getState()).thenReturn(ToolResultState.SUCCESS);

        TextBlockDeltaEvent delta = mock(TextBlockDeltaEvent.class);
        when(delta.getType()).thenReturn(AgentEventType.TEXT_BLOCK_DELTA);
        when(delta.getDelta()).thenReturn("Java 17");

        AgentResultEvent result = mock(AgentResultEvent.class);
        when(result.getType()).thenReturn(AgentEventType.AGENT_RESULT);
        when(result.getId()).thenReturn("e-res");
        Msg msg = mock(Msg.class);
        when(msg.getTextContent()).thenReturn("Java 17");
        when(result.getResult()).thenReturn(msg);

        when(harnessAgent.streamEvents(eq("问版本"), any(RuntimeContext.class)))
                .thenReturn(Flux.just(agentStart, toolStart, toolEnd, delta, result));

        StepVerifier.create(service.ask(new DevAgentRequest("u1", "s1", "问版本")))
                .expectNext(DevAgentEvent.session("s1"))
                .expectNextMatches(DevAgentServiceTest::isRequestContext)
                .expectNextMatches(e -> e.type() == DevAgentEventType.AGENT_START)
                .expectNext(DevAgentEvent.toolCallStart(
                        "s1", "e-ts", "call-1", "read_pom", "准备调用工具：read_pom"))
                .expectNext(DevAgentEvent.toolResultEnd(
                        "s1", "e-te", "call-1", "read_pom", "SUCCESS"))
                .expectNext(DevAgentEvent.message("s1", "Java 17"))
                .expectNext(DevAgentEvent.agentResult("s1", "e-res", "Java 17"))
                .expectNext(DevAgentEvent.done("s1"))
                .verifyComplete();
    }

    @Test
    void ask_mapsRequireUserConfirm() {
        RequireUserConfirmEvent confirm = mock(RequireUserConfirmEvent.class);
        when(confirm.getType()).thenReturn(AgentEventType.REQUIRE_USER_CONFIRM);
        when(confirm.getId()).thenReturn("e-c");
        ToolUseBlock toolCall = ToolUseBlock.builder()
                .id("call-9")
                .name("request_file_change")
                .input(Map.of(
                        "operation", "create",
                        "path", "notes/a.txt",
                        "content", "x"))
                .build();
        when(confirm.getToolCalls()).thenReturn(List.of(toolCall));

        RequestStopEvent stop = mock(RequestStopEvent.class);
        when(stop.getType()).thenReturn(AgentEventType.REQUEST_STOP);
        when(stop.getId()).thenReturn("e-s");
        when(stop.getGenerateReason()).thenReturn(GenerateReason.PERMISSION_ASKING);

        when(harnessAgent.streamEvents(eq("写文件"), any(RuntimeContext.class)))
                .thenReturn(Flux.just(confirm, stop));

        StepVerifier.create(service.ask(new DevAgentRequest("u1", "s1", "写文件")))
                .expectNext(DevAgentEvent.session("s1"))
                .expectNextMatches(DevAgentServiceTest::isRequestContext)
                .expectNextMatches(e ->
                        e.type() == DevAgentEventType.REQUIRE_USER_CONFIRM
                                && e.pendingToolCalls() != null
                                && e.pendingToolCalls().size() == 1
                                && "call-9".equals(e.pendingToolCalls().get(0).toolCallId()))
                .expectNextMatches(e ->
                        e.type() == DevAgentEventType.REQUEST_STOP
                                && e.content().contains("PERMISSION_ASKING"))
                .expectNext(DevAgentEvent.done("s1"))
                .verifyComplete();
    }

    @Test
    void confirm_withoutPending_emitsError() {
        when(agentStateStore.get(eq("u1"), eq("s-missing"), eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.empty());

        StepVerifier.create(service.confirm(new DevAgentConfirmRequest("u1", "s-missing", true)))
                .expectNext(DevAgentEvent.session("s-missing"))
                .expectNextMatches(DevAgentServiceTest::isRequestContext)
                .expectNextMatches(e ->
                        e.type() == DevAgentEventType.ERROR
                                && e.content().contains("待确认"))
                .verifyComplete();
    }

    @Test
    void confirm_whenStateStoreFails_emitsContextBeforeError() {
        when(agentStateStore.get(
                        eq("u1"),
                        eq("s-db-error"),
                        eq("agent_state"),
                        eq(AgentState.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        StepVerifier.create(service.confirm(
                        new DevAgentConfirmRequest("u1", "s-db-error", true)))
                .expectNext(DevAgentEvent.session("s-db-error"))
                .expectNextMatches(DevAgentServiceTest::isRequestContext)
                .expectNextMatches(e ->
                        e.type() == DevAgentEventType.ERROR
                                && e.content().contains("database unavailable"))
                .verifyComplete();
    }

    @Test
    void confirm_approved_resumesWithConfirmResultsMetadata() {
        ToolUseBlock asking = ToolUseBlock.builder()
                .id("call-9")
                .name("request_file_change")
                .input(Map.of("operation", "create", "path", "notes/a.txt", "content", "x"))
                .state(ToolCallState.ASKING)
                .build();
        Msg assistant = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(asking)
                .build();
        AgentState state = AgentState.builder()
                .userId("u1")
                .sessionId("s1")
                .context(List.of(assistant))
                .build();
        when(agentStateStore.get(eq("u1"), eq("s1"), eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.of(state));

        ToolResultEndEvent toolEnd = mock(ToolResultEndEvent.class);
        when(toolEnd.getType()).thenReturn(AgentEventType.TOOL_RESULT_END);
        when(toolEnd.getId()).thenReturn("e-te");
        when(toolEnd.getToolCallId()).thenReturn("call-9");
        when(toolEnd.getToolCallName()).thenReturn("request_file_change");
        when(toolEnd.getState()).thenReturn(ToolResultState.SUCCESS);
        when(harnessAgent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(Flux.just(toolEnd));

        AtomicReference<DevAgentEvent> emittedContext = new AtomicReference<>();
        StepVerifier.create(service.confirm(new DevAgentConfirmRequest("u1", "s1", true)))
                .expectNext(DevAgentEvent.session("s1"))
                .assertNext(event -> {
                    assertThat(isRequestContext(event)).isTrue();
                    emittedContext.set(event);
                })
                .expectNext(DevAgentEvent.toolResultEnd(
                        "s1", "e-te", "call-9", "request_file_change", "SUCCESS"))
                .expectNext(DevAgentEvent.done("s1"))
                .verifyComplete();

        ArgumentCaptor<Msg> msgCaptor = ArgumentCaptor.forClass(Msg.class);
        ArgumentCaptor<RuntimeContext> contextCaptor =
                ArgumentCaptor.forClass(RuntimeContext.class);
        verify(harnessAgent).streamEvents(msgCaptor.capture(), contextCaptor.capture());
        Msg resume = msgCaptor.getValue();
        assertThat(resume.getTextContent()).isEqualTo("approved");
        @SuppressWarnings("unchecked")
        List<ConfirmResult> results =
                (List<ConfirmResult>) resume.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).isConfirmed()).isTrue();
        assertThat(results.get(0).getToolCall().getId()).isEqualTo("call-9");
        AgentExecutionContext runtimeIds =
                AgentExecutionContext.from(contextCaptor.getValue());
        assertThat(runtimeIds).isEqualTo(new AgentExecutionContext(
                emittedContext.get().requestId(),
                emittedContext.get().traceId(),
                emittedContext.get().spanId()));
    }

    @Test
    void askAndConfirm_generateDifferentRequestIdsForSameSession() {
        ToolUseBlock asking = ToolUseBlock.builder()
                .id("call-10")
                .name("request_file_change")
                .input(Map.of("operation", "create", "path", "notes/b.txt", "content", "x"))
                .state(ToolCallState.ASKING)
                .build();
        AgentState state = AgentState.builder()
                .userId("u1")
                .sessionId("s1")
                .context(List.of(Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(asking)
                        .build()))
                .build();
        when(agentStateStore.get(eq("u1"), eq("s1"), eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.of(state));
        when(harnessAgent.streamEvents(eq("hi"), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());
        when(harnessAgent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());

        List<DevAgentEvent> askEvents =
                service.ask(new DevAgentRequest("u1", "s1", "hi"))
                        .collectList()
                        .block();
        List<DevAgentEvent> confirmEvents =
                service.confirm(new DevAgentConfirmRequest("u1", "s1", true))
                        .collectList()
                        .block();

        DevAgentEvent askContext = askEvents.stream()
                .filter(e -> e.type() == DevAgentEventType.REQUEST_CONTEXT)
                .findFirst()
                .orElseThrow();
        DevAgentEvent confirmContext = confirmEvents.stream()
                .filter(e -> e.type() == DevAgentEventType.REQUEST_CONTEXT)
                .findFirst()
                .orElseThrow();

        assertThat(askContext.sessionId()).isEqualTo("s1");
        assertThat(confirmContext.sessionId()).isEqualTo("s1");
        assertThat(confirmContext.requestId()).isNotEqualTo(askContext.requestId());

        ArgumentCaptor<RuntimeContext> askRuntime =
                ArgumentCaptor.forClass(RuntimeContext.class);
        verify(harnessAgent).streamEvents(eq("hi"), askRuntime.capture());
        ArgumentCaptor<RuntimeContext> confirmRuntime =
                ArgumentCaptor.forClass(RuntimeContext.class);
        verify(harnessAgent).streamEvents(any(Msg.class), confirmRuntime.capture());
        assertThat(AgentExecutionContext.from(askRuntime.getValue()).requestId())
                .isEqualTo(askContext.requestId());
        assertThat(AgentExecutionContext.from(confirmRuntime.getValue()).requestId())
                .isEqualTo(confirmContext.requestId());
    }
}
