# AgentScope AG-UI 协议双通道接入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保留现有 `/agentscope/dev-agent/*`（含 HITL/Diff）的前提下，接入 `agentscope-agui-spring-boot-starter` 的 `/agui/run`，并在 AgentScope Tab 用协议开关演示 AG-UI 文本流与工具事件。

**Architecture:** Starter 扫描已有 Bean `agentscopeDevAgent` 并注册 AG-UI SSE；`AgentExecutionLoggingMiddleware` 用 `Flux.deferContextual` 在 AG-UI `stream()` 路径下从 Reactor Context 取回 `RuntimeContext`；前端按协议分流，切协议强制新开会话（S3），AG-UI 模式不做 HITL/Diff（H1）。

**Tech Stack:** Java 21、Spring Boot 4.x、AgentScope Java 2.0.0、`agentscope-agui-spring-boot-starter`、Reactor、原生 HTML/CSS/JS、JUnit、AssertJ、Reactor StepVerifier。

**设计规范:** [docs/superpowers/specs/2026-08-01-agentscope-agui-protocol-design.md](../specs/2026-08-01-agentscope-agui-protocol-design.md)

## Global Constraints

- **双通道并存**：不改 `DevAgentController` / `DevAgentService` / `DevAgentEvent*` / confirm / apply-diff。
- **不改名** HarnessAgent Bean：保持 `agentscopeDevAgent`；`agentscope.agui.default-agent-id=agentscopeDevAgent`。
- **不做** AG-UI ↔ HITL / WORKSPACE_DIFF 桥接；**不做** `userId:threadId` 多用户隔离；**不引入** AG-UI 前端 SDK。
- `server-side-memory=false`；会话仍走现有 stateStore / DistributedStore。
- 端口 **8081**（非文章示例 8080）。
- 配置写入 `application.properties`（properties 风格，不新开无关 yml）。
- 编译门禁：`mvn -f demo2/pom.xml -DskipTests compile`
- 单测门禁：`mvn -f demo2/pom.xml -Dtest=AgentExecutionLoggingMiddlewareTest test`
- Windows PowerShell：多命令用 `;` 连接，不用 `&&`。

## File Map

**Modify**

- `demo2/pom.xml` — 增加 `agentscope-agui-spring-boot-starter`（版本由 BOM）
- `demo2/src/main/resources/application.properties` — `agentscope.agui.*`
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/middleware/AgentExecutionLoggingMiddleware.java` — `onAgent` → `deferContextual` + `requireRuntimeContext`
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/middleware/AgentExecutionLoggingMiddlewareTest.java` — null context + Reactor Context 用例
- `demo2/src/main/resources/static/index.html` — 协议开关控件
- `demo2/src/main/resources/static/css/tabs/agentscope.css` — 开关样式
- `demo2/src/main/resources/static/js/tabs/agentscope.js` — 协议状态、S3 切换、AG-UI SSE、H1 示例拦截
- `demo2/README.md` — AG-UI 说明与 curl

**Create**

- 无 Java 类（Starter 自注册 `/agui/run`）

**Do not touch**

- `AgentScopeConfig.java`、`DevAgentController.java`、`DevAgentService.java`、`WorkspaceDiffService.java`

---

### Task 1: Middleware — AG-UI 空 context 可恢复 RuntimeContext

