package com.jason.demo.demo2.service;

import com.jason.demo.demo2.sse.AbstractSseAgentService;
import com.jason.demo.demo2.sse.AgentSessionStatus;
import com.jason.demo.demo2.sse.AgentSseEvent;
import com.jason.demo.demo2.sse.AgentSseSession;
import com.jason.demo.demo2.sse.AgentSseSessionStore;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Slf4j
@Service
public class AskUserAgentService extends AbstractSseAgentService {

    private static final String SYSTEM_PROMPT = """
            你是一名专业的技术选型顾问。
            当用户需求模糊或缺少关键信息时，你必须调用 AskUserQuestion 工具向用户提出澄清问题。
            澄清维度可包括：项目类型、数据特征、规模要求、运维偏好、团队技术栈等。
            每个问题应提供 2-4 个具体选项，必要时允许多选。
            收到用户答案后，用中文输出：推荐方案、选择理由、备选方案对比。
            不要编造用户未提供的信息。
            """;

    private final ChatClient chatClient;

    public AskUserAgentService(ChatClient.Builder chatClientBuilder,
                               AskUserQuestionTool askUserQuestionTool,
                               AgentSseSessionStore sessionStore) {
        super(sessionStore);
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultTools(askUserQuestionTool)
                .build();
    }

    public void submitAnswer(String sessionId, Map<String, String> answers) {
        AgentSseSession session = sessionStore.find(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        if (session.getStatus() != AgentSessionStatus.AWAITING_INPUT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Session is not awaiting input");
        }
        session.setStatus(AgentSessionStatus.RUNNING);
        sessionStore.pushEvent(sessionId, AgentSseEvent.running());
        sessionStore.completeAnswer(sessionId, answers);
    }

    @Override
    protected void runAgent(String sessionId, String message) {
        runWithSession(sessionId, () -> chatClient.prompt()
                .user(message)
                .call()
                .content());
    }
}
