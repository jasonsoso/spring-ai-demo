# AgentScope Compaction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 启用 HarnessAgent Compaction，长会话在推理前把较早消息压成摘要；Service 在流结束后探测并推送 `COMPACTION` SSE，前端展示带条数的系统提示。

**Architecture:** `DevAgentProperties` 增加可配置 `compaction`（默认 trigger=6、keep=2 + summary-prompt）；`AgentScopeConfig` 组装 `CompactionConfig`（写死 keepTokens=0、flush/offload=false）并用 `.compaction(...)` 替换 `.disableCompaction()`。`DevAgentService` 在 ask/confirm 流前后对比 `AgentState.context` 条数，判定压缩后在 `DONE` 前插入 `COMPACTION`。

**Tech Stack:** Java 21, Spring Boot 4.x, AgentScope Java 2.0.0 (`agentscope-harness` 的 `CompactionConfig` / `CompactionMiddleware`), JUnit 5, AssertJ, Mockito, Reactor StepVerifier, Spring Boot `ApplicationContextRunner`

**设计规范:** [docs/superpowers/specs/2026-07-23-agentscope-compaction-design.md](../specs/2026-07-23-agentscope-compaction-design.md)

## Global Constraints

- **AgentScope 版本**：`2.0.0`
- **落地方式**：Harness 原生 `.compaction(CompactionConfig)`，移除 `.disableCompaction()`
- **可配置三项**：`triggerMessages` / `keepMessages` / `summaryPrompt`；前缀 `app.agentscope.dev-agent.compaction.*`
- **Demo 默认**：`trigger-messages=6`，`keep-messages=2`（便于四轮触发）；配置旁注释正式环境建议上调
- **写死**：`keepTokens(0)`、`flushBeforeCompact(false)`、`offloadBeforeCompact(false)`
- **不碰**：`ToolResultEvictionConfig`、Memory、自定义 Middleware、API 路径、HITL / Permission / Workspace 文件内容
- **探测时机**：`streamEvents` **结束后**再读 store（非 `MODEL_CALL_START`）；`COMPACTION` 在 `DONE` 之前
- **判定**：`afterCount > 0 && afterCount < beforeCount + 1`
- **文案**：`上下文已压缩：{beforeDisplay} 条 → 1 条摘要 + {keepMessages} 条原文（共 {afterCount} 条）`，其中 `beforeDisplay = beforeCount + 1`
- **编译门禁**：`mvn -f demo2/pom.xml -DskipTests compile` 必须 SUCCESS
- **单测门禁**：`mvn -f demo2/pom.xml -Dtest=DevAgentPropertiesBindingTest,AgentscopeCompactionConfigTest,DevAgentEventTest,DevAgentServiceTest test` 必须 SUCCESS

---

## File Structure

| 文件 | 职责 |
|------|------|
| `.../agentscope/config/DevAgentProperties.java` | 嵌套 `Compaction` record |
| `demo2/src/main/resources/application.properties` | trigger / keep 数值 + 正式环境注释 |
| `demo2/src/main/resources/application-agentscope-prompts.yml` | `compaction.summary-prompt` |
| `.../agentscope/config/AgentScopeConfig.java` | `toCompactionConfig` + Bean；Harness `.compaction(...)` |
| `.../agentscope/model/DevAgentEventType.java` | 枚举 `COMPACTION` |
| `.../agentscope/model/DevAgentEvent.java` | `compaction(...)` 工厂 |
| `.../agentscope/service/DevAgentService.java` | 条数快照 + 流后插入 `COMPACTION` |
| `demo2/src/main/resources/static/js/tabs/agentscope.js` | 渲染系统提示 + 示例 6 |
| `demo2/src/main/resources/static/css/tabs/agentscope.css` | 系统提示样式 |
| `demo2/src/main/resources/static/index.html` | 示例按钮 + 页头说明 |
| `demo2/README.md` | Compaction 小节 + curl |
| `.../DevAgentPropertiesBindingTest.java` | 绑定 compaction |
| `.../AgentscopeCompactionConfigTest.java` | 组装断言 |
| `.../DevAgentEventTest.java` | `compaction` 工厂 |
| `.../DevAgentServiceTest.java` | 探测发 / 不发 `COMPACTION`；构造器补 compaction |

