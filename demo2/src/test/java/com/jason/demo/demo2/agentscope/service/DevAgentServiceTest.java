package com.jason.demo.demo2.agentscope.service;

import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import com.jason.demo.demo2.agentscope.model.DevAgentEvent;
import com.jason.demo.demo2.agentscope.model.DevAgentEventType;
import com.jason.demo.demo2.agentscope.model.DevAgentRequest;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DevAgentServiceTest {

    @Mock
    HarnessAgent harnessAgent;

    DevAgentProperties properties;
    DevAgentService service;

    @BeforeEach
    void setUp() {
        properties = new DevAgentProperties(
                "dev-task-agent",
                "prompt",
                ".",
                new DevAgentProperties.Model("sk-test", "https://api.deepseek.com", "deepseek-v4-pro"));
        service = new DevAgentService(harnessAgent, properties);
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
                        new DevAgentProperties.Model("  ", "https://api.deepseek.com", "deepseek-v4-pro")));

        StepVerifier.create(service.ask(new DevAgentRequest(null, "s1", "hi")))
                .expectNext(DevAgentEvent.session("s1"))
                .expectNextMatches(e -> e.type() == DevAgentEventType.ERROR && e.content().contains("DEEPSEEK_API_KEY"))
                .verifyComplete();
    }

    @Test
    void ask_streamFailure_emitsError() {
        when(harnessAgent.streamEvents(any(String.class), any(RuntimeContext.class)))
                .thenReturn(Flux.error(new RuntimeException("upstream down")));

        StepVerifier.create(service.ask(new DevAgentRequest("u1", "s1", "hi")))
                .expectNext(DevAgentEvent.session("s1"))
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
}
