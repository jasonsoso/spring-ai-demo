package com.jason.demo.demo2.agentscope.config;

import com.jason.demo.demo2.agentscope.tool.ProjectInfoTools;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.DeepSeekFormatter;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;

@Configuration
public class AgentScopeConfig {

    @Bean
    @Qualifier("agentscopeDeepSeekModel")
    Model agentscopeDeepSeekModel(DevAgentProperties properties) {
        DevAgentProperties.Model model = properties.model();
        return OpenAIChatModel.builder()
                .apiKey(model.apiKey() == null ? "" : model.apiKey())
                .baseUrl(model.baseUrl())
                .modelName(model.name())
                .formatter(new DeepSeekFormatter())
                .stream(true)
                .build();
    }

    @Bean
    ProjectInfoTools projectInfoTools(DevAgentProperties properties) {
        return new ProjectInfoTools(Path.of(properties.projectRoot()));
    }

    @Bean
    HarnessAgent agentscopeDevAgent(
            @Qualifier("agentscopeDeepSeekModel") Model agentscopeDeepSeekModel,
            DevAgentProperties properties,
            ProjectInfoTools projectInfoTools) throws IOException {
        HarnessAgent agent = HarnessAgent.builder()
                .name(properties.name())
                .sysPrompt(properties.systemPrompt())
                .model(agentscopeDeepSeekModel)
                .stateStore(new InMemoryAgentStateStore())
                .permissionContext(PermissionContextState.builder()
                        .mode(PermissionMode.EXPLORE)
                        .build())
                .enableAgentTracingLog(false)
                .disableFilesystemTools()
                .disableShellTool()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableCompaction()
                .disableSubagents()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableToolsConfig()
                .build();
        agent.getToolkit().removeTool("wait_async_results");
        agent.getToolkit().registerTool(projectInfoTools);
        return agent;
    }
}
