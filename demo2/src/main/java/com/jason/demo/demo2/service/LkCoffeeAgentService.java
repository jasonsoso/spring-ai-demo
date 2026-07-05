package com.jason.demo.demo2.service;

import com.jason.demo.demo2.config.LkCoffeeAgentConfig;
import com.jason.demo.demo2.model.LkCoffeeChatRequest;
import com.jason.demo.demo2.model.LkCoffeeSseEvent;
import com.jason.demo.demo2.mcp.client.LkCoffeeMcpToolCallbacksProvider;
import com.jason.demo.demo2.mcp.client.McpConnection;
import com.jason.demo.demo2.mcp.client.config.McpClientLifecycle;
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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@ConditionalOnProperty(name = "agent.lkcoffee.enabled", havingValue = "true", matchIfMissing = true)
public class LkCoffeeAgentService {

    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");
    private static final Pattern GEO_LOCATION_PATTERN =
            Pattern.compile("\"location\"\\s*:\\s*\"([\\d.]+)\\s*,\\s*([\\d.]+)\"");

    private final ChatClient.Builder chatClientBuilder;
    private final LkCoffeeSkillLoader skillLoader;
    private final LkCoffeeAgentConfig config;
    private final LkCoffeeMcpToolCallbacksProvider toolCallbacksProvider;
    private final McpClientLifecycle mcpClientLifecycle;
    private final MessageChatMemoryAdvisor memoryAdvisor;
    private final ChatMemory chatMemory;
    private final JsonMapper jsonMapper;
    private volatile ChatClient chatClient;

    @Autowired
    public LkCoffeeAgentService(
            ChatClient.Builder chatClientBuilder,
            LkCoffeeSkillLoader skillLoader,
            LkCoffeeAgentConfig config,
            LkCoffeeMcpToolCallbacksProvider toolCallbacksProvider,
            McpClientLifecycle mcpClientLifecycle,
            JsonMapper jsonMapper,
            @Qualifier("lkCoffeeMessageChatMemoryAdvisor") MessageChatMemoryAdvisor memoryAdvisor,
            @Qualifier("lkCoffeeChatMemory") ChatMemory lkCoffeeChatMemory) {
        this.chatClientBuilder = chatClientBuilder;
        this.skillLoader = skillLoader;
        this.config = config;
        this.toolCallbacksProvider = toolCallbacksProvider;
        this.mcpClientLifecycle = mcpClientLifecycle;
        this.jsonMapper = jsonMapper;
        this.memoryAdvisor = memoryAdvisor;
        this.chatMemory = lkCoffeeChatMemory;
    }

