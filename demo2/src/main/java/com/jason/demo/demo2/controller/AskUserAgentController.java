package com.jason.demo.demo2.controller;

import com.jason.demo.demo2.model.AskUserAnswerRequest;
import com.jason.demo.demo2.model.AskUserAnswerResponse;
import com.jason.demo.demo2.model.AskUserChatRequest;
import com.jason.demo.demo2.model.AskUserChatResponse;
import com.jason.demo.demo2.service.AskUserAgentService;
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

@Tag(name = "AskUserQuestion", description = "AskUserQuestionTool 技术选型 Demo（SSE + POST）")
@RestController
@RequestMapping("/agent/ask-user")
@RequiredArgsConstructor
public class AskUserAgentController {

    private final AskUserAgentService askUserAgentService;

    @Operation(summary = "发起技术选型对话", description = "创建 Session 并异步启动 Agent，返回 sessionId")
    @PostMapping("/chat")
    public AskUserChatResponse chat(@RequestBody AskUserChatRequest request) {
        String sessionId = askUserAgentService.startChat(request.getMessage());
        return new AskUserChatResponse(sessionId);
    }

    @Operation(summary = "订阅 SSE 事件流", description = "推送 RUNNING / QUESTIONS / COMPLETED / FAILED 事件")
    @GetMapping(value = "/sse/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sse(@PathVariable String sessionId) {
        return askUserAgentService.connectSse(sessionId);
    }

    @Operation(summary = "提交澄清问题答案")
    @PostMapping("/answer")
    public AskUserAnswerResponse answer(@RequestBody AskUserAnswerRequest request) {
        askUserAgentService.submitAnswer(request.getSessionId(), request.getAnswers());
        return new AskUserAnswerResponse("accepted");
    }
}
