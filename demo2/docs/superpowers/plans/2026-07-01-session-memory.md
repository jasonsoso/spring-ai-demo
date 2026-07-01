# Session 事件溯源短期记忆 Demo Implementation Plan

> **Status:** ✅ 已完成（2026-07-01）— `mvn compile` / `mvn test`（14 tests）通过。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 在 demo2 新增「Session 事件溯源记忆」Tab：JDBC Event Store + `RecursiveSummarizationCompactionStrategy` + `SessionEventTools` Recall + 工具调用 Turn 安全 + 对话式 SSE 流式 UI，`mvn compile` / `mvn test` 通过。

**Architecture:** `SessionMemoryAgentConfig` 注册 `SessionMemoryAdvisor`（`TurnCountTrigger` + `RecursiveSummarization`）与 `SessionEventTools`；`SessionMemoryTripAgentService` 每轮 `chatClientBuilder.clone()` 挂 advisor + `WeatherTool`/`AttractionTool`，以 `userId` 作为 `SESSION_ID_CONTEXT_KEY`；`POST /chat/stream` 直接返回 `SseEmitter`（参考 `ChatController`）；事件侧栏经 `GET /events` 展示 active/archived/synthetic 统计。

**Tech Stack:** Java 21, Spring Boot 4.1, Spring AI 2.0.0, spring-ai-session BOM **0.2.0** (`spring-ai-starter-session-jdbc`), DeepSeek `deepseek-v4-pro`, 原生 HTML/CSS/JS

**设计规范:** [docs/superpowers/specs/2026-07-01-session-memory-design.md](../specs/2026-07-01-session-memory-design.md)

