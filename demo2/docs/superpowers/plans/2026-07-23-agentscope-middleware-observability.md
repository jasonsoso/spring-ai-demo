# AgentScope Middleware 请求关联可观测性 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为每次 AgentScope `/ask`、`/confirm` 执行生成 requestId，并用自定义 Middleware 将其与模型、工具、失败日志及现有 traceId/spanId 关联，同时通过 SSE 在错误时提供可复制的请求编号。

**Architecture:** `DevAgentService` 在进入任何短路判断前创建 `AgentExecutionContext`，将 requestId、traceId、spanId 写入 `RuntimeContext`，并发送独立 `REQUEST_CONTEXT` 事件。`AgentExecutionLoggingMiddleware` 从同一上下文读取标识，包裹 Agent、reasoning、model、acting、system prompt 五个阶段，只记录元数据和耗时；前端仅在错误时显示 requestId。

**Tech Stack:** Java 21、Spring Boot 4.1、AgentScope Java 2.0、Reactor 3.8、Micrometer Tracing 1.7、SLF4J/Logback、JUnit 6、Mockito、AssertJ、Reactor Test、原生 JavaScript/CSS。

## Global Constraints

- AgentScope 版本保持 `2.0.0`，不新增依赖。
- requestId 始终由服务端生成，为 32 位无连字符 UUID；每次 `/ask`、`/confirm` 使用不同值。
- `REQUEST_CONTEXT` 固定紧跟 `SESSION`，其他 SSE 事件语义不变。
- 不创建新的 OpenTelemetry Span；只关联当前 HTTP `traceId`、`spanId`，无上下文时使用 `-`。
- Agent、reasoning、model、acting 正常日志为 INFO，失败/取消为 WARN，system prompt 长度为 DEBUG。
- Middleware 不记录完整 Prompt、用户正文、回答正文、工具参数或工具结果。
- 保持 `.enableAgentTracingLog(false)`；保留现有 `LoggingAgentscopeModel` DEBUG 明细行为。
- requestId、traceId、spanId、reasoning round 只存在于 `RuntimeContext`，不写入 `AgentState` 或 PostgreSQL。
- 正常前端不显示 requestId；仅 `ERROR` 或已获得上下文后的网络异常显示并提供复制。
- 设计规范：`demo2/docs/superpowers/specs/2026-07-23-agentscope-middleware-observability-design.md`。

## File Map

**Create**

- `demo2/src/main/java/com/jason/demo/demo2/agentscope/observability/AgentExecutionContext.java`：生成、捕获、存取执行关联标识，并维护 reasoning round。
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/middleware/AgentExecutionLoggingMiddleware.java`：五个 AgentScope Middleware 入口的结构化日志。
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/observability/AgentExecutionContextTest.java`：上下文生成、回读和轮次测试。
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/middleware/AgentExecutionLoggingMiddlewareTest.java`：事件透明性、日志字段、token、工具、失败和取消测试。
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java`：验证自定义 Middleware 注册且默认 Trace 关闭产生的重复日志不会恢复。

**Modify**

- `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentEventType.java`：新增 `REQUEST_CONTEXT`。
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentEvent.java`：新增三个可空字段和工厂方法。
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java`：注入 `Tracer`、创建 Invocation、调整所有 SSE 序列和短路日志。
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`：声明并注册日志 Middleware。
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/model/DevAgentEventTest.java`：覆盖新事件与 JSON 兼容字段。
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java`：覆盖事件顺序、上下文传播、短路和 ask/confirm 独立 requestId。
- `demo2/src/main/resources/static/js/tabs/agentscope.js`：保存请求上下文并渲染错误请求编号。
- `demo2/src/main/resources/static/css/tabs/agentscope.css`：错误编号与复制按钮样式。
- `demo2/README.md`：更新 AgentScope SSE、Middleware、日志检索和敏感日志说明。
- `README.md`：更新 AgentScope Harness 功能摘要。

---

### Task 1: 请求上下文与 SSE 数据契约

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/observability/AgentExecutionContext.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/agentscope/observability/AgentExecutionContextTest.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentEventType.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentEvent.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/model/DevAgentEventTest.java`

**Interfaces:**
- Produces: `AgentExecutionContext.create(Tracer)`, `writeTo(RuntimeContext)`, `from(RuntimeContext)`, `nextReasoningRound(RuntimeContext)`.
- Produces: `DevAgentEvent.requestContext(String sessionId, String requestId, String traceId, String spanId)`.
- Produces: enum value `DevAgentEventType.REQUEST_CONTEXT`.
- Consumes: Micrometer `Tracer.currentTraceContext().context()` and AgentScope `RuntimeContext.put/getExtra`.

- [ ] **Step 1: Write failing context tests**

Create `AgentExecutionContextTest.java`:

