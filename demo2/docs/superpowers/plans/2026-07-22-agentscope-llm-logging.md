# AgentScope LLM 请求/响应日志 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 AgentScope `Model` 增加与 `LoggingChatModel` 对齐的 DEBUG 请求/响应日志（请求打一次，流式只打聚合完整响应）。

**Architecture:** 新增 `LoggingAgentscopeModel` 装饰 `io.agentscope.core.model.Model`；在 `AgentScopeConfig.agentscopeDeepSeekModel` 中包装 `OpenAIChatModel`；用 `logging.level...LoggingAgentscopeModel=DEBUG` 开关；`GenerateOptions.apiKey` 与敏感 Header 永不入日志。

**Tech Stack:** Java 21, Spring Boot 4.x, AgentScope Java 2.0.0, Reactor `Flux`, SLF4J / Logback, JUnit 5, AssertJ, Mockito, reactor-test

**设计规范:** [docs/superpowers/specs/2026-07-22-agentscope-llm-logging-design.md](../specs/2026-07-22-agentscope-llm-logging-design.md)

## Global Constraints

- **AgentScope 版本**：`2.0.0`
- **装饰接口**：`io.agentscope.core.model.Model#stream(List<Msg>, List<ToolSchema>, GenerateOptions)`
- **日志级别**：仅 DEBUG 打 request/response；错误用 WARN；关闭 DEBUG 时不做序列化/聚合字符串
- **流式**：透传 chunk；**不**逐 chunk 打 DEBUG；仅 `onComplete` 打聚合响应
- **脱敏**：`apiKey` 永不入日志；`Authorization` / `api-key` 等 Header 打 `***`
- **不修改**：SSE / HITL / 前端 / `enableAgentTracingLog`
- **编译门禁**：`mvn -f demo2/pom.xml -DskipTests compile` 必须 SUCCESS
- **单测门禁**：`mvn -f demo2/pom.xml -Dtest=LoggingAgentscopeModelTest test` 必须 SUCCESS

---

## File Structure

| 文件 | 职责 |
|------|------|
| `demo2/src/main/java/com/jason/demo/demo2/config/LoggingAgentscopeModel.java` | Model 装饰器：请求摘要、流式聚合响应、脱敏 |
| `demo2/src/test/java/com/jason/demo/demo2/config/LoggingAgentscopeModelTest.java` | 单元测试（Logback ListAppender + mock Model） |
| `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java` | Bean 外包装饰器 |
| `demo2/src/main/java/com/jason/demo/demo2/config/LoggingConfig.java` | 注释补第三种入口 |
| `demo2/src/main/resources/application.properties` | `LoggingAgentscopeModel=DEBUG` |

**已确认 API（AgentScope 2.0.0 jar）：**

- `Model.stream(List<Msg>, List<ToolSchema>, GenerateOptions) → Flux<ChatResponse>`
- `ChatResponse.builder().content(...).finishReason(...).usage(...).build()`
- `Msg.builder().role(MsgRole).textContent(...).build()` / `content(ContentBlock...)`
- `TextBlock.builder().text(...).build()`；`ToolUseBlock.builder().id(...).name(...).input(...).build()`
- `ToolSchema.builder().name(...).description(...).parameters(...).build()`
- `GenerateOptions.builder().apiKey(...).temperature(...).additionalHeader(...).build()`
- `ChatUsage` 构造：`(inputTokens, outputTokens, time)` 或带 `cachedTokens`

---

