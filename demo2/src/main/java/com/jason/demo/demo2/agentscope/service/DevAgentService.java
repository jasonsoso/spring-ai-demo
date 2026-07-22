package com.jason.demo.demo2.agentscope.service;

import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import com.jason.demo.demo2.agentscope.model.DevAgentConfirmRequest;
import com.jason.demo.demo2.agentscope.model.DevAgentEvent;
import com.jason.demo.demo2.agentscope.model.DevAgentEventType;
import com.jason.demo.demo2.agentscope.model.DevAgentRequest;
import com.jason.demo.demo2.agentscope.model.PendingToolCall;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DevAgentService {

    private final HarnessAgent agentscopeDevAgent;
    private final DevAgentProperties properties;
    private final ConcurrentHashMap<String, List<ToolUseBlock>> pendingConfirmations =
            new ConcurrentHashMap<>();

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

        String userId = normalizeUserId(request.userId());
        RuntimeContext context = RuntimeContext.builder()
                .sessionId(sessionId)
                .userId(userId)
                .build();

        Flux<DevAgentEvent> events = agentscopeDevAgent
                .streamEvents(request.message(), context)
                .handle((event, sink) -> {
                    DevAgentEvent mapped = mapEvent(userId, sessionId, event);
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

    public Flux<DevAgentEvent> confirm(DevAgentConfirmRequest request) {
        String sessionId = request.sessionId();
        String userId = normalizeUserId(request.userId());
        String apiKey = properties.model().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return Flux.just(
                    DevAgentEvent.session(sessionId),
                    DevAgentEvent.error(sessionId, "DEEPSEEK_API_KEY is not configured"));
        }

        List<ToolUseBlock> pending = pendingConfirmations.remove(confirmationKey(userId, sessionId));
        if (pending == null || pending.isEmpty()) {
            return Flux.just(
                    DevAgentEvent.session(sessionId),
                    DevAgentEvent.error(sessionId, "没有待确认的工具调用"));
        }

        List<ConfirmResult> confirmResults = pending.stream()
                .map(toolCall -> new ConfirmResult(request.approved(), toolCall))
                .toList();

        Msg resumeMessage = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(request.approved() ? "approved" : "denied")
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, confirmResults))
                .build();

        RuntimeContext context = RuntimeContext.builder()
                .sessionId(sessionId)
                .userId(userId)
                .build();

        Flux<DevAgentEvent> events = agentscopeDevAgent
                .streamEvents(resumeMessage, context)
                .handle((event, sink) -> {
                    DevAgentEvent mapped = mapEvent(userId, sessionId, event);
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

    private DevAgentEvent mapEvent(String userId, String sessionId, AgentEvent event) {
        return switch (event.getType()) {
            case AGENT_START -> DevAgentEvent.lifecycle(
                    DevAgentEventType.AGENT_START,
                    sessionId,
                    event.getId(),
                    "Agent 开始");
            case MODEL_CALL_START -> DevAgentEvent.lifecycle(
                    DevAgentEventType.MODEL_CALL_START,
                    sessionId,
                    event.getId(),
                    "模型调用开始");
            case AGENT_END -> DevAgentEvent.lifecycle(
                    DevAgentEventType.AGENT_END,
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
            case REQUIRE_USER_CONFIRM -> {
                RequireUserConfirmEvent e = (RequireUserConfirmEvent) event;
                List<ToolUseBlock> toolCalls = e.getToolCalls() == null ? List.of() : e.getToolCalls();
                pendingConfirmations.put(confirmationKey(userId, sessionId), List.copyOf(toolCalls));
                yield DevAgentEvent.confirmation(
                        sessionId,
                        e.getId(),
                        toolCalls.stream().map(this::toPendingToolCall).toList());
            }
            case REQUEST_STOP -> {
                RequestStopEvent e = (RequestStopEvent) event;
                String content = e.getGenerateReason() == null
                        ? e.getReason()
                        : e.getGenerateReason().name();
                yield DevAgentEvent.requestStop(sessionId, e.getId(), content);
            }
            default -> null;
        };
    }

    static String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "_anonymous" : userId.strip();
    }

    static String confirmationKey(String userId, String sessionId) {
        return normalizeUserId(userId) + "|" + sessionId;
    }

    private PendingToolCall toPendingToolCall(ToolUseBlock block) {
        return new PendingToolCall(block.getId(), block.getName(), block.getInput());
    }
}
