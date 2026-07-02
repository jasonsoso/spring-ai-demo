# Tool Reasoning Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 demo2 新增「🧠 工具推理捕获」Tab：通过 `AugmentedToolCallbackProvider` 捕获 LLM 工具选择时的 `innerThought` + `confidence`，以 SSE 对话式 UI 实时展示，支持多轮 `ChatMemory`，与现有 `agent-tools` Tab 形成对比。

**Architecture:** `ToolReasoningAgentConfig` 用 `AugmentedToolCallbackProvider` 包装 `WeatherTool`/`AttractionTool`；`argumentConsumer` 经 `ToolReasoningSseBridge` 推送 `TOOL_REASONING` SSE；`ToolReasoningAgentService.streamChat` 对齐 `SessionMemoryTripAgentService`（`POST /chat/stream` + `TOKEN` 流式）；前端对齐 `agent-session-memory.js`（`fetch` + `ReadableStream`）。

**Tech Stack:** Java 21, Spring Boot 4.1, Spring AI 2.0.0, DeepSeek `deepseek-chat`, 原生 HTML/CSS/JS

**设计规范:** [docs/superpowers/specs/2026-07-02-tool-reasoning-design.md](../specs/2026-07-02-tool-reasoning-design.md)

## Global Constraints

- **不修改** `WeatherTool.java`、`AttractionTool.java`、`ToolTripAgentService.java`
- **模型**：`agent.tool-reasoning.chat.model=deepseek-chat`
- **记忆**：复用 `@Primary` `ChatMemory`（`MemoryConfig`，窗口 20 条）；`ChatMemory.CONVERSATION_ID = sessionId`
- **sessionId 校验**：`^[a-zA-Z0-9_-]+$`，非法 `400`
- **API 前缀**：`/agent/tool-reasoning`；SSE 超时 **5 分钟**
- **增强器**：`removeExtraArgumentsAfterProcessing(true)`，原工具方法签名不变
- **SSE 事件类型**：`RUNNING` / `TOOL_REASONING` / `TOKEN` / `COMPLETED` / `FAILED`（独立 DTO `ToolReasoningSseEvent`，不扩展 `AgentSseEvent`）
- **前端**：零构建、聊天气泡 + 推理侧栏、`fetch` 读 POST SSE 流（非 EventSource）
- **编译门禁**：`mvn -pl demo2 -DskipTests compile` 通过；单元测试 `mvn -pl demo2 test` 通过

---

## File Structure

| 文件 | 职责 |
|------|------|
| `model/AgentThinking.java` | 增强参数字段 Record |
| `model/ToolReasoningChatRequest.java` | `{ sessionId, message }` |
| `model/ToolReasoningSseEvent.java` | SSE JSON 工厂方法 |
| `sse/ToolReasoningStreamContext.java` | ThreadLocal 绑定 emitter / jsonMapper / callIndex |
| `sse/ToolReasoningSseBridge.java` | `argumentConsumer` → `TOOL_REASONING` SSE |
| `config/ToolReasoningAgentConfig.java` | `AugmentedToolCallbackProvider` Bean |
| `service/ToolReasoningAgentService.java` | `streamChat`、`clearSession`、`validateSessionId` |
| `controller/ToolReasoningAgentController.java` | `POST /chat/stream`、`DELETE /clear` |
| `test/.../ToolReasoningAgentServiceTest.java` | sessionId 校验、clearSession |
| `test/.../ToolReasoningSseBridgeTest.java` | Bridge 推送 callIndex 递增 |
| `application.properties` | `agent.tool-reasoning.chat.model` |
| `static/css/tabs/tool-reasoning.css` | 对话区 + 推理侧栏 |
| `static/js/tabs/tool-reasoning.js` | 多轮 SSE 对话 |
| `static/index.html` | Tab 按钮、面板、link/script |

---

### Task 1: Model 与 SSE DTO

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/AgentThinking.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/ToolReasoningChatRequest.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/ToolReasoningSseEvent.java`

**Interfaces:**
- Produces: `AgentThinking` Record；`ToolReasoningChatRequest`（`sessionId`, `message`）；`ToolReasoningSseEvent` 静态工厂

- [ ] **Step 1: 创建 `AgentThinking.java`**

```java
package com.jason.demo.demo2.model;

import org.springframework.ai.tool.annotation.ToolParam;

public record AgentThinking(
        @ToolParam(description = "调用此工具前的逐步推理：为何选该工具、期望获得什么、如何影响后续规划", required = true)
        String innerThought,
        @ToolParam(description = "置信度：low / medium / high", required = false)
        String confidence
) {
}
```

- [ ] **Step 2: 创建 `ToolReasoningChatRequest.java`**

```java
package com.jason.demo.demo2.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "工具推理捕获对话请求")
public class ToolReasoningChatRequest {

    @Schema(description = "会话 ID（多轮记忆键）", example = "demo-session-001")
    private String sessionId;

