package com.jason.demo.demo2.mcp.client.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * MCP Client 延迟初始化器。
 * local-server 由 {@link com.jason.demo.demo2.mcp.client.controller.McpChatController} Order(2) 同步初始化；
 * 远程 MCP 在本类 Order(LOWEST) 后台最后尝试，失败不阻塞启动。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.mcp.client.init-on-startup", havingValue = "true", matchIfMissing = true)
public class McpClientInitializer {

    private final McpClientLifecycle mcpClientLifecycle;

    public McpClientInitializer(McpClientLifecycle mcpClientLifecycle) {
        this.mcpClientLifecycle = mcpClientLifecycle;
    }

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyInitRemote() {
        mcpClientLifecycle.initializeRemoteConnectionsAsync();
    }
}
