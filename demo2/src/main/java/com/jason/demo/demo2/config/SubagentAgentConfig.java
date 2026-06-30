package com.jason.demo.demo2.config;

import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentDefinition;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * Subagent Orchestration 配置（Spring AI 2.0 系列教程第五篇 · Architect-Builder）
 */
@Configuration
public class SubagentAgentConfig {

    private static final String ORCHESTRATOR_PROMPT = """
            You are a task orchestrator with access to specialized sub-agents via the Task tool.

            Available sub-agents:
            - architect: Use for complex analysis requiring deep reasoning. Returns a structured Blueprint only.
            - builder: Use to generate polished final content from a Blueprint.

            Guidelines:
            - For complex writing or analysis tasks: call architect first, then pass its Blueprint to builder.
            - For simple questions: answer directly without delegating.
            - Always return the final integrated response to the user in Chinese.
            """;

    @Bean
    @Qualifier("subagentOrchestratorClient")
    ChatClient subagentOrchestratorClient(ChatClient.Builder chatClientBuilder,
                                          @Value("${agent.tasks.paths}") String agentPathsConfig) {
        List<SubagentReference> agentRefs = Arrays.stream(agentPathsConfig.split(","))
                .map(String::trim)
                .map(path -> new SubagentReference(path, ClaudeSubagentDefinition.KIND))
                .toList();

        var taskTool = TaskTool.builder()
                .subagentTypes(ClaudeSubagentType.builder()
                        .chatClientBuilder("default", chatClientBuilder.clone())
                        .build())
                .subagentReferences(agentRefs)
                .build();

        return chatClientBuilder.clone()
                .defaultSystem(ORCHESTRATOR_PROMPT)
                .defaultTools(taskTool)
                .build();
    }
}
