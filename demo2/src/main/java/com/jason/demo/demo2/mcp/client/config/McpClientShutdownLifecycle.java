package com.jason.demo.demo2.mcp.client.config;

import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在 WebServer 优雅关闭之前同步关闭 MCP Client。
 * <p>
 * Spring AI 默认在 bean 销毁阶段调用 {@link McpSyncClient#close()}（异步），
 * 此时嵌入式 Tomcat 已停止，同 JVM 的 local-server 会收到 ConnectException，
 * 并被 MCP SDK 打成 {@code McpTransport - Error during asynchronous close} WARN。
 * 提前用 {@link McpSyncClient#closeGracefully()} 关闭可避免该噪音。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.ai.mcp.client.enabled", havingValue = "true", matchIfMissing = true)
public class McpClientShutdownLifecycle implements SmartLifecycle {

    private final List<McpSyncClient> mcpSyncClients;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public McpClientShutdownLifecycle(List<McpSyncClient> mcpSyncClients) {
        this.mcpSyncClients = mcpSyncClients;
    }

    @Override
    public void start() {
        running.set(true);
    }

    @Override
    public void stop() {
        stop(() -> {
        });
    }

    @Override
    public void stop(Runnable callback) {
        try {
            closeClientsOnce();
        } finally {
            running.set(false);
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        // 更高 phase 在 shutdown 时更早 stop，确保 DELETE session 时 Tomcat 仍可接受请求
        return WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE + 1;
    }

    private void closeClientsOnce() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (mcpSyncClients.isEmpty()) {
            return;
        }
        log.info("[MCP Client] 应用关闭前优雅关闭 {} 个 MCP Client...", mcpSyncClients.size());
        for (McpSyncClient client : mcpSyncClients) {
            try {
                client.closeGracefully();
            } catch (RuntimeException ex) {
                log.debug("[MCP Client] 关闭时忽略异常: {}", ex.toString());
            }
        }
    }
}
