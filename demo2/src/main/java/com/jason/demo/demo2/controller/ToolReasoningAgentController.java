package com.jason.demo.demo2.controller;

import com.jason.demo.demo2.model.ToolReasoningChatRequest;
import com.jason.demo.demo2.service.ToolReasoningAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

@Tag(name = "ToolReasoning", description = "Tool Argument Augmentation 工具推理捕获 Demo（SSE 对话式）")
@RestController
@RequestMapping("/agent/tool-reasoning")
@RequiredArgsConstructor
public class ToolReasoningAgentController {

    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final ToolReasoningAgentService toolReasoningAgentService;
    private final JsonMapper jsonMapper;

    @Operation(summary = "SSE 流式对话", description = "多轮 ChatMemory；实时推送 TOOL_REASONING + TOKEN")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ToolReasoningChatRequest request) {
        validateRequest(request);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        toolReasoningAgentService.streamChat(
                request.getSessionId(), request.getMessage(), emitter, jsonMapper);
        return emitter;
    }

    @Operation(summary = "清除会话记忆")
    @DeleteMapping("/clear")
    public Map<String, String> clear(
            @Parameter(description = "会话 ID") @RequestParam("sessionId") String sessionId) {
        try {
            toolReasoningAgentService.clearSession(sessionId);
            return Map.of("sessionId", sessionId, "message", "会话记忆已清除");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private void validateRequest(ToolReasoningChatRequest request) {
        try {
            toolReasoningAgentService.validateSessionId(request.getSessionId());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
    }
}
