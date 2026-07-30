# AgentScope Multi-Model Failover Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** DeepSeek 可重试失败耗尽后自动切到 Kimi `kimi-k3`，覆盖共享 AgentScope `Model` bean 的全部调用方（含 Memory Flush），并用日志区分 `configuredModel` / `actualModel`。

**Architecture:** 在 `OpenAIChatModel`（DeepSeek + Kimi）之上实现 `FailoverAgentscopeModel`（同模型 `maxAttempts` 重试，首 chunk 前失败才可重试/切备），再包已有 `LoggingAgentscopeModel`；共用 `OkHttpTransport`；**不**启用 `HarnessAgent.fallbackModel`，避免双重容错。

**Tech Stack:** Java 21、Spring Boot、AgentScope Java 2.0.0（`Model` / `OpenAIChatModel` / `OkHttpTransport` / `DeepSeekFormatter`）、Reactor、JUnit 5、Mockito、AssertJ、`reactor-test`。

**设计规范:** [docs/superpowers/specs/2026-07-30-agentscope-model-failover-design.md](../specs/2026-07-30-agentscope-model-failover-design.md)

## Global Constraints

- AgentScope 版本保持 `2.0.0`，**不新增** Maven 依赖（`OkHttpTransport` 已在 `agentscope-core`）
- **不**调用 `HarnessAgent.builder().fallbackModel(...)`；**不**为本特性单独抬高 `.maxRetries()`
- Bean 名保持 `@Qualifier("agentscopeDeepSeekModel")`
- 配置继续用 `application.properties`（不引入 yml）
- 备用默认模型名：`kimi-k3`；密钥：`KIMI_API_KEY` 或 `MOONSHOT_API_KEY`
- 流式：仅在**首个非 null chunk 之前**失败才重试/切备；半路失败原样抛出
- Kimi apiKey 空白：不创建备用 `OpenAIChatModel`，`fallbackOrNull=null`，启动 warn
- 编译门禁：在 `demo2` 目录 `.\mvnw.cmd -DskipTests compile`
- 单测门禁示例：`.\mvnw.cmd "-Dtest=DevAgentPropertiesBindingTest,FailoverAgentscopeModelTest,AgentExecutionLoggingMiddlewareTest" test`

---

## File Map

**Create**

- `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/FailoverAgentscopeModel.java`
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/model/FailoverAgentscopeModelTest.java`

**Modify**

- `demo2/src/main/resources/application.properties`：主模型 env 覆盖 + `model-fallback.*`
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/DevAgentProperties.java`：`ModelFallback` + 默认值
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/DevAgentPropertiesBindingTest.java`：绑定/默认用例
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`：`OkHttpTransport` + Failover 装配
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/middleware/AgentExecutionLoggingMiddleware.java`：`configuredModel` / `actualModel`
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/middleware/AgentExecutionLoggingMiddlewareTest.java`：观测断言
- `demo2/README.md`：Harness 段补充多模型容错说明

**不改**

- `DevAgentController` / SSE 协议 / 前端
- Spring AI / Embabel 聊天客户端
- Risk Review / SubAgent 注入点（继续吃同一 bean）

---

### Task 1: ModelFallback 配置绑定

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/DevAgentProperties.java`
- Modify: `demo2/src/main/resources/application.properties`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/DevAgentPropertiesBindingTest.java`

**Interfaces:**
- Produces:
  - `DevAgentProperties#modelFallback() -> ModelFallback`
  - `record ModelFallback(@Valid Model fallback, @Min(1) int maxAttempts)`
  - compact ctor：`modelFallback == null` 时默认 `maxAttempts=2`，fallback `baseUrl=https://api.moonshot.cn/v1`，`name=kimi-k3`，`apiKey=""`（或 null）
- Consumes: 现有 `Model(apiKey, baseUrl, name)`

- [ ] **Step 1: 写失败的绑定测试**

在 `DevAgentPropertiesBindingTest` 追加：

