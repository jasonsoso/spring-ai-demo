package com.jason.demo.demo2.agentscope.config;

import com.jason.demo.demo2.agentscope.mcp.AgentscopeMcpClientRegistry;
import com.jason.demo.demo2.agentscope.middleware.AgentExecutionLoggingMiddleware;
import com.jason.demo.demo2.agentscope.tool.FileChangeTool;
import com.jason.demo.demo2.agentscope.tool.ProjectInfoTools;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

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
        DevAgentProperties properties = disabledMemoryProperties();
        AgentExecutionLoggingMiddleware middleware =
                new AgentExecutionLoggingMiddleware();
        try (HarnessAgent agent = config.agentscopeDevAgent(
                model,
                properties,
                AgentScopeConfig.toCompactionConfig(properties.compaction()),
                AgentScopeConfig.toMemoryConfig(properties.memory()),
                new ProjectInfoTools(tempDir),
                new FileChangeTool(tempDir),
                new AgentscopeDistributedBackend.Local(store),
                middleware,
                AgentscopeMcpClientRegistry.create(properties))) {
            assertThat(agent.getDelegate().getMiddlewares())
                    .contains(middleware)
                    .noneMatch(item -> item.getClass().getSimpleName()
                            .equals("AgentTraceMiddleware"));
            assertThat(agent.getToolkit().getToolNames())
                    .doesNotContain("list_directory", "read_text_file", "list_allowed_directories");
        }
    }

    @Test
    void agentscopeDevAgent_memoryDisabled_omitsMemoryTools() throws Exception {
        try (HarnessAgent agent = buildAgent(disabledMemoryProperties())) {
            assertThat(agent.getToolkit().getToolNames())
                    .doesNotContain("memory_save", "memory_search", "memory_get");
        }
    }

    @Test
    void agentscopeDevAgent_memoryEnabled_registersMemoryTools() throws Exception {
        DevAgentProperties properties = new DevAgentProperties(
                "dev-task-agent",
                "prompt",
                tempDir.toString(),
                tempDir.toString(),
                new DevAgentProperties.Compaction(6, 2, "请整理：{messages}"),
                new DevAgentProperties.Model(
                        "sk-test",
                        "https://api.deepseek.com",
                        "deepseek-v4-pro"),
                new DevAgentProperties.McpSettings(false, List.of()),
                new DevAgentProperties.Memory(
                        true,
                        true,
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(30),
                        4000,
                        "flush",
                        "consol %d %d"));
        try (HarnessAgent agent = buildAgent(properties)) {
            assertThat(agent.getToolkit().getToolNames())
                    .contains("memory_save", "memory_search", "memory_get");
        }
    }

    private DevAgentProperties disabledMemoryProperties() {
        return new DevAgentProperties(
                "dev-task-agent",
                "prompt",
                tempDir.toString(),
                tempDir.toString(),
                new DevAgentProperties.Compaction(6, 2, "请整理：{messages}"),
                new DevAgentProperties.Model(
                        "sk-test",
                        "https://api.deepseek.com",
                        "deepseek-v4-pro"),
                new DevAgentProperties.McpSettings(false, List.of()),
                null);
    }

    private HarnessAgent buildAgent(DevAgentProperties properties) throws Exception {
        AgentScopeConfig config = new AgentScopeConfig();
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("test-model");
        AgentStateStore store = mock(AgentStateStore.class);
        AgentExecutionLoggingMiddleware middleware =
                new AgentExecutionLoggingMiddleware();
        return config.agentscopeDevAgent(
                model,
                properties,
                AgentScopeConfig.toCompactionConfig(properties.compaction()),
                AgentScopeConfig.toMemoryConfig(properties.memory()),
                new ProjectInfoTools(tempDir),
                new FileChangeTool(tempDir),
                new AgentscopeDistributedBackend.Local(store),
                middleware,
                AgentscopeMcpClientRegistry.create(properties));
    }
}
