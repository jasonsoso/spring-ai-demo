package com.jason.demo.demo2.agentscope.service;

import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import com.jason.demo.demo2.agentscope.model.DevAgentEvent;
import com.jason.demo.demo2.agentscope.model.DevAgentRequest;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
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
                new DevAgentProperties.Model("sk-test", "https://api.deepseek.com", "deepseek-v4-pro"));
        service = new DevAgentService(harnessAgent, properties);
    }

    @Test
    void ask_emitsSessionMessagesAndDone() {
        TextBlockDeltaEvent d1 = mock(TextBlockDeltaEvent.class);
        TextBlockDeltaEvent d2 = mock(TextBlockDeltaEvent.class);
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
                        new DevAgentProperties.Model("  ", "https://api.deepseek.com", "deepseek-v4-pro")));

        StepVerifier.create(service.ask(new DevAgentRequest(null, "s1", "hi")))
                .expectNext(DevAgentEvent.session("s1"))
                .expectNextMatches(e -> "ERROR".equals(e.type()) && e.content().contains("DEEPSEEK_API_KEY"))
                .verifyComplete();
    }

    @Test
    void ask_streamFailure_emitsError() {
        when(harnessAgent.streamEvents(any(String.class), any(RuntimeContext.class)))
                .thenReturn(Flux.error(new RuntimeException("upstream down")));

        StepVerifier.create(service.ask(new DevAgentRequest("u1", "s1", "hi")))
                .expectNext(DevAgentEvent.session("s1"))
                .expectNextMatches(e -> "ERROR".equals(e.type()) && e.content().contains("upstream down"))
                .verifyComplete();
    }
}
