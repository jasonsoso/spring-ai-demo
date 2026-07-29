package com.jason.demo.demo2.agentscope.config;

import com.jason.demo.demo2.agentscopea2a.client.RemoteRiskReviewService;
import com.jason.demo.demo2.agentscopea2a.client.RiskReviewTool;
import com.jason.demo.demo2.agentscope.mcp.AgentscopeMcpClientRegistry;
import com.jason.demo.demo2.agentscope.middleware.AgentExecutionLoggingMiddleware;
import com.jason.demo.demo2.agentscope.state.PathSafeAgentStateStore;
import com.jason.demo.demo2.agentscope.tool.FileChangeTool;
import com.jason.demo.demo2.agentscope.tool.ProjectInfoTools;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
        Path skillMd = tempDir.resolve("skills/code-reviewer/SKILL.md");
        Files.createDirectories(skillMd.getParent());
        Files.writeString(skillMd, """
                ---
                name: code-reviewer
                description: test skill for harness middleware
                ---
                # test
                """);
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
                store,
                middleware,
                AgentscopeMcpClientRegistry.create(properties))) {
            assertThat(agent.getDelegate().getMiddlewares())
                    .contains(middleware)
                    .noneMatch(item -> item.getClass().getSimpleName()
                            .equals("AgentTraceMiddleware"));
            assertThat(agent.getToolkit().getToolNames())
                    .doesNotContain("list_directory", "read_text_file", "list_allowed_directories");
            assertThat(agent.getToolkit().getToolNames())
                    .contains("agent_spawn", "agent_send", "agent_list");
            assertThat(agent.getDelegate().getMiddlewares())
                    .anyMatch(item -> item.getClass().getSimpleName()
                            .equals("HarnessSkillMiddleware"));
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
                        "consol %d %d"),
                null);
        try (HarnessAgent agent = buildAgent(properties)) {
            assertThat(agent.getToolkit().getToolNames())
                    .contains("memory_save", "memory_search", "memory_get");
        }
    }

    @Test
    void agentscopeDevAgent_sandboxDisabled_keepsFilesystemToolsOff() throws Exception {
        try (HarnessAgent agent = buildAgent(propertiesWithSandbox(false))) {
            assertThat(agent.getToolkit().getToolNames())
                    .doesNotContain("read_file", "edit_file", "execute", "write_file");
        }
    }

    @Test
    void agentscopeDevAgent_sandboxEnabled_exposesSandboxToolsWithoutWriteFile() throws Exception {
        try (HarnessAgent agent = buildAgent(propertiesWithSandbox(true))) {
            assertThat(agent.getToolkit().getToolNames())
                    .contains("read_file", "edit_file", "execute", "list_files", "glob_files", "grep_files")
                    .doesNotContain("write_file");
        }
    }

    @Test
    void agentscopeDevAgent_alwaysRegistersPlanModeAndTaskListTools() throws Exception {
        try (HarnessAgent off = buildAgent(propertiesWithSandbox(false));
             HarnessAgent on = buildAgent(propertiesWithSandbox(true))) {
            assertThat(off.getToolkit().getToolNames())
                    .contains("plan_enter", "plan_write", "plan_exit", "todo_write");
            assertThat(on.getToolkit().getToolNames())
                    .contains("plan_enter", "plan_write", "plan_exit", "todo_write");
            assertThat(on.getDelegate().getMiddlewares())
                    .anyMatch(m -> m.getClass().getSimpleName().equals("PlanModeMiddleware"));
        }
    }

    @Test
    void permissionContext_allowsPlanToolsButNotPlanExitOrExecute() {
        DevAgentProperties sandboxOn = propertiesWithSandbox(true);
        var ctx = AgentScopeConfig.permissionContext(
                sandboxOn, AgentscopeMcpClientRegistry.create(sandboxOn));
        assertThat(ctx.getAllowRules().keySet())
                .contains("plan_enter", "plan_write", "todo_write", "read_file",
                        "list_files", "glob_files", "grep_files")
                .doesNotContain("plan_exit", "execute");
    }

    @Test
    void sandboxProjectionRoots_includePlans() {
        assertThat(AgentScopeConfig.sandboxWorkspaceProjectionRoots())
                .contains("plans", "project", "AGENTS.md");
    }

    @Test
    void agentscopeDevAgent_sandboxEnabled_injectsPlanModePrompt() throws Exception {
        try (HarnessAgent on = buildAgent(propertiesWithSandbox(true));
             HarnessAgent off = buildAgent(propertiesWithSandbox(false))) {
            String onPrompt = on.getDelegate().getSysPrompt();
            String offPrompt = off.getDelegate().getSysPrompt();
            assertThat(onPrompt).contains("plan_enter");
            assertThat(onPrompt).contains("plans/PLAN.md");
            assertThat(onPrompt).contains("plan_exit");
            assertThat(offPrompt).doesNotContain("plans/PLAN.md");
        }
    }

    @Test
    void agentscopeDevAgent_registersRiskReviewToolIndependentOfSandbox() throws Exception {
        AgentScopeConfig config = new AgentScopeConfig();
        RiskReviewTool riskReviewTool = new RiskReviewTool(mock(RemoteRiskReviewService.class));

        try (HarnessAgent regularAgent = buildAgent(config, propertiesWithSandbox(false), riskReviewTool);
             HarnessAgent sandboxAgent = buildAgent(config, propertiesWithSandbox(true), riskReviewTool)) {
            assertThat(regularAgent.getToolkit().getToolNames()).contains("risk_review");
            assertThat(sandboxAgent.getToolkit().getToolNames()).contains("risk_review");
        }
    }

    private DevAgentProperties disabledMemoryProperties() {
        return propertiesWithSandbox(false);
    }

    private DevAgentProperties propertiesWithSandbox(boolean enabled) {
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
                null,
                new DevAgentProperties.Sandbox(
                        enabled,
                        "agentscope-java-sandbox:17",
                        "none",
                        "/workspace",
                        tempDir.resolve("snaps").toString(),
                        536870912L,
                        1L));
    }

    private HarnessAgent buildAgent(DevAgentProperties properties) throws Exception {
        return buildAgent(new AgentScopeConfig(), properties);
    }

    private HarnessAgent buildAgent(AgentScopeConfig config, DevAgentProperties properties)
            throws Exception {
        return buildAgent(config, properties, null);
    }

    private HarnessAgent buildAgent(
            AgentScopeConfig config,
            DevAgentProperties properties,
            RiskReviewTool riskReviewTool) throws Exception {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("test-model");
        AgentStateStore baseStore = mock(AgentStateStore.class);
        AgentStateStore harnessStore = properties.sandbox().enabled()
                ? new PathSafeAgentStateStore(baseStore)
                : baseStore;
        AgentExecutionLoggingMiddleware middleware =
                new AgentExecutionLoggingMiddleware();
        return config.agentscopeDevAgent(
                model,
                properties,
                AgentScopeConfig.toCompactionConfig(properties.compaction()),
                AgentScopeConfig.toMemoryConfig(properties.memory()),
                new ProjectInfoTools(tempDir),
                new FileChangeTool(tempDir),
                new AgentscopeDistributedBackend.Local(baseStore),
                harnessStore,
                middleware,
                AgentscopeMcpClientRegistry.create(properties),
                riskReviewTool);
    }
}
