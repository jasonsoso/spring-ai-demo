package com.jason.demo.demo.mcp.client.config;

import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

import java.util.List;

/**
 * MCP Client 延迟初始化器
 * 同一 JVM 中 Server 和 Client 共存时，需等 Tomcat 完全启动后再初始化 Client，
 * 否则 SSE 连接会因服务端尚未就绪而失败。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.mcp.client.init-on-startup", havingValue = "true", matchIfMissing = true)
public class McpClientInitializer {

    private final List<McpSyncClient> mcpSyncClients;

    public McpClientInitializer(List<McpSyncClient> mcpSyncClients) {
        this.mcpSyncClients = mcpSyncClients;
    }

    /**
     * Order(1) 确保在 McpChatController.init()（Order(2)）之前执行
     */
    @Order(1)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("[MCP Client] 应用就绪，开始初始化 {} 个 MCP Client...", mcpSyncClients.size());
        for (McpSyncClient client : mcpSyncClients) {
            try {
                client.initialize();
                log.info("[MCP Client] 初始化成功，服务端工具列表: {}",
                        client.listTools().tools().stream()
                                .map(t -> t.name())
                                .toList());
            } catch (Exception e) {
                log.error("[MCP Client] 初始化失败", e);
            }
        }
        log.info("[MCP Client] 全部 MCP Client 初始化完成");
    }
}