**目标 Properties 签名：**

```java
public record DevAgentProperties(
        @NotBlank String name,
        @NotBlank String systemPrompt,
        @NotBlank String projectRoot,
        @NotBlank String workspaceRoot,
        @Valid Compaction compaction,
        @Valid Model model) {

    public record Compaction(
            @Min(2) int triggerMessages,
            @Min(1) int keepMessages,
            @NotBlank String summaryPrompt) {
    }
}
```

**目标 Harness 片段：**

```java
HarnessAgent.builder()
    // ...
    .compaction(agentscopeCompactionConfig)
    // 不再调用 .disableCompaction()
    .disableFilesystemTools()
    // ...其余 disable 保持
    .build();
```

---

### Task 1: DevAgentProperties + 配置绑定

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/DevAgentProperties.java`
- Modify: `demo2/src/main/resources/application.properties`
- Modify: `demo2/src/main/resources/application-agentscope-prompts.yml`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/DevAgentPropertiesBindingTest.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java`（仅构造器补参，先让编译过）

**Interfaces:**
- Produces: `DevAgentProperties.Compaction(int triggerMessages, int keepMessages, String summaryPrompt)`；配置键 `app.agentscope.dev-agent.compaction.*`
- Consumes: 无

- [ ] **Step 1: 写失败的绑定测试**

改 `DevAgentPropertiesBindingTest`：在 `withPropertyValues` 增加 compaction 项，并新增断言方法：

```java
.withPropertyValues(
        "app.agentscope.dev-agent.name=dev-task-agent",
        "app.agentscope.dev-agent.system-prompt=short",
        "app.agentscope.dev-agent.project-root=.",
        "app.agentscope.dev-agent.workspace-root=workspace",
        "app.agentscope.dev-agent.compaction.trigger-messages=6",
        "app.agentscope.dev-agent.compaction.keep-messages=2",
        "app.agentscope.dev-agent.compaction.summary-prompt=请整理会话：{messages}",
        "app.agentscope.dev-agent.model.api-key=",
        "app.agentscope.dev-agent.model.base-url=https://api.deepseek.com",
        "app.agentscope.dev-agent.model.name=deepseek-v4-pro");

@Test
void bindsCompaction() {
    runner.run(ctx -> {
        DevAgentProperties.Compaction c = ctx.getBean(DevAgentProperties.class).compaction();
        assertThat(c.triggerMessages()).isEqualTo(6);
        assertThat(c.keepMessages()).isEqualTo(2);
        assertThat(c.summaryPrompt()).contains("{messages}");
    });
}
```

保留原有 `bindsWorkspaceRoot`。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -f demo2/pom.xml -Dtest=DevAgentPropertiesBindingTest#bindsCompaction test`

Expected: FAIL（`compaction` 不存在或绑定失败）

- [ ] **Step 3: 实现 Properties + 配置文件**

`DevAgentProperties.java` 改为：

```java
package com.jason.demo.demo2.agentscope.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.agentscope.dev-agent")
public record DevAgentProperties(
        @NotBlank String name,
        @NotBlank String systemPrompt,
        @NotBlank String projectRoot,
        @NotBlank String workspaceRoot,
        @Valid Compaction compaction,
        @Valid Model model) {

    public record Compaction(
            @Min(2) int triggerMessages,
            @Min(1) int keepMessages,
            @NotBlank String summaryPrompt) {
    }

    /**
     * apiKey 允许为空：缺 DEEPSEEK_API_KEY 时不阻止应用启动，由 Service 在 ask 时返回 ERROR。
     */
    public record Model(
            String apiKey,
            @NotBlank String baseUrl,
            @NotBlank String name) {
    }
}
```

在 `application.properties` 的 `app.agentscope.dev-agent` 段追加：

```properties
# Demo 默认偏低，便于四轮触发；正式环境请按上下文窗口 / 工具结果大小 / 平均轮数上调（勿贴模型上限）
app.agentscope.dev-agent.compaction.trigger-messages=6
app.agentscope.dev-agent.compaction.keep-messages=2
```