```java
@Test
void bindsModelFallback() {
    runner.withPropertyValues(
            "app.agentscope.dev-agent.model-fallback.max-attempts=3",
            "app.agentscope.dev-agent.model-fallback.fallback.api-key=kimi-key",
            "app.agentscope.dev-agent.model-fallback.fallback.base-url=https://api.moonshot.cn/v1",
            "app.agentscope.dev-agent.model-fallback.fallback.name=kimi-k3"
    ).run(ctx -> {
        DevAgentProperties.ModelFallback fb =
                ctx.getBean(DevAgentProperties.class).modelFallback();
        assertThat(fb.maxAttempts()).isEqualTo(3);
        assertThat(fb.fallback().apiKey()).isEqualTo("kimi-key");
        assertThat(fb.fallback().baseUrl()).isEqualTo("https://api.moonshot.cn/v1");
        assertThat(fb.fallback().name()).isEqualTo("kimi-k3");
    });
}

@Test
void modelFallbackDefaultsWhenAbsent() {
    runner.run(ctx -> {
        DevAgentProperties.ModelFallback fb =
                ctx.getBean(DevAgentProperties.class).modelFallback();
        assertThat(fb.maxAttempts()).isEqualTo(2);
        assertThat(fb.fallback().baseUrl()).isEqualTo("https://api.moonshot.cn/v1");
        assertThat(fb.fallback().name()).isEqualTo("kimi-k3");
        assertThat(fb.fallback().apiKey() == null || fb.fallback().apiKey().isBlank()).isTrue();
    });
}
```

- [ ] **Step 2: 跑测试确认失败**

Run:

```powershell
cd d:\ai\spring-ai-demo\demo2
.\mvnw.cmd "-Dtest=DevAgentPropertiesBindingTest#bindsModelFallback+modelFallbackDefaultsWhenAbsent" test
```

Expected: 编译失败或测试失败（尚无 `modelFallback()`）。

- [ ] **Step 3: 改 `DevAgentProperties`**

1. 在 record 主构造参数列表中，于 `model` 之后增加：`@Valid ModelFallback modelFallback`
2. 在 compact constructor 中增加：

```java
if (modelFallback == null) {
    modelFallback = new ModelFallback(
            new Model("", "https://api.moonshot.cn/v1", "kimi-k3"),
            2);
}
```

3. 新增嵌套 record：

```java
public record ModelFallback(
        @Valid Model fallback,
        @Min(1) int maxAttempts) {
}
```

注意：Spring 绑定属性名是 `model-fallback` → 字段 `modelFallback`。

- [ ] **Step 4: 改 `application.properties`**

将现有主模型三行替换/扩展为：

```properties
app.agentscope.dev-agent.model.api-key=${DEEPSEEK_API_KEY:}
app.agentscope.dev-agent.model.base-url=${DEEPSEEK_BASE_URL:https://api.deepseek.com}
app.agentscope.dev-agent.model.name=${DEEPSEEK_MODEL_NAME:deepseek-v4-pro}

# AgentScope 多模型容错（DeepSeek → Kimi；max-attempts 含首次调用）
app.agentscope.dev-agent.model-fallback.max-attempts=2
app.agentscope.dev-agent.model-fallback.fallback.api-key=${KIMI_API_KEY:${MOONSHOT_API_KEY:}}
app.agentscope.dev-agent.model-fallback.fallback.base-url=${KIMI_BASE_URL:https://api.moonshot.cn/v1}
app.agentscope.dev-agent.model-fallback.fallback.name=${KIMI_MODEL_NAME:kimi-k3}
```

- [ ] **Step 5: 跑绑定测试确认通过**

Run:

```powershell
.\mvnw.cmd "-Dtest=DevAgentPropertiesBindingTest" test
```

Expected: PASS（含新旧用例）。

- [ ] **Step 6: Commit**

```powershell
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/DevAgentProperties.java `
  demo2/src/main/resources/application.properties `
  demo2/src/test/java/com/jason/demo/demo2/agentscope/config/DevAgentPropertiesBindingTest.java
git commit -m "feat(demo2): bind AgentScope model-fallback properties"
```

---

### Task 2: FailoverAgentscopeModel（TDD）

**Files:**
- Create: `demo2/src/test/java/com/jason/demo/demo2/agentscope/model/FailoverAgentscopeModelTest.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/FailoverAgentscopeModel.java`

**Interfaces:**
- Produces:
  - `FailoverAgentscopeModel(Model primary, Model fallbackOrNull, int maxAttempts)`
  - `stream(List<Msg>, List<ToolSchema>, GenerateOptions) -> Flux<ChatResponse>`
  - `getModelName() -> String`（活跃委托；每次 `stream` 开始重置为 primary）
- Consumes: `io.agentscope.core.model.Model`

- [ ] **Step 1: 写失败的单测（完整类）**

