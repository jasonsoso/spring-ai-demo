package com.jason.demo.demo2.agentscope.service;

import com.jason.demo.demo2.agentscope.config.AgentscopeDevAgentRegistry;
import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import com.jason.demo.demo2.agentscope.model.DevAgentConfirmRequest;
import com.jason.demo.demo2.agentscope.model.DevAgentEvent;
import com.jason.demo.demo2.agentscope.model.DevAgentEventType;
import com.jason.demo.demo2.agentscope.model.DevAgentRequest;
import com.jason.demo.demo2.agentscope.model.PendingToolCall;
import com.jason.demo.demo2.agentscope.model.WorkspaceDiff;
import com.jason.demo.demo2.agentscope.diff.WorkspaceDiffService;
import com.jason.demo.demo2.agentscope.observability.AgentExecutionContext;
import com.jason.demo.demo2.agentscope.plan.PlanHostSyncService;
import com.jason.demo.demo2.agentscope.rag.AgentscopeRagMode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
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
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

@Service
public class DevAgentService {

    private static final Logger log = LoggerFactory.getLogger(DevAgentService.class);

    private final AgentscopeDevAgentRegistry agentscopeDevAgentRegistry;
    private final DevAgentProperties properties;
    private final AgentStateStore agentStateStore;
    private final Tracer tracer;
    private final WorkspaceDiffService workspaceDiffService;
    private final PlanHostSyncService planHostSyncService;
    private final ConcurrentMap<String, AgentscopeRagMode> lastRagModeBySession = new ConcurrentHashMap<>();
    /**
     * HarnessAgent 的 SandboxLifecycleMiddleware 持有共享的 currentAcquireResult，
     * 因此同一个 HarnessAgent 不能并发执行多个沙箱请求；多 ragMode 实例共用一把锁。
     */
    private final Semaphore sandboxRequestLock = new Semaphore(1);

    @Autowired
    public DevAgentService(
            AgentscopeDevAgentRegistry agentscopeDevAgentRegistry,
            DevAgentProperties properties,
            AgentStateStore agentStateStore,
            Tracer tracer,
            WorkspaceDiffService workspaceDiffService,
            PlanHostSyncService planHostSyncService) {
        this.agentscopeDevAgentRegistry = agentscopeDevAgentRegistry;
        this.properties = properties;
        this.agentStateStore = agentStateStore;
        this.tracer = tracer;
        this.workspaceDiffService = workspaceDiffService;
        this.planHostSyncService = planHostSyncService;
    }

    public DevAgentService(
            AgentscopeDevAgentRegistry agentscopeDevAgentRegistry,
            DevAgentProperties properties,
            AgentStateStore agentStateStore,
            Tracer tracer) {
        this(agentscopeDevAgentRegistry, properties, agentStateStore, tracer, null, null);
    }

    public DevAgentService(
            AgentscopeDevAgentRegistry agentscopeDevAgentRegistry,
            DevAgentProperties properties,
            AgentStateStore agentStateStore,
            Tracer tracer,
            WorkspaceDiffService workspaceDiffService) {
        this(agentscopeDevAgentRegistry, properties, agentStateStore, tracer, workspaceDiffService, null);
    }

    /** 测试兼容：单 Agent 包装为仅 NONE 的 Registry。 */
    public DevAgentService(
            HarnessAgent agentscopeDevAgent,
            DevAgentProperties properties,
            AgentStateStore agentStateStore,
            Tracer tracer) {
        this(agentscopeDevAgent, properties, agentStateStore, tracer, null, null);
    }

    public DevAgentService(
            HarnessAgent agentscopeDevAgent,
            DevAgentProperties properties,
            AgentStateStore agentStateStore,
            Tracer tracer,
            WorkspaceDiffService workspaceDiffService) {
        this(agentscopeDevAgent, properties, agentStateStore, tracer, workspaceDiffService, null);
    }

    public DevAgentService(
            HarnessAgent agentscopeDevAgent,
            DevAgentProperties properties,
            AgentStateStore agentStateStore,
            Tracer tracer,
            WorkspaceDiffService workspaceDiffService,
            PlanHostSyncService planHostSyncService) {
        this(
                new AgentscopeDevAgentRegistry(
                        agentscopeDevAgent,
                        mode -> agentscopeDevAgent,
                        com.jason.demo.demo2.agentscope.rag.AgentscopeRagKnowledgeHolder.unavailable(
                                new com.jason.demo.demo2.agentscope.rag.AgentscopeRagProperties(
                                        false,
                                        "agentscope-dev-knowledge.txt",
                                        3,
                                        0.3,
                                        false,
                                        "agentscope_dev_knowledge",
                                        1024,
                                        "",
                                        "https://open.bigmodel.cn/api/paas/v4",
                                        "embedding-2"))),
                properties,
                agentStateStore,
                tracer,
                workspaceDiffService,
                planHostSyncService);
    }