**Files:**
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/middleware/AgentExecutionLoggingMiddlewareTest.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/middleware/AgentExecutionLoggingMiddleware.java`

**Interfaces:**
- Consumes: 现有 `onAgent(Agent, RuntimeContext, AgentInput, Function)`；`AgentBase.RUNTIME_CONTEXT_KEY`（实现前用 jar / IDE 确认常量名；若包路径不同以实际为准）
- Produces: `onAgent` 在方法参数 `context == null` 时仍能从 Reactor Context 取回同一 `RuntimeContext`；仍为空则 `IllegalStateException`（消息含 `RuntimeContext is required`）

- [ ] **Step 1: 写失败测试（null context + Reactor Context）**

在 `AgentExecutionLoggingMiddlewareTest` 增加：

```java
@Test
void onAgent_nullContext_readsRuntimeContextFromReactorContext() {
    RuntimeContext runtime = runtime();
    runtime = RuntimeContext.builder()
            .userId("agui-user")
            .sessionId("agui-thread-1")
            .build();
    new AgentExecutionContext("request-agui", "trace-agui", "span-agui").writeTo(runtime);

    RuntimeContext finalRuntime = runtime;
    StepVerifier.create(
                    middleware.onAgent(
                                    agent,
                                    null,
                                    new AgentInput(List.of()),
                                    ignored -> Flux.empty())
                            .contextWrite(ctx -> ctx.put(
                                    io.agentscope.core.agent.AgentBase.RUNTIME_CONTEXT_KEY,
                                    finalRuntime)))
            .verifyComplete();

    assertThat(logs())
            .contains("sessionId=agui-thread-1")
            .contains("requestId=request-agui")
            .contains("Agent execution completed.");
}

@Test
void onAgent_nullContext_withoutReactorContext_failsClearly() {
    StepVerifier.create(middleware.onAgent(
                    agent,
                    null,
                    new AgentInput(List.of()),
                    ignored -> Flux.empty()))
            .expectErrorSatisfies(error -> assertThat(error)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("RuntimeContext is required"))
            .verify();
}
```

若 `AgentBase.RUNTIME_CONTEXT_KEY` 编译失败：在本地依赖中搜索 `RUNTIME_CONTEXT`，改用实际常量（文章与 2.0.0 文档指向 `AgentBase.RUNTIME_CONTEXT_KEY`）。

- [ ] **Step 2: 跑测试确认失败**

Run:

```powershell
mvn -f demo2/pom.xml "-Dtest=AgentExecutionLoggingMiddlewareTest#onAgent_nullContext_readsRuntimeContextFromReactorContext,AgentExecutionLoggingMiddlewareTest#onAgent_nullContext_withoutReactorContext_failsClearly" test
```

Expected: FAIL（NPE 或测试未通过），不是 COMPILE 无关错误。

- [ ] **Step 3: 实现 `deferContextual` + `requireRuntimeContext`**

将 `onAgent` 改为（保留现有日志字段与 `answerChars` 逻辑）：

```java
import io.agentscope.core.agent.AgentBase;
import reactor.util.context.ContextView;

@Override
public Flux<AgentEvent> onAgent(
        Agent agent,
        RuntimeContext context,
        AgentInput input,
        Function<AgentInput, Flux<AgentEvent>> next) {
    return Flux.deferContextual(contextView -> {
        RuntimeContext runtimeContext = requireRuntimeContext(context, contextView);
        AgentExecutionContext ids = AgentExecutionContext.from(runtimeContext);
        long startedAt = System.nanoTime();
        AtomicInteger answerChars = new AtomicInteger();
        log.info(
                "Agent execution started. requestId={}, traceId={}, spanId={}, "
                        + "agent={}, userId={}, sessionId={}",
                ids.requestId(),
                ids.traceId(),
                ids.spanId(),
                agent.getName(),
                runtimeContext.getUserId(),
                runtimeContext.getSessionId());
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
                        ids.requestId(),
                        ids.traceId(),
                        ids.spanId(),
                        elapsedMillis(startedAt),
                        answerChars.get()))
                .doOnError(error -> log.warn(
                        "Agent execution failed. requestId={}, traceId={}, spanId={}, "
                                + "durationMs={}, errorType={}, state=ERROR",
                        ids.requestId(),
                        ids.traceId(),
                        ids.spanId(),
                        elapsedMillis(startedAt),
                        error.getClass().getSimpleName()))
                .doOnCancel(() -> log.warn(
                        "Agent execution cancelled. requestId={}, traceId={}, spanId={}, "
                                + "durationMs={}, state=CANCELLED",
                        ids.requestId(),
                        ids.traceId(),
                        ids.spanId(),
                        elapsedMillis(startedAt)));
    });
}