```java
package com.jason.demo.demo2.agentscope.observability;

import io.agentscope.core.agent.RuntimeContext;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentExecutionContextTest {

    @Test
    void create_capturesTraceAndGeneratesCompactUuid() {
        Tracer tracer = mock(Tracer.class);
        CurrentTraceContext current = mock(CurrentTraceContext.class);
        TraceContext trace = mock(TraceContext.class);
        when(tracer.currentTraceContext()).thenReturn(current);
        when(current.context()).thenReturn(trace);
        when(trace.traceId()).thenReturn("trace-1");
        when(trace.spanId()).thenReturn("span-1");

        AgentExecutionContext ids = AgentExecutionContext.create(tracer);

        assertThat(ids.requestId()).matches("[0-9a-f]{32}");
        assertThat(ids.traceId()).isEqualTo("trace-1");
        assertThat(ids.spanId()).isEqualTo("span-1");
    }

    @Test
    void writeAndRead_roundTripsAndKeepsRoundPerRuntimeContext() {
        RuntimeContext runtime = RuntimeContext.builder()
                .userId("u1")
                .sessionId("s1")
                .build();
        AgentExecutionContext ids =
                new AgentExecutionContext("request-1", "trace-1", "span-1");

        ids.writeTo(runtime);

        assertThat(AgentExecutionContext.from(runtime)).isEqualTo(ids);
        assertThat(AgentExecutionContext.nextReasoningRound(runtime)).isEqualTo(1);
        assertThat(AgentExecutionContext.nextReasoningRound(runtime)).isEqualTo(2);
    }

    @Test
    void create_withoutTraceUsesDash() {
        Tracer tracer = mock(Tracer.class);
        CurrentTraceContext current = mock(CurrentTraceContext.class);
        when(tracer.currentTraceContext()).thenReturn(current);
        when(current.context()).thenReturn(null);

        AgentExecutionContext ids = AgentExecutionContext.create(tracer);

        assertThat(ids.traceId()).isEqualTo("-");
        assertThat(ids.spanId()).isEqualTo("-");
    }
}
```

- [ ] **Step 2: Write failing SSE contract test**

Add to `DevAgentEventTest`:

```java
@Test
void requestContext_carriesOnlyCorrelationFields() {
    DevAgentEvent event =
            DevAgentEvent.requestContext("s1", "request-1", "trace-1", "span-1");

    assertThat(event.type()).isEqualTo(DevAgentEventType.REQUEST_CONTEXT);
    assertThat(event.sessionId()).isEqualTo("s1");
    assertThat(event.content()).isEmpty();
    assertThat(event.requestId()).isEqualTo("request-1");
    assertThat(event.traceId()).isEqualTo("trace-1");
    assertThat(event.spanId()).isEqualTo("span-1");
    assertThat(event.eventId()).isNull();
    assertThat(event.pendingToolCalls()).isNull();
}
```

Update the existing direct `new DevAgentEvent(...)` assertion by appending three nulls for `requestId`、`traceId`、`spanId`.

- [ ] **Step 3: Run focused tests and verify RED**

Run:

```powershell
.\mvnw.cmd "-Dtest=AgentExecutionContextTest,DevAgentEventTest" test
```

Expected: test compilation fails because `AgentExecutionContext`、`REQUEST_CONTEXT` and `requestContext(...)` do not exist.

- [ ] **Step 4: Implement `AgentExecutionContext`**

Create:

```java
package com.jason.demo.demo2.agentscope.observability;

import io.agentscope.core.agent.RuntimeContext;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public record AgentExecutionContext(String requestId, String traceId, String spanId) {

    public static final String REQUEST_ID_KEY = "observability.request_id";
    public static final String TRACE_ID_KEY = "observability.trace_id";
    public static final String SPAN_ID_KEY = "observability.span_id";
    public static final String REASONING_ROUND_KEY = "observability.reasoning_round";
    private static final String UNKNOWN = "-";

    public static AgentExecutionContext create(Tracer tracer) {
        TraceContext trace = currentTrace(tracer);
        return new AgentExecutionContext(
                UUID.randomUUID().toString().replace("-", ""),
                trace == null ? UNKNOWN : valueOrUnknown(trace.traceId()),
                trace == null ? UNKNOWN : valueOrUnknown(trace.spanId()));
    }

    public void writeTo(RuntimeContext context) {
        context.put(REQUEST_ID_KEY, requestId);
        context.put(TRACE_ID_KEY, traceId);
        context.put(SPAN_ID_KEY, spanId);
    }

    public static AgentExecutionContext from(RuntimeContext context) {
        return new AgentExecutionContext(
                valueOrUnknown(context.get(REQUEST_ID_KEY)),
                valueOrUnknown(context.get(TRACE_ID_KEY)),
                valueOrUnknown(context.get(SPAN_ID_KEY)));
    }

    public static int nextReasoningRound(RuntimeContext context) {
        Object value = context.getExtra().computeIfAbsent(
                REASONING_ROUND_KEY, ignored -> new AtomicInteger());
        if (!(value instanceof AtomicInteger counter)) {
            throw new IllegalStateException(
                    REASONING_ROUND_KEY + " must contain AtomicInteger");
        }
        return counter.incrementAndGet();
    }

    private static TraceContext currentTrace(Tracer tracer) {
        if (tracer == null) {
            return null;
        }
        CurrentTraceContext current = tracer.currentTraceContext();
        return current == null ? null : current.context();
    }

    private static String valueOrUnknown(Object value) {
        if (value == null) {
            return UNKNOWN;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? UNKNOWN : text;
    }
}
```

