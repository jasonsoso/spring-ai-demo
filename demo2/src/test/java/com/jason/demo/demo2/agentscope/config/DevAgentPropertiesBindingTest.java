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

    @EnableConfigurationProperties(DevAgentProperties.class)
    static class TestConfig {
    }
}