**原文:** [Spring AI 2.0 系列教程（九）——事件溯源的短期记忆与上下文压缩](https://mp.weixin.qq.com/s/ggsibA5simFvW7Tk2I9ddg)（第七符文）

## Global Constraints

- **仅短期记忆**：**不**挂 `AutoMemoryToolsAdvisor` / `MessageChatMemoryAdvisor`（文章：与 Session API 不可叠加）
- **压缩**：`TurnCountTrigger(15)` + `RecursiveSummarizationCompactionStrategy`（`maxEventsToKeep=10`, `overlapSize=2`）
- **持久化**：`spring-ai-starter-session-jdbc`；`spring.ai.session.repository.jdbc.initialize-schema=always`；**`platform=mysql`**（jar 无 `schema-mariadb.sql`；ChatMemory 仍可用 `mariadb`）；复用 `spring.datasource.*`
- **Recall**：注册 `SessionEventTools`；SYSTEM_PROMPT 引导 `conversation_search`
- **模型**：`agent.session-memory.chat.model=deepseek-v4-pro`
- **Session ID**：`SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY = userId`
- **userId** 校验：`^[a-zA-Z0-9_-]+$`，非法 `400`
- **API 前缀**：`/agent/session-memory`；SSE 超时 **5 分钟**
- **不修改**现有三个记忆 Tab 后端逻辑
- **前端**：零构建、聊天气泡 + 底部输入框、全局函数、`fetch` 读 SSE 流（参考 `chat.js`）

---

## File Structure

| 文件 | 职责 |
|------|------|
| `pom.xml` | `spring-ai-session-bom` + `spring-ai-starter-session-jdbc` |
| `config/SessionMemoryAgentConfig.java` | `SessionMemoryAdvisor`、`SessionEventTools`、压缩参数、摘要用 `ChatClient` |
| `model/SessionMemoryChatRequest.java` | `{ userId, message }` |
| `model/SessionMemorySseEvent.java` | SSE JSON：`TOKEN` / `COMPLETED` / `FAILED` |
| `service/SessionMemoryTripAgentService.java` | `streamChat`、`listEvents`、`clearSession`、`validateUserId` |
| `controller/SessionMemoryAgentController.java` | REST + SSE 三端点 |
| `test/.../SessionMemoryTripAgentServiceTest.java` | userId 校验、clear、listEvents 空会话 |
| `application.properties` | Session JDBC + 压缩 + 模型配置 |
| `static/css/tabs/agent-session-memory.css` | 对话区 + 事件侧栏 |
| `static/js/tabs/agent-session-memory.js` | 多轮 SSE 对话 + 侧栏刷新 |
| `static/index.html` | Tab 按钮、面板、link/script |
| `README.md` | Session Demo 章节（可选） |

---

### Task 1: Maven 依赖与配置

**Files:**
- Modify: `demo2/pom.xml`
- Modify: `demo2/src/main/resources/application.properties`

**Interfaces:**
- Produces: 可解析的 `spring-ai-session` 依赖；`application.properties` 中 Session 与压缩配置项

- [x] **Step 1: 在 `pom.xml` `dependencyManagement` 增加 session BOM**

在现有 `spring-ai-agent-utils-bom` 之后添加：

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-session-bom</artifactId>
    <version>0.2.0</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

- [x] **Step 2: 在 `dependencies` 增加 JDBC Starter**

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-starter-session-jdbc</artifactId>
</dependency>
```

- [x] **Step 3: 若 `mvn compile` 无法解析 0.2.0**

1. 检查阿里云镜像是否同步；必要时在 `pom.xml` 增加 `spring-milestones` 或 Maven Central 直连 repository（仅当 compile 失败时）
2. 运行 `mvn dependency:tree -Dincludes=org.springaicommunity:spring-ai-session` 确认版本
3. 用 `jar tf` 查看实际包名（Task 2  import 以此为准）

- [x] **Step 4: 新增 `application.properties`**

```properties
# Session API JDBC（表 AI_SESSION / AI_SESSION_EVENT，与 spring_ai_chat_memory 独立）
spring.ai.session.repository.jdbc.initialize-schema=always
spring.ai.session.repository.jdbc.platform=mysql

agent.session-memory.compaction.turn-threshold=15
agent.session-memory.compaction.max-events-to-keep=10
agent.session-memory.compaction.overlap-size=2
agent.session-memory.chat.model=deepseek-v4-pro
```

- [x] **Step 5: 编译验证**

Run: `cd demo2 && mvn -q compile`
Expected: BUILD SUCCESS

---

### Task 2: SessionMemoryAgentConfig

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/config/SessionMemoryAgentConfig.java`

**Interfaces:**
- Consumes: 自动配置的 `SessionService`（starter 提供）、`ChatClient.Builder`
- Produces: `@Bean SessionMemoryAdvisor sessionMemoryAdvisor`、`@Bean SessionEventTools sessionEventTools`、`getSessionChatModel()` / 压缩参数 getter

- [x] **Step 1: 创建配置类**

```java
package com.jason.demo.demo2.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
// Task 1 编译后确认以下 import（典型为 org.springaicommunity.ai.session.*）
import org.springaicommunity.ai.session.SessionService;
import org.springaicommunity.ai.session.SessionMemoryAdvisor;
import org.springaicommunity.ai.session.compaction.RecursiveSummarizationCompactionStrategy;
import org.springaicommunity.ai.session.compaction.TurnCountTrigger;
import org.springaicommunity.ai.session.tools.SessionEventTools;

@Configuration
public class SessionMemoryAgentConfig {

    @Value("${agent.session-memory.compaction.turn-threshold:15}")
    private int turnThreshold;

    @Value("${agent.session-memory.compaction.max-events-to-keep:10}")
    private int maxEventsToKeep;

    @Value("${agent.session-memory.compaction.overlap-size:2}")
    private int overlapSize;

    @Value("${agent.session-memory.chat.model:deepseek-v4-pro}")
    private String sessionChatModel;

    public String getSessionChatModel() {
        return sessionChatModel;
    }

    @Bean
    public ChatClient sessionSummarizationChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.clone().build();
    }

    @Bean
    public SessionMemoryAdvisor sessionMemoryAdvisor(
            SessionService sessionService,
            ChatClient sessionSummarizationChatClient) {
        return SessionMemoryAdvisor.builder(sessionService)
                .compactionTrigger(new TurnCountTrigger(turnThreshold))
                .compactionStrategy(
                        RecursiveSummarizationCompactionStrategy.builder(sessionSummarizationChatClient)
                                .maxEventsToKeep(maxEventsToKeep)
                                .overlapSize(overlapSize)
                                .build())
                .build();
    }

    @Bean
    public SessionEventTools sessionEventTools(SessionService sessionService) {
        return SessionEventTools.builder(sessionService).build();
    }
}
```

> **注意：** import 路径以 Task 1 解压 jar 为准。实际 0.2.0 为 `org.springframework.ai.session.*`（非计划草稿中的 `org.springaicommunity.ai.session.*`），逻辑不变。

- [x] **Step 2: 编译**

Run: `cd demo2 && mvn -q compile`
Expected: BUILD SUCCESS（修正 import 直至通过）

---

### Task 3: 请求模型与 Service 单测

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/SessionMemoryChatRequest.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/service/SessionMemoryTripAgentService.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/service/SessionMemoryTripAgentServiceTest.java`

**Interfaces:**
- Produces: `SessionMemoryChatRequest`；`SessionMemoryTripAgentService.validateUserId(String)`、`clearSession(String)`、`listEvents(String) → Map<String,Object>`

- [x] **Step 1: 写失败测试 `SessionMemoryTripAgentServiceTest`**

```java
package com.jason.demo.demo2.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.ai.session.SessionService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionMemoryTripAgentServiceTest {

    private SessionService sessionService;
    private SessionMemoryTripAgentService service;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        service = new SessionMemoryTripAgentService(
                null, sessionService, null, null, null, null, null);
    }

    @Test
    void validateUserId_acceptsAlphanumeric() {
        assertDoesNotThrow(() -> service.validateUserId("user_1001"));
    }

    @Test
    void validateUserId_rejectsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> service.validateUserId("../evil"));
        assertThrows(IllegalArgumentException.class, () -> service.validateUserId("user 999"));
        assertThrows(IllegalArgumentException.class, () -> service.validateUserId(null));
    }

    @Test
    void listEvents_whenSessionMissing_returnsEmptyStats() {
        when(sessionService.findSession("1001")).thenReturn(java.util.Optional.empty());

        Map<String, Object> result = service.listEvents("1001");

        assertEquals("1001", result.get("userId"));
        assertEquals(0, result.get("totalEvents"));
    }

    @Test
    void clearSession_delegatesToSessionService() {
        service.clearSession("1001");
        verify(sessionService).deleteSession("1001");
    }
}
```

> 根据实际 `SessionService` API 调整 `findSession` / `deleteSession` 方法名（可能是 `getSession`、`removeSession` 等）。

- [x] **Step 2: 运行测试确认失败**

Run: `cd demo2 && mvn -q test -Dtest=SessionMemoryTripAgentServiceTest`
Expected: FAIL（类不存在）

- [x] **Step 3: 创建 `SessionMemoryChatRequest`**

```java
package com.jason.demo.demo2.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Session 记忆对话请求")
public class SessionMemoryChatRequest {
    private String userId;
    private String message;
}
```

- [x] **Step 4: 实现 `SessionMemoryTripAgentService`（非流式部分 + validate）**

```java
package com.jason.demo.demo2.service;

