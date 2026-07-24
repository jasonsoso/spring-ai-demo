package com.jason.demo.demo2.agentscope.mcp;

import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentscopeMcpClientRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void create_whenDisabled_returnsEmptyWithoutClients() {
        DevAgentProperties props = props(false, List.of(
                new DevAgentProperties.McpClientConfig(
                        "project-files",
                        true,
                        "npx",
                        List.of("-y", "@modelcontextprotocol/server-filesystem@2026.7.10"),
                        "mcp-files",
                        List.of("list_directory"))));

        try (AgentscopeMcpClientRegistry registry = AgentscopeMcpClientRegistry.create(props)) {
            assertThat(registry.entries()).isEmpty();
        }
    }

    @Test
    void resolveRoot_relativeUsesProjectRoot() {
        Path root = AgentscopeMcpClientRegistry.resolveRoot(tempDir.toString(), "mcp-files");
        assertThat(root).isEqualTo(tempDir.resolve("mcp-files").toAbsolutePath().normalize());
    }

    @Test
    void resolveRoot_absoluteKeepsNormalized() {
        Path abs = tempDir.resolve("abs-root").toAbsolutePath();
        Path root = AgentscopeMcpClientRegistry.resolveRoot(tempDir.toString(), abs.toString());
        assertThat(root).isEqualTo(abs.normalize());
    }

    @Test
    void primaryMcpRootDisplay_whenDisabled_returnsPlaceholder() {
        DevAgentProperties props = props(false, List.of());
        assertThat(AgentscopeMcpClientRegistry.primaryMcpRootDisplay(props))
                .isEqualTo("(MCP 未启用)");
    }

    @Test
    void primaryMcpRootDisplay_usesFirstEnabledClientWithRoot() {
        DevAgentProperties props = props(true, List.of(
                new DevAgentProperties.McpClientConfig(
                        "other", true, "echo", List.of("x"), null, List.of("t")),
                new DevAgentProperties.McpClientConfig(
                        "project-files",
                        true,
                        "npx",
                        List.of("-y"),
                        "mcp-files",
                        List.of("list_directory"))));

        String display = AgentscopeMcpClientRegistry.primaryMcpRootDisplay(props);
        assertThat(display).isEqualTo(
                tempDir.resolve("mcp-files").toAbsolutePath().normalize().toString());
    }

    private DevAgentProperties props(boolean enabled, List<DevAgentProperties.McpClientConfig> clients) {
        return new DevAgentProperties(
                "dev-task-agent",
                "prompt {mcpRoot}",
                tempDir.toString(),
                tempDir.toString(),
                new DevAgentProperties.Compaction(6, 2, "请整理：{messages}"),
                new DevAgentProperties.Model("sk", "https://api.deepseek.com", "deepseek-v4-pro"),
                new DevAgentProperties.McpSettings(enabled, clients));
    }
}
