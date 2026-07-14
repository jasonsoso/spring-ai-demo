package com.jason.demo.demo2.embabel.controller;

import com.jason.demo.demo2.embabel.model.AgentRequest;
import com.jason.demo.demo2.embabel.model.AgentResponse;
import com.jason.demo.demo2.embabel.service.EmbabelAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Tag(name = "Embabel", description = "Embabel Agent 自动选路（Closed 模式）")
@RestController
@RequestMapping("/embabel/agent")
@RequiredArgsConstructor
public class EmbabelAgentController {

    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final EmbabelAgentService embabelAgentService;
    private final JsonMapper jsonMapper;
    private final ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor();

    @Operation(summary = "SSE 流式问答", description = "Autonomy 选路 + Action 进度事件 + 最终结果")
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@Valid @RequestBody AgentRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        virtualThreads.execute(() -> embabelAgentService.streamAsk(request.message(), emitter, jsonMapper));
        return emitter;
    }

    @Operation(summary = "同步问答（调试）", description = "curl / Scalar 调试用")
    @PostMapping("/ask")
    public AgentResponse ask(@Valid @RequestBody AgentRequest request) {
        return embabelAgentService.ask(request.message());
    }
}
