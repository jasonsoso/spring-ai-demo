package com.jason.demo.demo2.mcp.client.config;

import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class McpClientShutdownLifecycleTest {

    @Test
    void phaseIsBeforeWebServerGracefulShutdown() {
        McpClientShutdownLifecycle lifecycle = new McpClientShutdownLifecycle(List.of());

        assertThat(lifecycle.getPhase()).isGreaterThan(WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE);
    }

    @Test
    void stopClosesClientsGracefullyOnce() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.closeGracefully()).thenReturn(true);
        McpClientShutdownLifecycle lifecycle = new McpClientShutdownLifecycle(List.of(client));
        lifecycle.start();

        lifecycle.stop();
        lifecycle.stop();

        verify(client).closeGracefully();
        verifyNoMoreInteractions(client);
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void stopSwallowsClientCloseFailures() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.closeGracefully()).thenThrow(new IllegalStateException("already closing"));
        AtomicInteger callbackCount = new AtomicInteger();
        McpClientShutdownLifecycle lifecycle = new McpClientShutdownLifecycle(List.of(client));
        lifecycle.start();

        lifecycle.stop(callbackCount::incrementAndGet);

        verify(client).closeGracefully();
        assertThat(callbackCount.get()).isEqualTo(1);
        assertThat(lifecycle.isRunning()).isFalse();
    }
}