```java
package com.jason.demo.demo2.agentscope.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FailoverAgentscopeModelTest {

    @Test
    void primarySuccess_doesNotCallFallback() {
        Model primary = mock(Model.class);
        Model fallback = mock(Model.class);
        when(primary.getModelName()).thenReturn("deepseek-v4-pro");
        when(fallback.getModelName()).thenReturn("kimi-k3");
        when(primary.stream(anyList(), any(), any()))
                .thenReturn(Flux.just(chunk("ok")));

        FailoverAgentscopeModel model = new FailoverAgentscopeModel(primary, fallback, 2);

        StepVerifier.create(model.stream(List.of(), List.of(), null))
                .expectNextCount(1)
                .verifyComplete();

        verify(fallback, never()).stream(anyList(), any(), any());
        assertThat(model.getModelName()).isEqualTo("deepseek-v4-pro");
    }

    @Test
    void primaryFailsBeforeChunk_thenFallbackSucceeds() {
        Model primary = mock(Model.class);
        Model fallback = mock(Model.class);
        when(primary.getModelName()).thenReturn("deepseek-v4-pro");
        when(fallback.getModelName()).thenReturn("kimi-k3");
        when(primary.stream(anyList(), any(), any()))
                .thenReturn(Flux.error(new RuntimeException("down")));
        when(fallback.stream(anyList(), any(), any()))
                .thenReturn(Flux.just(chunk("from-kimi")));

        FailoverAgentscopeModel model = new FailoverAgentscopeModel(primary, fallback, 2);

        StepVerifier.create(model.stream(List.of(), List.of(), null))
                .assertNext(r -> assertThat(r.getContent()).isNotEmpty())
                .verifyComplete();

        verify(primary, times(2)).stream(anyList(), any(), any());
        verify(fallback, times(1)).stream(anyList(), any(), any());
        assertThat(model.getModelName()).isEqualTo("kimi-k3");
    }

    @Test
    void midStreamFailure_doesNotSwitchToFallback() {
        Model primary = mock(Model.class);
        Model fallback = mock(Model.class);
        when(primary.getModelName()).thenReturn("deepseek-v4-pro");
        when(fallback.getModelName()).thenReturn("kimi-k3");
        when(primary.stream(anyList(), any(), any()))
                .thenReturn(Flux.concat(
                        Flux.just(chunk("partial")),
                        Flux.error(new RuntimeException("cut"))));

        FailoverAgentscopeModel model = new FailoverAgentscopeModel(primary, fallback, 2);

        StepVerifier.create(model.stream(List.of(), List.of(), null))
                .expectNextCount(1)
                .verifyErrorMessage("cut");

        verify(fallback, never()).stream(anyList(), any(), any());
    }

    @Test
    void nullFallback_retriesPrimaryOnlyThenFails() {
        Model primary = mock(Model.class);
        when(primary.getModelName()).thenReturn("deepseek-v4-pro");
        AtomicInteger calls = new AtomicInteger();
        when(primary.stream(anyList(), any(), any())).thenAnswer(inv -> {
            calls.incrementAndGet();
            return Flux.error(new RuntimeException("still-down"));
        });

        FailoverAgentscopeModel model = new FailoverAgentscopeModel(primary, null, 2);

        StepVerifier.create(model.stream(List.of(), List.of(), null))
                .verifyErrorMessage("still-down");

        assertThat(calls.get()).isEqualTo(2);
    }

    private static ChatResponse chunk(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }
}
```

若 `ChatResponse.builder()` / `TextBlock.builder()` API 与仓库现有用法不一致，以 `LoggingAgentscopeModel` / 现有测试中的构造方式为准，保持语义不变。

- [ ] **Step 2: 跑测试确认失败**

Run:

```powershell
.\mvnw.cmd "-Dtest=FailoverAgentscopeModelTest" test
```

Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现 `FailoverAgentscopeModel`**