private static RuntimeContext requireRuntimeContext(
        RuntimeContext context, ContextView contextView) {
    RuntimeContext runtimeContext =
            context != null
                    ? context
                    : contextView.getOrDefault(AgentBase.RUNTIME_CONTEXT_KEY, null);
    if (runtimeContext == null) {
        throw new IllegalStateException(
                "RuntimeContext is required for agent execution logging");
    }
    return runtimeContext;
}
```

**注意：** 仅改 `onAgent`。`onReasoning` / `onModelCall` / `onActing` / `onSystemPrompt` 本版不动（AG-UI 空指针问题出在最外层 `onAgent`）。

- [ ] **Step 4: 跑全套 Middleware 测试**

Run:

```powershell
mvn -f demo2/pom.xml "-Dtest=AgentExecutionLoggingMiddlewareTest" test
```

Expected: BUILD SUCCESS，全部 PASS（含原有非 null context 用例）。

- [ ] **Step 5: Commit**

```powershell
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/middleware/AgentExecutionLoggingMiddleware.java demo2/src/test/java/com/jason/demo/demo2/agentscope/middleware/AgentExecutionLoggingMiddlewareTest.java
git commit -m "fix(demo2): recover RuntimeContext from Reactor Context for AG-UI"
```

---

### Task 2: Maven Starter + `agentscope.agui.*` 配置

**Files:**
- Modify: `demo2/pom.xml`
- Modify: `demo2/src/main/resources/application.properties`

**Interfaces:**
- Consumes: 已有 BOM `${agentscope.version}`；Bean 名 `agentscopeDevAgent`
- Produces: 依赖可解析；启动后存在 `POST /agui/run`（本 Task 以 compile 门禁验收；curl 放到 Task 4 文档/手工）

- [ ] **Step 1: 在 `pom.xml` 增加依赖**

紧挨现有 AgentScope A2A starter 依赖之后加入（**不要**写 `<version>`，走 BOM）：

```xml
        <!-- AgentScope AG-UI：把 Agent 事件接到前端协议 -->
        <dependency>
            <groupId>io.agentscope</groupId>
            <artifactId>agentscope-agui-spring-boot-starter</artifactId>
        </dependency>
```

- [ ] **Step 2: 写入 `application.properties`**

在 AgentScope 相关配置区块追加（端口注释标明 8081）：

```properties
# ===== AgentScope AG-UI（与 /agentscope/dev-agent 双通道；HITL 仍走旧接口）=====
agentscope.agui.path-prefix=/agui
agentscope.agui.cors-enabled=true
agentscope.agui.default-agent-id=agentscopeDevAgent
agentscope.agui.agent-id-header=X-Agent-Id
agentscope.agui.enable-path-routing=true
agentscope.agui.emit-tool-call-args=true
agentscope.agui.enable-reasoning=false
agentscope.agui.server-side-memory=false
```

- [ ] **Step 3: 编译验证**

Run:

```powershell
mvn -f demo2/pom.xml -DskipTests compile
```

Expected: BUILD SUCCESS。若 artifact 找不到：确认 Central/BOM 含 `agentscope-agui-spring-boot-starter:2.0.0`；必要时临时加与 harness 相同的 version 属性排查，但仍优先 BOM。

- [ ] **Step 4: Commit**

```powershell
git add demo2/pom.xml demo2/src/main/resources/application.properties
git commit -m "feat(demo2): add AgentScope AG-UI starter and config"
```

---

### Task 3: 前端协议开关 + AG-UI SSE + H1/S3

**Files:**
- Modify: `demo2/src/main/resources/static/index.html`
- Modify: `demo2/src/main/resources/static/css/tabs/agentscope.css`
- Modify: `demo2/src/main/resources/static/js/tabs/agentscope.js`

**Interfaces:**
- Consumes: `POST /agui/run` SSE JSON（`type` 字段）；现有 `beginAgentscopeAssistantTurn` / `upsertAgentscopeToolItem` / `consumeAgentscopeSse` 模式
- Produces:
  - `agentscopeProtocol` ∈ `{ 'dev-agent', 'agui' }`，默认 `'dev-agent'`
  - `switchAgentscopeProtocol(next)`：abort in-flight → `resetAgentscopeConversation()` → 更新开关 UI
  - AG-UI 发送：`threadId=sessionId`，每次新 `runId` / message `id`
  - H1：示例 `4,9,13,15` 在 AG-UI 下提示切回 DevAgent，不填入输入框发送路径

- [ ] **Step 1: HTML 增加协议开关**

在 `index.html` 的 `.agentscope-meta` 内、`userId` 标签之前插入：

```html
            <label class="agentscope-protocol">协议
                <select id="agentscopeProtocol">
                    <option value="dev-agent" selected>DevAgent</option>
                    <option value="agui">AG-UI</option>
                </select>
            </label>
