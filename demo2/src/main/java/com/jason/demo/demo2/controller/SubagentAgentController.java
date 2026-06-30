package com.jason.demo.demo2.controller;

import com.jason.demo.demo2.service.SubagentAgentService;
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
 * Subagent Orchestration 接口（Spring AI 2.0 系列教程第五篇）
 */
@Tag(name = "Subagent Orchestration",
        description = "主协调器通过 TaskTool 委派 architect / builder 子代理，独立上下文、结果回传")
@RestController
@RequestMapping("/agent/subagent")
@RequiredArgsConstructor
public class SubagentAgentController {

    private final SubagentAgentService subagentAgentService;

    @Operation(summary = "Subagent 编排对话",
            description = "复杂任务：architect 产出 Blueprint → builder 生成最终报告；简单问题由主代理直接回答")
    @GetMapping("/chat")
    public Map<String, String> chat(
            @Parameter(description = "用户消息",
                    example = "分析 Spring AI RAG 架构并写一份入门指南")
            @RequestParam("message") String message) {
        String response = subagentAgentService.chat(message);
        return Map.of(
                "message", message,
                "response", response,
                "agentType", "Subagent Orchestration · Architect-Builder · TaskTool"
        );
    }
}