    /** 包级可见，供单元测试 validateSessionId */
    LkCoffeeAgentService(ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
        this.chatClientBuilder = null;
        this.skillLoader = null;
        this.config = null;
        this.toolCallbacksProvider = null;
        this.mcpClientLifecycle = null;
        this.jsonMapper = null;
        this.memoryAdvisor = null;
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

    public void streamChat(LkCoffeeChatRequest request, SseEmitter emitter, JsonMapper jsonMapper) {
        validateSessionId(request.getSessionId());
        if (!StringUtils.hasText(request.getMessage())) {
            sendSse(emitter, jsonMapper, LkCoffeeSseEvent.failed("message 不能为空"));
            emitter.complete();
            return;
        }

        if (!StringUtils.hasText(config.resolveDefaultToken())) {
            sendSse(emitter, jsonMapper, LkCoffeeSseEvent.failed(
                    "缺少瑞幸 Token：请设置环境变量 LKCOFFEE_TOKEN 后重启应用，前往 https://open.lkcoffee.com/mcp 获取"));
            emitter.complete();
            return;
        }

        mcpClientLifecycle.ensureLkCoffeeAndAmapIfConfigured();
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
                                LkCoffeeStreamContext.clear();
                                emitter.completeWithError(err);
                            },
                            () -> {
                                sendSse(emitter, jsonMapper, LkCoffeeSseEvent.completed());
                                LkCoffeeStreamContext.clear();
                                emitter.complete();
                            });
        } catch (Exception e) {
            log.error("LkCoffee chat failed, sessionId={}", request.getSessionId(), e);
            sendSse(emitter, jsonMapper, LkCoffeeSseEvent.failed(e.getMessage()));
            LkCoffeeStreamContext.clear();
            emitter.completeWithError(e);
        }
    }

    public Map<String, Object> geocodeAddress(String address, String city) {
        if (!StringUtils.hasText(address)) {
            throw new IllegalArgumentException("address 不能为空");
        }
        mcpClientLifecycle.ensureInitialized(McpConnection.AMAP);
        ToolCallback geoTool = findGeoTool()
                .orElseThrow(() -> new IllegalStateException(
                        "高德 geocode 工具不可用，请检查 AMAP_API_KEY 并确认 MCP 已初始化"));
        Map<String, String> args = new HashMap<>();
        args.put("address", address.trim());
        if (StringUtils.hasText(city)) {
            args.put("city", city.trim());
        }
        String raw = geoTool.call(jsonMapper.writeValueAsString(args));
        return parseGeoResult(raw);
    }

    private Map<String, Object> parseGeoResult(String raw) {
        Map<String, Object> result = new HashMap<>();
        result.put("raw", raw);
        if (!StringUtils.hasText(raw)) {
            return result;
        }
        try {
            extractGeoFields(jsonMapper.readTree(raw), result);
            if (result.containsKey("longitude") && result.containsKey("latitude")) {
                return result;
            }
        } catch (RuntimeException ignored) {
            // fall through to regex
        }
        Matcher matcher = GEO_LOCATION_PATTERN.matcher(raw);
        if (matcher.find()) {
            result.put("longitude", Double.parseDouble(matcher.group(1)));
            result.put("latitude", Double.parseDouble(matcher.group(2)));
        }
        return result;
    }

    private void extractGeoFields(JsonNode node, Map<String, Object> result) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isString()) {
            String text = node.asString().trim();
            if (text.startsWith("{") || text.startsWith("[")) {
                try {
                    extractGeoFields(jsonMapper.readTree(text), result);
                } catch (RuntimeException ignored) {
                    // embedded JSON in MCP text block may be malformed
                }
            }
            return;
        }
        if (node.isObject()) {
            if (node.hasNonNull("longitude") && node.hasNonNull("latitude")) {
                result.put("longitude", node.get("longitude").asDouble());
                result.put("latitude", node.get("latitude").asDouble());
            }
            JsonNode formatted = firstPresent(node, "formattedAddress", "formatted_address");
            if (formatted != null) {
                result.put("formattedAddress", formatted.asString());
            }
            JsonNode results = node.get("results");
            if (results != null && results.isArray() && !results.isEmpty()) {
                applyAmapGeoHit(results.get(0), result);
            }
            JsonNode location = node.get("location");
            if (location != null && location.isString()) {
                putLocationString(location.asString(), result);
            }
            node.properties().iterator().forEachRemaining(entry -> extractGeoFields(entry.getValue(), result));
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> extractGeoFields(child, result));
        }
    }

    private void applyAmapGeoHit(JsonNode hit, Map<String, Object> result) {
        if (hit == null || hit.isNull()) {
            return;
        }
        JsonNode location = hit.get("location");
        if (location != null && location.isString()) {
            putLocationString(location.asString(), result);
        }
        if (!result.containsKey("formattedAddress")) {
            StringBuilder formatted = new StringBuilder();
            for (String field : new String[]{"province", "city", "district", "street", "number"}) {
                JsonNode part = hit.get(field);
                if (part != null && part.isString() && StringUtils.hasText(part.asString())) {
                    formatted.append(part.asString());
                }
            }
            if (!formatted.isEmpty()) {
                result.put("formattedAddress", formatted.toString());
            }
        }
    }

    private static JsonNode firstPresent(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private static void putLocationString(String location, Map<String, Object> result) {
        if (!StringUtils.hasText(location)) {
            return;
        }
        String[] parts = location.split(",");
        if (parts.length != 2) {
            return;
        }
        result.put("longitude", Double.parseDouble(parts[0].trim()));
        result.put("latitude", Double.parseDouble(parts[1].trim()));
    }

    private java.util.Optional<ToolCallback> findGeoTool() {
        return Arrays.stream(toolCallbacksProvider.getToolCallbacks())
                .filter(tc -> {
                    String name = tc.getToolDefinition().name().toLowerCase();
                    return name.contains("maps_geo") || name.contains("geocode");
                })
                .filter(tc -> !tc.getToolDefinition().name().toLowerCase().contains("regeocode"))
                .findFirst();
    }

    private ChatClient chatClient() {
        ToolCallback[] tools = toolCallbacksProvider.getToolCallbacks();
        ChatClient client = chatClient;
        if (client != null) {
            return client;
        }
        synchronized (this) {
            if (chatClient == null) {
                chatClient = chatClientBuilder.clone()
                        .defaultSystem(skillLoader.buildSystemPrompt())
                        .defaultOptions(DeepSeekChatOptions.builder().model(config.getLkCoffeeChatModel()))
                        .defaultTools(tools)
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