```java
package com.jason.demo.demo2.agentscope.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 主模型按 maxAttempts 重试；仍在首 chunk 前失败且存在备用模型时切换。
 * 半路（已发出非 null chunk）失败不切备。
 */
public final class FailoverAgentscopeModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(FailoverAgentscopeModel.class);

    private final Model primary;
    private final Model fallbackOrNull;
    private final int maxAttempts;
    private final AtomicReference<Model> active;

    public FailoverAgentscopeModel(Model primary, Model fallbackOrNull, int maxAttempts) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.fallbackOrNull = fallbackOrNull;
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
        this.active = new AtomicReference<>(primary);
    }

    @Override
    public String getModelName() {
        Model current = active.get();
        return current == null ? primary.getModelName() : current.getModelName();
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        active.set(primary);
        return tryModel(primary, maxAttempts, true, messages, tools, options);
    }

    private Flux<ChatResponse> tryModel(
            Model model,
            int remaining,
            boolean canSwitchToFallback,
            List<Msg> messages,
            List<ToolSchema> tools,
            GenerateOptions options) {
        return Flux.defer(() -> {
            AtomicBoolean emitted = new AtomicBoolean(false);
            int attemptIndex = maxAttempts - remaining + 1;
            return model.stream(messages, tools, options)
                    .doOnNext(chunk -> {
                        if (chunk != null) {
                            emitted.set(true);
                        }
                    })
                    .onErrorResume(error -> {
                        if (emitted.get()) {
                            return Flux.error(error);
                        }
                        String modelName = safeName(model);
                        if (remaining > 1) {
                            log.info(
                                    "Model {} attempt {}/{} failed: {}",
                                    modelName,
                                    attemptIndex,
                                    maxAttempts,
                                    error.getClass().getSimpleName());
                            return tryModel(
                                    model,
                                    remaining - 1,
                                    canSwitchToFallback,
                                    messages,
                                    tools,
                                    options);
                        }
                        if (canSwitchToFallback && fallbackOrNull != null) {
                            log.info(
                                    "Primary model {} failed, switching to fallback {}",
                                    modelName,
                                    safeName(fallbackOrNull));
                            active.set(fallbackOrNull);
                            return tryModel(
                                    fallbackOrNull,
                                    maxAttempts,
                                    false,
                                    messages,
                                    tools,
                                    options);
                        }
                        log.warn(
                                "Model {} exhausted after {} attempts: {}",
                                modelName,
                                maxAttempts,
                                error.getClass().getSimpleName());
                        return Flux.error(error);
                    });
        });
    }

    private static String safeName(Model model) {
        try {
            String name = model.getModelName();
            return name == null || name.isBlank() ? "-" : name;
        } catch (RuntimeException ex) {
            return "-";
        }
    }
}
```

日志文案要求（实现时统一，避免测试脆弱依赖）：

- 同模型还有剩余次数：`Model {name} attempt {i}/{n} failed: {errorType}`（primary/fallback 通用）
- 切备：`Primary model {primary} failed, switching to fallback {fallback}`
- 耗尽：`Model {name} exhausted after {n} attempts: {errorType}`

- [ ] **Step 4: 跑单测确认通过**

Run:

```powershell
.\mvnw.cmd "-Dtest=FailoverAgentscopeModelTest" test
```

Expected: PASS。若 `ChatResponse.builder()` 签名不同，先用 `javap` 或现有代码对齐构造，再重跑。

- [ ] **Step 5: Commit**

```powershell
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/model/FailoverAgentscopeModel.java `
  demo2/src/test/java/com/jason/demo/demo2/agentscope/model/FailoverAgentscopeModelTest.java
git commit -m "feat(demo2): add FailoverAgentscopeModel for DeepSeek to Kimi"
```

---

### Task 3: AgentScopeConfig 装配 OkHttp + Failover

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`（`agentscopeDeepSeekModel` 方法及新增 transport bean）

**Interfaces:**
- Consumes: `DevAgentProperties.model()` / `modelFallback()`；`FailoverAgentscopeModel`；`OkHttpTransport`
- Produces: 仍为 `@Qualifier("agentscopeDeepSeekModel") Model`（Logging 包 Failover）

- [ ] **Step 1: 增加共享 `HttpTransport` bean**

在 `AgentScopeConfig` 中新增（import：`io.agentscope.core.model.transport.HttpTransport`、`OkHttpTransport`）：

```java
@Bean(destroyMethod = "close")
HttpTransport agentscopeModelHttpTransport() {
    return new OkHttpTransport();
}
```

- [ ] **Step 2: 重写 `agentscopeDeepSeekModel`**

替换现有方法体为：