```

更新 header 说明一句：双通道；AG-UI 仅演示文本/工具，HITL/Diff 请用 DevAgent。

- [ ] **Step 2: CSS**

在 `agentscope.css` 追加：

```css
.agentscope-protocol select {
    margin-left: 0.35rem;
    padding: 0.25rem 0.4rem;
    border: 1px solid #cbd5e1;
    border-radius: 4px;
    background: #fff;
}
.agentscope-protocol-hint {
    font-size: 0.85rem;
    color: #64748b;
}
```

- [ ] **Step 3: JS — 状态、切换（S3）、AbortController**

在 `agentscope.js` 顶部常量区增加：

```javascript
let agentscopeProtocol = 'dev-agent'; // 'dev-agent' | 'agui'
let agentscopeAbortController = null;
const AGENTSCOPE_AGUI_HITL_SAMPLES = new Set([4, 9, 13, 15]);
```

扩展 `resetAgentscopeConversation`：若存在 `agentscopeAbortController`，`abort()` 并置 `null`（保留现有清消息 / 新 sessionId / 复位标志逻辑）。

新增：

```javascript
function getAgentscopeProtocol() {
    const el = document.getElementById('agentscopeProtocol');
    return (el && el.value) || agentscopeProtocol || 'dev-agent';
}

function switchAgentscopeProtocol(next) {
    const value = next === 'agui' ? 'agui' : 'dev-agent';
    if (getAgentscopeProtocol() === value && agentscopeProtocol === value) {
        return;
    }
    if (agentscopeAbortController) {
        try { agentscopeAbortController.abort('protocol-switch'); } catch (_) { /* ignore */ }
        agentscopeAbortController = null;
    }
    agentscopeProtocol = value;
    const el = document.getElementById('agentscopeProtocol');
    if (el) el.value = value;
    resetAgentscopeConversation();
    setAgentscopeStatus(value === 'agui' ? '就绪（AG-UI）' : '就绪（DevAgent）');
}
```

`fillAgentscopeSample(n)` 开头：

```javascript
    if (getAgentscopeProtocol() === 'agui' && AGENTSCOPE_AGUI_HITL_SAMPLES.has(n)) {
        appendAgentscopeSystemMessage('当前为 AG-UI 演示模式，写文件 / Memory 确认 / 沙箱改码 / Plan Mode 请切回 DevAgent 协议。');
        setAgentscopeStatus('请切回 DevAgent');
        return;
    }
