package com.jason.demo.demo2.service;

import com.jason.demo.demo2.model.AskUserSession;
import com.jason.demo.demo2.model.AskUserSessionStatus;
import com.jason.demo.demo2.model.AskUserSseEvent;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class AskUserAgentService {

    private static final String SYSTEM_PROMPT = """
            你是一名专业的技术选型顾问。
            当用户需求模糊或缺少关键信息时，你必须调用 AskUserQuestion 工具向用户提出澄清问题。
            澄清维度可包括：项目类型、数据特征、规模要求、运维偏好、团队技术栈等。
            每个问题应提供 2-4 个具体选项，必要时允许多选。
            收到用户答案后，用中文输出：推荐方案、选择理由、备选方案对比。
            不要编造用户未提供的信息。
            """;

    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final ChatClient chatClient;
    private final AskUserSessionStore sessionStore;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AskUserAgentService(ChatClient.Builder chatClientBuilder,
                               AskUserQuestionTool askUserQuestionTool,
                               AskUserSessionStore sessionStore) {
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultTools(askUserQuestionTool)
                .build();
        this.sessionStore = sessionStore;
    }

    public String startChat(String message) {
        AskUserSession session = sessionStore.create(message);
        String sessionId = session.getSessionId();
        executor.submit(() -> runAgent(sessionId, message));
        return sessionId;
    }

    public SseEmitter connectSse(String sessionId) {
        AskUserSession session = sessionStore.find(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        sessionStore.attachEmitter(sessionId, emitter);
        if (session.getEventBuffer().isEmpty() && session.getStatus() == AskUserSessionStatus.RUNNING) {
            sessionStore.pushEvent(sessionId, AskUserSseEvent.running());
        }
        return emitter;
    }

    public void submitAnswer(String sessionId, Map<String, String> answers) {
        AskUserSession session = sessionStore.find(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        if (session.getStatus() != AskUserSessionStatus.AWAITING_INPUT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Session is not awaiting input");
        }
        session.setStatus(AskUserSessionStatus.RUNNING);
        sessionStore.pushEvent(sessionId, AskUserSseEvent.running());
        sessionStore.completeAnswer(sessionId, answers);
    }

    private void runAgent(String sessionId, String message) {
        AskUserSessionHolder.setSessionId(sessionId);
        try {
            sessionStore.pushEvent(sessionId, AskUserSseEvent.running());
            String response = chatClient.prompt()
                    .user(message)
                    .call()
                    .content();
            sessionStore.find(sessionId).ifPresent(session -> session.setStatus(AskUserSessionStatus.COMPLETED));
            sessionStore.pushEvent(sessionId, AskUserSseEvent.completed(response));
        } catch (Exception e) {
            log.error("AskUser agent failed: {}", sessionId, e);
            sessionStore.find(sessionId).ifPresent(session -> session.setStatus(AskUserSessionStatus.FAILED));
            sessionStore.pushEvent(sessionId, AskUserSseEvent.failed("Agent 执行失败: " + e.getMessage()));
        } finally {
            AskUserSessionHolder.clear();
        }
    }
}