import com.jason.demo.demo2.config.SessionMemoryAgentConfig;
import com.jason.demo.demo2.tools.AttractionTool;
import com.jason.demo.demo2.tools.WeatherTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.stereotype.Service;
import org.springaicommunity.ai.session.SessionEvent;
import org.springaicommunity.ai.session.SessionMemoryAdvisor;
import org.springaicommunity.ai.session.SessionService;
import org.springaicommunity.ai.session.tools.SessionEventTools;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SessionMemoryTripAgentService {

    private static final Pattern USER_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");
    private static final int EVENT_PREVIEW_LIMIT = 20;

    private final ChatClient.Builder chatClientBuilder;
    private final SessionService sessionService;
    private final SessionMemoryAdvisor sessionMemoryAdvisor;
    private final SessionEventTools sessionEventTools;
    private final WeatherTool weatherTool;
    private final AttractionTool attractionTool;
    private final String sessionChatModel;

    // 包级构造器供测试注入 mock
    SessionMemoryTripAgentService(
            ChatClient.Builder chatClientBuilder,
            SessionService sessionService,
            SessionMemoryAdvisor sessionMemoryAdvisor,
            SessionEventTools sessionEventTools,
            WeatherTool weatherTool,
            AttractionTool attractionTool,
            String sessionChatModel) {
        this.chatClientBuilder = chatClientBuilder;
        this.sessionService = sessionService;
        this.sessionMemoryAdvisor = sessionMemoryAdvisor;
        this.sessionEventTools = sessionEventTools;
        this.weatherTool = weatherTool;
        this.attractionTool = attractionTool;
        this.sessionChatModel = sessionChatModel;
    }

    public SessionMemoryTripAgentService(
            ChatClient.Builder chatClientBuilder,
            SessionService sessionService,
            SessionMemoryAdvisor sessionMemoryAdvisor,
            SessionEventTools sessionEventTools,
            WeatherTool weatherTool,
            AttractionTool attractionTool,
            SessionMemoryAgentConfig config) {
        this(chatClientBuilder, sessionService, sessionMemoryAdvisor, sessionEventTools,
                weatherTool, attractionTool, config.getSessionChatModel());
    }

    public void validateUserId(String userId) {
        if (userId == null || !USER_ID_PATTERN.matcher(userId).matches()) {
            throw new IllegalArgumentException("userId 仅允许字母、数字、下划线与连字符");
        }
    }

    public Map<String, Object> listEvents(String userId) {
        validateUserId(userId);
        List<SessionEvent> all = loadAllEvents(userId);
        long archived = all.stream().filter(SessionEvent::isArchived).count();
        long synthetic = all.stream().filter(SessionEvent::isSynthetic).count();
        long active = all.size() - archived;

        List<Map<String, Object>> preview = all.stream()
                .sorted(Comparator.comparing(SessionEvent::timestamp).reversed())
                .limit(EVENT_PREVIEW_LIMIT)
                .map(this::toEventSummary)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("totalEvents", all.size());
        result.put("activeEvents", active);
        result.put("archivedEvents", archived);
        result.put("syntheticEvents", synthetic);
        result.put("events", preview);
        return result;
    }

    public void clearSession(String userId) {
        validateUserId(userId);
        sessionService.deleteSession(userId);
    }

    private List<SessionEvent> loadAllEvents(String userId) {
        return sessionService.findSession(userId)
                .map(s -> sessionService.getEvents(s.id()))
                .orElse(List.of());
    }

    private Map<String, Object> toEventSummary(SessionEvent event) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", event.id());
        m.put("messageType", event.getMessage().getMessageType().name());
        m.put("archived", event.isArchived());
        m.put("synthetic", event.isSynthetic());
        m.put("timestamp", event.timestamp().toString());
        return m;
    }

    ChatClient buildChatClient() {
        return chatClientBuilder.clone()
                .defaultOptions(DeepSeekChatOptions.builder().model(sessionChatModel).build())
                .defaultTools(sessionEventTools, weatherTool, attractionTool)
                .defaultAdvisors(sessionMemoryAdvisor, ToolCallingAdvisor.builder().build())
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    private static final String SYSTEM_PROMPT = """
            你是带 Session 事件溯源短期记忆的智能行程规划 Agent，严格遵守：
            1. 查询天气必须调用 getWeather；推荐景点必须调用 recommendAttractions；
            2. 优先使用当前会话历史；若早期细节不在上下文中，主动调用 conversation_search 检索；
            3. 结合用户偏好生成按天/时段划分的行程，语言简洁、结构清晰。
            """;
}
```

> 实现时对照真实 `SessionService` / `SessionEvent` API 微调方法名（`getEvents`、`isSynthetic` 等）。

- [x] **Step 5: 运行测试**

Run: `cd demo2 && mvn -q test -Dtest=SessionMemoryTripAgentServiceTest`
Expected: PASS

---

### Task 4: SSE 流式 chat

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/SessionMemorySseEvent.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/service/SessionMemoryTripAgentService.java`

