package com.jason.demo.demo2.controller;

import com.jason.demo.demo2.service.SkillsAgentService;
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
 * Agent Skills 接口（Spring AI 2.0 系列教程 · Agent Skills）
 */
@Tag(name = "Agent Skills", description = "SkillsTool 语义匹配加载 SKILL.md，配合 Glob/Grep/FileSystem/Shell 工具执行")
@RestController
@RequestMapping("/agent/skills")
@RequiredArgsConstructor
public class SkillsAgentController {

    private final SkillsAgentService skillsAgentService;

    @Operation(summary = "Skills Agent 聊天",
            description = "AI 根据用户请求语义匹配并加载 Cursor skills 目录下的 SKILL.md，自动调用相关 skill")
    @GetMapping("/chat")
    public Map<String, String> chat(
            @Parameter(description = "用户消息", example = "帮我按代码规范检查 ChatController.java")
            @RequestParam("message") String message) {
        String response = skillsAgentService.chat(message);
        return Map.of(
                "message", message,
                "response", response,
                "agentType", "Agent Skills（SkillsTool + Glob/Grep/FileSystem/Shell）"
        );
    }

    @Operation(summary = "教程示例：强化学习 + ai-tutor skill",
            description = "复现 skills-demo 官方示例 prompt，演示 ai-tutor skill 与 YouTube 字幕脚本")
    @GetMapping("/demo")
    public Map<String, String> demo() {
        String response = skillsAgentService.demoReinforcementLearning();
        return Map.of(
                "message", "skills-demo 官方示例（强化学习 + YouTube + ai-tutor）",
                "response", response,
                "agentType", "Agent Skills Demo"
        );
    }

    @Operation(summary = "教程示例：PDF 合并 + pdf skill",
            description = "演示 pdf skill 语义匹配，说明 pypdf 合并 PDF 的步骤及 reference.md 用法")
    @GetMapping("/demo-pdf")
    public Map<String, String> demoPdf() {
        String response = skillsAgentService.demoPdf();
        return Map.of(
                "message", "skills-demo 示例（PDF 合并 + pdf skill）",
                "response", response,
                "agentType", "Agent Skills PDF Demo"
        );
    }
}
