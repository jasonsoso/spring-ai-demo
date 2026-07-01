package com.jason.demo.demo2.controller;

import com.jason.demo.demo2.model.SessionMemoryChatRequest;
import com.jason.demo.demo2.service.SessionMemoryTripAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Tag(name = "SessionMemory", description = "Session API 事件溯源短期记忆 + RecursiveSummarization Demo")
@RestController
@RequestMapping("/agent/session-memory")
@RequiredArgsConstructor
public class SessionMemoryAgentController {

    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final SessionMemoryTripAgentService sessionMemoryTripAgentService;
    private final JsonMapper jsonMapper;
    private final ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor();

    @Operation(summary = "SSE 流式对话", description = "固定 userId 多轮；SessionMemoryAdvisor 自动加载/追加/压缩")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody SessionMemoryChatRequest request) {
        validateRequest(request);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        virtualThreads.execute(() ->
                sessionMemoryTripAgentService.streamChat(
                        request.getUserId(), request.getMessage(), emitter, jsonMapper));
        return emitter;
    }

    @Operation(summary = "事件摘要", description = "active/archived/synthetic 统计 + 最近 20 条元数据")
    @GetMapping("/events")
    public Map<String, Object> events(
            @Parameter(description = "用户/会话 ID", example = "1001")
            @RequestParam("userId") String userId) {
        try {
            return sessionMemoryTripAgentService.listEvents(userId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @Operation(summary = "清除会话", description = "删除该 userId 的 Session 及全部 events")
    @DeleteMapping("/clear")
    public Map<String, String> clear(
            @Parameter(description = "用户/会话 ID", example = "1001")
            @RequestParam("userId") String userId) {
        try {
            sessionMemoryTripAgentService.clearSession(userId);
            return Map.of("userId", userId, "message", "Session 及事件日志已清除");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private void validateRequest(SessionMemoryChatRequest request) {
        try {
            sessionMemoryTripAgentService.validateUserId(request.getUserId());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
    }
}