**Interfaces:**
- Consumes: `buildChatClient()` from Task 3
- Produces: `void streamChat(String userId, String message, SseEmitter emitter)`

- [x] **Step 1: 创建 `SessionMemorySseEvent`**

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
public class SessionMemorySseEvent {
    private String type;    // TOKEN | COMPLETED | FAILED
    private String content;
    private String error;

    public static SessionMemorySseEvent token(String content) {
        return new SessionMemorySseEvent("TOKEN", content, null);
    }
    public static SessionMemorySseEvent completed() {
        return new SessionMemorySseEvent("COMPLETED", null, null);
    }
    public static SessionMemorySseEvent failed(String error) {
        return new SessionMemorySseEvent("FAILED", null, error);
    }
}
```

- [x] **Step 2: 在 Service 增加 `streamChat`**

```java
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;
import reactor.core.scheduler.Schedulers;

public void streamChat(String userId, String message, SseEmitter emitter, JsonMapper jsonMapper) {
    validateUserId(userId);
    ChatClient client = buildChatClient();
    client.prompt()
            .user(message)
            .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, userId))
            .stream()
            .content()
            .publishOn(Schedulers.boundedElastic())
            .subscribe(
                    chunk -> sendSse(emitter, jsonMapper, SessionMemorySseEvent.token(chunk)),
                    err -> {
                        sendSse(emitter, jsonMapper, SessionMemorySseEvent.failed(err.getMessage()));
                        emitter.completeWithError(err);
                    },
                    () -> {
                        sendSse(emitter, jsonMapper, SessionMemorySseEvent.completed());
                        emitter.complete();
                    });
}