```java
@Bean
@Qualifier("agentscopeDeepSeekModel")
Model agentscopeDeepSeekModel(
        DevAgentProperties properties,
        HttpTransport agentscopeModelHttpTransport) {
    DevAgentProperties.Model primaryCfg = properties.model();
    Model primary = OpenAIChatModel.builder()
            .apiKey(primaryCfg.apiKey() == null ? "" : primaryCfg.apiKey())
            .baseUrl(primaryCfg.baseUrl())
            .modelName(primaryCfg.name())
            .formatter(new DeepSeekFormatter())
            .httpTransport(agentscopeModelHttpTransport)
            .stream(true)
            .build();

    DevAgentProperties.ModelFallback fallbackCfg = properties.modelFallback();
    Model fallback = null;
    String fallbackKey = fallbackCfg.fallback().apiKey();
    if (fallbackKey != null && !fallbackKey.isBlank()) {
        DevAgentProperties.Model f = fallbackCfg.fallback();
        fallback = OpenAIChatModel.builder()
                .apiKey(f.apiKey())
                .baseUrl(f.baseUrl())
                .modelName(f.name())
                .httpTransport(agentscopeModelHttpTransport)
                .stream(true)
                .build();
    } else {
        log.warn(
                "AgentScope model fallback disabled: set KIMI_API_KEY or MOONSHOT_API_KEY to enable");
    }

    Model failover = new FailoverAgentscopeModel(primary, fallback, fallbackCfg.maxAttempts());
    return new LoggingAgentscopeModel(failover, "agentscope-deepseek");
}
```

若 `AgentScopeConfig` 尚无 `Logger`，增加：

```java
private static final Logger log = LoggerFactory.getLogger(AgentScopeConfig.class);
```

**禁止**：在 `HarnessAgent.builder()` 上增加 `.fallbackModel(...)` 或为本特性改 `.maxRetries(...)`。

- [ ] **Step 3: 编译验证**

Run:

```powershell
.\mvnw.cmd -DskipTests compile
```

Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```powershell
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java
git commit -m "feat(demo2): wire OkHttpTransport and FailoverAgentscopeModel"
```

---

### Task 4: Middleware configuredModel / actualModel

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/middleware/AgentExecutionLoggingMiddleware.java`（`onModelCall`）
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/middleware/AgentExecutionLoggingMiddlewareTest.java`

**Interfaces:**
- Consumes: `ModelCallInput.model().getModelName()`（开始与结束各读一次）
- Produces: 日志字段 `configuredModel`、`actualModel`

- [ ] **Step 1: 写/改失败的 middleware 测试**

将现有 `onModelCall_logsUnknownUsageAsDash` 的断言从 `model=` 改为同时兼容或改为：

```java
@Test
void onModelCall_logsConfiguredAndActualModel() {
    Model model = mock(Model.class);
    when(model.getModelName())
            .thenReturn("deepseek-v4-pro")
            .thenReturn("kimi-k3");
    ModelCallInput input = new ModelCallInput(List.of(), List.of(), null, model);

    middleware.onModelCall(agent, runtime(), input, ignored -> Flux.empty()).blockLast();

    assertThat(logs())
            .contains("configuredModel=deepseek-v4-pro")
            .contains("actualModel=kimi-k3");
}
```

并更新其它依赖旧字段 `model=` 的 `onModelCall_*` 用例：started 用 `configuredModel=`，completed 用 `actualModel=`（同名模型两次返回相同即可）。

- [ ] **Step 2: 跑测试确认失败**

Run:

```powershell
.\mvnw.cmd "-Dtest=AgentExecutionLoggingMiddlewareTest#onModelCall_logsConfiguredAndActualModel" test
```

Expected: FAIL（尚无新字段）。

- [ ] **Step 3: 改 `onModelCall`**

核心逻辑：

