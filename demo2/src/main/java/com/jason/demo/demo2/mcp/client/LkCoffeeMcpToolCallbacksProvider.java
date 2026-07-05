package com.jason.demo.demo2.mcp.client;

import com.jason.demo.demo2.mcp.client.config.LkCoffeeMcpConfig;
import com.jason.demo.demo2.mcp.client.config.McpClientLifecycle;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 延迟加载瑞幸/高德 MCP 工具，避免在 Spring 上下文刷新阶段调用 listTools
 * （须等 {@link com.jason.demo.demo2.mcp.client.config.McpClientInitializer} 完成初始化）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.lkcoffee.enabled", havingValue = "true", matchIfMissing = true)
public class LkCoffeeMcpToolCallbacksProvider {

    private static final Set<String> REMOTE_CONNECTIONS = Set.of(
            McpConnection.LKCOFFEE.getConnectionName(),
            McpConnection.AMAP.getConnectionName());

    private final List<McpSyncClient> mcpSyncClients;
    private final McpClientLifecycle mcpClientLifecycle;
    private volatile ToolCallback[] cached;

    public LkCoffeeMcpToolCallbacksProvider(
            List<McpSyncClient> mcpSyncClients,
            McpClientLifecycle mcpClientLifecycle) {
        this.mcpSyncClients = mcpSyncClients;
        this.mcpClientLifecycle = mcpClientLifecycle;
    }

    public ToolCallback[] getToolCallbacks() {
        mcpClientLifecycle.ensureLkCoffeeAndAmapIfConfigured();
        ToolCallback[] result = cached;
        if (result != null) {
            return result;
        }
        synchronized (this) {
            result = cached;
            if (result == null) {
                result = mcpSyncClients.stream()
                        .filter(this::isRemoteConnection)
                        .flatMap(client -> client.listTools().tools().stream()
                                .filter(tool -> LkCoffeeMcpConfig.isAllowedTool(tool.name()))
                                .map(tool -> toToolCallback(client, tool)))
                        .toArray(ToolCallback[]::new);
                cached = result;
                log.info("[LkCoffee] 已加载 {} 个 MCP 工具", result.length);
            }
            return result;
        }
    }

    private boolean isRemoteConnection(McpSyncClient client) {
        if (client.getClientInfo() == null) {
            return false;
        }
        String title = client.getClientInfo().title();
        if (title != null && REMOTE_CONNECTIONS.contains(title)) {
            return true;
        }
        String name = client.getClientInfo().name();
        return name != null && REMOTE_CONNECTIONS.stream().anyMatch(name::contains);
    }

    private static ToolCallback toToolCallback(McpSyncClient client, McpSchema.Tool tool) {
        return new LkCoffeeToolCallbackWrapper(SyncMcpToolCallback.builder()
                .mcpClient(client)
                .tool(tool)
                .prefixedToolName(tool.name())
                .build());
    }
}