private void sendSse(SseEmitter emitter, JsonMapper jsonMapper, SessionMemorySseEvent event) {
    try {
        emitter.send(SseEmitter.event().data(jsonMapper.writeValueAsString(event)).build());
    } catch (Exception e) {
        emitter.completeWithError(e);
    }
}
```

- [x] **Step 3: 编译**

Run: `cd demo2 && mvn -q compile`
Expected: BUILD SUCCESS

---

### Task 5: Controller

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/controller/SessionMemoryAgentController.java`

**Interfaces:**
- Consumes: `SessionMemoryTripAgentService`
- Produces: `POST /agent/session-memory/chat/stream` → `SseEmitter`；`GET /events`；`DELETE /clear`

- [x] **Step 1: 实现 Controller**

```java
package com.jason.demo.demo2.controller;

import com.jason.demo.demo2.model.SessionMemoryChatRequest;
import com.jason.demo.demo2.service.SessionMemoryTripAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.concurrent.Executors;

@Tag(name = "SessionMemory", description = "Session API 事件溯源短期记忆 + RecursiveSummarization Demo")
@RestController
@RequestMapping("/agent/session-memory")
@RequiredArgsConstructor
public class SessionMemoryAgentController {

    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final SessionMemoryTripAgentService sessionMemoryTripAgentService;
    private final JsonMapper jsonMapper;
    private final Executors.NewVirtualThreadPerTaskExecutor virtualThreads =
            (Executors.NewVirtualThreadPerTaskExecutor) Executors.newVirtualThreadPerTaskExecutor();

    @Operation(summary = "SSE 流式对话", description = "固定 userId 多轮；SessionMemoryAdvisor 自动加载/追加/压缩")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody SessionMemoryChatRequest request) {
        validateRequest(request);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        virtualThreads.execute(() ->
                sessionMemoryTripAgentService.streamChat(
                        request.getUserId(), request.getMessage(), emitter, jsonMapper));
        return emitter;
    }

    @Operation(summary = "事件摘要", description = "active/archived/synthetic 统计 + 最近 20 条元数据")
    @GetMapping("/events")
    public Map<String, Object> events(@RequestParam String userId) {
        try {
            return sessionMemoryTripAgentService.listEvents(userId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @Operation(summary = "清除会话", description = "删除该 userId 的 Session 及全部 events")
    @DeleteMapping("/clear")
    public Map<String, String> clear(@RequestParam String userId) {
        try {
            sessionMemoryTripAgentService.clearSession(userId);
            return Map.of("userId", userId, "message", "Session 及事件日志已清除");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private void validateRequest(SessionMemoryChatRequest request) {
        try {
            sessionMemoryTripAgentService.validateUserId(request.getUserId());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
    }
}
```

