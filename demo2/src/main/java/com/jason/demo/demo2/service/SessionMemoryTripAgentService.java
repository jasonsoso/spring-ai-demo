package com.jason.demo.demo2.service;

import com.jason.demo.demo2.config.SessionMemoryAgentConfig;
import com.jason.demo.demo2.model.SessionMemorySseEvent;
import com.jason.demo.demo2.tools.AttractionTool;
import com.jason.demo.demo2.tools.WeatherTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.tool.SessionEventTools;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SessionMemoryTripAgentService {

    private static final Pattern USER_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");
    private static final int EVENT_PREVIEW_LIMIT = 20;

    private static final String SYSTEM_PROMPT = """
            你是带 Session 事件溯源短期记忆的智能行程规划 Agent，严格遵守：
            1. 查询天气必须调用 getWeather；推荐景点必须调用 recommendAttractions；
            2. 优先使用当前会话历史；若早期细节不在上下文中，主动调用 conversation_search 检索；
            3. 结合用户偏好生成按天/时段划分的行程，语言简洁、结构清晰。
            """;

    private final ChatClient.Builder chatClientBuilder;
    private final SessionService sessionService;
    private final SessionMemoryAdvisor sessionMemoryAdvisor;
    private final SessionEventTools sessionEventTools;
    private final WeatherTool weatherTool;
    private final AttractionTool attractionTool;
    private final String sessionChatModel;

    SessionMemoryTripAgentService(
            ChatClient.Builder chatClientBuilder,
            SessionService sessionService,
            SessionMemoryAdvisor sessionMemoryAdvisor,
            SessionEventTools sessionEventTools,
            WeatherTool weatherTool,
            AttractionTool attractionTool,
            String sessionChatModel) {
        this.chatClientBuilder = chatClientBuilder;
        this.sessionService = sessionService;
        this.sessionMemoryAdvisor = sessionMemoryAdvisor;
        this.sessionEventTools = sessionEventTools;
        this.weatherTool = weatherTool;
        this.attractionTool = attractionTool;
        this.sessionChatModel = sessionChatModel;
    }

    @Autowired
    public SessionMemoryTripAgentService(
            ChatClient.Builder chatClientBuilder,
            SessionService sessionService,
            SessionMemoryAdvisor sessionMemoryAdvisor,
            SessionEventTools sessionEventTools,
            WeatherTool weatherTool,
            AttractionTool attractionTool,
            SessionMemoryAgentConfig config) {
        this(chatClientBuilder, sessionService, sessionMemoryAdvisor, sessionEventTools,
                weatherTool, attractionTool, config.getSessionChatModel());
    }

    public void validateUserId(String userId) {
        if (userId == null || !USER_ID_PATTERN.matcher(userId).matches()) {
            throw new IllegalArgumentException("userId 仅允许字母、数字、下划线与连字符");
        }
    }

    public Map<String, Object> listEvents(String userId) {
        validateUserId(userId);
        Session session = sessionService.findById(userId);
        if (session == null) {
            return emptyEventStats(userId);
        }

        List<SessionEvent> all = sessionService.getEvents(userId);
        long synthetic = all.stream().filter(SessionEvent::isSynthetic).count();
        int promptMessageCount = sessionService.getMessages(userId).size();
        long activeEvents = Math.max(0, all.size() - synthetic);
        long archivedEvents = Math.max(0, all.size() - promptMessageCount);

        List<Map<String, Object>> preview = all.stream()
                .sorted(Comparator.comparing(SessionEvent::getTimestamp).reversed())
                .limit(EVENT_PREVIEW_LIMIT)
                .map(this::toEventSummary)
                .collect(Collectors.toCollection(ArrayList::new));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("totalEvents", all.size());
        result.put("activeEvents", activeEvents);
        result.put("archivedEvents", archivedEvents);
        result.put("syntheticEvents", synthetic);
        result.put("events", preview);
        return result;
    }

    public void clearSession(String userId) {
        validateUserId(userId);
        if (sessionService.findById(userId) != null) {
            sessionService.delete(userId);
        }
    }

    public void streamChat(String userId, String message, SseEmitter emitter, JsonMapper jsonMapper) {
        validateUserId(userId);
        ChatClient client = buildChatClient(userId);
        client.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, userId))
                .toolContext(Map.of(SessionEventTools.SESSION_ID_CONTEXT_KEY, userId))
                .stream()
                .content()
                .subscribe(
                        chunk -> sendSse(emitter, jsonMapper, SessionMemorySseEvent.token(chunk)),
                        err -> {
                            log.error("Session 流式对话失败, userId={}", userId, err);
                            sendSse(emitter, jsonMapper, SessionMemorySseEvent.failed(err.getMessage()));
                            emitter.completeWithError(err);
                        },
                        () -> {
                            sendSse(emitter, jsonMapper, SessionMemorySseEvent.completed());
                            emitter.complete();
                        });
    }

    private ChatClient buildChatClient(String userId) {
        return chatClientBuilder.clone()
                .defaultOptions(DeepSeekChatOptions.builder().model(sessionChatModel))
                .defaultTools(sessionEventTools, weatherTool, attractionTool)
                .defaultAdvisors(
                        sessionMemoryAdvisor,
                        ToolCallingAdvisor.builder().disableInternalConversationHistory().build())
                .defaultSystem(SYSTEM_PROMPT)
                .defaultToolContext(Map.of(SessionEventTools.SESSION_ID_CONTEXT_KEY, userId))
                .build();
    }

    private Map<String, Object> emptyEventStats(String userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("totalEvents", 0);
        result.put("activeEvents", 0);
        result.put("archivedEvents", 0);
        result.put("syntheticEvents", 0);
        result.put("events", List.of());
        return result;
    }

    private Map<String, Object> toEventSummary(SessionEvent event) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("eventId", event.getId());
        summary.put("messageType", event.getMessageType().name());
        summary.put("synthetic", event.isSynthetic());
        summary.put("hasToolCalls", event.hasToolCalls());
        summary.put("timestamp", event.getTimestamp().toString());
        return summary;
    }

    private void sendSse(SseEmitter emitter, JsonMapper jsonMapper, SessionMemorySseEvent event) {
        try {
            emitter.send(SseEmitter.event().data(jsonMapper.writeValueAsString(event)).build());
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
}