- [ ] **Step 5: Implement the SSE event shape**

Add `REQUEST_CONTEXT` immediately after `SESSION` in `DevAgentEventType`.

Extend the end of the `DevAgentEvent` record:

```java
public record DevAgentEvent(
        DevAgentEventType type,
        String sessionId,
        String content,
        String eventId,
        String toolCallId,
        String name,
        String state,
        List<PendingToolCall> pendingToolCalls,
        String requestId,
        String traceId,
        String spanId) {
```

Add:

```java
public static DevAgentEvent requestContext(
        String sessionId, String requestId, String traceId, String spanId) {
    return new DevAgentEvent(
            DevAgentEventType.REQUEST_CONTEXT,
            sessionId,
            "",
            null,
            null,
            null,
            null,
            null,
            requestId,
            traceId,
            spanId);
}
```

For every existing factory in `DevAgentEvent`, append `null, null, null` to its constructor call. Do not populate correlation fields on any event except `REQUEST_CONTEXT`.

- [ ] **Step 6: Run focused tests and verify GREEN**

Run:

```powershell
.\mvnw.cmd "-Dtest=AgentExecutionContextTest,DevAgentEventTest" test
```

Expected: `BUILD SUCCESS` and both test classes pass.

- [ ] **Step 7: Commit the contract**

```powershell
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/observability/AgentExecutionContext.java demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentEventType.java demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentEvent.java demo2/src/test/java/com/jason/demo/demo2/agentscope/observability/AgentExecutionContextTest.java demo2/src/test/java/com/jason/demo/demo2/agentscope/model/DevAgentEventTest.java
git commit -m "feat(demo2): add AgentScope request context contract"
```

---

### Task 2: Service 上下文传播与 SSE 顺序

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java`

**Interfaces:**
- Consumes: `AgentExecutionContext.create(Tracer)` and `writeTo(RuntimeContext)` from Task 1.
- Produces: every valid Service call starts with `SESSION`, `REQUEST_CONTEXT`.
- Produces: private `Invocation(AgentExecutionContext ids, RuntimeContext runtimeContext)` used by ask and confirm.

- [ ] **Step 1: Add Tracer mocks and update the test fixture**

Add mocks:

```java
@Mock Tracer tracer;
@Mock CurrentTraceContext currentTraceContext;
@Mock TraceContext traceContext;
```

In `setUp()` configure stable Trace values and pass `tracer` to the Service:

```java
lenient().when(tracer.currentTraceContext()).thenReturn(currentTraceContext);
lenient().when(currentTraceContext.context()).thenReturn(traceContext);
lenient().when(traceContext.traceId()).thenReturn("trace-test");
lenient().when(traceContext.spanId()).thenReturn("span-test");
service = new DevAgentService(harnessAgent, properties, agentStateStore, tracer);
```

Update every other `new DevAgentService(...)` call in this test class with the same final `tracer` argument.

- [ ] **Step 2: Write failing event-order and propagation tests**

Add:

```java
@Test
void ask_emitsRequestContextAndWritesItToRuntimeContext() {
    when(harnessAgent.streamEvents(eq("hi"), any(RuntimeContext.class)))
            .thenReturn(Flux.empty());

    StepVerifier.create(service.ask(new DevAgentRequest("u1", "s1", "hi")))
            .expectNext(DevAgentEvent.session("s1"))
            .assertNext(event -> {
                assertThat(event.type()).isEqualTo(DevAgentEventType.REQUEST_CONTEXT);
                assertThat(event.requestId()).matches("[0-9a-f]{32}");
                assertThat(event.traceId()).isEqualTo("trace-test");
                assertThat(event.spanId()).isEqualTo("span-test");
            })
            .expectNext(DevAgentEvent.done("s1"))
            .verifyComplete();

    ArgumentCaptor<RuntimeContext> captor =
            ArgumentCaptor.forClass(RuntimeContext.class);
    verify(harnessAgent).streamEvents(eq("hi"), captor.capture());
    AgentExecutionContext stored =
            AgentExecutionContext.from(captor.getValue());
    assertThat(stored.traceId()).isEqualTo("trace-test");
    assertThat(stored.spanId()).isEqualTo("span-test");
    assertThat(stored.requestId()).matches("[0-9a-f]{32}");
}

