package com.jason.demo.demo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jason.demo.demo.model.ChatRequest;
import com.jason.demo.demo.model.ChatResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@Tag(name = "AI 聊天")
@RestController
@RequestMapping("/ai")
public class ChatController {

    private final ChatClient chatClient;

    @Autowired
    private ObjectMapper objectMapper;

    public ChatController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Operation(summary = "同步聊天", description = "发送消息并等待 DeepSeek 返回完整回复")
    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest chatRequest) {
        String resp = chatClient.prompt()
                .user(chatRequest.getMessage())
                .call()
                .content();
        return new ChatResponse(resp);
    }

    @Operation(summary = "流式聊天", description = "通过 SSE 流式返回回复；Swagger UI 无法完整模拟流式响应，请使用 curl 或前端 EventSource 测试")
    @PostMapping("/chatStream")
    public SseEmitter chatStream(@RequestBody ChatRequest chatRequest) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        chatClient.prompt()
                .user(chatRequest.getMessage())
                .stream()
                .content()
                .subscribe(
                        chunk -> {
                            try {
                                ChatResponse chatResponse = new ChatResponse();
                                chatResponse.setResponse(chunk);
                                chatResponse.setCode(200);
                                chatResponse.setMessage("streaming");

                                String json = objectMapper.writeValueAsString(chatResponse);
                                emitter.send(SseEmitter.event().data(json).build());
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        emitter::completeWithError,
                        emitter::complete
                );

        return emitter;
    }
}
