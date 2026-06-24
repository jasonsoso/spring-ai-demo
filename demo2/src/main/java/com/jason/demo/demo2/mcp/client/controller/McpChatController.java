package com.jason.demo.demo2.mcp.client.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.*;

/**
 * MCP Client 聊天控制器
 * 通过 MCP 协议获取 Server 端工具，结合 DeepSeek 大模型实现智能对话
 * Embedding 使用智谱 embedding-2，Chat 使用 DeepSeek
 */
@Slf4j
@Tag(name = "MCP Client 聊天", description = "通过 MCP 协议调用 Server 端工具 + DeepSeek 对话")
@RestController
@RequestMapping("/mcp/client")
public class McpChatController {

    private final ChatClient.Builder chatClientBuilder;
    private final SyncMcpToolCallbackProvider mcpToolCallbackProvider;

    /**
     * 延迟构建，等 MCP Client 初始化完成后（ApplicationReadyEvent Order(2)）再组装
     */
    private volatile ChatClient chatClient;

    public McpChatController(ChatClient.Builder chatClientBuilder,
                              SyncMcpToolCallbackProvider mcpToolCallbackProvider) {
        this.chatClientBuilder = chatClientBuilder;
        this.mcpToolCallbackProvider = mcpToolCallbackProvider;
    }

    /**
     * Order(2) 在 McpClientInitializer（Order(1)）初始化完 Client 后再构建 ChatClient，
     * 确保 MCP 工具已可用
     */
    @Order(2)
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        try {
            this.chatClient = chatClientBuilder
                    .defaultTools(mcpToolCallbackProvider)
                    .build();
            log.info("[MCP Client] ChatClient 构建完成");
        } catch (Exception e) {
            log.warn("[MCP Client] ChatClient 构建失败，MCP 工具暂不可用: {}", e.getMessage());
        }
    }

    @Operation(summary = "MCP 工具调用聊天（DeepSeek 模型 + 智谱 Embedding）",
               description = "通过 MCP 协议调用 Server 端天气/景点工具，Chat 走 DeepSeek，Embedding 走智谱 embedding-2")
    @GetMapping("/chat")
    public String chat(@RequestParam String message) {
        log.debug("[MCP Client] 收到请求, message={}", message);
        if (chatClient == null) {
            return "MCP Client 尚未初始化，请稍后重试";
        }
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }

    @Operation(summary = "查看当前可用的 MCP 工具列表")
    @GetMapping("/tools")
    public java.util.List<String> listTools() {
        return java.util.Arrays.stream(mcpToolCallbackProvider.getToolCallbacks())
                .map(t -> t.getToolDefinition().name() + " - " + t.getToolDefinition().description())
                .toList();
    }
}