在 `application-agentscope-prompts.yml` 的 `dev-agent:` 下追加（与 `system-prompt` 同级）：

```yaml
      compaction:
        summary-prompt: |
          请把下面的会话整理成一份供后续任务继续使用的上下文摘要。
          只保留用户目标、已经确认的事实、尚未完成的事项和明确编号。
          不要补充会话中没有出现的信息。

          使用下面的结构：

          ## 当前目标
          ## 已确认信息
          ## 待处理事项

          会话内容：
          {messages}
```

同步改 `DevAgentServiceTest` 所有 `new DevAgentProperties(...)`，在 `workspace` 与 `Model` 之间插入：

```java
new DevAgentProperties.Compaction(6, 2, "请整理会话：{messages}"),
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -f demo2/pom.xml -Dtest=DevAgentPropertiesBindingTest,DevAgentServiceTest test`

Expected: SUCCESS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/DevAgentProperties.java \
  demo2/src/main/resources/application.properties \
  demo2/src/main/resources/application-agentscope-prompts.yml \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/config/DevAgentPropertiesBindingTest.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java
git commit -m "feat(demo2): add AgentScope Compaction properties binding"
```

---

### Task 2: CompactionConfig Bean + 启用 Harness Compaction

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentscopeCompactionConfigTest.java`

**Interfaces:**
- Consumes: `DevAgentProperties.Compaction`
- Produces: `static CompactionConfig toCompactionConfig(DevAgentProperties.Compaction c)`；Bean 名方法 `agentscopeCompactionConfig`；Harness `.compaction(CompactionConfig)`

- [ ] **Step 1: 写失败的组装测试**

创建 `AgentscopeCompactionConfigTest.java`：

```java
package com.jason.demo.demo2.agentscope.config;

import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentscopeCompactionConfigTest {

    @Test
    void toCompactionConfig_mapsThreeKnobs_andHardcodesFlags() {
        DevAgentProperties.Compaction input = new DevAgentProperties.Compaction(
                6, 2, "请整理：{messages}");

        CompactionConfig config = AgentScopeConfig.toCompactionConfig(input);

        assertThat(config.getTriggerMessages()).isEqualTo(6);
        assertThat(config.getKeepMessages()).isEqualTo(2);
        assertThat(config.getSummaryPrompt()).isEqualTo("请整理：{messages}");
        assertThat(config.getKeepTokens()).isEqualTo(0);
        assertThat(config.isFlushBeforeCompact()).isFalse();
        assertThat(config.isOffloadBeforeCompact()).isFalse();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -f demo2/pom.xml -Dtest=AgentscopeCompactionConfigTest test`

Expected: FAIL（`toCompactionConfig` 不存在）

- [ ] **Step 3: 实现 Bean 并替换 disableCompaction**

在 `AgentScopeConfig` 增加 import：

```java
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
```

增加静态工厂 + Bean（放在 `agentscopeDevAgent` 之前）：

```java
static CompactionConfig toCompactionConfig(DevAgentProperties.Compaction c) {
    return CompactionConfig.builder()
            .triggerMessages(c.triggerMessages())
            .keepMessages(c.keepMessages())
            .keepTokens(0)
            .summaryPrompt(c.summaryPrompt())
            .flushBeforeCompact(false)
            .offloadBeforeCompact(false)
            .build();
}

@Bean
CompactionConfig agentscopeCompactionConfig(DevAgentProperties properties) {
    return toCompactionConfig(properties.compaction());
}
```

改 `agentscopeDevAgent` 方法签名，增加参数 `CompactionConfig agentscopeCompactionConfig`，并将：

```java
.disableCompaction()
```

替换为：

```java
.compaction(agentscopeCompactionConfig)
```

其余 builder 调用不变。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -f demo2/pom.xml -Dtest=AgentscopeCompactionConfigTest,DevAgentPropertiesBindingTest -DskipTests=false test`

同时：`mvn -f demo2/pom.xml -DskipTests compile`

Expected: 两者 SUCCESS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentscopeCompactionConfigTest.java
git commit -m "feat(demo2): enable AgentScope Harness Compaction"
```

---

