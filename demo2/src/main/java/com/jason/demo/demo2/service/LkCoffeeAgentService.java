package com.jason.demo.demo2.service;

import com.jason.demo.demo2.config.LkCoffeeAgentConfig;
import com.jason.demo.demo2.model.LkCoffeeChatRequest;
import com.jason.demo.demo2.model.LkCoffeeSseEvent;
import com.jason.demo.demo2.mcp.client.LkCoffeeMcpToolCallbacksProvider;
import com.jason.demo.demo2.mcp.client.LkCoffeeTokenContext;
import com.jason.demo.demo2.sse.LkCoffeeStreamContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Service
@ConditionalOnProperty(name = "agent.lkcoffee.enabled", havingValue = "true", matchIfMissing = true)
public class LkCoffeeAgentService {

    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");

    private final ChatClient.Builder chatClientBuilder;
    private final LkCoffeeSkillLoader skillLoader;
    private final LkCoffeeAgentConfig config;
    private final LkCoffeeMcpToolCallbacksProvider toolCallbacksProvider;
    private final MessageChatMemoryAdvisor memoryAdvisor;
    private final ChatMemory chatMemory;
    private final String defaultToken;
    private volatile ChatClient chatClient;

    @Autowired
    public LkCoffeeAgentService(
            ChatClient.Builder chatClientBuilder,
            LkCoffeeSkillLoader skillLoader,
            LkCoffeeAgentConfig config,
            LkCoffeeMcpToolCallbacksProvider toolCallbacksProvider,
            @Qualifier("lkCoffeeMessageChatMemoryAdvisor") MessageChatMemoryAdvisor memoryAdvisor,
            @Qualifier("lkCoffeeChatMemory") ChatMemory lkCoffeeChatMemory) {
        this.chatClientBuilder = chatClientBuilder;
        this.skillLoader = skillLoader;
        this.config = config;
        this.toolCallbacksProvider = toolCallbacksProvider;
        this.memoryAdvisor = memoryAdvisor;
        this.chatMemory = lkCoffeeChatMemory;
        this.defaultToken = config.getDefaultToken();
    }

    /** 包级可见，供单元测试 validateSessionId / resolveToken */
    LkCoffeeAgentService(ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
        this.defaultToken = "";
        this.chatClientBuilder = null;
        this.skillLoader = null;
        this.config = null;
        this.toolCallbacksProvider = null;
        this.memoryAdvisor = null;
        this.chatClient = null;
    }

    public String resolveToken(String requestToken, String envToken) {
        if (StringUtils.hasText(requestToken)) {
            return requestToken.trim();
        }
        return envToken != null ? envToken.trim() : "";
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

    public void streamChat(LkCoffeeChatRequest request, SseEmitter emitter, JsonMapper jsonMapper) {
        validateSessionId(request.getSessionId());
        if (!StringUtils.hasText(request.getMessage())) {
            sendSse(emitter, jsonMapper, LkCoffeeSseEvent.failed("message 不能为空"));
            emitter.complete();
            return;
        }

        String token = resolveToken(request.getToken(), defaultToken);
        if (!StringUtils.hasText(token)) {
            sendSse(emitter, jsonMapper, LkCoffeeSseEvent.failed(
                    "缺少瑞幸 Token，请在 Tab 设置或配置 LKCOFFEE_TOKEN，前往 https://open.lkcoffee.com/mcp 获取"));
            emitter.complete();
            return;
        }

        LkCoffeeTokenContext.set(token);
        LkCoffeeStreamContext.bind(emitter, jsonMapper);
        try {
            sendSse(emitter, jsonMapper, LkCoffeeSseEvent.running());
            String userMessage = buildUserMessage(request);
            chatClient().prompt()
                    .user(userMessage)
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, request.getSessionId()))
                    .stream()
                    .content()
                    .subscribe(
                            chunk -> sendSse(emitter, jsonMapper, LkCoffeeSseEvent.token(chunk)),
                            err -> {
                                log.error("LkCoffee stream failed, sessionId={}", request.getSessionId(), err);
                                sendSse(emitter, jsonMapper, LkCoffeeSseEvent.failed(err.getMessage()));
                                LkCoffeeTokenContext.clear();
                                LkCoffeeStreamContext.clear();
                                emitter.completeWithError(err);
                            },
                            () -> {
                                sendSse(emitter, jsonMapper, LkCoffeeSseEvent.completed());
                                LkCoffeeTokenContext.clear();
                                LkCoffeeStreamContext.clear();
                                emitter.complete();
                            });
        } catch (Exception e) {
            log.error("LkCoffee chat failed, sessionId={}", request.getSessionId(), e);
            sendSse(emitter, jsonMapper, LkCoffeeSseEvent.failed(e.getMessage()));
            LkCoffeeTokenContext.clear();
            LkCoffeeStreamContext.clear();
            emitter.completeWithError(e);
        }
    }

    public Map<String, Object> geocodeAddress(String address, String city) {
        if (!StringUtils.hasText(address)) {
            throw new IllegalArgumentException("address 不能为空");
        }
        String prompt = city != null && !city.isBlank()
                ? "请仅调用地理编码工具，将地址「" + address + "」（城市：" + city + "）转换为经纬度，以 JSON 返回 longitude、latitude、formattedAddress。"
                : "请仅调用地理编码工具，将地址「" + address + "」转换为经纬度，以 JSON 返回 longitude、latitude、formattedAddress。";
        String content = chatClient().prompt().user(prompt).call().content();
        Map<String, Object> result = new HashMap<>();
        result.put("raw", content);
        return result;
    }

    private ChatClient chatClient() {
        ChatClient client = chatClient;
        if (client != null) {
            return client;
        }
        synchronized (this) {
            if (chatClient == null) {
                chatClient = chatClientBuilder.clone()
                        .defaultSystem(skillLoader.buildSystemPrompt())
                        .defaultOptions(DeepSeekChatOptions.builder().model(config.getLkCoffeeChatModel()))
                        .defaultTools(toolCallbacksProvider.getToolCallbacks())
                        .defaultAdvisors(memoryAdvisor, ToolCallingAdvisor.builder().build())
                        .build();
            }
            return chatClient;
        }
    }

    private String buildUserMessage(LkCoffeeChatRequest request) {
        StringBuilder sb = new StringBuilder();
        if (request.getLongitude() != null && request.getLatitude() != null) {
            sb.append("[当前坐标: longitude=").append(request.getLongitude())
                    .append(", latitude=").append(request.getLatitude()).append("]\n");
        }
        if (StringUtils.hasText(request.getAddress())) {
            sb.append("[用户地址: ").append(request.getAddress()).append("]\n");
        }
        sb.append(request.getMessage());
        return sb.toString();
    }

    private void sendSse(SseEmitter emitter, JsonMapper jsonMapper, LkCoffeeSseEvent event) {
        try {
            emitter.send(SseEmitter.event().data(jsonMapper.writeValueAsString(event)).build());
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
}
