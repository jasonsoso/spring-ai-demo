package com.jason.demo.demo2.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Subagent Orchestration 服务（TaskTool · architect + builder）
 */
@Slf4j
@Service
public class SubagentAgentService {

    private final ChatClient subagentOrchestratorClient;

    public SubagentAgentService(
            @Qualifier("subagentOrchestratorClient") ChatClient subagentOrchestratorClient) {
        this.subagentOrchestratorClient = subagentOrchestratorClient;
    }

    public String chat(String message) {
        try {
            log.info("[Subagent] user message: {}", message);
            String response = subagentOrchestratorClient.prompt()
                    .user(message)
                    .call()
                    .content();
            log.info("[Subagent] completed, response length={}", response != null ? response.length() : 0);
            return response != null ? response : "";
        } catch (Exception e) {
            log.error("[Subagent] orchestration failed", e);
            return "调用 AI 模型失败：" + e.getMessage();
        }
    }
}
