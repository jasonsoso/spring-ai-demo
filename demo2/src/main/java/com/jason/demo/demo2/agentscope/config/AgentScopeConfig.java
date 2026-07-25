package com.jason.demo.demo2.agentscope.config;

import com.jason.demo.demo2.agentscope.mcp.AgentscopeMcpClientRegistry;
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
import io.agentscope.harness.agent.memory.MemoryConfig;
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

    static MemoryConfig toMemoryConfig(DevAgentProperties.Memory config) {
        return MemoryConfig.builder()
                .flushTrigger(MemoryConfig.FlushTrigger.throttled(config.flushMinGap()))
                .consolidationMinGap(config.consolidationMinGap())
                .consolidationMaxTokens(config.consolidationMaxTokens())
                .flushPrompt(config.flushPrompt())
                .consolidationPrompt(config.consolidationPrompt())
                .build();
    }

    @Bean
    CompactionConfig agentscopeCompactionConfig(DevAgentProperties properties) {
        return toCompactionConfig(properties.compaction());
    }

    @Bean
    MemoryConfig agentscopeMemoryConfig(DevAgentProperties properties) {
        return toMemoryConfig(properties.memory());
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

    @Bean(destroyMethod = "close")
    AgentscopeMcpClientRegistry agentscopeMcpClientRegistry(DevAgentProperties properties) {
        return AgentscopeMcpClientRegistry.create(properties);
    }

    @Bean
    HarnessAgent agentscopeDevAgent(
            @Qualifier("agentscopeDeepSeekModel") Model agentscopeDeepSeekModel,
            DevAgentProperties properties,
            CompactionConfig agentscopeCompactionConfig,
            MemoryConfig agentscopeMemoryConfig,
            ProjectInfoTools projectInfoTools,
            FileChangeTool fileChangeTool,
            AgentStateStore agentscopeAgentStateStore,
            AgentExecutionLoggingMiddleware agentExecutionLoggingMiddleware,
            AgentscopeMcpClientRegistry agentscopeMcpClientRegistry) throws IOException {
        String systemPrompt = properties.systemPrompt()
                .replace("{mcpRoot}", AgentscopeMcpClientRegistry.primaryMcpRootDisplay(properties));
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(properties.name())
                .sysPrompt(systemPrompt)
                .model(agentscopeDeepSeekModel)
                .workspace(Path.of(properties.workspaceRoot()))
                .stateStore(agentscopeAgentStateStore)
                .permissionContext(permissionContext(properties, agentscopeMcpClientRegistry))
                .middleware(agentExecutionLoggingMiddleware)
                .enableAgentTracingLog(false)
                .disableFilesystemTools()
                .disableShellTool()
                .compaction(agentscopeCompactionConfig)
                .disableSubagents()
                .disableAtPathExpansion()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableToolsConfig();
        if (properties.memory().enabled()) {
            builder.memory(agentscopeMemoryConfig);
        } else {
            builder.disableMemoryTools().disableMemoryHooks();
        }
        HarnessAgent agent = builder.build();
        agent.getToolkit().removeTool("wait_async_results");
        agent.getToolkit().registerTool(projectInfoTools);
        agent.getToolkit().registerAgentTool(fileChangeTool);
        for (AgentscopeMcpClientRegistry.Entry entry : agentscopeMcpClientRegistry.entries()) {
            agent.getToolkit().registerTool(entry.tools());
        }
        return agent;
    }

    private static PermissionContextState permissionContext(
            DevAgentProperties properties,
            AgentscopeMcpClientRegistry agentscopeMcpClientRegistry) {
        PermissionContextState.Builder builder =
                PermissionContextState.builder().mode(PermissionMode.DEFAULT);
        READ_ONLY_TOOL_NAMES.forEach(
                toolName -> builder.addAllowRule(toolName, allowRule(toolName)));
        for (AgentscopeMcpClientRegistry.Entry entry : agentscopeMcpClientRegistry.entries()) {
            for (String toolName : entry.enabledTools()) {
                builder.addAllowRule(toolName, allowRule(toolName));
            }
        }
        applyMemoryAllowRules(builder, properties.memory());
        return builder.build();
    }

    /** package-visible for tests */
    static void applyMemoryAllowRules(
            PermissionContextState.Builder builder, DevAgentProperties.Memory memory) {
        if (!memory.enabled()) {
            return;
        }
        builder.addAllowRule("memory_search", allowRule("memory_search"));
        builder.addAllowRule("memory_get", allowRule("memory_get"));
        if (!memory.saveRequiresConfirm()) {
            builder.addAllowRule("memory_save", allowRule("memory_save"));
        }
    }

    private static PermissionRule allowRule(String toolName) {
        return new PermissionRule(toolName, null, PermissionBehavior.ALLOW, "app");
    }
}