    @Schema(description = "用户消息", example = "帮我规划北京周末游，先看天气再推荐人文景点")
    private String message;
}
```

- [ ] **Step 3: 创建 `ToolReasoningSseEvent.java`**

```java
package com.jason.demo.demo2.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolReasoningSseEvent {

    private String type;
    private String content;
    private String error;
    private String toolName;
    private String innerThought;
    private String confidence;
    private Integer callIndex;

    public static ToolReasoningSseEvent running() {
        return new ToolReasoningSseEvent("RUNNING", null, null, null, null, null, null);
    }

    public static ToolReasoningSseEvent toolReasoning(
            String toolName, String innerThought, String confidence, int callIndex) {
        return new ToolReasoningSseEvent(
                "TOOL_REASONING", null, null, toolName, innerThought, confidence, callIndex);
    }

    public static ToolReasoningSseEvent token(String content) {
        return new ToolReasoningSseEvent("TOKEN", content, null, null, null, null, null);
    }

    public static ToolReasoningSseEvent completed() {
        return new ToolReasoningSseEvent("COMPLETED", null, null, null, null, null, null);
    }

    public static ToolReasoningSseEvent failed(String error) {
        return new ToolReasoningSseEvent("FAILED", null, error, null, null, null, null);
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `cd demo2 && mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/model/AgentThinking.java \
        demo2/src/main/java/com/jason/demo/demo2/model/ToolReasoningChatRequest.java \
        demo2/src/main/java/com/jason/demo/demo2/model/ToolReasoningSseEvent.java
git commit -m "feat(demo2): add tool reasoning model and SSE event DTOs"
```

---

### Task 2: SSE Stream Context 与 Bridge

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/sse/ToolReasoningStreamContext.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/sse/ToolReasoningSseBridge.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/sse/ToolReasoningSseBridgeTest.java`

**Interfaces:**
- Consumes: `ToolReasoningSseEvent`, `AgentThinking`
- Produces: `ToolReasoningStreamContext.set/clear/get`；`ToolReasoningSseBridge.onToolReasoning(String toolName, AgentThinking thinking)`

- [ ] **Step 1: 写失败测试 `ToolReasoningSseBridgeTest`**

```java
package com.jason.demo.demo2.sse;

import com.jason.demo.demo2.model.AgentThinking;
import com.jason.demo.demo2.model.ToolReasoningSseEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolReasoningSseBridgeTest {

  private final JsonMapper jsonMapper = JsonMapper.builder().build();
  private final List<String> sentPayloads = new ArrayList<>();

  @AfterEach
  void tearDown() {
    ToolReasoningStreamContext.clear();
  }

  @Test
  void onToolReasoning_incrementsCallIndexAndSendsEvent() throws Exception {
    SseEmitter emitter = new SseEmitter();
    emitter.onCompletion(() -> {});
    ToolReasoningStreamContext.set(emitter, jsonMapper, new AtomicInteger(0), sentPayloads::add);

    ToolReasoningSseBridge.onToolReasoning("getWeather",
        new AgentThinking("需要北京实时天气", "high"));
    ToolReasoningSseBridge.onToolReasoning("recommendAttractions",
        new AgentThinking("推荐人文景点", "medium"));

    assertEquals(2, sentPayloads.size());
    ToolReasoningSseEvent first = jsonMapper.readValue(sentPayloads.get(0), ToolReasoningSseEvent.class);
    ToolReasoningSseEvent second = jsonMapper.readValue(sentPayloads.get(1), ToolReasoningSseEvent.class);
    assertEquals("TOOL_REASONING", first.getType());
    assertEquals("getWeather", first.getToolName());
    assertEquals(1, first.getCallIndex());
    assertEquals(2, second.getCallIndex());
  }

  @Test
  void onToolReasoning_withoutContext_isNoOp() {
    assertTrue(ToolReasoningStreamContext.get().isEmpty());
    ToolReasoningSseBridge.onToolReasoning("getWeather",
        new AgentThinking("x", "low"));
    assertEquals(0, sentPayloads.size());
  }
}
```

> 注：`ToolReasoningStreamContext` 需支持测试注入 `Consumer<String>` 发送回调；生产代码用真实 `SseEmitter.send`。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd demo2 && mvn -q test -Dtest=ToolReasoningSseBridgeTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 `ToolReasoningStreamContext.java`**

```java
package com.jason.demo.demo2.sse;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class ToolReasoningStreamContext {

    public record Holder(
            SseEmitter emitter,
            JsonMapper jsonMapper,
            AtomicInteger callIndex,
            Consumer<String> sender
    ) {}

    private static final ThreadLocal<Holder> HOLDER = new ThreadLocal<>();

    private ToolReasoningStreamContext() {
    }

    public static void set(SseEmitter emitter, JsonMapper jsonMapper, AtomicInteger callIndex) {
        set(emitter, jsonMapper, callIndex, json -> send(emitter, json));
    }

    static void set(SseEmitter emitter, JsonMapper jsonMapper, AtomicInteger callIndex,
                    Consumer<String> sender) {
        HOLDER.set(new Holder(emitter, jsonMapper, callIndex, sender));
    }

    public static Optional<Holder> get() {
        return Optional.ofNullable(HOLDER.get());
    }

    public static void clear() {
        HOLDER.remove();
    }

    private static void send(SseEmitter emitter, String json) {
        try {
            emitter.send(SseEmitter.event().data(json).build());
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }
}
```

- [ ] **Step 4: 实现 `ToolReasoningSseBridge.java`**

```java
package com.jason.demo.demo2.sse;

import com.jason.demo.demo2.model.AgentThinking;
import com.jason.demo.demo2.model.ToolReasoningSseEvent;

public final class ToolReasoningSseBridge {

    private ToolReasoningSseBridge() {
    }

    public static void onToolReasoning(String toolName, AgentThinking thinking) {
        ToolReasoningStreamContext.get().ifPresent(ctx -> {
            int callIndex = ctx.callIndex().incrementAndGet();
            String innerThought = thinking != null && thinking.innerThought() != null
                    ? thinking.innerThought() : "（模型未提供推理）";
            String confidence = thinking != null ? thinking.confidence() : null;
            ToolReasoningSseEvent event = ToolReasoningSseEvent.toolReasoning(
                    toolName, innerThought, confidence, callIndex);
            try {
                ctx.sender().accept(ctx.jsonMapper().writeValueAsString(event));
            } catch (Exception e) {
                ctx.emitter().completeWithError(e);
            }
        });
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd demo2 && mvn -q test -Dtest=ToolReasoningSseBridgeTest`
Expected: BUILD SUCCESS, 2 tests passed

- [ ] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/sse/ToolReasoningStreamContext.java \
        demo2/src/main/java/com/jason/demo/demo2/sse/ToolReasoningSseBridge.java \
        demo2/src/test/java/com/jason/demo/demo2/sse/ToolReasoningSseBridgeTest.java
git commit -m "feat(demo2): add tool reasoning SSE bridge and stream context"
```

---

### Task 3: Config 与 application.properties

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/config/ToolReasoningAgentConfig.java`
- Modify: `demo2/src/main/resources/application.properties`

**Interfaces:**
- Consumes: `WeatherTool`, `AttractionTool`
- Produces: `@Bean AugmentedToolCallbackProvider<AgentThinking> toolReasoningProvider`；`getToolReasoningChatModel()` 返回 `String`

- [ ] **Step 1: 在 `application.properties` 追加**

```properties
# Tool Reasoning：多轮 ToolCall 使用 deepseek-chat，避免 reasoning_content 回放问题
agent.tool-reasoning.chat.model=deepseek-chat
```

- [ ] **Step 2: 创建 `ToolReasoningAgentConfig.java`**

```java
package com.jason.demo.demo2.config;

import com.jason.demo.demo2.model.AgentThinking;
import com.jason.demo.demo2.sse.ToolReasoningSseBridge;
import com.jason.demo.demo2.tools.AttractionTool;
import com.jason.demo.demo2.tools.WeatherTool;
import org.springframework.ai.tool.augment.AugmentedToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolReasoningAgentConfig {

    @Value("${agent.tool-reasoning.chat.model:deepseek-chat}")
    private String toolReasoningChatModel;

    public String getToolReasoningChatModel() {
        return toolReasoningChatModel;
    }

    @Bean
    public AugmentedToolCallbackProvider<AgentThinking> toolReasoningProvider(
            WeatherTool weatherTool,
            AttractionTool attractionTool) {
        return AugmentedToolCallbackProvider.<AgentThinking>builder()
                .toolObject(weatherTool, attractionTool)
                .argumentType(AgentThinking.class)
                .argumentConsumer(event -> ToolReasoningSseBridge.onToolReasoning(
                        event.toolDefinition().name(), event.arguments()))
                .removeExtraArgumentsAfterProcessing(true)
                .build();
    }
}
```

> 若 `toolObject(weatherTool, attractionTool)` 编译报错，改为：
> ```java
> .toolCallbacks(MethodToolCallbackProvider.builder()
>     .toolObjects(weatherTool, attractionTool).build())
> ```
> 并 `import org.springframework.ai.tool.method.MethodToolCallbackProvider;`

- [ ] **Step 3: 编译验证**

Run: `cd demo2 && mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/config/ToolReasoningAgentConfig.java \
        demo2/src/main/resources/application.properties
git commit -m "feat(demo2): configure AugmentedToolCallbackProvider for tool reasoning"
```

---

### Task 4: ToolReasoningAgentService

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/service/ToolReasoningAgentService.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/service/ToolReasoningAgentServiceTest.java`

**Interfaces:**
- Consumes: `ChatClient.Builder`, `ChatMemory`, `MessageChatMemoryAdvisor`, `AugmentedToolCallbackProvider<AgentThinking>`, `ToolReasoningAgentConfig`
- Produces: `void validateSessionId(String)`；`void clearSession(String)`；`void streamChat(String sessionId, String message, SseEmitter emitter, JsonMapper jsonMapper)`

- [ ] **Step 1: 写失败测试 `ToolReasoningAgentServiceTest`**

```java
package com.jason.demo.demo2.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ToolReasoningAgentServiceTest {

    private ChatMemory chatMemory;
    private ToolReasoningAgentService service;

    @BeforeEach
    void setUp() {
        chatMemory = mock(ChatMemory.class);
        service = new ToolReasoningAgentService(
                null, chatMemory, null, null, "deepseek-chat");
    }

    @Test
    void validateSessionId_acceptsAlphanumeric() {
        assertDoesNotThrow(() -> service.validateSessionId("session_001"));
    }

    @Test
    void validateSessionId_rejectsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> service.validateSessionId("../evil"));
        assertThrows(IllegalArgumentException.class, () -> service.validateSessionId("bad id"));
        assertThrows(IllegalArgumentException.class, () -> service.validateSessionId(null));
    }

    @Test
    void clearSession_delegatesToChatMemory() {
        service.clearSession("session_001");
        verify(chatMemory).clear("session_001");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd demo2 && mvn -q test -Dtest=ToolReasoningAgentServiceTest`
Expected: FAIL

- [ ] **Step 3: 实现 `ToolReasoningAgentService.java`**

```java
package com.jason.demo.demo2.service;

import com.jason.demo.demo2.config.ToolReasoningAgentConfig;
import com.jason.demo.demo2.model.ToolReasoningSseEvent;
import com.jason.demo.demo2.sse.ToolReasoningStreamContext;
import com.jason.demo.demo2.tools.AttractionTool;
import com.jason.demo.demo2.tools.WeatherTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.tool.augment.AugmentedToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ToolReasoningAgentService {

    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");

    private static final String SYSTEM_PROMPT = """
            你是一个具备工具调用能力的智能行程规划 Agent，核心规则如下：
            1. 当用户询问天气时，必须调用 getWeather 工具获取实时天气数据；
            2. 当用户需要景点推荐时，必须调用 recommendAttractions 工具获取景点信息；
            3. 结合天气数据和景点信息，生成完整的行程规划建议；
            4. 行程安排要考虑天气因素（如雨天推荐室内景点，晴天推荐户外景点）；
            5. 输出结构清晰，包含天气概况、推荐景点、行程安排、实用提示；
            6. 所有实时信息必须通过工具获取，严禁编造天气或景点数据。
            7. 每次调用工具时，必须在 innerThought 中说明：为何选择该工具、期望获得什么、如何影响后续规划；confidence 如实填写 low/medium/high。
            回复风格：简洁专业，突出实用信息，适合移动端阅读。
            """;

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    public ToolReasoningAgentService(
            ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory,
            MessageChatMemoryAdvisor messageChatMemoryAdvisor,
            AugmentedToolCallbackProvider<com.jason.demo.demo2.model.AgentThinking> toolReasoningProvider,
            String toolReasoningChatModel) {
        this.chatMemory = chatMemory;
        this.chatClient = chatClientBuilder.clone()
                .defaultSystem(SYSTEM_PROMPT)
                .defaultOptions(DeepSeekChatOptions.builder().model(toolReasoningChatModel).build())
                .defaultTools(toolReasoningProvider)
                .defaultAdvisors(
                        messageChatMemoryAdvisor,
                        ToolCallingAdvisor.builder().build())
                .build();
    }

    @Autowired
    public ToolReasoningAgentService(
            ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory,
            MessageChatMemoryAdvisor messageChatMemoryAdvisor,
            AugmentedToolCallbackProvider<com.jason.demo.demo2.model.AgentThinking> toolReasoningProvider,
            ToolReasoningAgentConfig config) {
        this(chatClientBuilder, chatMemory, messageChatMemoryAdvisor,
                toolReasoningProvider, config.getToolReasoningChatModel());
    }

    public void validateSessionId(String sessionId) {
        if (sessionId == null || !SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("sessionId 仅允许字母、数字、下划线与连字符");
        }
    }

    public void clearSession(String sessionId) {
        validateSessionId(sessionId);
        chatMemory.clear(sessionId);
    }

    public void streamChat(String sessionId, String message, SseEmitter emitter, JsonMapper jsonMapper) {
        validateSessionId(sessionId);
        ToolReasoningStreamContext.set(emitter, jsonMapper, new AtomicInteger(0));
        try {
            sendSse(emitter, jsonMapper, ToolReasoningSseEvent.running());
            chatClient.prompt()
                    .user(message)
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .stream()
                    .content()
                    .subscribe(
                            chunk -> sendSse(emitter, jsonMapper, ToolReasoningSseEvent.token(chunk)),
                            err -> {
                                log.error("Tool reasoning stream failed, sessionId={}", sessionId, err);
                                sendSse(emitter, jsonMapper,
                                        ToolReasoningSseEvent.failed(err.getMessage()));
                                emitter.completeWithError(err);
                            },
                            () -> {
                                sendSse(emitter, jsonMapper, ToolReasoningSseEvent.completed());
                                emitter.complete();
                            });
        } catch (Exception e) {
            log.error("Tool reasoning chat failed, sessionId={}", sessionId, e);
            sendSse(emitter, jsonMapper, ToolReasoningSseEvent.failed(e.getMessage()));
            emitter.completeWithError(e);
        } finally {
            ToolReasoningStreamContext.clear();
        }
    }

    private void sendSse(SseEmitter emitter, JsonMapper jsonMapper, ToolReasoningSseEvent event) {
        try {
            emitter.send(SseEmitter.event().data(jsonMapper.writeValueAsString(event)).build());
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
}
```

> 注：`MessageChatMemoryAdvisor` 使用 `@Primary` Bean（`MemoryConfig` 未单独命名 advisor）。若项目仅有 `mysqlMessageChatMemoryAdvisor`，则注入 `@Primary` 或新增 `@Bean("toolReasoningMessageChatMemoryAdvisor")` 基于内存 `chatMemory()`：
> ```java
> MessageChatMemoryAdvisor.builder(chatMemory).build()
> ```
> 在 `ToolReasoningAgentConfig` 中注册并注入，避免误用 MySQL 记忆。

- [ ] **Step 4: 确认 `MessageChatMemoryAdvisor` 注入**

检查 `MemoryConfig`：仅有 `chatMemory()` Bean，无独立 `MessageChatMemoryAdvisor`。在 `ToolReasoningAgentConfig` 追加：

```java
@Bean
public MessageChatMemoryAdvisor toolReasoningMessageChatMemoryAdvisor(ChatMemory chatMemory) {
    return MessageChatMemoryAdvisor.builder(chatMemory).build();
}
```

Service 构造函数改为注入 `@Qualifier("toolReasoningMessageChatMemoryAdvisor") MessageChatMemoryAdvisor`。

- [ ] **Step 5: 运行测试确认通过**

Run: `cd demo2 && mvn -q test -Dtest=ToolReasoningAgentServiceTest`
Expected: BUILD SUCCESS, 3 tests passed

- [ ] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/service/ToolReasoningAgentService.java \
        demo2/src/test/java/com/jason/demo/demo2/service/ToolReasoningAgentServiceTest.java
git commit -m "feat(demo2): add ToolReasoningAgentService with SSE stream chat"
```

---

### Task 5: Controller

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/controller/ToolReasoningAgentController.java`

**Interfaces:**
- Consumes: `ToolReasoningAgentService.streamChat/clearSession/validateSessionId`
- Produces: `POST /agent/tool-reasoning/chat/stream`；`DELETE /agent/tool-reasoning/clear`

- [ ] **Step 1: 创建 `ToolReasoningAgentController.java`**

```java
package com.jason.demo.demo2.controller;

import com.jason.demo.demo2.model.ToolReasoningChatRequest;
import com.jason.demo.demo2.service.ToolReasoningAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Tag(name = "ToolReasoning", description = "Tool Argument Augmentation 工具推理捕获 Demo（SSE 对话式）")
@RestController
@RequestMapping("/agent/tool-reasoning")
@RequiredArgsConstructor
public class ToolReasoningAgentController {

    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final ToolReasoningAgentService toolReasoningAgentService;
    private final JsonMapper jsonMapper;
    private final ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor();

    @Operation(summary = "SSE 流式对话", description = "多轮 ChatMemory；实时推送 TOOL_REASONING + TOKEN")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ToolReasoningChatRequest request) {
        validateRequest(request);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        virtualThreads.execute(() ->
                toolReasoningAgentService.streamChat(
                        request.getSessionId(), request.getMessage(), emitter, jsonMapper));
        return emitter;
    }

    @Operation(summary = "清除会话记忆")
    @DeleteMapping("/clear")
    public Map<String, String> clear(
            @Parameter(description = "会话 ID") @RequestParam("sessionId") String sessionId) {
        try {
            toolReasoningAgentService.clearSession(sessionId);
            return Map.of("sessionId", sessionId, "message", "会话记忆已清除");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private void validateRequest(ToolReasoningChatRequest request) {
        try {
            toolReasoningAgentService.validateSessionId(request.getSessionId());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `cd demo2 && mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/controller/ToolReasoningAgentController.java
git commit -m "feat(demo2): add ToolReasoningAgentController REST and SSE endpoints"
```

---

### Task 6: 前端 CSS

**Files:**
- Create: `demo2/src/main/resources/static/css/tabs/tool-reasoning.css`

**Interfaces:**
- Produces: 样式类 `.tool-reasoning-header`、`.tool-reasoning-layout`、`.reasoning-card`、`.confidence-high/medium/low`

- [ ] **Step 1: 创建 `tool-reasoning.css`**

参考 `agent-session-memory.css` 紫色系改为靛蓝推理主题（`#312e81` → `#6366f1`），包含：

```css
/* 头部渐变 */
.tool-reasoning-header { background: linear-gradient(135deg, #312e81 0%, #6366f1 100%); ... }

/* 双栏布局：聊天 + 推理侧栏 */
.tool-reasoning-layout { display: grid; grid-template-columns: 1fr 300px; gap: 16px; }

/* 消息气泡（复用 .message.user / .message.assistant 模式） */
.tool-reasoning-messages { flex: 1; overflow-y: auto; padding: 16px; }

/* 推理卡片 */
.reasoning-card { border-left: 4px solid #6366f1; background: #eef2ff; padding: 12px; margin-bottom: 8px; border-radius: 8px; }
.reasoning-card .tool-name { font-weight: 600; color: #3730a3; }
.reasoning-card .confidence-high { color: #059669; }
.reasoning-card .confidence-medium { color: #d97706; }
.reasoning-card .confidence-low { color: #dc2626; }

/* 侧栏 */
.tool-reasoning-sidebar { border: 1px solid #e5e7eb; border-radius: 12px; padding: 12px; max-height: 520px; overflow-y: auto; }

/* 底部输入栏 */
.tool-reasoning-input-bar { display: flex; gap: 8px; padding: 12px; border-top: 1px solid #e5e7eb; }
.btn-tool-reasoning { background: linear-gradient(135deg, #312e81 0%, #6366f1 100%); color: white; }
```

- [ ] **Step 2: Commit**

```bash
git add demo2/src/main/resources/static/css/tabs/tool-reasoning.css
git commit -m "feat(demo2): add tool-reasoning tab styles"
```

---

### Task 7: 前端 JavaScript

**Files:**
- Create: `demo2/src/main/resources/static/js/tabs/tool-reasoning.js`

**Interfaces:**
- Consumes: `POST /agent/tool-reasoning/chat/stream`，`DELETE /agent/tool-reasoning/clear?sessionId=`
- Produces: 全局函数 `sendToolReasoningMessage()`、`clearToolReasoningSession()`、`fillToolReasoningMessage(text)`

- [ ] **Step 1: 创建 `tool-reasoning.js`**

核心逻辑（完整文件）：

```javascript
// ========== Tool Reasoning 工具推理捕获 ==========
let toolReasoningSessionId = crypto.randomUUID();

const TOOL_REASONING_SAMPLES = {
    1: '北京明天天气怎么样？适合出行吗？',
    2: '推荐几个北京的人文景点，最好评分高的',
    3: '帮我规划北京周末两天游，先看看天气，再推荐几个人文景点，生成完整行程',
    4: '广州天气怎么样？如果下雨的话推荐室内景点'
};

function fillToolReasoningMessage(text) {
    document.getElementById('toolReasoningMessageInput').value = text;
    document.getElementById('toolReasoningMessageInput').focus();
}

function fillToolReasoningSample(n) {
    fillToolReasoningMessage(TOOL_REASONING_SAMPLES[n] || '');
}

function setToolReasoningInputEnabled(enabled) {
    document.getElementById('toolReasoningMessageInput').disabled = !enabled;
    document.getElementById('toolReasoningSendBtn').disabled = !enabled;
}

function scrollToolReasoningMessages() {
    const box = document.getElementById('toolReasoningMessages');
    box.scrollTop = box.scrollHeight;
}

function appendToolReasoningBubble(text, isUser) {
    const box = document.getElementById('toolReasoningMessages');
    const welcome = document.getElementById('toolReasoningWelcome');
    if (welcome) welcome.remove();
    const div = document.createElement('div');
    div.className = 'message ' + (isUser ? 'user' : 'assistant');
    const content = document.createElement('div');
    content.className = 'message-content';
    if (isUser) {
        content.textContent = text;
    } else {
        content.innerHTML = '<div class="reasoning-inline-cards"></div><div class="response-text"></div>';
    }
    div.appendChild(content);
    box.appendChild(div);
    scrollToolReasoningMessages();
    return content;
}

function confidenceClass(confidence) {
    const c = (confidence || '').toLowerCase();
    if (c === 'high') return 'confidence-high';
    if (c === 'low') return 'confidence-low';
    return 'confidence-medium';
}

function toolIcon(toolName) {
    if (!toolName) return '🔧';
    if (toolName.toLowerCase().includes('weather')) return '🌤️';
    if (toolName.toLowerCase().includes('attraction')) return '🏛️';
    return '🔧';
}

function appendReasoningCard(payload, inlineContainer) {
    const card = document.createElement('div');
    card.className = 'reasoning-card';
    card.dataset.callIndex = payload.callIndex;
    card.innerHTML =
        '<div class="tool-name">' + toolIcon(payload.toolName) + ' #' + payload.callIndex +
        ' ' + escapeHtml(payload.toolName || 'tool') + '</div>' +
        '<div class="' + confidenceClass(payload.confidence) + '">置信度: ' +
        escapeHtml(payload.confidence || '—') + '</div>' +
        '<div class="inner-thought">' + escapeHtml(payload.innerThought || '') + '</div>';
    if (inlineContainer) inlineContainer.appendChild(card);

    const sidebar = document.getElementById('toolReasoningSidebarList');
    if (sidebar) sidebar.appendChild(card.cloneNode(true));
    scrollToolReasoningMessages();
}

async function sendToolReasoningMessage() {
    const message = document.getElementById('toolReasoningMessageInput').value.trim();
    if (!message) return;

    document.getElementById('toolReasoningMessageInput').value = '';
    appendToolReasoningBubble(message, true);
    const assistantContent = appendToolReasoningBubble('', false);
    const inlineCards = assistantContent.querySelector('.reasoning-inline-cards');
    const responseText = assistantContent.querySelector('.response-text');
    setToolReasoningInputEnabled(false);

    try {
        const response = await fetch('/agent/tool-reasoning/chat/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sessionId: toolReasoningSessionId, message })
        });
        if (!response.ok) throw new Error(await response.text() || 'HTTP ' + response.status);

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop() || '';
            for (const line of lines) {
                const trimmed = line.trim();
                if (!trimmed.startsWith('data:')) continue;
                const json = trimmed.replace(/^data:\s*/, '');
                if (!json) continue;
                const evt = JSON.parse(json);
                if (evt.type === 'TOOL_REASONING') {
                    appendReasoningCard(evt, inlineCards);
                } else if (evt.type === 'TOKEN' && evt.content) {
                    responseText.textContent += evt.content;
                    scrollToolReasoningMessages();
                } else if (evt.type === 'FAILED') {
                    throw new Error(evt.error || 'Agent 失败');
                }
            }
        }
    } catch (e) {
        responseText.textContent = '错误：' + e.message;
        assistantContent.classList.add('error');
    } finally {
        setToolReasoningInputEnabled(true);
        document.getElementById('toolReasoningMessageInput').focus();
    }
}

async function clearToolReasoningSession() {
    if (!confirm('确认清除当前 sessionId 的对话记忆？')) return;
    try {
        const res = await fetch('/agent/tool-reasoning/clear?sessionId=' +
            encodeURIComponent(toolReasoningSessionId), { method: 'DELETE' });
        if (!res.ok) throw new Error(await res.text() || 'HTTP ' + res.status);
        document.getElementById('toolReasoningMessages').innerHTML =
            '<div id="toolReasoningWelcome" class="message assistant"><div class="message-content">' +
            '会话已清除。发送消息开始多轮对话，工具调用时将实时展示 innerThought 与 confidence。</div></div>';
        document.getElementById('toolReasoningSidebarList').innerHTML = '';
        toolReasoningSessionId = crypto.randomUUID();
        document.getElementById('toolReasoningSessionIdDisplay').textContent = toolReasoningSessionId;
    } catch (e) {
        alert('清除失败：' + e.message);
    }
}

document.getElementById('toolReasoningForm')?.addEventListener('submit', function (e) {
    e.preventDefault();
    sendToolReasoningMessage();
});

document.addEventListener('DOMContentLoaded', function () {
    const el = document.getElementById('toolReasoningSessionIdDisplay');
    if (el) el.textContent = toolReasoningSessionId;
});
```

- [ ] **Step 2: Commit**

```bash
git add demo2/src/main/resources/static/js/tabs/tool-reasoning.js
git commit -m "feat(demo2): add tool-reasoning SSE chat frontend"
```

---

### Task 8: index.html 集成

**Files:**
- Modify: `demo2/src/main/resources/static/index.html`

**Interfaces:**
- Produces: Tab 按钮 `data-tab="tool-reasoning"`；`#tab-tool-reasoning` 面板；css/js 引用

- [ ] **Step 1: 在 `<head>` 引入 CSS**

在 `agent-tools.css` 之后添加：

```html
<link rel="stylesheet" href="/css/tabs/tool-reasoning.css">
```

- [ ] **Step 2: 在 Tab 按钮区 `agent-tools` 之后添加按钮**

```html
<button class="tab-btn" data-tab="tool-reasoning" onclick="switchTab('tool-reasoning')">🧠 工具推理捕获</button>
```

- [ ] **Step 3: 在 `#tab-agent-tools` 之后添加 Tab 内容区**

```html
<div id="tab-tool-reasoning" class="tab-content">
    <div class="tool-reasoning-header">
        <h1>Tool Argument Augmentation · 工具推理捕获</h1>
        <p>AugmentedToolCallbackProvider 捕获 innerThought + confidence · SSE 多轮对话 · 与 Agent Tools 对照</p>
    </div>
    <div class="rag-body">
        <div class="tool-reasoning-flow">
            <span>用户消息</span><span class="arrow">→</span>
            <span>LLM 选择工具</span><span class="arrow">→</span>
            <span>捕获推理</span><span class="arrow">→</span>
            <span>执行工具</span><span class="arrow">→</span>
            <span>流式回复</span>
        </div>
        <div class="card">
            <div class="card-title">💡 快捷测试（与 Agent Tools 相同场景）</div>
            <div class="card-body">
                <div class="sample-questions">
                    <button class="tool-reasoning-sample-btn" onclick="fillToolReasoningSample(1)">测试1：单工具·天气</button>
                    <button class="tool-reasoning-sample-btn" onclick="fillToolReasoningSample(2)">测试2：单工具·景点</button>
                    <button class="tool-reasoning-sample-btn" onclick="fillToolReasoningSample(3)">测试3：多工具组合</button>
                    <button class="tool-reasoning-sample-btn" onclick="fillToolReasoningSample(4)">测试4：天气影响决策</button>
                </div>
            </div>
        </div>
        <div class="tool-reasoning-layout">
            <div class="tool-reasoning-chat-panel">
                <div class="tool-reasoning-session-bar">
                    <span>sessionId: <code id="toolReasoningSessionIdDisplay"></code></span>
                    <button type="button" class="btn btn-secondary" onclick="clearToolReasoningSession()">清除会话</button>
                </div>
                <div id="toolReasoningMessages" class="tool-reasoning-messages">
                    <div id="toolReasoningWelcome" class="message assistant">
                        <div class="message-content">发送消息开始多轮对话。每次工具调用将实时展示 LLM 的 innerThought 与 confidence。</div>
                    </div>
                </div>
                <form id="toolReasoningForm" class="tool-reasoning-input-bar">
                    <input type="text" id="toolReasoningMessageInput" placeholder="输入消息，Enter 发送..." autocomplete="off">
                    <button type="submit" class="btn btn-tool-reasoning" id="toolReasoningSendBtn">发送</button>
                </form>
            </div>
            <div class="tool-reasoning-sidebar">
                <h3>推理时间线</h3>
                <div id="toolReasoningSidebarList"></div>
            </div>
        </div>
    </div>
</div>
```

- [ ] **Step 4: 在底部 script 区 `agent-tools.js` 之后引入**

```html
<script src="/js/tabs/tool-reasoning.js"></script>
```

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/resources/static/index.html
git commit -m "feat(demo2): wire tool-reasoning tab in index.html"
```

---

### Task 9: 全量验证

**Files:**
- (none — verification only)

- [ ] **Step 1: 编译**

Run: `cd demo2 && mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 2: 单元测试**

Run: `cd demo2 && mvn -q test`
Expected: 全部 tests passed（含新增 5 个）

- [ ] **Step 3: 启动应用手动验证**

Run: `cd demo2 && mvn spring-boot:run`
打开 `http://localhost:8081` → Tab「🧠 工具推理捕获」

| 步骤 | 操作 | 预期 |
|------|------|------|
| 1 | 发送测试3（多工具组合） | 右侧推理侧栏 ≥2 张卡片，callIndex 递增 |
| 2 | 追问「刚才天气怎么样？」 | assistant 基于记忆回答 |
| 3 | 点击「清除会话」 | 气泡清空，新 sessionId |
| 4 | 对比 agent-tools 同输入 | agent-tools 无推理展示 |

- [ ] **Step 4: Swagger 检查**

打开 `http://localhost:8081/scalar` → 确认 `ToolReasoning` 标签下 2 个端点

- [ ] **Step 5: 最终 Commit（若有修复）**

```bash
git add -A
git commit -m "fix(demo2): address tool-reasoning integration issues"
```

---

## Spec Coverage Checklist

| 规范要求 | 对应 Task |
|---------|----------|
| AugmentedToolCallbackProvider | Task 3 |
| innerThought + confidence | Task 1, 3 |
| SSE 对话式 POST /chat/stream | Task 4, 5, 7 |
| 多轮 ChatMemory | Task 4 |
| TOOL_REASONING + TOKEN 事件 | Task 1, 2, 7 |
| 不修改 WeatherTool/AttractionTool | Global Constraints |
| deepseek-chat 模型 | Task 3 |
| 前端 Tab + 侧栏 | Task 6, 7, 8 |
| DELETE /clear | Task 5, 7 |
| 编译 + 测试 | Task 9 |

---

## Manual Test Scenarios (from spec §6)

1. **单工具·天气** — 1 条 `TOOL_REASONING` + 流式回复
2. **多工具组合** — ≥2 推理卡片，`callIndex` 1→2
3. **多轮追问** — 第二轮引用上下文
4. **清除会话** — 记忆不再影响
5. **与 agent-tools 对比** — 仅新 Tab 展示推理
