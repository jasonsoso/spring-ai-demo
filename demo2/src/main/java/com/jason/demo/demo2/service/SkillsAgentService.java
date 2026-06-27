package com.jason.demo.demo2.service;

import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.ShellTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

/**
 * Agent Skills 服务（源自 spring-ai-agent-utils skills-demo）
 * <p>
 * 通过 SkillsTool 语义匹配加载 SKILL.md，配合文件/搜索/Shell 工具执行 skill 指令。
 */
@Slf4j
@Service
public class SkillsAgentService {

    private static final String SYSTEM_PROMPT = """
            Always use the available skills to assist the user in their requests.
            When a skill is relevant, invoke it before answering.
            """;

    private final ChatClient chatClient;

    public SkillsAgentService(ChatClient.Builder chatClientBuilder,
                              ToolCallback skillsToolCallback,
                              ShellTools shellTools,
                              FileSystemTools fileSystemTools,
                              GlobTool globTool,
                              GrepTool grepTool) {
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultTools(skillsToolCallback, shellTools, fileSystemTools, globTool, grepTool)
                .build();
    }

    /**
     * 使用 Agent Skills 处理用户请求
     *
     * @param message 用户消息
     * @return AI 回复
     */
    public String chat(String message) {
        try {
            return chatClient.prompt()
                    .user(message)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("Skills Agent 执行失败", e);
            return "调用 AI 模型失败：" + e.getMessage();
        }
    }

    /**
     * 教程示例：强化学习 + YouTube 视频 + ai-tutor skill
     */
    public String demoReinforcementLearning() {
        return chat("""
                Explain reinforcement learning in simple terms and use.
                Use required skills.
                Then use the Youtube video https://youtu.be/vXtfdGphr3c?si=xy8U2Al_Um5vE4Jd transcript to support your answer.
                Use absolute paths for the skills and scripts. Do not ask me for more details.
                """);
    }

    /**
     * 教程示例：PDF 合并操作 + pdf skill
     */
    public String demoPdf() {
        return chat("""
                I need to merge two PDF files into one document.
                Use the pdf skill to answer.
                Explain the merge steps with a pypdf code example, and mention when to read reference.md or forms.md.
                Use absolute paths for the skill directory and scripts. Do not ask me for more details.
                """);
    }
}
