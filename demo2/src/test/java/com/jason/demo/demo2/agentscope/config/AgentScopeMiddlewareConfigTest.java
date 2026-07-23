package com.jason.demo.demo2.agentscope.config;

import com.jason.demo.demo2.agentscope.middleware.AgentExecutionLoggingMiddleware;
import com.jason.demo.demo2.agentscope.tool.FileChangeTool;
import com.jason.demo.demo2.agentscope.tool.ProjectInfoTools;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentScopeMiddlewareConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void agentscopeDevAgent_registersCustomLoggingAndDisablesDefaultTrace()
            throws Exception {
        AgentScopeConfig config = new AgentScopeConfig();
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("test-model");
        AgentStateStore store = mock(AgentStateStore.class);
        DevAgentProperties properties = new DevAgentProperties(
                "dev-task-agent",
                "prompt",
                tempDir.toString(),
                tempDir.toString(),
                new DevAgentProperties.Compaction(6, 2, "请整理：{messages}"),
                new DevAgentProperties.Model(
                        "sk-test",
                        "https://api.deepseek.com",
                        "deepseek-v4-pro"));
        AgentExecutionLoggingMiddleware middleware =
                new AgentExecutionLoggingMiddleware();

        try (HarnessAgent agent = config.agentscopeDevAgent(
                model,
                properties,
                AgentScopeConfig.toCompactionConfig(properties.compaction()),
                new ProjectInfoTools(tempDir),
                new FileChangeTool(tempDir),
                store,
                middleware)) {
            assertThat(agent.getDelegate().getMiddlewares())
                    .contains(middleware)
                    .noneMatch(item -> item.getClass().getSimpleName()
                            .equals("AgentTraceMiddleware"));
        }
    }
}
