package com.jason.demo.demo2.agentscope.service;

import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import com.jason.demo.demo2.agentscope.model.DevAgentEvent;
import com.jason.demo.demo2.agentscope.model.DevAgentRequest;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class DevAgentService {

    private final HarnessAgent agentscopeDevAgent;
    private final DevAgentProperties properties;

    public DevAgentService(HarnessAgent agentscopeDevAgent, DevAgentProperties properties) {
        this.agentscopeDevAgent = agentscopeDevAgent;
        this.properties = properties;
    }

    public Flux<DevAgentEvent> ask(DevAgentRequest request) {
        String sessionId = request.sessionId();
        String apiKey = properties.model().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return Flux.just(
                    DevAgentEvent.session(sessionId),
                    DevAgentEvent.error(sessionId, "DEEPSEEK_API_KEY is not configured"));
        }

        RuntimeContext.Builder contextBuilder = RuntimeContext.builder().sessionId(sessionId);
        if (request.userId() != null && !request.userId().isBlank()) {
            contextBuilder.userId(request.userId().strip());
        }
        RuntimeContext context = contextBuilder.build();

        Flux<DevAgentEvent> events = agentscopeDevAgent
                .streamEvents(request.message(), context)
                .handle((event, sink) -> {
                    DevAgentEvent mapped = mapEvent(sessionId, event);
                    if (mapped != null) {
                        sink.next(mapped);
                    }
                });

        return Flux.concat(
                        Mono.just(DevAgentEvent.session(sessionId)),
                        events,
                        Mono.just(DevAgentEvent.done(sessionId)))
                .onErrorResume(ex -> Flux.just(DevAgentEvent.error(
                        sessionId,
                        ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage())));
    }

    private DevAgentEvent mapEvent(String sessionId, AgentEvent event) {
        return switch (event.getType()) {
            case AGENT_START -> DevAgentEvent.lifecycle(
                    "AGENT_START",
                    sessionId,
                    event.getId(),
                    "Agent 开始");
            case MODEL_CALL_START -> DevAgentEvent.lifecycle(
                    "MODEL_CALL_START",
                    sessionId,
                    event.getId(),
                    "模型调用开始");
            case AGENT_END -> DevAgentEvent.lifecycle(
                    "AGENT_END",
                    sessionId,
                    event.getId(),
                    "Agent 结束");
            case TEXT_BLOCK_DELTA -> DevAgentEvent.message(
                    sessionId, ((TextBlockDeltaEvent) event).getDelta());
            case TOOL_CALL_START -> {
                ToolCallStartEvent e = (ToolCallStartEvent) event;
                yield DevAgentEvent.toolCallStart(
                        sessionId,
                        e.getId(),
                        e.getToolCallId(),
                        e.getToolCallName(),
                        "准备调用工具：" + e.getToolCallName());
            }
            case TOOL_RESULT_END -> {
                ToolResultEndEvent e = (ToolResultEndEvent) event;
                yield DevAgentEvent.toolResultEnd(
                        sessionId,
                        e.getId(),
                        e.getToolCallId(),
                        e.getToolCallName(),
                        e.getState() == null ? null : e.getState().name());
            }
            case AGENT_RESULT -> {
                AgentResultEvent e = (AgentResultEvent) event;
                String text = e.getResult() == null ? "" : e.getResult().getTextContent();
                yield DevAgentEvent.agentResult(sessionId, e.getId(), text);
            }
            default -> null;
        };
    }
}