@Test
void ask_blankApiKey_stillEmitsRequestContextBeforeError() {
    DevAgentProperties blankKeyProperties = new DevAgentProperties(
            "dev-task-agent",
            "prompt",
            ".",
            "workspace",
            new DevAgentProperties.Compaction(
                    6, 2, "请整理会话：{messages}"),
            new DevAgentProperties.Model(
                    "  ",
                    "https://api.deepseek.com",
                    "deepseek-v4-pro"));
    service = new DevAgentService(
            harnessAgent,
            blankKeyProperties,
            agentStateStore,
            tracer);

    StepVerifier.create(service.ask(new DevAgentRequest(null, "s1", "hi")))
            .expectNext(DevAgentEvent.session("s1"))
            .expectNextMatches(e -> e.type() == DevAgentEventType.REQUEST_CONTEXT)
            .expectNextMatches(e -> e.type() == DevAgentEventType.ERROR)
            .verifyComplete();
}
```

Extend `confirm_withoutPending_emitsError()` to expect `REQUEST_CONTEXT` between `SESSION` and `ERROR`.

- [ ] **Step 3: Run the Service test and verify RED**

Run:

```powershell
.\mvnw.cmd "-Dtest=DevAgentServiceTest" test
```

Expected: compilation fails on the new constructor and existing output assertions fail because `REQUEST_CONTEXT` is not emitted.

- [ ] **Step 4: Implement Invocation creation**

Add fields/imports:

```java
private static final Logger log =
        LoggerFactory.getLogger(DevAgentService.class);
private final Tracer tracer;
```

Update the constructor:

```java
public DevAgentService(
        HarnessAgent agentscopeDevAgent,
        DevAgentProperties properties,
        AgentStateStore agentStateStore,
        Tracer tracer) {
    this.agentscopeDevAgent = agentscopeDevAgent;
    this.properties = properties;
    this.agentStateStore = agentStateStore;
    this.tracer = tracer;
}
```

Add:

```java
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

private record Invocation(
        AgentExecutionContext ids, RuntimeContext runtimeContext) {
}
```

- [ ] **Step 5: Reorder ask and confirm**

For `ask`:

1. Normalize userId before checking API Key.
2. Create `Invocation`.
3. On blank API Key, call `logRejected(invocation, "missing_api_key")` and return exactly:

```java
return Flux.just(
        DevAgentEvent.session(sessionId),
        requestContextEvent(sessionId, invocation.ids()),
        DevAgentEvent.error(
                sessionId, "DEEPSEEK_API_KEY is not configured"));
```

4. Pass `invocation.runtimeContext()` to `streamEvents`.
5. Add `Mono.just(requestContextEvent(...))` immediately after `SESSION` in the normal `Flux.concat`.

For `confirm`, create the Invocation before API Key and pending-call checks. Use reason `missing_api_key` or `no_pending_tool_call` on the two short circuits, and insert `REQUEST_CONTEXT` in both short and normal sequences. Remove both old inline `RuntimeContext.builder()` blocks.

Do not add a second Service WARN in `onErrorResume`; Middleware will record failures after Agent execution begins.

- [ ] **Step 6: Update all existing Service expectations**

In every `DevAgentServiceTest` sequence, add:

```java
.expectNextMatches(e ->
        e.type() == DevAgentEventType.REQUEST_CONTEXT
                && e.requestId().matches("[0-9a-f]{32}"))
```

immediately after `DevAgentEvent.session(...)`.

In `confirm_approved_resumesWithConfirmResultsMetadata`, capture the confirm RuntimeContext as well as the Msg. Add:

```java
@Test
void askAndConfirm_generateDifferentRequestIdsForSameSession() {
    when(harnessAgent.streamEvents(eq("hi"), any(RuntimeContext.class)))
            .thenReturn(Flux.empty());

    List<DevAgentEvent> askEvents =
            service.ask(new DevAgentRequest("u1", "s1", "hi"))
                    .collectList()
                    .block();
    List<DevAgentEvent> confirmEvents =
            service.confirm(new DevAgentConfirmRequest("u1", "s1", true))
                    .collectList()
                    .block();

    DevAgentEvent askContext = askEvents.stream()
            .filter(e -> e.type() == DevAgentEventType.REQUEST_CONTEXT)
            .findFirst()
            .orElseThrow();
    DevAgentEvent confirmContext = confirmEvents.stream()
            .filter(e -> e.type() == DevAgentEventType.REQUEST_CONTEXT)
            .findFirst()
            .orElseThrow();

    assertThat(askContext.sessionId()).isEqualTo("s1");
    assertThat(confirmContext.sessionId()).isEqualTo("s1");
    assertThat(confirmContext.requestId())
            .isNotEqualTo(askContext.requestId());
}
```

- [ ] **Step 7: Run the Service suite and verify GREEN**

Run:

```powershell
.\mvnw.cmd "-Dtest=DevAgentServiceTest" test
```

Expected: `BUILD SUCCESS`; all old event mappings still pass with one new event after `SESSION`.

- [ ] **Step 8: Commit Service propagation**

```powershell
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java
git commit -m "feat(demo2): propagate AgentScope request context"
```

---

### Task 3: Agent execution logging Middleware

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/middleware/AgentExecutionLoggingMiddleware.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/agentscope/middleware/AgentExecutionLoggingMiddlewareTest.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`