### Task 3: COMPACTION 事件模型

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentEventType.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentEvent.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/model/DevAgentEventTest.java`

**Interfaces:**
- Produces: `DevAgentEventType.COMPACTION`；`DevAgentEvent.compaction(String sessionId, String content)`
- Consumes: 无

- [ ] **Step 1: 写失败的工厂测试**

在 `DevAgentEventTest` 追加：

```java
@Test
void compaction_setsTypeAndContent() {
    DevAgentEvent event = DevAgentEvent.compaction(
            "s1", "上下文已压缩：7 条 → 1 条摘要 + 2 条原文（共 3 条）");
    assertThat(event.type()).isEqualTo(DevAgentEventType.COMPACTION);
    assertThat(event.sessionId()).isEqualTo("s1");
    assertThat(event.content()).contains("7 条").contains("共 3 条");
    assertThat(event.pendingToolCalls()).isNull();
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -f demo2/pom.xml -Dtest=DevAgentEventTest#compaction_setsTypeAndContent test`

Expected: FAIL

- [ ] **Step 3: 实现枚举与工厂**

`DevAgentEventType` 末尾追加：

```java
/** 本轮会话上下文已压缩（摘要替换较早消息）。 */
COMPACTION
```

`DevAgentEvent` 追加：

```java
public static DevAgentEvent compaction(String sessionId, String content) {
    return new DevAgentEvent(
            DevAgentEventType.COMPACTION,
            sessionId,
            content == null ? "" : content,
            null,
            null,
            null,
            null,
            null);
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -f demo2/pom.xml -Dtest=DevAgentEventTest test`

Expected: SUCCESS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentEventType.java \
  demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentEvent.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/model/DevAgentEventTest.java
git commit -m "feat(demo2): add COMPACTION SSE event type"
```

---

### Task 4: DevAgentService 压缩探测

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java`

**Interfaces:**
- Consumes: `AgentStateStore.get(userId, sessionId, "agent_state", AgentState.class)`；`properties.compaction().keepMessages()`；`DevAgentEvent.compaction(...)`
- Produces: ask/confirm 流在 `DONE` 前可能多一条 `COMPACTION`

- [ ] **Step 1: 写失败的探测测试**

在 `DevAgentServiceTest` 的 `@BeforeEach` 增加 lenient 默认 stub（避免未 stub 时 NPE）：

```java
import static org.mockito.Mockito.lenient;

lenient()
        .when(agentStateStore.get(any(), any(), eq("agent_state"), eq(AgentState.class)))
        .thenReturn(Optional.empty());
```

追加辅助方法与两个测试：

```java
private static AgentState stateWithMessageCount(int n) {
    java.util.ArrayList<Msg> context = new java.util.ArrayList<>();
    for (int i = 0; i < n; i++) {
        context.add(Msg.builder()
                .role(i % 2 == 0 ? MsgRole.USER : MsgRole.ASSISTANT)
                .textContent("m" + i)
                .build());
    }
    return AgentState.builder().userId("u1").sessionId("s1").context(context).build();
}

@Test
void ask_whenContextShrunk_emitsCompactionBeforeDone() {
    when(agentStateStore.get(eq("u1"), eq("s1"), eq("agent_state"), eq(AgentState.class)))
            .thenReturn(Optional.of(stateWithMessageCount(6)))
            .thenReturn(Optional.of(stateWithMessageCount(4)));

    TextBlockDeltaEvent d1 = mock(TextBlockDeltaEvent.class);
    when(d1.getType()).thenReturn(AgentEventType.TEXT_BLOCK_DELTA);
    when(d1.getDelta()).thenReturn("ok");
    when(harnessAgent.streamEvents(eq("汇总"), any(RuntimeContext.class)))
            .thenReturn(Flux.just(d1));

    StepVerifier.create(service.ask(new DevAgentRequest("u1", "s1", "汇总")))
            .expectNext(DevAgentEvent.session("s1"))
            .expectNext(DevAgentEvent.message("s1", "ok"))
            .expectNextMatches(e ->
                    e.type() == DevAgentEventType.COMPACTION
                            && e.content().contains("7 条")
                            && e.content().contains("共 4 条")
                            && e.content().contains("2 条原文"))
            .expectNext(DevAgentEvent.done("s1"))
            .verifyComplete();
}

@Test
void ask_whenContextGrew_doesNotEmitCompaction() {
    when(agentStateStore.get(eq("u1"), eq("s1"), eq("agent_state"), eq(AgentState.class)))
            .thenReturn(Optional.of(stateWithMessageCount(2)))
            .thenReturn(Optional.of(stateWithMessageCount(4)));

    TextBlockDeltaEvent d1 = mock(TextBlockDeltaEvent.class);
    when(d1.getType()).thenReturn(AgentEventType.TEXT_BLOCK_DELTA);
    when(d1.getDelta()).thenReturn("ack");
    when(harnessAgent.streamEvents(eq("hi"), any(RuntimeContext.class)))
            .thenReturn(Flux.just(d1));

    StepVerifier.create(service.ask(new DevAgentRequest("u1", "s1", "hi")))
            .expectNext(DevAgentEvent.session("s1"))
            .expectNext(DevAgentEvent.message("s1", "ack"))
            .expectNext(DevAgentEvent.done("s1"))
            .verifyComplete();
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -f demo2/pom.xml -Dtest=DevAgentServiceTest#ask_whenContextShrunk_emitsCompactionBeforeDone,DevAgentServiceTest#ask_whenContextGrew_doesNotEmitCompaction test`

Expected: FAIL（尚无 COMPACTION 插入逻辑）

- [ ] **Step 3: 实现探测逻辑**

在 `DevAgentService` 中抽取共用方法，并改写 `ask` / `confirm` 的返回流。

新增私有方法：

```java
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
    if (afterCount <= 0 || afterCount >= beforeCount + 1) {
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
```

将 `ask` 中成功路径改为（保留 apiKey 早退）：

```java
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
```

`confirm` 在拿到 `pending` 且准备 `streamEvents` 之前同样读取 `beforeCount`，返回流同样 `Flux.concat(session, events, Mono.defer(() -> compactionEventIfNeeded(...)), done)`。`confirm` 早退错误路径不变。

注意：`beforeCount == -1`（读失败）时不发 `COMPACTION`。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -f demo2/pom.xml -Dtest=DevAgentServiceTest test`

Expected: SUCCESS（含原有用例）

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java
git commit -m "feat(demo2): emit COMPACTION SSE after context shrink"
```

---

### Task 5: 前端系统提示 + 示例按钮

**Files:**
- Modify: `demo2/src/main/resources/static/js/tabs/agentscope.js`
- Modify: `demo2/src/main/resources/static/css/tabs/agentscope.css`
- Modify: `demo2/src/main/resources/static/index.html`

**Interfaces:**
- Consumes: SSE `payload.type === 'COMPACTION'`，`payload.content`
- Produces: 消息区系统行；示例 6 预填 CTX-009 首轮文案并固定 session

- [ ] **Step 1: 增加系统提示渲染**

在 `agentscope.js` 增加：

```javascript
function appendAgentscopeSystemMessage(text) {
    const box = document.getElementById('agentscopeMessages');
    if (!box) return;
    const welcome = document.getElementById('agentscopeWelcome');
    if (welcome) welcome.remove();
    const div = document.createElement('div');
    div.className = 'message system';
    const content = document.createElement('div');
    content.className = 'message-content';
    content.textContent = text || '';
    div.appendChild(content);
    box.appendChild(div);
    scrollAgentscopeMessages();
}
```

在 `handleAgentscopeSsePayload` 增加分支（建议放在 `DONE` 之前）：

```javascript
} else if (payload.type === 'COMPACTION') {
    setAgentscopeStatus('COMPACTION');
    appendAgentscopeSystemMessage(payload.content || '上下文已压缩');
```

- [ ] **Step 2: CSS**

在 `agentscope.css` 追加：

```css
.agentscope-messages .message.system {
    justify-content: center;
}
.agentscope-messages .message.system .message-content {
    background: #f3f4f6;
    color: #6b7280;
    border: 1px dashed #d1d5db;
    font-size: 0.85rem;
    max-width: 90%;
    text-align: center;
}
```

- [ ] **Step 3: 示例按钮 6 + 页头**

`index.html` 页头说明追加「长会话达阈值会压缩并提示条数」；samples 区追加：

```html
<button type="button" onclick="fillAgentscopeSample(6)">示例：Compaction 四轮</button>
```

`fillAgentscopeSample` 的 `samples` 增加：

```javascript
6: '任务编号是 CTX-009。需要确认 Java 版本、Spring Boot 版本、启动类、源码目录、构建命令和测试命令。只确认收到，不要调用工具。'
```

并在 `n === 6` 时：

```javascript
if (n === 6) {
    const userId = document.getElementById('agentscopeUserId');
    const sessionId = document.getElementById('agentscopeSessionId');
    if (userId) userId.value = 'context-user-009';
    if (sessionId) sessionId.value = 'context-session-009';
}
```

欢迎文案可补一句：可用「Compaction 四轮」示例，同一 session 连发四轮观察压缩提示。

- [ ] **Step 4: 手工快速检查（无编译依赖）**

浏览器打开 AgentScope Tab，确认按钮与样式存在即可（完整四轮放 Task 6）。

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/resources/static/js/tabs/agentscope.js \
  demo2/src/main/resources/static/css/tabs/agentscope.css \
  demo2/src/main/resources/static/index.html
git commit -m "feat(demo2): show AgentScope Compaction system notice in UI"
```

---

### Task 6: README + 回归门禁 + 手工验收说明

**Files:**
- Modify: `demo2/README.md`（AgentScope 相关小节）

**Interfaces:**
- Produces: 文档中的配置说明与四轮 curl（路径使用本项目 `/agentscope/dev-agent/ask`）

- [ ] **Step 1: 更新 README**

在 AgentScope 章节补充：

1. Compaction 与 PostgreSQL 职责对照（存状态 vs 缩历史）
2. 配置项：`trigger-messages` / `keep-messages` / `summary-prompt`；写死 keepTokens/flush/offload
3. SSE 新增 `COMPACTION`（流结束后、`DONE` 前）
4. 四轮 curl 示例（`userId=context-user-009`，`sessionId=context-session-009`，消息对照文章；URL 为 `http://localhost:8080/agentscope/dev-agent/ask`）
5. 说明日志关键字：`Compaction triggered` / `Compaction complete`
6. 注明与 Session Memory Tab 的 RecursiveSummarization **无关**

- [ ] **Step 2: 跑全部门禁单测 + compile**

Run:

```bash
mvn -f demo2/pom.xml -DskipTests compile
mvn -f demo2/pom.xml -Dtest=DevAgentPropertiesBindingTest,AgentscopeCompactionConfigTest,DevAgentEventTest,DevAgentServiceTest test
```

Expected: 全部 SUCCESS

- [ ] **Step 3: Commit**

```bash
git add demo2/README.md
git commit -m "docs(demo2): document AgentScope Compaction usage"
```

- [ ] **Step 4: 手工验收清单（实现者勾选）**

启动应用（PostgreSQL 可用、`DEEPSEEK_API_KEY` 已配），同一 session 发四轮「只确认、不调工具」：

1. 日志出现 `Compaction triggered` / `Compaction complete`
2. 第四轮 SSE 含 `COMPACTION`，文案含前后条数
3. 前端出现系统提示
4. 第四轮回答仍能汇总已确认信息与待办
5. `/agentscope/dev-agent/ask` 与 `/confirm` 路径未变

---

## Spec Coverage Checklist

| Spec 项 | Task |
|---------|------|
| 启用 `.compaction(CompactionConfig)`，移除 disable | Task 2 |
| 可配置 trigger/keep/summaryPrompt，默认 6/2 | Task 1 |
| keepTokens=0、flush/offload=false 写死 | Task 2 |
| 不碰 ToolResultEviction / Memory / API 路径 | Global + 各 Task 未改 |
| COMPACTION SSE + 文案格式 | Task 3–4 |
| 流结束后探测，`DONE` 前插入 | Task 4 |
| 判定 `afterCount < beforeCount + 1` | Task 4 |
| 前端系统提示 + 条数 | Task 5 |
| Properties / Config / Service 单测 | Task 1–4 |
| 四轮手工验收 + README | Task 5–6 |
