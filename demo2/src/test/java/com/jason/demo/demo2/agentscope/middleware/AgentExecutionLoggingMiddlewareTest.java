package com.jason.demo.demo2.agentscope.middleware;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jason.demo.demo2.agentscope.observability.AgentExecutionContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentExecutionLoggingMiddlewareTest {

    private Logger logger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;
    private AgentExecutionLoggingMiddleware middleware;
    private Agent agent;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(AgentExecutionLoggingMiddleware.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        middleware = new AgentExecutionLoggingMiddleware();
        agent = mock(Agent.class);
        when(agent.getName()).thenReturn("test-agent");
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
        appender.stop();
    }

    @Test
    void onAgent_forwardsEventsAndCountsOnlyTextDeltas() {
        TextBlockDeltaEvent delta = mock(TextBlockDeltaEvent.class);
        when(delta.getDelta()).thenReturn("hello");

        StepVerifier.create(middleware.onAgent(
                        agent,
                        runtime(),
                        new AgentInput(List.of()),
                        ignored -> Flux.just(delta)))
                .assertNext(event -> assertThat(event).isSameAs(delta))
                .verifyComplete();

        assertThat(logs())
                .contains("requestId=request-1")
                .contains("traceId=trace-1")
                .contains("spanId=span-1")
                .contains("answerChars=5");
        assertThat(countOccurrences(logs(), "Agent execution completed.")).isEqualTo(1);
    }

    @Test
    void onReasoning_incrementsRound() {
        RuntimeContext runtime = runtime();
        ReasoningInput input = new ReasoningInput(List.of(), List.of(), null);

        middleware.onReasoning(agent, runtime, input, ignored -> Flux.empty()).blockLast();
        middleware.onReasoning(agent, runtime, input, ignored -> Flux.empty()).blockLast();

        assertThat(logs()).contains("round=1").contains("round=2");
    }

    @Test
    void onModelCall_logsUnknownUsageAsDash() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("test-model");
        ModelCallInput input = new ModelCallInput(List.of(), List.of(), null, model);

        middleware.onModelCall(agent, runtime(), input, ignored -> Flux.empty()).blockLast();

        assertThat(logs())
                .contains("model=test-model")
                .contains("inputTokens=-")
                .contains("outputTokens=-");
    }

    @Test
    void onModelCall_logsUsageFromEndEvent() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("test-model");
        ModelCallInput input = new ModelCallInput(List.of(), List.of(), null, model);
        ModelCallEndEvent end = mock(ModelCallEndEvent.class);
        when(end.getUsage()).thenReturn(new ChatUsage(10, 4, 0.0));

        middleware.onModelCall(agent, runtime(), input, ignored -> Flux.just(end)).blockLast();

        assertThat(logs()).contains("inputTokens=10").contains("outputTokens=4");
    }

    @Test
    void onActing_logsToolMetadataWithoutInput() {
        ToolUseBlock toolCall = ToolUseBlock.builder()
                .id("call-1")
                .name("read_pom")
                .input(Map.of("value", "secret"))
                .build();
        ToolResultEndEvent result = mock(ToolResultEndEvent.class);
        when(result.getToolCallName()).thenReturn("read_pom");
        when(result.getToolCallId()).thenReturn("call-1");
        when(result.getState()).thenReturn(ToolResultState.SUCCESS);

        middleware.onActing(
                        agent,
                        runtime(),
                        new ActingInput(List.of(toolCall)),
                        ignored -> Flux.just(result))
                .blockLast();

        assertThat(logs())
                .contains("tool=read_pom")
                .contains("toolCallId=call-1")
                .contains("state=SUCCESS")
                .doesNotContain("secret");
    }

    @Test
    void onAgent_errorIsPropagatedAndLoggedWithoutMessage() {
        IllegalStateException failure = new IllegalStateException("sensitive");

        StepVerifier.create(middleware.onAgent(
                        agent,
                        runtime(),
                        new AgentInput(List.of()),
                        ignored -> Flux.error(failure)))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
                .verify();

        assertThat(logs())
                .contains("errorType=IllegalStateException")
                .contains("state=ERROR")
                .doesNotContain("sensitive");
        assertThat(countOccurrences(logs(), "Agent execution failed.")).isEqualTo(1);
    }

    @Test
    void onAgent_cancelIsLogged() {
        Disposable subscription = middleware.onAgent(
                        agent,
                        runtime(),
                        new AgentInput(List.of()),
                        ignored -> Flux.never())
                .subscribe();

        subscription.dispose();

        assertThat(logs()).contains("state=CANCELLED");
        assertThat(countOccurrences(logs(), "Agent execution cancelled.")).isEqualTo(1);
    }

    @Test
    void synchronousNextFailure_isConvertedAndLoggedOnce() {
        StepVerifier.create(middleware.onAgent(
                        agent,
                        runtime(),
                        new AgentInput(List.of()),
                        ignored -> {
                            throw new IllegalArgumentException("sensitive");
                        }))
                .expectError(IllegalArgumentException.class)
                .verify();

        assertThat(countOccurrences(logs(), "Agent execution failed.")).isEqualTo(1);
        assertThat(logs())
                .contains("errorType=IllegalArgumentException")
                .doesNotContain("sensitive");
    }

    @Test
    void reasoningModelAndActing_failuresArePropagatedAndLoggedOnce() {
        IllegalStateException failure = new IllegalStateException("hidden");
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("test-model");

        StepVerifier.create(middleware.onReasoning(
                        agent,
                        runtime(),
                        new ReasoningInput(List.of(), List.of(), null),
                        ignored -> Flux.error(failure)))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
                .verify();
        StepVerifier.create(middleware.onModelCall(
                        agent,
                        runtime(),
                        new ModelCallInput(List.of(), List.of(), null, model),
                        ignored -> Flux.error(failure)))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
                .verify();
        StepVerifier.create(middleware.onActing(
                        agent,
                        runtime(),
                        new ActingInput(List.of()),
                        ignored -> Flux.error(failure)))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
                .verify();

        assertThat(countOccurrences(logs(), "Reasoning failed.")).isEqualTo(1);
        assertThat(countOccurrences(logs(), "Model call failed.")).isEqualTo(1);
        assertThat(countOccurrences(logs(), "Tool execution failed.")).isEqualTo(1);
        assertThat(logs()).doesNotContain("hidden");
    }

    @Test
    void reasoningModelAndActing_cancellationsLogOneTerminalEach() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("test-model");

        Disposable reasoning = middleware.onReasoning(
                        agent,
                        runtime(),
                        new ReasoningInput(List.of(), List.of(), null),
                        ignored -> Flux.never())
                .subscribe();
        Disposable modelCall = middleware.onModelCall(
                        agent,
                        runtime(),
                        new ModelCallInput(List.of(), List.of(), null, model),
                        ignored -> Flux.never())
                .subscribe();
        Disposable acting = middleware.onActing(
                        agent,
                        runtime(),
                        new ActingInput(List.of()),
                        ignored -> Flux.never())
                .subscribe();

        reasoning.dispose();
        modelCall.dispose();
        acting.dispose();

        assertThat(countOccurrences(logs(), "Reasoning cancelled.")).isEqualTo(1);
        assertThat(countOccurrences(logs(), "Model call cancelled.")).isEqualTo(1);
        assertThat(countOccurrences(logs(), "Tool execution cancelled.")).isEqualTo(1);
    }

    @Test
    void onSystemPrompt_returnsOriginalTextWithoutLoggingIt() {
        String prompt = "private prompt";

        String result = middleware.onSystemPrompt(agent, runtime(), prompt).block();

        assertThat(result).isEqualTo(prompt);
        assertThat(logs())
                .contains("promptChars=" + prompt.length())
                .doesNotContain(prompt);
    }

    private RuntimeContext runtime() {
        RuntimeContext runtime = RuntimeContext.builder()
                .userId("u1")
                .sessionId("s1")
                .build();
        new AgentExecutionContext("request-1", "trace-1", "span-1").writeTo(runtime);
        return runtime;
    }

    private String logs() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private static int countOccurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }
}
