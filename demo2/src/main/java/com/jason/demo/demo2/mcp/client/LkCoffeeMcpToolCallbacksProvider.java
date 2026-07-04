package com.jason.demo.demo2.mcp.client;

import com.jason.demo.demo2.mcp.client.config.LkCoffeeMcpConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 延迟加载瑞幸/高德 MCP 工具，避免在 Spring 上下文刷新阶段调用 listTools
 * （须等 {@link com.jason.demo.demo2.mcp.client.config.McpClientInitializer} 完成初始化）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.lkcoffee.enabled", havingValue = "true", matchIfMissing = true)
public class LkCoffeeMcpToolCallbacksProvider {

    private final SyncMcpToolCallbackProvider mcpToolCallbackProvider;
    private volatile ToolCallback[] cached;

    public LkCoffeeMcpToolCallbacksProvider(SyncMcpToolCallbackProvider mcpToolCallbackProvider) {
        this.mcpToolCallbackProvider = mcpToolCallbackProvider;
    }

    public ToolCallback[] getToolCallbacks() {
        ToolCallback[] result = cached;
        if (result != null) {
            return result;
        }
        synchronized (this) {
            result = cached;
            if (result == null) {
                result = Arrays.stream(mcpToolCallbackProvider.getToolCallbacks())
                        .filter(tc -> LkCoffeeMcpConfig.isAllowedTool(tc.getToolDefinition().name()))
                        .map(LkCoffeeToolCallbackWrapper::new)
                        .toArray(ToolCallback[]::new);
                cached = result;
                log.info("[LkCoffee] 已加载 {} 个 MCP 工具", result.length);
            }
            return result;
        }
    }
}
