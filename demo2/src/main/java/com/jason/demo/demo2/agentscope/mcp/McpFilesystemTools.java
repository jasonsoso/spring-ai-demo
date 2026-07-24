package com.jason.demo.demo2.agentscope.mcp;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 以 AgentScope {@code @Tool} 暴露 MCP filesystem 白名单工具（SDK 2.0），
 * 与 {@code ProjectInfoTools} 同一注册路径，避免 {@code ToolBase}/{@code McpClientBuilder}。
 */
public final class McpFilesystemTools {

    private final McpSyncClient client;
    private final Set<String> enabledTools;

    public McpFilesystemTools(McpSyncClient client, Set<String> enabledTools) {
        this.client = Objects.requireNonNull(client, "client");
        this.enabledTools = Set.copyOf(enabledTools);
    }

    @Tool(
            name = "list_allowed_directories",
            description = "Returns the list of directories that this MCP filesystem server is allowed to access.",
            readOnly = true)
    public String listAllowedDirectories() {
        return call("list_allowed_directories", Map.of());
    }

    @Tool(
            name = "list_directory",
            description = "List files and directories under an allowed MCP path. Prefer the MCP root path from the system prompt.",
            readOnly = true)
    public String listDirectory(
            @ToolParam(name = "path", description = "Absolute or relative path within the allowed MCP root")
            String path) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (path != null && !path.isBlank()) {
            args.put("path", path);
        }
        return call("list_directory", args);
    }

    @Tool(
            name = "read_text_file",
            description = "Read a text file within the allowed MCP directories.",
            readOnly = true)
    public String readTextFile(
            @ToolParam(name = "path", description = "Absolute or relative file path within the allowed MCP root")
            String path,
            @ToolParam(name = "head", description = "Optional: return only the first N lines", required = false)
            Integer head,
            @ToolParam(name = "tail", description = "Optional: return only the last N lines", required = false)
            Integer tail) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (path != null && !path.isBlank()) {
            args.put("path", path);
        }
        if (head != null) {
            args.put("head", head);
        }
        if (tail != null) {
            args.put("tail", tail);
        }
        return call("read_text_file", args);
    }

    private String call(String toolName, Map<String, Object> arguments) {
        if (!enabledTools.contains(toolName)) {
            return "Error: tool '" + toolName + "' is not enabled for this MCP client";
        }
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest(toolName, arguments));
        String text = toText(result);
        if (Boolean.TRUE.equals(result.isError())) {
            return text == null || text.isBlank() ? "Error: MCP tool failed" : text;
        }
        return text == null ? "" : text;
    }

    private static String toText(McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) {
            return result.structuredContent() == null ? "" : String.valueOf(result.structuredContent());
        }
        return result.content().stream()
                .map(content -> {
                    if (content instanceof McpSchema.TextContent text) {
                        return text.text() == null ? "" : text.text();
                    }
                    return String.valueOf(content);
                })
                .collect(Collectors.joining("\n"));
    }
}
