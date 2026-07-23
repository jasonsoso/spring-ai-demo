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
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Service
public class DevAgentService {

    private final HarnessAgent agentscopeDevAgent;
    private final DevAgentProperties properties;
    private final AgentStateStore agentStateStore;

    public DevAgentService(
            HarnessAgent agentscopeDevAgent,
            DevAgentProperties properties,
            AgentStateStore agentStateStore) {
        this.agentscopeDevAgent = agentscopeDevAgent;
        this.properties = properties;
        this.agentStateStore = agentStateStore;
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
        int beforeCount = contextMessageCount(userId, sessionId);
        RuntimeContext context = RuntimeContext.builder()
                .sessionId(sessionId)
                .userId(userId)
                .build();

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
                        Mono.defer(() -> compactionEventIfNeeded(userId, sessionId, beforeCount)),
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

        List<ToolUseBlock> pending = loadPendingToolCalls(userId, sessionId);
        if (pending.isEmpty()) {
            return Flux.just(
                    DevAgentEvent.session(sessionId),
                    DevAgentEvent.error(sessionId, "没有待确认的工具调用"));
        }

        int beforeCount = contextMessageCount(userId, sessionId);

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
                    DevAgentEvent mapped = mapEvent(sessionId, event);
                    if (mapped != null) {
                        sink.next(mapped);
                    }
                });

        return Flux.concat(
                        Mono.just(DevAgentEvent.session(sessionId)),
                        events,
                        Mono.defer(() -> compactionEventIfNeeded(userId, sessionId, beforeCount)),
                        Mono.just(DevAgentEvent.done(sessionId)))
                .onErrorResume(ex -> Flux.just(DevAgentEvent.error(
                        sessionId,
                        ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage())));
    }

    private int contextMessageCount(String userId, String sessionId) {
        try {
            return agentStateStore
                    .get(userId, sessionId, "agent_state", AgentState.class)
                    .map(state -> {
                        List<Msg> context = state.getContext();
                        return context == null ? 0 : context.size();
                    })
                    .orElse(0);
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    private Mono<DevAgentEvent> compactionEventIfNeeded(
            String userId, String sessionId, int beforeCount) {
        if (beforeCount < 0) {
            return Mono.empty();
        }
        int afterCount = contextMessageCount(userId, sessionId);
        if (afterCount <= 0 || afterCount >= beforeCount) {
            return Mono.empty();
        }
        int beforeDisplay = beforeCount + 1;
        int keep = properties.compaction().keepMessages();
        String content = "上下文已压缩："
                + beforeDisplay
                + " 条 → 1 条摘要 + "
                + keep
                + " 条原文（共 "
                + afterCount
                + " 条）";
        return Mono.just(DevAgentEvent.compaction(sessionId, content));
    }

    private List<ToolUseBlock> loadPendingToolCalls(String userId, String sessionId) {
        return agentStateStore
                .get(userId, sessionId, "agent_state", AgentState.class)
                .map(this::findAskingToolCalls)
                .orElseGet(List::of);
    }

    private List<ToolUseBlock> findAskingToolCalls(AgentState state) {
        List<Msg> context = state.getContext();
        if (context == null || context.isEmpty()) {
            return List.of();
        }
        for (int i = context.size() - 1; i >= 0; i--) {
            Msg msg = context.get(i);
            if (msg.getRole() == MsgRole.ASSISTANT) {
                return msg.getContentBlocks(ToolUseBlock.class).stream()
                        .filter(block -> block.getState() == ToolCallState.ASKING)
                        .toList();
            }
        }
        return List.of();
    }

    private DevAgentEvent mapEvent(String sessionId, AgentEvent event) {
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

    private PendingToolCall toPendingToolCall(ToolUseBlock block) {
        return new PendingToolCall(block.getId(), block.getName(), block.getInput());
    }
}
