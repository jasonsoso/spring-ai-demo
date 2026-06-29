package com.jason.demo.demo2.controller;

import com.jason.demo.demo2.model.TodoChatRequest;
import com.jason.demo.demo2.model.TodoChatResponse;
import com.jason.demo.demo2.service.TodoAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "TodoWrite", description = "TodoWriteTool 学习计划 Demo（SSE 实时 Todo 看板）")
@RestController
@RequestMapping("/agent/todo")
@RequiredArgsConstructor
public class TodoAgentController {

    private final TodoAgentService todoAgentService;

    @Operation(summary = "发起学习计划对话", description = "创建 Session 并异步启动 Agent，返回 sessionId")
    @PostMapping("/chat")
    public TodoChatResponse chat(@RequestBody TodoChatRequest request) {
        String sessionId = todoAgentService.startChat(request.getMessage());
        return new TodoChatResponse(sessionId);
    }

    @Operation(summary = "订阅 SSE 事件流", description = "推送 RUNNING / TODOS / COMPLETED / FAILED 事件")
    @GetMapping(value = "/sse/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sse(@PathVariable String sessionId) {
        return todoAgentService.connectSse(sessionId);
    }
}
