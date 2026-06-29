package com.jason.demo.demo2.service;

import com.jason.demo.demo2.sse.AbstractSseAgentService;
import com.jason.demo.demo2.sse.AgentSseSessionStore;
import com.jason.demo.demo2.sse.AgentTextAccumulator;
import com.jason.demo.demo2.sse.AgentTextAccumulatorAdvisor;
import com.jason.demo.demo2.sse.TodoSseBridge;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TodoAgentService extends AbstractSseAgentService {

    private static final String SYSTEM_PROMPT = """
            你是一名专业的学习规划导师。
            当用户提出学习计划、知识体系搭建等多步骤任务时，你必须使用 TodoWrite 工具拆解任务并跟踪进度。
            执行过程中及时更新 Todo 状态：pending → in_progress → completed。
            工作流程（严格遵守）：
            1. 用 TodoWrite 拆出 3～6 个子任务；
            2. 将当前子任务标为 in_progress；
            3. 输出该子任务对应的学习计划片段（Markdown，含具体知识点/实践建议）；
            4. 将该子任务标为 completed，再进入下一项；
            5. 全部子任务完成后，输出一份整合后的完整学习计划作为最终回复。
            不要编造用户未提供的信息；若信息不足，基于合理假设并说明。
            """;

    private final ChatClient chatClient;

    public TodoAgentService(ChatClient.Builder chatClientBuilder,
                            TodoWriteTool todoWriteTool,
                            AgentSseSessionStore sessionStore) {
        super(sessionStore);
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultTools(todoWriteTool)
                .defaultAdvisors(new AgentTextAccumulatorAdvisor())
                .build();
    }

    @Override
    protected void runAgent(String sessionId, String message) {
        runWithSession(sessionId, () -> {
            AgentTextAccumulator.clear();
            TodoSseBridge.clear();
            try {
                String lastContent = chatClient.prompt()
                        .user(message)
                        .call()
                        .content();
                // 分步产出已在 TASK_RESULT 推送；此处仅返回最后一轮整合文本
                String finalSection = AgentTextAccumulator.consumeNewText();
                if (!finalSection.isBlank()) {
                    return finalSection;
                }
                return lastContent != null ? lastContent : "";
            } finally {
                AgentTextAccumulator.clear();
                TodoSseBridge.clear();
            }
        });
    }
}
