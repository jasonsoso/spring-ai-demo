package com.jason.demo.demo2.service;

import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springaicommunity.agent.common.task.subagent.SubagentType;
import org.springaicommunity.agent.subagent.a2a.A2ASubagentDefinition;
import org.springaicommunity.agent.subagent.a2a.A2ASubagentExecutor;
import org.springaicommunity.agent.subagent.a2a.A2ASubagentResolver;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * A2A 跨系统协调器服务（TaskTool + 内嵌天气专家 Agent）
 * <p>
 * 同 JVM 内嵌 A2A Server 时，须在应用就绪后再构建 ChatClient，
 * 否则 TaskTool 拉取 AgentCard 会因 HTTP 尚未监听而失败。
 */
@Slf4j
@Service
public class A2aOrchestratorService {

    private static final String A2A_ORCHESTRATOR_PROMPT = """
            You are a cross-system task orchestrator with access to a remote Weather Agent via the Task tool.

            Guidelines:
            - For weather-related questions (city conditions, travel advice based on weather): \
            delegate to the remote Weather Agent using the Task tool.
            - You may delegate multiple cities in one or more Task calls.
            - After receiving remote results, synthesize a clear Chinese response for the user.
            - For non-weather questions, answer briefly or explain that this demo focuses on weather via A2A.
            """;

    private final ChatClient.Builder chatClientBuilder;
    private final String a2aRemoteUrl;
    private volatile ChatClient a2aOrchestratorClient;

    public A2aOrchestratorService(ChatClient.Builder chatClientBuilder,
                                  @Value("${agent.a2a.remote.url}") String a2aRemoteUrl) {
        this.chatClientBuilder = chatClientBuilder;
        this.a2aRemoteUrl = a2aRemoteUrl;
    }

    /**
     * 应用完全就绪后再构建 ChatClient。
     * <p>
     * 监听 {@link ApplicationReadyEvent}，等内嵌 Web 服务器开始监听后再初始化，
     * 避免 TaskTool 通过 HTTP 拉取 AgentCard 时因 A2A Server 尚未就绪而失败。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        try {
            var taskTool = TaskTool.builder()
                    .subagentTypes(new SubagentType(new A2ASubagentResolver(), new A2ASubagentExecutor()))
                    .subagentReferences(new SubagentReference(a2aRemoteUrl, A2ASubagentDefinition.KIND))
                    .build();

            this.a2aOrchestratorClient = chatClientBuilder.clone()
                    .defaultSystem(A2A_ORCHESTRATOR_PROMPT)
                    .defaultTools(taskTool)
                    .build();
            log.info("[A2A] Orchestrator ChatClient 构建完成，remote={}", a2aRemoteUrl);
        } catch (Exception e) {
            log.error("[A2A] Orchestrator ChatClient 构建失败", e);
        }
    }

    public String chat(String message) {
        if (a2aOrchestratorClient == null) {
            return "A2A 协调器尚未初始化，请稍后重试";
        }
        try {
            log.info("[A2A] user message: {}", message);
            String response = a2aOrchestratorClient.prompt()
                    .user(message)
                    .call()
                    .content();
            log.info("[A2A] completed, response length={}", response != null ? response.length() : 0);
            return response != null ? response : "";
        } catch (Exception e) {
            log.error("[A2A] orchestration failed", e);
            return "调用 AI 模型失败：" + e.getMessage();
        }
    }
}