```

绑定：

```javascript
document.getElementById('agentscopeProtocol')?.addEventListener('change', function (e) {
    switchAgentscopeProtocol(e.target.value);
});
```

- [ ] **Step 4: JS — AG-UI 事件处理与发送**

新增 handler（字段名以首次真实 SSE 为准；下列对齐常见 AG-UI / 文章）：

```javascript
function handleAgentscopeAguiPayload(turn, payload) {
    const type = payload.type;
    if (type === 'RUN_STARTED') {
        setAgentscopeStatus('RUN_STARTED');
    } else if (type === 'TEXT_MESSAGE_START') {
        turn.aguiMessageId = payload.messageId || turn.aguiMessageId;
        setAgentscopeStatus('TEXT_MESSAGE_START');
    } else if (type === 'TEXT_MESSAGE_CONTENT') {
        setAgentscopeStatus('流式中…');
        turn.content.textContent += (payload.delta || payload.content || '');
        scrollAgentscopeMessages();
    } else if (type === 'TEXT_MESSAGE_END') {
        setAgentscopeStatus('TEXT_MESSAGE_END');
    } else if (type === 'TOOL_CALL_START') {
        const id = payload.toolCallId || payload.id;
        const name = payload.toolCallName || payload.name || 'tool';
        setAgentscopeStatus('TOOL_CALL_START ' + name);
        upsertAgentscopeToolItem(turn, id, name, null);
    } else if (type === 'TOOL_CALL_ARGS') {
        setAgentscopeStatus('TOOL_CALL_ARGS');
        // 可选：不单独开工具条项；参数摘要可拼到 status
    } else if (type === 'TOOL_CALL_END') {
        const id = payload.toolCallId || payload.id;
        upsertAgentscopeToolItem(turn, id, payload.toolCallName || payload.name, 'END');
    } else if (type === 'TOOL_CALL_RESULT') {
        const id = payload.toolCallId || payload.id;
        upsertAgentscopeToolItem(turn, id, payload.toolCallName || payload.name, 'SUCCESS');
        setAgentscopeStatus('TOOL_CALL_RESULT');
    } else if (type === 'RUN_FINISHED') {
        setAgentscopeStatus('RUN_FINISHED');
    } else if (type === 'RUN_ERROR') {
        setAgentscopeStatus('RUN_ERROR');
        renderAgentscopeError(turn, payload.message || payload.content || 'AG-UI RUN_ERROR');
    }
}
```

新增 SSE 消费（复用 `\n\n` / `data:` 解析）：

```javascript
async function consumeAgentscopeAguiSse(res, turn) {
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const parts = buffer.split('\n\n');
        buffer = parts.pop();
        for (const part of parts) {
            let data = '';
            part.split('\n').forEach(function (line) {
                if (line.startsWith('data:')) data += line.slice(5).trim();
            });
            if (!data || data === '[DONE]') continue;
            let payload;
            try {
                payload = JSON.parse(data);
            } catch (_) {
                continue;
            }
            handleAgentscopeAguiPayload(turn, payload);
        }
    }
}
```

改写 `sendAgentscopeMessage`：在拼 body / fetch 前按协议分支。

DevAgent 分支：保持现有 `/agentscope/dev-agent/ask` 逻辑；创建 `agentscopeAbortController = new AbortController()`，`fetch(..., { signal })`，`finally` 里若仍是同一 controller 则置 null。

AG-UI 分支：

```javascript
        const runId = newAgentscopeSessionId();
        const messageId = newAgentscopeSessionId();
        agentscopeAbortController = new AbortController();
        const res = await fetch('/agui/run', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Accept': 'text/event-stream'
            },
            signal: agentscopeAbortController.signal,
            body: JSON.stringify({
                threadId: sessionId,
                runId: runId,
                messages: [
                    { id: messageId, role: 'user', content: message }
                ]
            })
        });
        if (!res.ok) {
            throw new Error(await res.text() || ('HTTP ' + res.status));
        }
        await consumeAgentscopeAguiSse(res, turn);
```

AG-UI 分支 **不要** 设置 `agentscopeAwaitingConfirm`；`finally` 始终 `setAgentscopeInputEnabled(true)`（除非已被 protocol-switch abort，可忽略 AbortError 或提示已切换）。

- [ ] **Step 5: 浏览器手工冒烟（需本地已启动应用）**

1. 打开 `http://localhost:8081` → AgentScope Tab，默认 DevAgent：发「用三句话说明发布前为什么要跑测试」→ 仍有 MESSAGE 流。
2. 切到 AG-UI：确认会话已刷新；发同样问题 → 状态出现 `RUN_STARTED` / 流式文字 / `RUN_FINISHED`。
3. AG-UI 下点示例「写 notes 文件（HITL）」→ 系统提示切回 DevAgent，不发起请求。
4. 切回 DevAgent → 示例 HITL 仍弹出确认卡。