- [x] **Step 2: 编译**

Run: `cd demo2 && mvn -q compile`
Expected: BUILD SUCCESS

---

### Task 6: 前端对话式 Tab

**Files:**
- Create: `demo2/src/main/resources/static/css/tabs/agent-session-memory.css`
- Create: `demo2/src/main/resources/static/js/tabs/agent-session-memory.js`
- Modify: `demo2/src/main/resources/static/index.html`

**Interfaces:**
- Consumes: `POST /agent/session-memory/chat/stream`、`GET /events`、`DELETE /clear`

- [x] **Step 1: 在 `index.html` 增加 Tab**

在「Agent 自主记忆」按钮后添加：

```html
<button class="tab-btn" data-tab="agent-session-memory" onclick="switchTab('agent-session-memory')">🗂️ Session 事件溯源记忆</button>
```

在 `tab-agent-auto-memory` 面板后添加 `tab-agent-session-memory` 面板（含 userId 输入、对话区 `#sessionMemoryMessages`、侧栏 `#sessionMemoryEventPanel`、底部 `#sessionMemoryForm` + `#sessionMemoryMessageInput`、快捷按钮）。

`<head>` 增加：`<link rel="stylesheet" href="/css/tabs/agent-session-memory.css">`

`</body>` 前、`agent-auto-memory.js` 之后增加：`<script src="/js/tabs/agent-session-memory.js"></script>`

- [x] **Step 2: 创建 `agent-session-memory.css`**

布局：左侧对话气泡（参考 `chat.css` 的 `.message.user/.assistant`），右侧固定宽度事件侧栏，底部 sticky 输入区。

- [x] **Step 3: 创建 `agent-session-memory.js`**

核心逻辑（参考 `chat.js` 的 `fetch` + `ReadableStream`）：

```javascript
async function sendSessionMemoryMessage(message) {
    const userId = document.getElementById('sessionMemoryUserIdInput').value.trim();
    if (!userId) { alert('请输入 userId'); return; }
    appendSessionBubble(message, true);
    const assistantBubble = appendSessionBubble('', false);
    setSessionMemoryInputEnabled(false);

    try {
        const response = await fetch('/agent/session-memory/chat/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId, message })
        });
        if (!response.ok) throw new Error(await response.text() || 'HTTP ' + response.status);

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            for (const line of buffer.split('\n')) {
                if (!line.startsWith('data:')) continue;
                const json = line.replace(/^data:\s*/, '').trim();
                if (!json) continue;
                const evt = JSON.parse(json);
                if (evt.type === 'TOKEN') {
                    assistantBubble.textContent += evt.content;
                    scrollSessionMessages();
                } else if (evt.type === 'FAILED') {
                    throw new Error(evt.error || 'Agent 失败');
                }
            }
            buffer = '';
        }
        await refreshSessionMemoryEvents();
    } catch (e) {
        assistantBubble.textContent = '错误：' + e.message;
        assistantBubble.classList.add('error');
    } finally {
        setSessionMemoryInputEnabled(true);
    }
}
```

