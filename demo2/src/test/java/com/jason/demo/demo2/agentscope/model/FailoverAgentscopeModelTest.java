package com.jason.demo.demo2.agentscope.model;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FailoverAgentscopeModelTest {

    @Test
    void primarySuccess_doesNotCallFallback() {
        Model primary = mock(Model.class);
        Model fallback = mock(Model.class);
        when(primary.getModelName()).thenReturn("deepseek-v4-pro");
        when(fallback.getModelName()).thenReturn("kimi-k3");
        when(primary.stream(anyList(), any(), any()))
                .thenReturn(Flux.just(chunk("ok")));

        FailoverAgentscopeModel model = new FailoverAgentscopeModel(primary, fallback, 2);

        StepVerifier.create(model.stream(List.of(), List.of(), null))
                .expectNextCount(1)
                .verifyComplete();

        verify(fallback, never()).stream(anyList(), any(), any());
        assertThat(model.getModelName()).isEqualTo("deepseek-v4-pro");
    }

    @Test
    void primaryFailsBeforeChunk_thenFallbackSucceeds() {
        Model primary = mock(Model.class);
        Model fallback = mock(Model.class);
        when(primary.getModelName()).thenReturn("deepseek-v4-pro");
        when(fallback.getModelName()).thenReturn("kimi-k3");
        when(primary.stream(anyList(), any(), any()))
                .thenReturn(Flux.error(new RuntimeException("down")));
        when(fallback.stream(anyList(), any(), any()))
                .thenReturn(Flux.just(chunk("from-kimi")));

        FailoverAgentscopeModel model = new FailoverAgentscopeModel(primary, fallback, 2);

        StepVerifier.create(model.stream(List.of(), List.of(), null))
                .assertNext(r -> assertThat(r.getContent()).isNotEmpty())
                .verifyComplete();

        verify(primary, times(2)).stream(anyList(), any(), any());
        verify(fallback, times(1)).stream(anyList(), any(), any());
        assertThat(model.getModelName()).isEqualTo("kimi-k3");
    }

    @Test
    void midStreamFailure_doesNotSwitchToFallback() {
        Model primary = mock(Model.class);
        Model fallback = mock(Model.class);
        when(primary.getModelName()).thenReturn("deepseek-v4-pro");
        when(fallback.getModelName()).thenReturn("kimi-k3");
        when(primary.stream(anyList(), any(), any()))
                .thenReturn(Flux.concat(
                        Flux.just(chunk("partial")),
                        Flux.error(new RuntimeException("cut"))));

        FailoverAgentscopeModel model = new FailoverAgentscopeModel(primary, fallback, 2);

        StepVerifier.create(model.stream(List.of(), List.of(), null))
                .expectNextCount(1)
                .verifyErrorMessage("cut");

        verify(fallback, never()).stream(anyList(), any(), any());
    }

    @Test
    void nullFallback_retriesPrimaryOnlyThenFails() {
        Model primary = mock(Model.class);
        when(primary.getModelName()).thenReturn("deepseek-v4-pro");
        AtomicInteger calls = new AtomicInteger();
        when(primary.stream(anyList(), any(), any())).thenAnswer(inv -> {
            calls.incrementAndGet();
            return Flux.error(new RuntimeException("still-down"));
        });

        FailoverAgentscopeModel model = new FailoverAgentscopeModel(primary, null, 2);

        StepVerifier.create(model.stream(List.of(), List.of(), null))
                .verifyErrorMessage("still-down");

        assertThat(calls.get()).isEqualTo(2);
    }

    private static ChatResponse chunk(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }
}
