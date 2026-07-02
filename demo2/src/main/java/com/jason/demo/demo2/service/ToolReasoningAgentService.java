package com.jason.demo.demo2.service;

import com.jason.demo.demo2.config.ToolReasoningAgentConfig;
import com.jason.demo.demo2.model.AgentThinking;
import com.jason.demo.demo2.model.ToolReasoningSseEvent;
import com.jason.demo.demo2.sse.ToolReasoningStreamContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.tool.augment.AugmentedToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ToolReasoningAgentService {

    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");

    private static final String SYSTEM_PROMPT = """
            你是一个具备工具调用能力的智能行程规划 Agent，核心规则如下：
            1. 当用户询问天气时，必须调用 getWeather 工具获取实时天气数据；
            2. 当用户需要景点推荐时，必须调用 recommendAttractions 工具获取景点信息；
            3. 结合天气数据和景点信息，生成完整的行程规划建议；
            4. 行程安排要考虑天气因素（如雨天推荐室内景点，晴天推荐户外景点）；
            5. 输出结构清晰，包含天气概况、推荐景点、行程安排、实用提示；
            6. 所有实时信息必须通过工具获取，严禁编造天气或景点数据。
            7. 每次调用工具时，必须在 innerThought 中说明：为何选择该工具、期望获得什么、如何影响后续规划；confidence 如实填写 low/medium/high。
            回复风格：简洁专业，突出实用信息，适合移动端阅读。
            """;

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    public ToolReasoningAgentService(
            ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory,
            @Qualifier("toolReasoningMessageChatMemoryAdvisor") MessageChatMemoryAdvisor messageChatMemoryAdvisor,
            AugmentedToolCallbackProvider<AgentThinking> toolReasoningProvider,
            String toolReasoningChatModel) {
        this.chatMemory = chatMemory;
        this.chatClient = chatClientBuilder.clone()
                .defaultSystem(SYSTEM_PROMPT)
                .defaultOptions(DeepSeekChatOptions.builder().model(toolReasoningChatModel))
                .defaultTools(toolReasoningProvider)
                .defaultAdvisors(
                        messageChatMemoryAdvisor,
                        ToolCallingAdvisor.builder().build())
                .build();
    }

    @Autowired
    public ToolReasoningAgentService(
            ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory,
            @Qualifier("toolReasoningMessageChatMemoryAdvisor") MessageChatMemoryAdvisor messageChatMemoryAdvisor,
            AugmentedToolCallbackProvider<AgentThinking> toolReasoningProvider,
            ToolReasoningAgentConfig config) {
        this(chatClientBuilder, chatMemory, messageChatMemoryAdvisor,
                toolReasoningProvider, config.getToolReasoningChatModel());
    }

    /** 仅用于单元测试 validateSessionId / clearSession */
    ToolReasoningAgentService(ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
        this.chatClient = null;
    }

    public void validateSessionId(String sessionId) {
        if (sessionId == null || !SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("sessionId 仅允许字母、数字、下划线与连字符");
        }
    }

    public void clearSession(String sessionId) {
        validateSessionId(sessionId);
        chatMemory.clear(sessionId);
    }

    public void streamChat(String sessionId, String message, SseEmitter emitter, JsonMapper jsonMapper) {
        validateSessionId(sessionId);
        ToolReasoningStreamContext.set(emitter, jsonMapper, new AtomicInteger(0));
        try {
            sendSse(emitter, jsonMapper, ToolReasoningSseEvent.running());
            chatClient.prompt()
                    .user(message)
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .stream()
                    .content()
                    .subscribe(
                            chunk -> sendSse(emitter, jsonMapper, ToolReasoningSseEvent.token(chunk)),
                            err -> {
                                log.error("Tool reasoning stream failed, sessionId={}", sessionId, err);
                                sendSse(emitter, jsonMapper,
                                        ToolReasoningSseEvent.failed(err.getMessage()));
                                ToolReasoningStreamContext.clear();
                                emitter.completeWithError(err);
                            },
                            () -> {
                                sendSse(emitter, jsonMapper, ToolReasoningSseEvent.completed());
                                ToolReasoningStreamContext.clear();
                                emitter.complete();
                            });
        } catch (Exception e) {
            log.error("Tool reasoning chat failed, sessionId={}", sessionId, e);
            sendSse(emitter, jsonMapper, ToolReasoningSseEvent.failed(e.getMessage()));
            ToolReasoningStreamContext.clear();
            emitter.completeWithError(e);
        }
    }

    private void sendSse(SseEmitter emitter, JsonMapper jsonMapper, ToolReasoningSseEvent event) {
        try {
            emitter.send(SseEmitter.event().data(jsonMapper.writeValueAsString(event)).build());
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
}
