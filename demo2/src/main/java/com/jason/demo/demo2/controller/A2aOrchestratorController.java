package com.jason.demo.demo2.controller;

import com.jason.demo.demo2.service.A2aOrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * A2A 跨系统对话接口（Spring AI 2.0 系列教程第六篇）
 */
@Tag(name = "A2A Orchestration",
        description = "主协调器通过 A2A 协议调用内嵌天气专家 Agent（AgentCard 发现 + JSON-RPC）")
@RestController
@RequestMapping("/agent/a2a")
@RequiredArgsConstructor
public class A2aOrchestratorController {

    private final A2aOrchestratorService a2aOrchestratorService;

    @Operation(summary = "A2A 编排对话",
            description = "协调器通过 TaskTool 委派远程 Weather Agent，内嵌于同进程 A2A Server")
    @GetMapping("/chat")
    public Map<String, String> chat(
            @Parameter(description = "用户消息",
                    example = "查北京和上海的天气，并给出周末出行建议")
            @RequestParam("message") String message) {
        String response = a2aOrchestratorService.chat(message);
        return Map.of(
                "message", message,
                "response", response,
                "agentType", "A2A Orchestration · TaskTool + Weather Agent (embedded)"
        );
    }
}
