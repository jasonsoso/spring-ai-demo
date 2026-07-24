package com.jason.demo.demo2.agentscope.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DevAgentPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "app.agentscope.dev-agent.name=dev-task-agent",
                    "app.agentscope.dev-agent.system-prompt=short",
                    "app.agentscope.dev-agent.project-root=.",
                    "app.agentscope.dev-agent.workspace-root=workspace",
                    "app.agentscope.dev-agent.compaction.trigger-messages=6",
                    "app.agentscope.dev-agent.compaction.keep-messages=2",
                    "app.agentscope.dev-agent.compaction.summary-prompt=请整理会话：{messages}",
                    "app.agentscope.dev-agent.model.api-key=",
                    "app.agentscope.dev-agent.model.base-url=https://api.deepseek.com",
                    "app.agentscope.dev-agent.model.name=deepseek-v4-pro");

    @Test
    void bindsWorkspaceRoot() {
        runner.run(ctx -> {
            DevAgentProperties props = ctx.getBean(DevAgentProperties.class);
            assertThat(props.workspaceRoot()).isEqualTo("workspace");
            assertThat(props.projectRoot()).isEqualTo(".");
        });
    }

    @Test
    void bindsCompaction() {
        runner.run(ctx -> {
            DevAgentProperties.Compaction c = ctx.getBean(DevAgentProperties.class).compaction();
            assertThat(c.triggerMessages()).isEqualTo(6);
            assertThat(c.keepMessages()).isEqualTo(2);
            assertThat(c.summaryPrompt()).contains("{messages}");
        });
    }

    @Test
    void bindsMcpClientsList() {
        runner.withPropertyValues(
                "app.agentscope.dev-agent.mcp.enabled=true",
                "app.agentscope.dev-agent.mcp.clients[0].name=project-files",
                "app.agentscope.dev-agent.mcp.clients[0].command=npx",
                "app.agentscope.dev-agent.mcp.clients[0].arguments[0]=-y",
                "app.agentscope.dev-agent.mcp.clients[0].arguments[1]=@modelcontextprotocol/server-filesystem@2026.7.10",
                "app.agentscope.dev-agent.mcp.clients[0].root=mcp-files",
                "app.agentscope.dev-agent.mcp.clients[0].enabled-tools[0]=list_directory",
                "app.agentscope.dev-agent.mcp.clients[0].enabled-tools[1]=read_text_file"
        ).run(ctx -> {
            DevAgentProperties.McpSettings mcp = ctx.getBean(DevAgentProperties.class).mcp();
            assertThat(mcp.enabled()).isTrue();
            assertThat(mcp.clients()).hasSize(1);
            DevAgentProperties.McpClientConfig c0 = mcp.clients().getFirst();
            assertThat(c0.name()).isEqualTo("project-files");
            assertThat(c0.enabled()).isTrue();
            assertThat(c0.command()).isEqualTo("npx");
            assertThat(c0.arguments()).containsExactly(
                    "-y", "@modelcontextprotocol/server-filesystem@2026.7.10");
            assertThat(c0.root()).isEqualTo("mcp-files");
            assertThat(c0.enabledTools()).containsExactly("list_directory", "read_text_file");
        });
    }

    @Test
    void mcpDefaultsToDisabledWhenAbsent() {
        runner.run(ctx -> {
            DevAgentProperties.McpSettings mcp = ctx.getBean(DevAgentProperties.class).mcp();
            assertThat(mcp.enabled()).isFalse();
            assertThat(mcp.clients()).isEmpty();
        });
    }

    @EnableConfigurationProperties(DevAgentProperties.class)
    static class TestConfig {
    }
}
