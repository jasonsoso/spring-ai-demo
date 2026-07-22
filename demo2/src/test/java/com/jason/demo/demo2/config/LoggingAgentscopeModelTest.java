package com.jason.demo.demo2.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoggingAgentscopeModelTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;
    private Level previousLevel;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(LoggingAgentscopeModel.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    @Test
    void stream_logsRequestWithoutApiKey_andAggregatedResponse() {
        Model delegate = mock(Model.class);
        when(delegate.getModelName()).thenReturn("deepseek-v4-pro");
        when(delegate.stream(anyList(), anyList(), any())).thenReturn(Flux.just(
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("Hel").build()))
                        .build(),
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("lo").build()))
                        .finishReason("stop")
                        .usage(new ChatUsage(10, 2, 0.1))
                        .build()));

        LoggingAgentscopeModel model = new LoggingAgentscopeModel(delegate, "agentscope-deepseek");

        Msg user = Msg.builder().role(MsgRole.USER).textContent("hi").build();
        ToolSchema tool = ToolSchema.builder()
                .name("read_pom")
                .description("read pom")
                .parameters(Map.of("type", "object"))
                .build();
        GenerateOptions options = GenerateOptions.builder()
                .apiKey("sk-secret")
                .temperature(0.2)
                .stream(true)
                .additionalHeader("Authorization", "Bearer sk-secret")
                .build();

        StepVerifier.create(model.stream(List.of(user), List.of(tool), options))
                .expectNextCount(2)
                .verifyComplete();

        String joined = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(joined).contains("LLM request [agentscope-deepseek]");
        assertThat(joined).contains("USER");
        assertThat(joined).contains("hi");
        assertThat(joined).contains("read_pom");
        assertThat(joined).doesNotContain("sk-secret");
        assertThat(joined).contains("***");
        assertThat(joined).contains("LLM response [agentscope-deepseek]");
        assertThat(joined).contains("Hello");
        assertThat(joined).contains("stop");
    }

    @Test
    void stream_onError_warnsAndDoesNotLogCompleteResponse() {
        Model delegate = mock(Model.class);
        when(delegate.getModelName()).thenReturn("deepseek-v4-pro");
        when(delegate.stream(anyList(), anyList(), any()))
                .thenReturn(Flux.error(new RuntimeException("boom")));

        LoggingAgentscopeModel model = new LoggingAgentscopeModel(delegate, "agentscope-deepseek");

        StepVerifier.create(model.stream(List.of(), List.of(), GenerateOptions.builder().build()))
                .expectErrorMessage("boom")
                .verify();

        String joined = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(joined).contains("LLM request [agentscope-deepseek]");
        assertThat(joined).doesNotContain("LLM response [agentscope-deepseek]");
        assertThat(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN)).isTrue();
    }

    @Test
    void stream_passthrough_preservesChunks() {
        Model delegate = mock(Model.class);
        when(delegate.getModelName()).thenReturn("m");
        ChatResponse chunk = ChatResponse.builder()
                .content(List.of(TextBlock.builder().text("x").build()))
                .build();
        when(delegate.stream(anyList(), anyList(), any())).thenReturn(Flux.just(chunk));

        AtomicReference<ChatResponse> seen = new AtomicReference<>();
        new LoggingAgentscopeModel(delegate, "t")
                .stream(List.of(), List.of(), GenerateOptions.builder().build())
                .doOnNext(seen::set)
                .blockLast();

        assertThat(seen.get()).isSameAs(chunk);
    }
}