若 AG-UI 字段名与假设不符：对照 Network 面板改 `handleAgentscopeAguiPayload` 映射（仍本 Task 内完成）。

- [ ] **Step 6: Commit**

```powershell
git add demo2/src/main/resources/static/index.html demo2/src/main/resources/static/css/tabs/agentscope.css demo2/src/main/resources/static/js/tabs/agentscope.js
git commit -m "feat(demo2): add AgentScope Tab AG-UI protocol switch"
```

---

### Task 4: README 验收说明

**Files:**
- Modify: `demo2/README.md`

**Interfaces:**
- Consumes: Task 2 配置与 `/agui/run`；Task 3 开关行为
- Produces: 读者能按文档用 curl + UI 验收双通道

- [ ] **Step 1: 在「AgentScope HarnessAgent」章节追加 AG-UI 小节**

紧接现有 `/agentscope/dev-agent` 表后增加：

```markdown
#### AG-UI 协议（`/agui/run`，与 DevAgent 双通道）

- 依赖：`agentscope-agui-spring-boot-starter`；默认 Agent：`agentscopeDevAgent`
- 配置前缀：`agentscope.agui.*`（`server-side-memory=false`，会话仍走现有 stateStore）
- 前端：AgentScope Tab「协议」开关；**切换协议会新开 sessionId**
- **范围**：AG-UI 演示文本流 + 工具事件；**HITL / WORKSPACE_DIFF / `/confirm` 仅 DevAgent**
- **不可混用**：`/agui/run` 的 `threadId` 不能拿去调 `/agentscope/dev-agent/confirm`

```bash
curl -sN -X POST "http://localhost:8081/agui/run" \
  -H "Content-Type: application/json" \
  -d "{\"threadId\":\"agui-thread-020\",\"runId\":\"agui-run-020-001\",\"messages\":[{\"id\":\"agui-message-020-001\",\"role\":\"user\",\"content\":\"请用三句话说明这个研发任务应该先做什么。\"}]}"
# 期望：RUN_STARTED → TEXT_MESSAGE_* → RUN_FINISHED
```

读文件/工具：

```bash
curl -sN -X POST "http://localhost:8081/agui/run/agentscopeDevAgent" \
  -H "Content-Type: application/json" \
  -d "{\"threadId\":\"agui-tool-thread-020\",\"runId\":\"agui-tool-run-020-001\",\"messages\":[{\"id\":\"agui-tool-message-020-001\",\"role\":\"user\",\"content\":\"请查看项目 Java / Spring Boot 版本和启动类。\"}]}"
# 期望：穿插 TOOL_CALL_START → TOOL_CALL_ARGS? → TOOL_CALL_END → TOOL_CALL_RESULT
```
```

并在能力表或 Tab 一览中加一句「协议：DevAgent | AG-UI」。

- [ ] **Step 2: Commit**

```powershell
git add demo2/README.md
git commit -m "docs(demo2): document AgentScope AG-UI dual-path usage"
```

---

## Spec Coverage Checklist

| Spec 要求 | Task |
|-----------|------|
| Maven starter | Task 2 |
| `agentscope.agui.*` + `default-agent-id=agentscopeDevAgent` | Task 2 |
| Middleware `deferContextual` / 空 context | Task 1 |
| 不改 DevAgent 后端主流程 | Global + File Map |
| 前端协议开关 B2 | Task 3 |
| S3 切协议新会话 + abort | Task 3 |
| AG-UI 事件映射文本/工具 | Task 3 |
| H1 HITL/Diff 降级与示例拦截 | Task 3 |
| README + curl 8081 | Task 4 |
| Middleware 单测 | Task 1 |
| compile 门禁 | Task 2 |

## Self-Review Notes

- 无 TBD/TODO 占位；AG-UI JSON 字段名允许在 Task 3 Step 5 按真实 SSE 微调（已写明）。
- `AgentBase.RUNTIME_CONTEXT_KEY` 若常量名漂移，Task 1 Step 1 已要求以 jar 为准。
- 仅 `onAgent` 做 context 恢复，与规格「AG-UI stream 最外层」一致。