**Interfaces:**
- Consumes: `AgentExecutionContext.from(RuntimeContext)` and `nextReasoningRound(RuntimeContext)`.
- Implements: AgentScope `MiddlewareBase`.
- Produces: transparent `Flux<AgentEvent>` for `onAgent`、`onReasoning`、`onModelCall`、`onActing`.
- Produces: unchanged `Mono<String>` from `onSystemPrompt`.

- [ ] **Step 1: Write failing transparency and stage tests**

Create a Logback `ListAppender<ILoggingEvent>` in `AgentExecutionLoggingMiddlewareTest`. Build a RuntimeContext with:

```java
private RuntimeContext runtime() {
    RuntimeContext runtime = RuntimeContext.builder()
            .userId("u1")
            .sessionId("s1")
            .build();
    new AgentExecutionContext(
            "request-1", "trace-1", "span-1").writeTo(runtime);
    return runtime;
}
```

Add these concrete test cases:

1. `onAgent_forwardsEventsAndCountsOnlyTextDeltas`
   - mock one `TextBlockDeltaEvent` with delta `"hello"`
   - invoke `onAgent(..., ignored -> Flux.just(delta))`
   - StepVerifier receives the exact same event
   - captured logs contain `requestId=request-1` and `answerChars=5`
2. `onReasoning_incrementsRound`
   - call twice with `new ReasoningInput(List.of(), List.of(), null)`
   - logs contain `round=1` and `round=2`
3. `onModelCall_logsUnknownUsageAsDash`
   - return `Flux.empty()`
   - logs contain `inputTokens=-` and `outputTokens=-`
4. `onModelCall_logsUsageFromEndEvent`
   - mock `ModelCallEndEvent.getUsage()` as `new ChatUsage(10, 4, 0.0)`
   - logs contain `inputTokens=10` and `outputTokens=4`
5. `onActing_logsToolMetadataWithoutInput`
   - use `ToolUseBlock` named `read_pom` with input containing `"secret"`
   - emit a mocked `ToolResultEndEvent` with `SUCCESS`
   - logs contain tool name/id/state and do not contain `"secret"`
6. `onAgent_errorIsPropagatedAndLogged`
   - next returns `Flux.error(new IllegalStateException("sensitive"))`
   - StepVerifier sees `IllegalStateException`
   - logs contain `errorType=IllegalStateException` and not `"sensitive"`
7. `onAgent_cancelIsLogged`
   - subscribe to `Flux.never()`, dispose subscription
   - logs contain `state=CANCELLED`
8. `onSystemPrompt_returnsOriginalText`
   - block the Mono and assert exact equality
   - DEBUG log contains only `promptChars=<length>`, not the prompt text

- [ ] **Step 2: Run Middleware tests and verify RED**

Run:

```powershell
.\mvnw.cmd "-Dtest=AgentExecutionLoggingMiddlewareTest" test
```

Expected: test compilation fails because the Middleware class does not exist.

- [ ] **Step 3: Implement the Middleware shell and common helpers**

Create a `final` class implementing `MiddlewareBase` with:

```java
private static final Logger log =
        LoggerFactory.getLogger(AgentExecutionLoggingMiddleware.class);

private static long elapsedMillis(long startedAt) {
    return TimeUnit.NANOSECONDS.toMillis(
            System.nanoTime() - startedAt);
}

private static int size(List<?> values) {
    return values == null ? 0 : values.size();
}

private static String modelName(ModelCallInput input) {
    return input.model() == null ? "-" : input.model().getModelName();
}
```

Every reactive override must start with `Flux.defer(() -> { ... })`. Create timers and `AtomicReference`/`AtomicInteger` inside that defer, then call `Flux.defer(() -> next.apply(input))`. Never call `next.apply` before subscription.

- [ ] **Step 4: Implement `onAgent`**

Use the exact signature from AgentScope 2.0:

```java
@Override
public Flux<AgentEvent> onAgent(
        Agent agent,
        RuntimeContext context,
        AgentInput input,
        Function<AgentInput, Flux<AgentEvent>> next) {
    return Flux.defer(() -> {
        AgentExecutionContext ids = AgentExecutionContext.from(context);
        long startedAt = System.nanoTime();
        AtomicInteger answerChars = new AtomicInteger();
        log.info(
                "Agent execution started. requestId={}, traceId={}, spanId={}, "
                        + "agent={}, userId={}, sessionId={}",
                ids.requestId(), ids.traceId(), ids.spanId(),
                agent.getName(), context.getUserId(), context.getSessionId());
        return Flux.defer(() -> next.apply(input))
                .doOnNext(event -> {
                    if (event instanceof TextBlockDeltaEvent delta
                            && delta.getDelta() != null) {
                        answerChars.addAndGet(delta.getDelta().length());
                    }
                })
                .doOnComplete(() -> log.info(
                        "Agent execution completed. requestId={}, traceId={}, "
                                + "spanId={}, durationMs={}, answerChars={}, state=SUCCESS",
                        ids.requestId(), ids.traceId(), ids.spanId(),
                        elapsedMillis(startedAt), answerChars.get()))
                .doOnError(error -> log.warn(
                        "Agent execution failed. requestId={}, traceId={}, spanId={}, "
                                + "durationMs={}, errorType={}, state=ERROR",
                        ids.requestId(), ids.traceId(), ids.spanId(),
                        elapsedMillis(startedAt),
                        error.getClass().getSimpleName()))
                .doOnCancel(() -> log.warn(
                        "Agent execution cancelled. requestId={}, traceId={}, spanId={}, "
                                + "durationMs={}, state=CANCELLED",
                        ids.requestId(), ids.traceId(), ids.spanId(),
                        elapsedMillis(startedAt)));
    });
}
```

- [ ] **Step 5: Implement the remaining four entry points**

`onReasoning`:

- call `AgentExecutionContext.nextReasoningRound(context)` once inside defer
- start log fields: IDs、round、`size(input.messages())`、`size(input.tools())`
- complete/error/cancel logs include elapsed time and terminal state

`onModelCall`:

- keep `AtomicReference<ChatUsage> usage`
- on each event, store `ModelCallEndEvent.getUsage()`
- complete log uses `usage == null ? "-" : usage.getInputTokens()` and output equivalent
- start/terminal logs include IDs、`modelName(input)`、duration and state

`onActing`:

- start log includes tool count
- log each planned tool as name/id only:

```java
for (ToolUseBlock toolCall : input.toolCalls()) {
    log.info(
            "Tool execution planned. requestId={}, traceId={}, spanId={}, "
                    + "tool={}, toolCallId={}",
            ids.requestId(), ids.traceId(), ids.spanId(),
            toolCall.getName(), toolCall.getId());
}
```

- on `ToolResultEndEvent`, log name、call id、state
- complete/error/cancel logs include group duration
- never log `toolCall.getInput()`

`onSystemPrompt`:

```java
@Override
public Mono<String> onSystemPrompt(
        Agent agent, RuntimeContext context, String systemPrompt) {
    AgentExecutionContext ids = AgentExecutionContext.from(context);
    log.debug(
            "System prompt observed. requestId={}, traceId={}, spanId={}, promptChars={}",
            ids.requestId(),
            ids.traceId(),
            ids.spanId(),
            systemPrompt == null ? 0 : systemPrompt.length());
    return Mono.justOrEmpty(systemPrompt);
}
```

- [ ] **Step 6: Run Middleware tests and verify GREEN**

Run:

```powershell
.\mvnw.cmd "-Dtest=AgentExecutionLoggingMiddlewareTest" test
```

Expected: `BUILD SUCCESS`; event identity and propagated errors remain unchanged.

- [ ] **Step 7: Wire the Middleware and test registration**

In `AgentScopeConfig` add:

```java
@Bean
AgentExecutionLoggingMiddleware agentExecutionLoggingMiddleware() {
    return new AgentExecutionLoggingMiddleware();
}
```

Add it to `agentscopeDevAgent(...)` parameters and register before `.enableAgentTracingLog(false)`:

```java
.middleware(agentExecutionLoggingMiddleware)
.enableAgentTracingLog(false)
```

Create `AgentScopeMiddlewareConfigTest` in the same package so it can call the package-private configuration factory:

```java
package com.jason.demo.demo2.agentscope.config;

import com.jason.demo.demo2.agentscope.middleware.AgentExecutionLoggingMiddleware;
import com.jason.demo.demo2.agentscope.tool.FileChangeTool;
import com.jason.demo.demo2.agentscope.tool.ProjectInfoTools;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentScopeMiddlewareConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void agentscopeDevAgent_registersCustomLoggingAndDisablesDefaultTrace()
            throws Exception {
        AgentScopeConfig config = new AgentScopeConfig();
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("test-model");
        AgentStateStore store = mock(AgentStateStore.class);
        DevAgentProperties properties = new DevAgentProperties(
                "dev-task-agent",
                "prompt",
                tempDir.toString(),
                tempDir.toString(),
                new DevAgentProperties.Compaction(
                        6, 2, "请整理：{messages}"),
                new DevAgentProperties.Model(
                        "sk-test",
                        "https://api.deepseek.com",
                        "deepseek-v4-pro"));
        AgentExecutionLoggingMiddleware middleware =
                new AgentExecutionLoggingMiddleware();

        try (HarnessAgent agent = config.agentscopeDevAgent(
                model,
                properties,
                AgentScopeConfig.toCompactionConfig(properties.compaction()),
                new ProjectInfoTools(tempDir),
                new FileChangeTool(tempDir),
                store,
                middleware)) {
            assertThat(agent.getDelegate().getMiddlewares())
                    .contains(middleware)
                    .noneMatch(item -> item.getClass().getSimpleName()
                            .equals("AgentTraceMiddleware"));
        }
    }
}
```

Keep the production `agentscopeDevAgent(...)` parameter order identical to this test. Do not use reflection to inspect the builder.

- [ ] **Step 8: Run config and AgentScope tests**

Run:

```powershell
.\mvnw.cmd "-Dtest=AgentExecutionLoggingMiddlewareTest,AgentScopeMiddlewareConfigTest,AgentscopeCompactionConfigTest" test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 9: Commit Middleware logging**

```powershell
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/middleware/AgentExecutionLoggingMiddleware.java demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java demo2/src/test/java/com/jason/demo/demo2/agentscope/middleware/AgentExecutionLoggingMiddlewareTest.java demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java
git commit -m "feat(demo2): log AgentScope execution stages"
```

---

### Task 4: 前端错误请求编号

**Files:**
- Modify: `demo2/src/main/resources/static/js/tabs/agentscope.js`
- Modify: `demo2/src/main/resources/static/css/tabs/agentscope.css`

**Interfaces:**
- Consumes: `REQUEST_CONTEXT` payload `{requestId, traceId, spanId}`.
- Produces: `turn.requestContext` and `renderAgentscopeError(turn, message)`.
- UI contract: normal completion shows no request ID; error renders one copy button at most.

- [ ] **Step 1: Add per-turn state**

Change `beginAgentscopeAssistantTurn()` return value to:

```javascript
return {
    col: col,
    strip: strip,
    content: content,
    tools: new Map(),
    requestContext: null,
    errorRendered: false
};
```

This naturally resets context for every ask. Confirm continues using the same visual turn, but `REQUEST_CONTEXT` overwrites `turn.requestContext` with the new confirm execution ID.

- [ ] **Step 2: Add one error renderer**

Add before `handleAgentscopeSsePayload`:

```javascript
function renderAgentscopeError(turn, message) {
    if (!turn || turn.errorRendered) return;
    turn.errorRendered = true;
    turn.content.textContent += (turn.content.textContent ? '\n' : '')
        + '[ERROR] ' + (message || '出错');

    const requestId = turn.requestContext?.requestId;
    if (!requestId) return;

    const row = document.createElement('div');
    row.className = 'agentscope-error-request';
    const label = document.createElement('span');
    label.textContent = '请求编号：' + requestId;
    const copy = document.createElement('button');
    copy.type = 'button';
    copy.textContent = '复制';
    copy.onclick = async function () {
        await navigator.clipboard.writeText(requestId);
        copy.textContent = '已复制';
    };
    row.appendChild(label);
    row.appendChild(copy);
    turn.col.appendChild(row);
    scrollAgentscopeMessages();
}
```

- [ ] **Step 3: Handle `REQUEST_CONTEXT` and route all errors**

In `handleAgentscopeSsePayload` immediately after `SESSION`:

```javascript
} else if (payload.type === 'REQUEST_CONTEXT') {
    turn.requestContext = {
        requestId: payload.requestId || '',
        traceId: payload.traceId || '-',
        spanId: payload.spanId || '-'
    };
```

Replace the existing `ERROR` text append with:

```javascript
renderAgentscopeError(turn, payload.content || '出错');
```

In both `catch` blocks (`submitAgentscopeConfirm` and `sendAgentscopeMessage`), replace direct text append with:

```javascript
renderAgentscopeError(turn, e.message || String(e));
```

At the start of `submitAgentscopeConfirm`, set:

```javascript
turn.requestContext = null;
turn.errorRendered = false;
```

so confirm uses only its own execution context. Do not reset the rest of the assistant turn or tool strip.

- [ ] **Step 4: Add focused CSS**

Append:

```css
.agentscope-error-request {
    display: flex;
    align-items: center;
    gap: 8px;
    color: #991b1b;
    background: #fef2f2;
    border: 1px solid #fecaca;
    border-radius: 6px;
    padding: 6px 8px;
    font-size: 12px;
    word-break: break-all;
}
.agentscope-error-request button {
    flex: 0 0 auto;
    border: 1px solid #fca5a5;
    background: #fff;
    color: #991b1b;
    border-radius: 5px;
    padding: 3px 8px;
    cursor: pointer;
}
```

- [ ] **Step 5: Run syntax check**

Run:

```powershell
node --check src/main/resources/static/js/tabs/agentscope.js
```

Expected: exit code `0` and no output.

- [ ] **Step 6: Manually verify the browser contract**

Start the application with the existing local configuration and verify:

1. A successful AgentScope request has no visible request number.
2. Temporarily use an invalid model key or trigger a Service `ERROR`; the error shows one 32-character request number.
3. Copy writes exactly the visible requestId.
4. A subsequent request does not reuse the old ID.
5. A failed confirm shows the confirm execution ID, not the ask ID.

- [ ] **Step 7: Commit frontend support**

```powershell
git add demo2/src/main/resources/static/js/tabs/agentscope.js demo2/src/main/resources/static/css/tabs/agentscope.css
git commit -m "feat(demo2): show AgentScope request ID on errors"
```

---

### Task 5: 文档与全量回归

**Files:**
- Modify: `demo2/README.md`
- Modify: `README.md`
- Verify: all files from Tasks 1–4

**Interfaces:**
- Consumes: final SSE and logging behavior.
- Produces: operator-facing instructions for requestId → logs → traceId → Tempo.

- [ ] **Step 1: Update the AgentScope README section**

In `demo2/README.md` update the `/ask` event sequence to include:

```text
SESSION → REQUEST_CONTEXT →（Agent/模型/工具/消息事件）→ COMPACTION? → DONE|ERROR
```

Add a “Middleware 请求关联日志” subsection containing:

```markdown
**Middleware 请求关联日志：**

- 每次 `/ask`、`/confirm` 生成独立 requestId；前端仅在失败时展示并可复制。
- 服务端用同一 requestId 串联 Agent、reasoning、model、acting 与失败日志。
- 每条日志同时带 traceId/spanId；先按 requestId 定位执行日志，再按 traceId 到 Tempo 查询 HTTP 链路。
- 本功能不创建额外 AgentScope Span。Compaction 耗时可能计入 reasoning，但摘要模型调用不保证进入 onModelCall。
- 自定义 Middleware 不记录 Prompt、工具参数或工具结果；现有 `LoggingAgentscopeModel` DEBUG 明细仅适合受控本地环境。
```

Add a curl example note explaining that the second SSE event is `REQUEST_CONTEXT`.

- [ ] **Step 2: Update root README summary**

Change the AgentScope Harness feature row to include “Middleware requestId 关联日志”，并在可观测性说明中注明复用现有 OpenTelemetry Trace、不新增自定义 Span。

- [ ] **Step 3: Run all AgentScope tests**

Run:

```powershell
.\mvnw.cmd "-Dtest=com.jason.demo.demo2.agentscope.**" test
```

Expected: `BUILD SUCCESS`, no failures or errors.

If Surefire does not accept the package glob, run:

```powershell
.\mvnw.cmd "-Dtest=AgentExecutionContextTest,DevAgentEventTest,DevAgentServiceTest,AgentExecutionLoggingMiddlewareTest,AgentScopeMiddlewareConfigTest,AgentscopeCompactionConfigTest,AgentStateStoreFactoryTest,DevAgentPropertiesBindingTest,ProjectInfoToolsTest,FileChangeToolTest" test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Run full demo2 regression**

Run:

```powershell
.\mvnw.cmd test
```

Expected: `BUILD SUCCESS`; existing Spring AI、Embabel、AgentScope tests all pass.

- [ ] **Step 5: Run static checks**

Run:

```powershell
node --check src/main/resources/static/js/tabs/agentscope.js
git diff --check
```

Expected: both commands exit `0`; no syntax or whitespace errors.

- [ ] **Step 6: Perform log correlation acceptance**

With `DEEPSEEK_API_KEY` and the existing `otel` profile:

1. Send the documented project-info request.
2. Capture requestId from `REQUEST_CONTEXT`.
3. Confirm Agent、reasoning round 1、model、acting、reasoning round 2 and completion logs share that ID.
4. Confirm logs contain traceId/spanId.
5. Query the traceId in Tempo and confirm only the existing HTTP Trace is present; no custom AgentScope Span was created.
6. Trigger a model failure and confirm Middleware logs stop at the failing stage while SSE ends with `ERROR`.
7. Confirm Middleware logs contain no Prompt、tool input or tool result text.

- [ ] **Step 7: Commit docs and verification-ready state**

```powershell
git add README.md demo2/README.md
git commit -m "docs(demo2): document AgentScope request correlation"
```

Final expected history for this feature contains four focused implementation commits plus the documentation commit, with no unrelated `.idea/workspace.xml` or local configuration files staged.
