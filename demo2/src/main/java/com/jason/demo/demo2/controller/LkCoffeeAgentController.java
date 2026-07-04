package com.jason.demo.demo2.controller;

import com.jason.demo.demo2.model.LkCoffeeChatRequest;
import com.jason.demo.demo2.mcp.client.LkCoffeeMcpToolCallbacksProvider;
import com.jason.demo.demo2.service.LkCoffeeAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Tag(name = "LkCoffee", description = "瑞幸 MCP + My Coffee Skill SSE 点单 Demo")
@RestController
@RequestMapping("/agent/lkcoffee")
@ConditionalOnProperty(name = "agent.lkcoffee.enabled", havingValue = "true", matchIfMissing = true)
public class LkCoffeeAgentController {

    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final LkCoffeeAgentService lkCoffeeAgentService;
    private final JsonMapper jsonMapper;
    private final LkCoffeeMcpToolCallbacksProvider toolCallbacksProvider;

    public LkCoffeeAgentController(
            LkCoffeeAgentService lkCoffeeAgentService,
            JsonMapper jsonMapper,
            LkCoffeeMcpToolCallbacksProvider toolCallbacksProvider) {
        this.lkCoffeeAgentService = lkCoffeeAgentService;
        this.jsonMapper = jsonMapper;
        this.toolCallbacksProvider = toolCallbacksProvider;
    }

    @Operation(summary = "SSE 流式对话", description = "My Coffee Skill 编排 + 瑞幸/高德 MCP 工具")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody LkCoffeeChatRequest request) {
        validateRequest(request);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        lkCoffeeAgentService.streamChat(request, emitter, jsonMapper);
        return emitter;
    }

    @Operation(summary = "清除会话记忆")
    @DeleteMapping("/clear")
    public Map<String, String> clear(
            @Parameter(description = "会话 ID") @RequestParam("sessionId") String sessionId) {
        try {
            lkCoffeeAgentService.clearSession(sessionId);
            return Map.of("sessionId", sessionId, "message", "会话记忆已清除");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @Operation(summary = "当前可用 MCP 工具列表")
    @GetMapping("/tools")
    public List<String> listTools() {
        return Arrays.stream(toolCallbacksProvider.getToolCallbacks())
                .map(t -> t.getToolDefinition().name() + " - " + t.getToolDefinition().description())
                .toList();
    }

    @Operation(summary = "地址转经纬度", description = "内部调用高德 MCP 地理编码")
    @GetMapping("/geocode")
    public Map<String, Object> geocode(
            @RequestParam("address") String address,
            @RequestParam(value = "city", required = false) String city) {
        try {
            return lkCoffeeAgentService.geocodeAddress(address, city);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private void validateRequest(LkCoffeeChatRequest request) {
        try {
            lkCoffeeAgentService.validateSessionId(request.getSessionId());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
    }
}
