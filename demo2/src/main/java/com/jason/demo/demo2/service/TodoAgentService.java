package com.jason.demo.demo2.service;

import com.jason.demo.demo2.sse.AbstractSseAgentService;
import com.jason.demo.demo2.sse.AgentSseSessionStore;
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
            每完成一个子任务后更新 Todo 列表。
            最终输出完整、结构化的中文学习计划，包含每日主题、学习内容、实践建议。
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
                .build();
    }

    @Override
    protected void runAgent(String sessionId, String message) {
        runWithSession(sessionId, () -> chatClient.prompt()
                .user(message)
                .call()
                .content());
    }
}