```java
@Override
public Flux<AgentEvent> onModelCall(
        Agent agent,
        RuntimeContext context,
        ModelCallInput input,
        Function<ModelCallInput, Flux<AgentEvent>> next) {
    return Flux.defer(() -> {
        AgentExecutionContext ids = AgentExecutionContext.from(context);
        long startedAt = System.nanoTime();
        AtomicReference<ChatUsage> completedUsage = new AtomicReference<>();
        String configuredModel = modelName(input);
        log.info(
                "Model call started. requestId={}, traceId={}, spanId={}, configuredModel={}",
                ids.requestId(),
                ids.traceId(),
                ids.spanId(),
                configuredModel);
        return Flux.defer(() -> next.apply(input))
                .doOnNext(event -> {
                    if (event instanceof ModelCallEndEvent endEvent) {
                        completedUsage.set(endEvent.getUsage());
                    }
                })
                .doOnComplete(() -> {
                    ChatUsage usage = completedUsage.get();
                    String actualModel = modelName(input);
                    log.info(
                            "Model call completed. requestId={}, traceId={}, spanId={}, "
                                    + "configuredModel={}, actualModel={}, durationMs={}, "
                                    + "inputTokens={}, outputTokens={}, state=SUCCESS",
                            ids.requestId(),
                            ids.traceId(),
                            ids.spanId(),
                            configuredModel,
                            actualModel,
                            elapsedMillis(startedAt),
                            inputTokens(usage),
                            outputTokens(usage));
                    if (!Objects.equals(configuredModel, actualModel)) {
                        log.info(
                                "Model fallback observed. requestId={}, configuredModel={}, "
                                        + "actualModel={}",
                                ids.requestId(),
                                configuredModel,
                                actualModel);
                    }
                })
                .doOnError(error -> {
                    String actualModel = modelName(input);
                    log.warn(
                            "Model call failed. requestId={}, traceId={}, spanId={}, "
                                    + "configuredModel={}, actualModel={}, durationMs={}, "
                                    + "errorType={}, state=ERROR",
                            ids.requestId(),
                            ids.traceId(),
                            ids.spanId(),
                            configuredModel,
                            actualModel,
                            elapsedMillis(startedAt),
                            error.getClass().getSimpleName());
                })
                .doOnCancel(() -> log.warn(
                        "Model call cancelled. requestId={}, traceId={}, spanId={}, "
                                + "configuredModel={}, durationMs={}, state=CANCELLED",
                        ids.requestId(),
                        ids.traceId(),
                        ids.spanId(),
                        configuredModel,
                        elapsedMillis(startedAt)));
    });
}
```

增加 `import java.util.Objects;`。

- [ ] **Step 4: 跑 middleware 全量测试**

Run:

```powershell
.\mvnw.cmd "-Dtest=AgentExecutionLoggingMiddlewareTest" test
```

Expected: PASS。

- [ ] **Step 5: Commit**

```powershell
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/middleware/AgentExecutionLoggingMiddleware.java `
  demo2/src/test/java/com/jason/demo/demo2/agentscope/middleware/AgentExecutionLoggingMiddlewareTest.java
git commit -m "feat(demo2): log configuredModel vs actualModel on model calls"
```

---

### Task 5: README + 回归门禁

**Files:**
- Modify: `demo2/README.md`（AgentScope Harness 相关小节，约 `### AgentScope HarnessAgent`）

- [ ] **Step 1: 补充简短说明**

在 Harness 配置/能力列表中增加要点（中文，保持现有风格）：

- 多模型容错：`FailoverAgentscopeModel`，DeepSeek → Kimi（`KIMI_API_KEY` / `MOONSHOT_API_KEY`，默认 `kimi-k3`）
- `max-attempts` 含首次；Memory Flush 等同 bean 受益
- 手工验证：`DEEPSEEK_BASE_URL=http://127.0.0.1:65535` 后调 `/dev-agent/ask`，日志应出现 `switching to fallback` 与 `actualModel=kimi-k3`

- [ ] **Step 2: 跑相关单测门禁**

Run:

```powershell
.\mvnw.cmd "-Dtest=DevAgentPropertiesBindingTest,FailoverAgentscopeModelTest,AgentExecutionLoggingMiddlewareTest" test
```

Expected: BUILD SUCCESS，tests PASS。

- [ ] **Step 3: Commit**

```powershell
git add demo2/README.md
git commit -m "docs(demo2): document AgentScope DeepSeek to Kimi failover"
```

---

## Spec Coverage Checklist

| Spec 要求 | Task |
|-----------|------|
| `model-fallback` 配置 + 默认 kimi-k3 | Task 1 |
| `FailoverAgentscopeModel` 重试/切备/半路不切 | Task 2 |
| `OkHttpTransport` + 双 `OpenAIChatModel` + Logging 外包 | Task 3 |
| 缺 Kimi key 时 fallback 禁用 + warn | Task 3 |
| 不启用 `HarnessAgent.fallbackModel` | Task 3 约束 |
| bean 名不变 | Task 3 |
| middleware configured/actual | Task 4 |
| 单元测试表 | Task 1/2/4 |
| README / 手工验证说明 | Task 5 |

## Out of Scope（计划不实现）

- Spring AI / Embabel failover
- 多级备用链
- HTTP 状态过滤（仅 429/5xx 切备）
- 半路断流改用备用模型拼接