实现 `refreshSessionMemoryEvents()`、`clearSessionMemory()`、快捷填充三条验证场景。

- [x] **Step 4: 手工冒烟（需 MySQL + DeepSeek API Key）**

1. 启动应用，打开 Tab，userId=1001
2. 连续发送 3 轮含「查北京天气」的消息，确认 SSE 流式回复
3. 点击「刷新事件」，确认 `totalEvents` 增长
4. `DELETE /clear?userId=1001` 后事件归零

---

### Task 7: 全量验证与文档

**Files:**
- Modify: `demo2/README.md`（可选，新增 Session Demo 小节）

- [x] **Step 1: 全量测试**

Run: `cd demo2 && mvn -q test`
Expected: BUILD SUCCESS

- [x] **Step 2: 全量编译**

Run: `cd demo2 && mvn -q compile`
Expected: BUILD SUCCESS

- [x] **Step 3: README（可选）**

在记忆相关章节增加：

- Tab 名称与文章链接
- 四种记忆 Tab 对比表（摘自 spec §2.1）
- 手工验证四步场景
- 配置项说明

---

## Spec Coverage Self-Review

| Spec 要求 | Task |
|-----------|------|
| spring-ai-session-jdbc | Task 1 |
| RecursiveSummarization + TurnCountTrigger(15) | Task 2 |
| SessionEventTools / conversation_search | Task 2, 3 SYSTEM_PROMPT |
| 不挂 ChatMemory / AutoMemory | Task 3 `buildChatClient` |
| WeatherTool + AttractionTool | Task 3 |
| deepseek-v4-pro | Task 1 properties, Task 3 |
| POST /chat/stream SSE | Task 4, 5 |
| GET /events, DELETE /clear | Task 3, 5 |
| 对话式 UI | Task 6 |
| mvn compile/test | Task 7 |
| userId 校验 | Task 3, 5 |

**API 风险：** `SessionService` / `SessionEvent` 方法名以 jar 为准，Task 3 Step 4 与单测需同步调整。

---

## Implementation Notes（相对计划的实际偏差）

执行方式：**Subagent-Driven（选项 1）**。

| 项 | 计划 | 实际 |
|----|------|------|
| Java 包名 | `org.springaicommunity.ai.session.*` | `org.springframework.ai.session.*` |
| `SessionService` | `findSession` / `deleteSession` | `findById` / `delete` / `getEvents` / `getMessages` |
| `SessionEvent` | `isArchived()`、`event.id()` | `getId()`、`isSynthetic()`、`hasToolCalls()`；无 `isArchived` |
| JDBC platform | `mariadb` | **`mysql`**（0.2.0 jar 仅有 mysql/postgresql/h2 schema） |
| Spring 注入 | 单 `@Service` 构造器 | 测试用包级构造器 + **`@Autowired` 公开构造器** |
| `DeepSeekChatOptions` | `.build()` | 对齐项目惯例：**省略** `.build()` |
| `clearSession` | 直接 `deleteSession` | `findById` 非空才 `delete` |
| 单测 `Session` | `mock(Session.class)` | `Session.builder().id("1001").userId("1001").build()`（final 类） |
| `listEvents` archived | `SessionEvent::isArchived` | `archivedEvents = totalEvents - promptMessageCount`（估算） |

**验证记录：**

```text
mvn -q test   → BUILD SUCCESS（14 tests，含 SessionMemoryTripAgentServiceTest ×5）
mvn -q compile → BUILD SUCCESS
```

**手工冒烟**（需 MySQL `spring_ai_agent2` + `DEEPSEEK_API_KEY`）：见 spec §5 四步验证场景。

Spec: [2026-07-01-session-memory-design.md](../specs/2026-07-01-session-memory-design.md)
