package com.jason.demo.demo2.agentscope.config;

import com.jason.demo.demo2.agentscope.middleware.AgentExecutionLoggingMiddleware;
import com.jason.demo.demo2.agentscope.tool.FileChangeTool;
import com.jason.demo.demo2.agentscope.tool.ProjectInfoTools;
import com.jason.demo.demo2.config.LoggingAgentscopeModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.DeepSeekFormatter;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@Configuration
public class AgentScopeConfig {

    private static final List<String> READ_ONLY_TOOL_NAMES =
            List.of("read_pom", "list_source_folders", "find_main_class");

    static CompactionConfig toCompactionConfig(DevAgentProperties.Compaction c) {
        return CompactionConfig.builder()
                .triggerMessages(c.triggerMessages())
                .keepMessages(c.keepMessages())
                .keepTokens(0)
                .summaryPrompt(c.summaryPrompt())
                .flushBeforeCompact(false)
                .offloadBeforeCompact(false)
                .build();
    }

    @Bean
    CompactionConfig agentscopeCompactionConfig(DevAgentProperties properties) {
        return toCompactionConfig(properties.compaction());
    }

    @Bean
    @Qualifier("agentscopeDeepSeekModel")
    Model agentscopeDeepSeekModel(DevAgentProperties properties) {
        DevAgentProperties.Model model = properties.model();
        Model openAi = OpenAIChatModel.builder()
                .apiKey(model.apiKey() == null ? "" : model.apiKey())
                .baseUrl(model.baseUrl())
                .modelName(model.name())
                .formatter(new DeepSeekFormatter())
                .stream(true)
                .build();
        return new LoggingAgentscopeModel(openAi, "agentscope-deepseek");
    }

    @Bean
    ProjectInfoTools projectInfoTools(DevAgentProperties properties) {
        return new ProjectInfoTools(Path.of(properties.projectRoot()));
    }

    @Bean
    FileChangeTool fileChangeTool(DevAgentProperties properties) {
        return new FileChangeTool(Path.of(properties.projectRoot()));
    }

    @Bean
    AgentStateStore agentscopeAgentStateStore(AgentScopeDataSourceProperties dataSourceProperties) {
        return AgentStateStoreFactory.create(dataSourceProperties);
    }

    @Bean
    AgentExecutionLoggingMiddleware agentExecutionLoggingMiddleware() {
        return new AgentExecutionLoggingMiddleware();
    }

    @Bean
    HarnessAgent agentscopeDevAgent(
            @Qualifier("agentscopeDeepSeekModel") Model agentscopeDeepSeekModel,
            DevAgentProperties properties,
            CompactionConfig agentscopeCompactionConfig,
            ProjectInfoTools projectInfoTools,
            FileChangeTool fileChangeTool,
            AgentStateStore agentscopeAgentStateStore,
            AgentExecutionLoggingMiddleware agentExecutionLoggingMiddleware) throws IOException {
        HarnessAgent agent = HarnessAgent.builder()
                .name(properties.name())
                .sysPrompt(properties.systemPrompt())
                .model(agentscopeDeepSeekModel)
                .workspace(Path.of(properties.workspaceRoot()))
                .stateStore(agentscopeAgentStateStore)
                .permissionContext(permissionContext())
                .middleware(agentExecutionLoggingMiddleware)
                .enableAgentTracingLog(false)
                .disableFilesystemTools()
                .disableShellTool()
                .disableMemoryTools()
                .disableMemoryHooks()
                .compaction(agentscopeCompactionConfig)
                .disableSubagents()
                .disableAtPathExpansion()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableToolsConfig()
                .build();
        agent.getToolkit().removeTool("wait_async_results");
        agent.getToolkit().registerTool(projectInfoTools);
        agent.getToolkit().registerAgentTool(fileChangeTool);
        return agent;
    }

    private static PermissionContextState permissionContext() {
        PermissionContextState.Builder builder =
                PermissionContextState.builder().mode(PermissionMode.DEFAULT);
        READ_ONLY_TOOL_NAMES.forEach(
                toolName -> builder.addAllowRule(toolName, allowRule(toolName)));
        return builder.build();
    }

    private static PermissionRule allowRule(String toolName) {
        return new PermissionRule(toolName, null, PermissionBehavior.ALLOW, "app");
    }
}
