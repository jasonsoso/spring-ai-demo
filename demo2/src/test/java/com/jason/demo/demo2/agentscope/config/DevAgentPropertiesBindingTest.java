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
                    "app.agentscope.dev-agent.compaction.trigger-messages=12",
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
            assertThat(c.triggerMessages()).isEqualTo(12);
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

    @Test
    void bindsMemorySettings() {
        runner.withPropertyValues(
                "app.agentscope.dev-agent.memory.enabled=true",
                "app.agentscope.dev-agent.memory.save-requires-confirm=false",
                "app.agentscope.dev-agent.memory.flush-min-gap=5m",
                "app.agentscope.dev-agent.memory.consolidation-min-gap=15m",
                "app.agentscope.dev-agent.memory.consolidation-max-tokens=2000",
                "app.agentscope.dev-agent.memory.flush-prompt=flush-me",
                "app.agentscope.dev-agent.memory.consolidation-prompt=consol %d %d"
        ).run(ctx -> {
            DevAgentProperties.Memory memory = ctx.getBean(DevAgentProperties.class).memory();
            assertThat(memory.enabled()).isTrue();
            assertThat(memory.saveRequiresConfirm()).isFalse();
            assertThat(memory.flushMinGap()).isEqualTo(java.time.Duration.ofMinutes(5));
            assertThat(memory.consolidationMinGap()).isEqualTo(java.time.Duration.ofMinutes(15));
            assertThat(memory.consolidationMaxTokens()).isEqualTo(2000);
            assertThat(memory.flushPrompt()).isEqualTo("flush-me");
            assertThat(memory.consolidationPrompt()).isEqualTo("consol %d %d");
        });
    }

    @Test
    void memoryDefaultsToDisabledWhenAbsent() {
        runner.run(ctx -> {
            DevAgentProperties.Memory memory = ctx.getBean(DevAgentProperties.class).memory();
            assertThat(memory.enabled()).isFalse();
            assertThat(memory.saveRequiresConfirm()).isTrue();
            assertThat(memory.flushMinGap()).isEqualTo(java.time.Duration.ofMinutes(10));
            assertThat(memory.consolidationMinGap()).isEqualTo(java.time.Duration.ofMinutes(30));
            assertThat(memory.consolidationMaxTokens()).isEqualTo(4000);
            assertThat(memory.consolidationPrompt()).contains("%d");
        });
    }

    @Test
    void sandboxDefaultsToDisabledWhenAbsent() {
        runner.run(ctx -> {
            DevAgentProperties.Sandbox sandbox = ctx.getBean(DevAgentProperties.class).sandbox();
            assertThat(sandbox.enabled()).isFalse();
            assertThat(sandbox.image()).isEqualTo("agentscope-java-sandbox:17");
            assertThat(sandbox.network()).isEqualTo("none");
            assertThat(sandbox.workspaceRoot()).isEqualTo("/workspace");
            assertThat(sandbox.snapshotRoot()).isEqualTo(".agentscope/sandbox-snapshots");
            assertThat(sandbox.memorySizeBytes()).isEqualTo(536870912L);
            assertThat(sandbox.cpuCount()).isEqualTo(1L);
        });
    }

    @Test
    void bindsSandboxSettings() {
        runner.withPropertyValues(
                "app.agentscope.dev-agent.sandbox.enabled=true",
                "app.agentscope.dev-agent.sandbox.image=custom-sandbox:1",
                "app.agentscope.dev-agent.sandbox.network=bridge",
                "app.agentscope.dev-agent.sandbox.workspace-root=/work",
                "app.agentscope.dev-agent.sandbox.snapshot-root=.agentscope/snaps",
                "app.agentscope.dev-agent.sandbox.memory-size-bytes=268435456",
                "app.agentscope.dev-agent.sandbox.cpu-count=2"
        ).run(ctx -> {
            DevAgentProperties.Sandbox sandbox = ctx.getBean(DevAgentProperties.class).sandbox();
            assertThat(sandbox.enabled()).isTrue();
            assertThat(sandbox.image()).isEqualTo("custom-sandbox:1");
            assertThat(sandbox.network()).isEqualTo("bridge");
            assertThat(sandbox.workspaceRoot()).isEqualTo("/work");
            assertThat(sandbox.snapshotRoot()).isEqualTo(".agentscope/snaps");
            assertThat(sandbox.memorySizeBytes()).isEqualTo(268435456L);
            assertThat(sandbox.cpuCount()).isEqualTo(2L);
        });
    }

    @Test
    void sandboxEnabledWithBlankImageFails() {
        runner.withPropertyValues(
                "app.agentscope.dev-agent.sandbox.enabled=true",
                "app.agentscope.dev-agent.sandbox.image= "
        ).run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void bindsModelFallback() {
        runner.withPropertyValues(
                "app.agentscope.dev-agent.model-fallback.max-attempts=3",
                "app.agentscope.dev-agent.model-fallback.fallback.api-key=kimi-key",
                "app.agentscope.dev-agent.model-fallback.fallback.base-url=https://api.moonshot.cn/v1",
                "app.agentscope.dev-agent.model-fallback.fallback.name=kimi-k3"
        ).run(ctx -> {
            DevAgentProperties.ModelFallback fb =
                    ctx.getBean(DevAgentProperties.class).modelFallback();
            assertThat(fb.maxAttempts()).isEqualTo(3);
            assertThat(fb.fallback().apiKey()).isEqualTo("kimi-key");
            assertThat(fb.fallback().baseUrl()).isEqualTo("https://api.moonshot.cn/v1");
            assertThat(fb.fallback().name()).isEqualTo("kimi-k3");
        });
    }

    @Test
    void modelFallbackDefaultsWhenAbsent() {
        runner.run(ctx -> {
            DevAgentProperties.ModelFallback fb =
                    ctx.getBean(DevAgentProperties.class).modelFallback();
            assertThat(fb.maxAttempts()).isEqualTo(2);
            assertThat(fb.fallback().baseUrl()).isEqualTo("https://api.moonshot.cn/v1");
            assertThat(fb.fallback().name()).isEqualTo("kimi-k3");
            assertThat(fb.fallback().apiKey() == null || fb.fallback().apiKey().isBlank()).isTrue();
        });
    }

    @EnableConfigurationProperties(DevAgentProperties.class)
    static class TestConfig {
    }
}