### Task 1: LoggingAgentscopeModel + 单元测试

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/config/LoggingAgentscopeModel.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/config/LoggingAgentscopeModelTest.java`

**Interfaces:**
- Produces:
  ```java
  public final class LoggingAgentscopeModel implements Model {
      public LoggingAgentscopeModel(Model delegate, String label);
      public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options);
      public String getModelName();
  }
  ```
- Consumes: AgentScope `Model` / `Msg` / `ToolSchema` / `GenerateOptions` / `ChatResponse`

- [ ] **Step 1: 写失败测试**

创建 `LoggingAgentscopeModelTest.java`：

```java
package com.jason.demo.demo2.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoggingAgentscopeModelTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;
    private Level previousLevel;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(LoggingAgentscopeModel.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    @Test
    void stream_logsRequestWithoutApiKey_andAggregatedResponse() {
        Model delegate = mock(Model.class);
        when(delegate.getModelName()).thenReturn("deepseek-v4-pro");
        when(delegate.stream(anyList(), anyList(), any())).thenReturn(Flux.just(
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("Hel").build()))
                        .build(),
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("lo").build()))
                        .finishReason("stop")
                        .usage(new ChatUsage(10, 2, 0.1))
                        .build()));

        LoggingAgentscopeModel model = new LoggingAgentscopeModel(delegate, "agentscope-deepseek");

        Msg user = Msg.builder().role(MsgRole.USER).textContent("hi").build();
        ToolSchema tool = ToolSchema.builder()
                .name("read_pom")
                .description("read pom")
                .parameters(Map.of("type", "object"))
                .build();
        GenerateOptions options = GenerateOptions.builder()
                .apiKey("sk-secret")
                .temperature(0.2)
                .stream(true)
                .additionalHeader("Authorization", "Bearer sk-secret")
                .build();

        StepVerifier.create(model.stream(List.of(user), List.of(tool), options))
                .expectNextCount(2)
                .verifyComplete();

        String joined = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(joined).contains("LLM request [agentscope-deepseek]");
        assertThat(joined).contains("USER");
        assertThat(joined).contains("hi");
        assertThat(joined).contains("read_pom");
        assertThat(joined).doesNotContain("sk-secret");
        assertThat(joined).contains("***");
        assertThat(joined).contains("LLM response [agentscope-deepseek]");
        assertThat(joined).contains("Hello");
        assertThat(joined).contains("stop");
    }

    @Test
    void stream_onError_warnsAndDoesNotLogCompleteResponse() {
        Model delegate = mock(Model.class);
        when(delegate.getModelName()).thenReturn("deepseek-v4-pro");
        when(delegate.stream(anyList(), anyList(), any()))
                .thenReturn(Flux.error(new RuntimeException("boom")));

        LoggingAgentscopeModel model = new LoggingAgentscopeModel(delegate, "agentscope-deepseek");

        StepVerifier.create(model.stream(List.of(), List.of(), GenerateOptions.builder().build()))
                .expectErrorMessage("boom")
                .verify();

        String joined = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(joined).contains("LLM request [agentscope-deepseek]");
        assertThat(joined).doesNotContain("LLM response [agentscope-deepseek]");
        assertThat(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN)).isTrue();
    }

    @Test
    void stream_passthrough_preservesChunks() {
        Model delegate = mock(Model.class);
        when(delegate.getModelName()).thenReturn("m");
        ChatResponse chunk = ChatResponse.builder()
                .content(List.of(TextBlock.builder().text("x").build()))
                .build();
        when(delegate.stream(anyList(), anyList(), any())).thenReturn(Flux.just(chunk));

        AtomicReference<ChatResponse> seen = new AtomicReference<>();
        new LoggingAgentscopeModel(delegate, "t")
                .stream(List.of(), List.of(), null)
                .doOnNext(seen::set)
                .blockLast();

        assertThat(seen.get()).isSameAs(chunk);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=LoggingAgentscopeModelTest test
```

Expected: FAIL（`LoggingAgentscopeModel` 类不存在 / 编译失败）

- [ ] **Step 3: 实现 `LoggingAgentscopeModel`**

创建 `LoggingAgentscopeModel.java`：

```java
package com.jason.demo.demo2.config;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 在 AgentScope {@link Model} 层打印 request / 聚合 response，
 * 对齐 {@link LoggingChatModel}（覆盖不经 Spring AI Advisor 的 AgentScope 调用）。
 */
public final class LoggingAgentscopeModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(LoggingAgentscopeModel.class);

    private final Model delegate;
    private final String label;

    public LoggingAgentscopeModel(Model delegate, String label) {
        this.delegate = delegate;
        this.label = label == null || label.isBlank() ? "agentscope-model" : label;
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        if (!log.isDebugEnabled()) {
            return delegate.stream(messages, tools, options);
        }

        log.debug("LLM request [{}]: modelName={}, messages={}, tools={}, options={}",
                label,
                safe(delegate.getModelName()),
                summarizeMessages(messages),
                summarizeTools(tools),
                summarizeOptions(options));

        StringBuilder textAgg = new StringBuilder();
        List<String> otherBlocks = new ArrayList<>();
        AtomicReference<String> finishReason = new AtomicReference<>();
        AtomicReference<ChatUsage> usage = new AtomicReference<>();

        return delegate.stream(messages, tools, options)
                .doOnNext(chunk -> {
                    if (chunk == null) {
                        return;
                    }
                    if (chunk.getFinishReason() != null && !chunk.getFinishReason().isBlank()) {
                        finishReason.set(chunk.getFinishReason());
                    }
                    if (chunk.getUsage() != null) {
                        usage.set(chunk.getUsage());
                    }
                    List<ContentBlock> content = chunk.getContent();
                    if (content == null) {
                        return;
                    }
                    for (ContentBlock block : content) {
                        if (block instanceof TextBlock textBlock) {
                            String t = textBlock.getText();
                            if (t != null) {
                                textAgg.append(t);
                            }
                        } else if (block != null) {
                            otherBlocks.add(summarizeBlock(block));
                        }
                    }
                })
                .doOnComplete(() -> log.debug(
                        "LLM response [{}]: content={}, finishReason={}, usage={}",
                        label,
                        buildAggregatedContent(textAgg, otherBlocks),
                        finishReason.get(),
                        summarizeUsage(usage.get())))
                .doOnError(err -> log.warn(
                        "LLM stream error [{}]: {}: {}",
                        label,
                        err.getClass().getSimpleName(),
                        err.getMessage()));
    }

    private static String buildAggregatedContent(StringBuilder textAgg, List<String> otherBlocks) {
        StringBuilder out = new StringBuilder();
        if (!textAgg.isEmpty()) {
            out.append("text=").append(textAgg);
        }
        if (!otherBlocks.isEmpty()) {
            if (!out.isEmpty()) {
                out.append("; ");
            }
            out.append("blocks=").append(otherBlocks);
        }
        return out.isEmpty() ? "[]" : out.toString();
    }

    private static String summarizeMessages(List<Msg> messages) {
        if (messages == null) {
            return "null";
        }
        List<String> parts = new ArrayList<>(messages.size());
        for (Msg msg : messages) {
            if (msg == null) {
                parts.add("null");
                continue;
            }
            parts.add("{role=" + msg.getRole() + ", content=" + summarizeContent(msg.getContent()) + "}");
        }
        return parts.toString();
    }

    private static String summarizeContent(List<ContentBlock> content) {
        if (content == null) {
            return "null";
        }
        List<String> parts = new ArrayList<>(content.size());
        for (ContentBlock block : content) {
            parts.add(summarizeBlock(block));
        }
        return parts.toString();
    }

    private static String summarizeBlock(ContentBlock block) {
        if (block == null) {
            return "null";
        }
        if (block instanceof TextBlock textBlock) {
            return "text(" + textBlock.getText() + ")";
        }
        if (block instanceof ToolUseBlock toolUse) {
            return "tool_use(id=" + toolUse.getId()
                    + ", name=" + toolUse.getName()
                    + ", input=" + toolUse.getInput() + ")";
        }
        if (block instanceof ToolResultBlock toolResult) {
            return "tool_result(id=" + toolResult.getId()
                    + ", name=" + toolResult.getName()
                    + ", output=" + summarizeContent(toolResult.getOutput()) + ")";
        }
        return block.getClass().getSimpleName();
    }

    private static String summarizeTools(List<ToolSchema> tools) {
        if (tools == null) {
            return "null";
        }
        List<String> parts = new ArrayList<>(tools.size());
        for (ToolSchema tool : tools) {
            if (tool == null) {
                parts.add("null");
                continue;
            }
            parts.add("{name=" + tool.getName()
                    + ", description=" + tool.getDescription()
                    + ", parameters=" + tool.getParameters() + "}");
        }
        return parts.toString();
    }

    private static String summarizeOptions(GenerateOptions options) {
        if (options == null) {
            return "null";
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("modelName", options.getModelName());
        safe.put("stream", options.getStream());
        safe.put("temperature", options.getTemperature());
        safe.put("topP", options.getTopP());
        safe.put("maxTokens", options.getMaxTokens());
        safe.put("maxCompletionTokens", options.getMaxCompletionTokens());
        safe.put("toolChoice", options.getToolChoice());
        safe.put("parallelToolCalls", options.getParallelToolCalls());
        safe.put("thinkingBudget", options.getThinkingBudget());
        safe.put("reasoningEffort", options.getReasoningEffort());
        safe.put("additionalHeaders", redactHeaders(options.getAdditionalHeaders()));
        // 故意不放入 apiKey / baseUrl query secrets
        return safe.toString();
    }

    private static Map<String, String> redactHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return headers;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey();
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.contains("authorization")
                    || lower.contains("api-key")
                    || lower.contains("apikey")
                    || lower.contains("token")) {
                out.put(e.getKey(), "***");
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static String summarizeUsage(ChatUsage usage) {
        if (usage == null) {
            return "null";
        }
        return "{input=" + usage.getInputTokens()
                + ", output=" + usage.getOutputTokens()
                + ", total=" + usage.getTotalTokens() + "}";
    }

    private static String safe(String value) {
        return value == null ? "null" : value;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=LoggingAgentscopeModelTest test
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`

若 `Hello` 断言失败：检查聚合是否把相邻 `TextBlock` 拼成 `Hello`；必要时调整 `buildAggregatedContent` 的日志字符串，但测试应仍能匹配拼接后的全文。

若 Mockito `anyList()` 与 `null` options 冲突：将 passthrough 测试改为 `GenerateOptions.builder().build()`。

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/config/LoggingAgentscopeModel.java \
        demo2/src/test/java/com/jason/demo/demo2/config/LoggingAgentscopeModelTest.java
git commit -m "feat(demo2): add LoggingAgentscopeModel for AgentScope LLM debug logs"
```

---

### Task 2: 接线 AgentScopeConfig + LoggingConfig + properties

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/config/LoggingConfig.java`
- Modify: `demo2/src/main/resources/application.properties`（LLM 日志配置段，约 201–210 行附近）

**Interfaces:**
- Consumes: `LoggingAgentscopeModel(Model, String)`
- Produces: `@Qualifier("agentscopeDeepSeekModel") Model` 实际类型为装饰后的 `LoggingAgentscopeModel`

- [ ] **Step 1: 包装 Model Bean**

在 `AgentScopeConfig.java` 增加 import：

```java
import com.jason.demo.demo2.config.LoggingAgentscopeModel;
```

将 `agentscopeDeepSeekModel` 方法体改为：

```java
@Bean
@Qualifier("agentscopeDeepSeekModel")
Model agentscopeDeepSeekModel(DevAgentProperties properties) {
    DevAgentProperties.Model model = properties.model();
    Model openAi = OpenAIChatModel.builder()
            .apiKey(model.apiKey() == null ? "" : model.apiKey())
            .baseUrl(model.baseUrl())
            .modelName(model.name())
            .formatter(new DeepSeekFormatter())
            .stream(true)
            .build();
    return new LoggingAgentscopeModel(openAi, "agentscope-deepseek");
}
```

- [ ] **Step 2: 更新 `LoggingConfig` 注释**

替换类级 Javadoc 为：

```java
/**
 * LLM 请求响应日志：
 * <ul>
 *   <li>业务 ChatClient：SimpleLoggerAdvisor（经 ChatClientBuilderCustomizer）</li>
 *   <li>Embabel：LoggingChatModel 包装 ChatModel（见 EmbabelLlmModelFixConfig）</li>
 *   <li>AgentScope：LoggingAgentscopeModel 包装 Model（见 AgentScopeConfig）</li>
 * </ul>
 * 说明：Spring AI 2.0 已废弃 {@code ChatClientCustomizer}（forRemoval），
 * 且 Embabel 主路径经 {@code SpringAiLlmMessageSender} 直接 {@code ChatModel.call()}，
 * 不会进入 ChatClient Advisor 链，故 Embabel 不能依赖 Advisor 打日志。
 * AgentScope 使用自有 {@code io.agentscope.core.model.Model}，同样不经 Advisor。
 */
```

- [ ] **Step 3: 增加 logging.level**

在 `application.properties` 的 LLM 日志段（`LoggingChatModel=DEBUG` 附近）追加：

```properties
# AgentScope LLM 请求/响应（Model 装饰器；流式只打聚合响应）
logging.level.com.jason.demo.demo2.config.LoggingAgentscopeModel=DEBUG
```

并更新该段顶部注释，使三入口并列可见，例如：

```properties
# LLM 请求响应日志配置
# 业务 ChatClient：SimpleLoggerAdvisor；Embabel：LoggingChatModel；AgentScope：LoggingAgentscopeModel
```

- [ ] **Step 4: 编译 + 单测回归**

Run:

```bash
mvn -f demo2/pom.xml -DskipTests compile
mvn -f demo2/pom.xml -Dtest=LoggingAgentscopeModelTest test
```

Expected: 两处均 SUCCESS。

- [ ] **Step 5: 手动冒烟（可选，有 Key 时）**

1. 启动 `demo2`（端口 8081）
2. 调用 AgentScope Tab 或 `POST /agentscope/dev-agent/ask`
3. 日志中应出现：
   - `LLM request [agentscope-deepseek]: ...`
   - `LLM response [agentscope-deepseek]: ...`（一条聚合，非逐 token）
4. 确认日志中**没有**真实 `apiKey`

- [ ] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java \
        demo2/src/main/java/com/jason/demo/demo2/config/LoggingConfig.java \
        demo2/src/main/resources/application.properties
git commit -m "feat(demo2): wire LoggingAgentscopeModel into AgentScope config"
```

---

## Spec Coverage Checklist

| Spec 要求 | Task |
|-----------|------|
| `LoggingAgentscopeModel` 装饰 `Model` | Task 1 |
| 请求打 messages / tools / 安全 options | Task 1 |
| 流式只打聚合响应 + usage / finishReason | Task 1 |
| apiKey / 敏感 Header 脱敏 | Task 1 |
| DEBUG 关闭零序列化开销 | Task 1 |
| 流 error → WARN，不打完整响应 | Task 1 |
| `AgentScopeConfig` 接线 | Task 2 |
| `LoggingConfig` 注释第三入口 | Task 2 |
| `application.properties` DEBUG 开关 | Task 2 |
| 单元测试 | Task 1 |
| 非目标：tracing / Hook / HTTP 原始 / SSE 改动 | 全计划未触及 |

---

## Self-Review Notes

- 无 TBD /「类似 Task N」占位
- 类型名与 AgentScope 2.0.0 API 一致：`Model` / `ChatResponse` / `GenerateOptions` / `ToolSchema` / `Msg`
- label 固定 `agentscope-deepseek`，与测试断言一致
- 聚合策略：相邻 `TextBlock` 文本拼接；非文本 block 追加摘要列表（满足「不打 chunk、打完整响应」）