    public Flux<DevAgentEvent> ask(DevAgentRequest request) {
        String sessionId = request.sessionId();
        String userId = normalizeUserId(request.userId());
        Invocation invocation = newInvocation(userId, sessionId);
        String apiKey = properties.model().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            logRejected(invocation, "missing_api_key");
            return withRequestContext(
                    sessionId,
                    invocation,
                    Flux.just(DevAgentEvent.error(
                            sessionId, "DEEPSEEK_API_KEY is not configured")));
        }

        return withRequestContext(
                sessionId,
                invocation,
                Flux.defer(() -> askAfterContext(request, userId, invocation)));
    }

    public Flux<DevAgentEvent> confirm(DevAgentConfirmRequest request) {
        String sessionId = request.sessionId();
        String userId = normalizeUserId(request.userId());
        Invocation invocation = newInvocation(userId, sessionId);
        String apiKey = properties.model().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            logRejected(invocation, "missing_api_key");
            return withRequestContext(
                    sessionId,
                    invocation,
                    Flux.just(DevAgentEvent.error(
                            sessionId, "DEEPSEEK_API_KEY is not configured")));
        }

        return withRequestContext(
                sessionId,
                invocation,
                Flux.defer(() -> confirmAfterContext(request, userId, invocation)));
    }

    private Flux<DevAgentEvent> askAfterContext(
            DevAgentRequest request, String userId, Invocation invocation) {
        String sessionId = request.sessionId();
        try {
            AgentscopeRagMode mode = AgentscopeRagMode.from(request.ragMode());
            lastRagModeBySession.put(sessionKey(userId, sessionId), mode);
            HarnessAgent agent = agentscopeDevAgentRegistry.get(mode);
            captureBaseline(userId, sessionId);
            int beforeCount = contextMessageCount(userId, sessionId);
            Flux<DevAgentEvent> events = mapAgentEvents(
                    userId,
                    sessionId,
                    agent.streamEvents(
                            request.message(), invocation.runtimeContext()));
            return Flux.concat(
                    events,
                    workspaceDiffEvent(userId, sessionId),
                    Mono.defer(() -> compactionEventIfNeeded(
                            userId, sessionId, beforeCount)),
                    Mono.just(DevAgentEvent.done(sessionId)));
        } catch (RuntimeException ex) {
            logRejected(invocation, "pre_agent_failure:" + ex.getClass().getSimpleName());
            return Flux.error(ex);
        }
    }

    private Flux<DevAgentEvent> confirmAfterContext(
            DevAgentConfirmRequest request, String userId, Invocation invocation) {
        String sessionId = request.sessionId();
        try {
            List<ToolUseBlock> pending = loadPendingToolCalls(userId, sessionId);
            if (pending.isEmpty()) {
                logRejected(invocation, "no_pending_tool_call");
                return Flux.just(DevAgentEvent.error(
                        sessionId, "没有待确认的工具调用"));
            }

            AgentscopeRagMode mode = lastRagModeBySession.getOrDefault(
                    sessionKey(userId, sessionId), AgentscopeRagMode.NONE);
            HarnessAgent agent = agentscopeDevAgentRegistry.get(mode);

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
            Flux<DevAgentEvent> events = mapAgentEvents(
                    userId,
                    sessionId,
                    agent.streamEvents(
                            resumeMessage, invocation.runtimeContext()));
            return Flux.concat(
                    events,
                    workspaceDiffEvent(userId, sessionId),
                    Mono.defer(() -> compactionEventIfNeeded(
                            userId, sessionId, beforeCount)),
                    Mono.just(DevAgentEvent.done(sessionId)));
        } catch (RuntimeException ex) {
            logRejected(invocation, "pre_agent_failure:" + ex.getClass().getSimpleName());
            return Flux.error(ex);
        }
    }

    private void captureBaseline(String userId, String sessionId) {
        if (workspaceDiffService != null && properties.sandbox().enabled()) {
            workspaceDiffService.captureBaseline(userId, sessionId);
        }
    }

    private Mono<DevAgentEvent> workspaceDiffEvent(String userId, String sessionId) {
        if (workspaceDiffService == null || !properties.sandbox().enabled()) {
            return Mono.empty();
        }
        return Mono.defer(() -> {
            WorkspaceDiff diff = workspaceDiffService.createDiff(userId, sessionId);
            return diff == null
                    ? Mono.empty()
                    : Mono.just(DevAgentEvent.workspaceDiff(sessionId, diff));
        });
    }

    private Flux<DevAgentEvent> mapAgentEvents(
            String userId, String sessionId, Flux<AgentEvent> agentEvents) {
        return agentEvents.handle((event, sink) -> {
            maybeSyncPlanToHost(userId, sessionId, event);
            DevAgentEvent mapped = mapEvent(sessionId, event);
            if (mapped != null) {
                sink.next(mapped);
            }
        });
    }

    private void maybeSyncPlanToHost(String userId, String sessionId, AgentEvent event) {
        if (planHostSyncService == null || event.getType() != AgentEventType.TOOL_RESULT_END) {
            return;
        }
        ToolResultEndEvent e = (ToolResultEndEvent) event;
        if (!"plan_write".equals(e.getToolCallName()) || e.getState() != ToolResultState.SUCCESS) {
            return;
        }
        planHostSyncService.syncAfterPlanWrite(userId, sessionId);
    }

    private Flux<DevAgentEvent> withRequestContext(
            String sessionId, Invocation invocation, Flux<DevAgentEvent> body) {
        return Flux.defer(() -> {
            boolean sandbox = properties.sandbox().enabled();
            if (sandbox) {
                sandboxRequestLock.acquireUninterruptibly();
            }
            return Flux.concat(
                            Mono.just(DevAgentEvent.session(sessionId)),
                            Mono.just(requestContextEvent(sessionId, invocation.ids())),
                            body)
                    .onErrorResume(ex -> Flux.just(DevAgentEvent.error(
                            sessionId,
                            ex.getMessage() == null
                                    ? ex.getClass().getSimpleName()
                                    : ex.getMessage())))
                    .doFinally(signal -> {
                        if (sandbox) {
                            sandboxRequestLock.release();
                        }
                    });
        });
    }

    private Invocation newInvocation(String userId, String sessionId) {
        AgentExecutionContext ids = AgentExecutionContext.create(tracer);
        RuntimeContext runtime = RuntimeContext.builder()
                .sessionId(sessionId)
                .userId(userId)
                .build();
        ids.writeTo(runtime);
        return new Invocation(ids, runtime);
    }

    private DevAgentEvent requestContextEvent(
            String sessionId, AgentExecutionContext ids) {
        return DevAgentEvent.requestContext(
                sessionId, ids.requestId(), ids.traceId(), ids.spanId());
    }

    private void logRejected(Invocation invocation, String reason) {
        AgentExecutionContext ids = invocation.ids();
        log.warn(
                "Agent request rejected. requestId={}, traceId={}, spanId={}, "
                        + "userId={}, sessionId={}, reason={}",
                ids.requestId(),
                ids.traceId(),
                ids.spanId(),
                invocation.runtimeContext().getUserId(),
                invocation.runtimeContext().getSessionId(),
                reason);
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
        String source = event.getSource();
        return switch (event.getType()) {
            case AGENT_START -> DevAgentEvent.lifecycle(
                    DevAgentEventType.AGENT_START,
                    sessionId,
                    source,
                    event.getId(),
                    "Agent 开始");
            case MODEL_CALL_START -> DevAgentEvent.lifecycle(
                    DevAgentEventType.MODEL_CALL_START,
                    sessionId,
                    source,
                    event.getId(),
                    "模型调用开始");
            case AGENT_END -> DevAgentEvent.lifecycle(
                    DevAgentEventType.AGENT_END,
                    sessionId,
                    source,
                    event.getId(),
                    "Agent 结束");
            case TEXT_BLOCK_DELTA -> DevAgentEvent.message(
                    sessionId, source, ((TextBlockDeltaEvent) event).getDelta());
            case TOOL_CALL_START -> {
                ToolCallStartEvent e = (ToolCallStartEvent) event;
                yield DevAgentEvent.toolCallStart(
                        sessionId,
                        source,
                        e.getId(),
                        e.getToolCallId(),
                        e.getToolCallName(),
                        "准备调用工具：" + e.getToolCallName());
            }
            case TOOL_RESULT_END -> {
                ToolResultEndEvent e = (ToolResultEndEvent) event;
                yield DevAgentEvent.toolResultEnd(
                        sessionId,
                        source,
                        e.getId(),
                        e.getToolCallId(),
                        e.getToolCallName(),
                        e.getState() == null ? null : e.getState().name());
            }
            case AGENT_RESULT -> {
                AgentResultEvent e = (AgentResultEvent) event;
                String text = e.getResult() == null ? "" : e.getResult().getTextContent();
                yield DevAgentEvent.agentResult(sessionId, source, e.getId(), text);
            }
            case REQUIRE_USER_CONFIRM -> {
                RequireUserConfirmEvent e = (RequireUserConfirmEvent) event;
                List<ToolUseBlock> toolCalls = e.getToolCalls() == null ? List.of() : e.getToolCalls();
                yield DevAgentEvent.confirmation(
                        sessionId,
                        source,
                        e.getId(),
                        toolCalls.stream().map(this::toPendingToolCall).toList());
            }
            case REQUEST_STOP -> {
                RequestStopEvent e = (RequestStopEvent) event;
                String content = e.getGenerateReason() == null
                        ? e.getReason()
                        : e.getGenerateReason().name();
                yield DevAgentEvent.requestStop(sessionId, source, e.getId(), content);
            }
            default -> null;
        };
    }

    static String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "_anonymous" : userId.strip();
    }

    static String sessionKey(String userId, String sessionId) {
        return normalizeUserId(userId) + "|" + sessionId;
    }

    private PendingToolCall toPendingToolCall(ToolUseBlock block) {
        return new PendingToolCall(block.getId(), block.getName(), block.getInput());
    }

    private record Invocation(
            AgentExecutionContext ids, RuntimeContext runtimeContext) {
    }
}
