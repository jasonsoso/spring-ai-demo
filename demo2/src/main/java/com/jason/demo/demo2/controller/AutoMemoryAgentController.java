package com.jason.demo.demo2.controller;

import com.jason.demo.demo2.model.AutoMemoryChatRequest;
import com.jason.demo.demo2.service.AutoMemoryTripAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Tag(name = "AutoMemory", description = "AutoMemoryTools 自主持久记忆 + MySQL 短暂记忆 Demo")
@RestController
@RequestMapping("/agent/auto-memory")
@RequiredArgsConstructor
public class AutoMemoryAgentController {

    private final AutoMemoryTripAgentService autoMemoryTripAgentService;

    @Operation(summary = "自主记忆对话", description = "MySQL 短期记忆 + AutoMemoryTools 长期 Markdown 记忆")
    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody AutoMemoryChatRequest request) {
        validateUserId(request.getUserId());
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        String reply = autoMemoryTripAgentService.chat(request.getUserId(), request.getMessage());
        return Map.of(
                "userId", request.getUserId(),
                "message", request.getMessage(),
                "reply", reply,
                "agentType", "AutoMemoryTools 行程 Agent"
        );
    }

    @Operation(summary = "列出记忆文件", description = "列出该 userId 目录下 MEMORY.md 与 .md 文件")
    @GetMapping("/memories")
    public Map<String, Object> listMemories(
            @Parameter(description = "用户唯一标识", example = "1001")
            @RequestParam("userId") String userId) {
        validateUserId(userId);
        return autoMemoryTripAgentService.listMemories(userId);
    }

    @Operation(summary = "清除记忆", description = "清除 MySQL 短期记忆并删除长期记忆目录")
    @DeleteMapping("/clear-memory")
    public Map<String, String> clearMemory(
            @Parameter(description = "用户唯一标识", example = "1001")
            @RequestParam("userId") String userId) {
        validateUserId(userId);
        autoMemoryTripAgentService.clearMemory(userId);
        return Map.of(
                "userId", userId,
                "message", "短期记忆与长期记忆目录已清除"
        );
    }

    private static void validateUserId(String userId) {
        try {
            if (userId == null || userId.isBlank()) {
                throw new IllegalArgumentException("userId 不能为空");
            }
            if (!userId.matches("^[a-zA-Z0-9_-]+$")) {
                throw new IllegalArgumentException("userId 仅允许字母、数字、下划线与连字符");
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
