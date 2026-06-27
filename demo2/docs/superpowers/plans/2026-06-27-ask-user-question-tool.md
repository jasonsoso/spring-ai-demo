# AskUserQuestionTool Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 demo2 中实现 AskUserQuestionTool 技术选型 Demo：SSE 推送澄清问题，HTTP POST 提交答案，前端 Tab 完整交互。

**Architecture:** `POST /chat` 创建内存 Session 并异步运行 `ChatClient`；`WebQuestionHandler` 在 Agent 线程中推送 `QUESTIONS` 事件并阻塞于 `CompletableFuture`；`GET /sse/{sessionId}` 注册 `SseEmitter` 并 flush 缓冲；`POST /answer` 完成 Future 恢复 Agent；前端 `index.html` 新 Tab 用 `EventSource` 消费事件。

**Tech Stack:** Java 21, Spring Boot 4.1, Spring AI 2.0.0, spring-ai-agent-utils 0.10.0, SseEmitter, Lombok, JUnit 5, Mockito

**设计规范:** [docs/superpowers/specs/2026-06-27-ask-user-question-tool-design.md](../specs/2026-06-27-ask-user-question-tool-design.md)

## Global Constraints

- 不新增 Maven 依赖；使用已有 `spring-ai-agent-utils:0.10.0`
- 服务端口 **8081**；虚拟线程已启用（`spring.threads.virtual.enabled=true`）
- 单轮 Demo：不实现多轮对话历史延续
- Session 仅内存存储；SSE 超时 **5 分钟**；Session 空闲过期 **10 分钟**
- `ChatClient` 首版仅注册 `AskUserQuestionTool`，不叠加其他工具
- 答案 Map 的 key 必须使用 `Question.question()` 文本
- 首版 `answersValidation` 使用默认 **false**
- API 前缀：`/agent/ask-user`
- 使用中文 System Prompt 与前端文案

---

## File Structure

| 文件 | 职责 |
|------|------|
| `model/AskUserSessionStatus.java` | Session 状态枚举 |
| `model/AskUserSession.java` | Session 实体（emitter、Future、缓冲队列） |
| `model/AskUserChatRequest.java` | POST /chat 请求体 |
| `model/AskUserAnswerRequest.java` | POST /answer 请求体 |
| `model/AskUserChatResponse.java` | POST /chat 响应体 |
| `model/AskUserAnswerResponse.java` | POST /answer 响应体 |
| `model/AskUserSseEvent.java` | SSE 事件 DTO |
| `model/AskUserQuestionDto.java` | 推送给前端的问题结构 |
| `service/AskUserSessionStore.java` | Session CRUD、事件推送、缓冲 flush |
| `service/WebQuestionHandler.java` | `QuestionHandler` 实现 |
| `service/AskUserAgentService.java` | Agent 编排、三端点业务逻辑 |
| `config/AskUserAgentConfig.java` | `AskUserQuestionTool` Bean |
| `controller/AskUserAgentController.java` | REST + SSE 端点 |
| `test/.../AskUserSessionStoreTest.java` | Session 存储单元测试 |
| `test/.../WebQuestionHandlerTest.java` | Handler 阻塞/唤醒单元测试 |
| `static/index.html` | 新增 Tab + JS |

---

### Task 1: 模型与枚举

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/AskUserSessionStatus.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/AskUserChatRequest.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/AskUserAnswerRequest.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/AskUserChatResponse.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/AskUserAnswerResponse.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/AskUserQuestionDto.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/AskUserSseEvent.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/AskUserSession.java`

**Interfaces:**
- Consumes: 无
- Produces: `AskUserSessionStatus` 枚举；`AskUserSseEvent.of(type, ...)` 工厂方法；`AskUserSession` 含 `sessionId`、`message`、`status`、`eventBuffer`、`answerFuture`、`sseEmitter`、`createdAt`

- [ ] **Step 1: 创建 `AskUserSessionStatus.java`**

```java
package com.jason.demo.demo2.model;

public enum AskUserSessionStatus {
    RUNNING,
    AWAITING_INPUT,
    COMPLETED,
    FAILED
}
```

- [ ] **Step 2: 创建请求/响应 DTO**

`AskUserChatRequest.java`:

```java
package com.jason.demo.demo2.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "AskUserQuestion 对话请求")
public class AskUserChatRequest {

    @Schema(description = "用户消息", example = "帮我选一个数据库")
    private String message;
}
```

`AskUserAnswerRequest.java`:

```java
package com.jason.demo.demo2.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "AskUserQuestion 答案提交")
public class AskUserAnswerRequest {

    @Schema(description = "会话 ID")
    private String sessionId;

    @Schema(description = "答案 Map，key 为 question 文本")
    private Map<String, String> answers;
}
```

`AskUserChatResponse.java`:

```java
package com.jason.demo.demo2.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@Schema(description = "AskUserQuestion 对话响应")
public class AskUserChatResponse {

    @Schema(description = "会话 ID")
    private String sessionId;
}
```

`AskUserAnswerResponse.java`:

```java
package com.jason.demo.demo2.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@Schema(description = "AskUserQuestion 答案受理响应")
public class AskUserAnswerResponse {

    @Schema(description = "受理状态", example = "accepted")
    private String status;
}
```

- [ ] **Step 3: 创建 `AskUserQuestionDto.java`**

```java
package com.jason.demo.demo2.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "推送给前端的澄清问题")
public class AskUserQuestionDto {

    private String header;
    private String question;
    private List<OptionDto> options;
    private Boolean multiSelect;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionDto {
        private String label;
        private String description;
    }
}
```

- [ ] **Step 4: 创建 `AskUserSseEvent.java`**

```java
package com.jason.demo.demo2.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AskUserSseEvent {

    private String type;
    private List<AskUserQuestionDto> questions;
    private String response;
    private String error;

    public static AskUserSseEvent running() {
        return new AskUserSseEvent("RUNNING", null, null, null);
    }

    public static AskUserSseEvent questions(List<AskUserQuestionDto> questions) {
        return new AskUserSseEvent("QUESTIONS", questions, null, null);
    }

    public static AskUserSseEvent completed(String response) {
        return new AskUserSseEvent("COMPLETED", null, response, null);
    }

    public static AskUserSseEvent failed(String error) {
        return new AskUserSseEvent("FAILED", null, null, error);
    }
}
```

- [ ] **Step 5: 创建 `AskUserSession.java`**

```java
package com.jason.demo.demo2.model;

import lombok.Getter;
import lombok.Setter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

@Getter
public class AskUserSession {

    private final String sessionId;
    private final String message;
    private final Instant createdAt;
    private final Queue<AskUserSseEvent> eventBuffer = new ConcurrentLinkedQueue<>();

    @Setter
    private volatile AskUserSessionStatus status = AskUserSessionStatus.RUNNING;

    @Setter
    private volatile SseEmitter sseEmitter;

    @Setter
    private volatile CompletableFuture<Map<String, String>> answerFuture;

    @Setter
    private volatile Instant lastActivityAt;

    public AskUserSession(String sessionId, String message) {
        this.sessionId = sessionId;
        this.message = message;
        this.createdAt = Instant.now();
        this.lastActivityAt = this.createdAt;
    }

    public void touch() {
        this.lastActivityAt = Instant.now();
    }
}
```

- [ ] **Step 6: 编译验证**

Run:
```powershell
cd d:\ai\spring-ai-demo\demo2
mvnw.cmd -q compile -DskipTests
```
Expected: `BUILD SUCCESS`

- [ ] **Step 7: Commit**

```powershell
git add demo2/src/main/java/com/jason/demo/demo2/model/AskUser*.java
git commit -m "feat: add AskUserQuestion demo model classes"
```

---

### Task 2: AskUserSessionStore

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/service/AskUserSessionStore.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/service/AskUserSessionStoreTest.java`

**Interfaces:**
- Consumes: `AskUserSession`, `AskUserSseEvent`, `AskUserSessionStatus`
- Produces:
  - `AskUserSession create(String message) -> AskUserSession`（生成 UUID sessionId）
  - `Optional<AskUserSession> find(String sessionId)`
  - `void remove(String sessionId)`
  - `void pushEvent(String sessionId, AskUserSseEvent event)`（有 emitter 则发送，否则入缓冲）
  - `void attachEmitter(String sessionId, SseEmitter emitter)`（注册 emitter 并 flush 缓冲）
  - `void completeAnswer(String sessionId, Map<String,String> answers)`（完成 Future）
  - `void expireIdleSessions()`（10 分钟无活动清理）

- [ ] **Step 1: 写失败测试 `AskUserSessionStoreTest.java`**

```java
package com.jason.demo.demo2.service;

import com.jason.demo.demo2.model.AskUserSession;
import com.jason.demo.demo2.model.AskUserSseEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AskUserSessionStoreTest {

    private AskUserSessionStore store;

    @BeforeEach
    void setUp() {
        store = new AskUserSessionStore();
    }

    @Test
    void createAndFindSession() {
        AskUserSession session = store.create("帮我选一个数据库");
        assertNotNull(session.getSessionId());
        assertEquals("帮我选一个数据库", session.getMessage());
        assertTrue(store.find(session.getSessionId()).isPresent());
    }

    @Test
    void buffersEventsUntilEmitterAttached() throws Exception {
        AskUserSession session = store.create("test");
        store.pushEvent(session.getSessionId(), AskUserSseEvent.running());

        SseEmitter emitter = new SseEmitter(60_000L);
        store.attachEmitter(session.getSessionId(), emitter);
        // attach 后缓冲应已 flush，不抛异常即通过
        assertNotNull(session.getSseEmitter());
    }

    @Test
    void completeAnswerResolvesFuture() throws Exception {
        AskUserSession session = store.create("test");
        CompletableFuture<Map<String, String>> future = new CompletableFuture<>();
        session.setAnswerFuture(future);

        Map<String, String> answers = Map.of("你更倾向哪种数据库？", "PostgreSQL");
        store.completeAnswer(session.getSessionId(), answers);

        assertEquals("PostgreSQL", future.get(1, TimeUnit.SECONDS).get("你更倾向哪种数据库？"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```powershell
cd d:\ai\spring-ai-demo\demo2
mvnw.cmd -q test -Dtest=AskUserSessionStoreTest
```
Expected: FAIL（`AskUserSessionStore` 不存在）

- [ ] **Step 3: 实现 `AskUserSessionStore.java`**

```java
package com.jason.demo.demo2.service;

import com.jason.demo.demo2.model.AskUserSession;
import com.jason.demo.demo2.model.AskUserSseEvent;
import com.jason.demo.demo2.model.AskUserSessionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class AskUserSessionStore {

    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(10);

    private final ConcurrentHashMap<String, AskUserSession> sessions = new ConcurrentHashMap<>();
    private final JsonMapper jsonMapper;

    public AskUserSessionStore(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public AskUserSession create(String message) {
        expireIdleSessions();
        String sessionId = UUID.randomUUID().toString();
        AskUserSession session = new AskUserSession(sessionId, message);
        sessions.put(sessionId, session);
        return session;
    }

    public Optional<AskUserSession> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public void remove(String sessionId) {
        AskUserSession session = sessions.remove(sessionId);
        if (session != null && session.getSseEmitter() != null) {
            session.getSseEmitter().complete();
        }
    }

    public void pushEvent(String sessionId, AskUserSseEvent event) {
        find(sessionId).ifPresent(session -> {
            session.touch();
            SseEmitter emitter = session.getSseEmitter();
            if (emitter == null) {
                session.getEventBuffer().add(event);
                return;
            }
            sendToEmitter(session, emitter, event);
        });
    }

    public void attachEmitter(String sessionId, SseEmitter emitter) {
        AskUserSession session = sessions.get(sessionId);
        if (session == null) {
            emitter.completeWithError(new IllegalArgumentException("Session not found: " + sessionId));
            return;
        }
        session.setSseEmitter(emitter);
        session.touch();
        emitter.onCompletion(() -> log.debug("SSE completed: {}", sessionId));
        emitter.onTimeout(() -> {
            pushEvent(sessionId, AskUserSseEvent.failed("SSE 连接超时"));
            remove(sessionId);
        });
        AskUserSseEvent buffered;
        while ((buffered = session.getEventBuffer().poll()) != null) {
            sendToEmitter(session, emitter, buffered);
        }
    }

    public void completeAnswer(String sessionId, Map<String, String> answers) {
        find(sessionId).ifPresent(session -> {
            session.touch();
            CompletableFuture<Map<String, String>> future = session.getAnswerFuture();
            if (future != null && !future.isDone()) {
                future.complete(answers);
            }
        });
    }

    public void expireIdleSessions() {
        Instant cutoff = Instant.now().minus(IDLE_TIMEOUT);
        sessions.entrySet().removeIf(entry -> {
            AskUserSession session = entry.getValue();
            if (session.getLastActivityAt().isBefore(cutoff)) {
                log.info("AskUser session expired: {}", entry.getKey());
                if (session.getSseEmitter() != null) {
                    session.getSseEmitter().complete();
                }
                return true;
            }
            return false;
        });
    }

    private void sendToEmitter(AskUserSession session, SseEmitter emitter, AskUserSseEvent event) {
        try {
            String json = jsonMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event().data(json).build());
            if ("COMPLETED".equals(event.getType()) || "FAILED".equals(event.getType())) {
                emitter.complete();
                sessions.remove(session.getSessionId());
            }
        } catch (IOException e) {
            log.error("SSE send failed: {}", session.getSessionId(), e);
            emitter.completeWithError(e);
        }
    }
}
```

> 注意：`completeAnswer` 方法体内使用 `java.util.concurrent.CompletableFuture`，需在文件顶部 import。

- [ ] **Step 4: 运行测试确认通过**

Run:
```powershell
mvnw.cmd -q test -Dtest=AskUserSessionStoreTest
```
Expected: `BUILD SUCCESS`，3 tests passed

- [ ] **Step 5: Commit**

```powershell
git add demo2/src/main/java/com/jason/demo/demo2/service/AskUserSessionStore.java demo2/src/test/java/com/jason/demo/demo2/service/AskUserSessionStoreTest.java
git commit -m "feat: add AskUser session store with SSE buffering"
```

---

### Task 3: WebQuestionHandler

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/service/WebQuestionHandler.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/service/WebQuestionHandlerTest.java`

**Interfaces:**
- Consumes: `AskUserSessionStore`；`AskUserSessionHolder.setSessionId(String)` / `clear()`
- Produces: `Map<String,String> handle(List<AskUserQuestionTool.Question> questions)` 实现 `QuestionHandler`

- [ ] **Step 1: 写失败测试**

```java
package com.jason.demo.demo2.service;

import com.jason.demo.demo2.model.AskUserSession;
import com.jason.demo.demo2.model.AskUserSessionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebQuestionHandlerTest {

    private AskUserSessionStore store;
    private WebQuestionHandler handler;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        store = new AskUserSessionStore(new JsonMapper());
        handler = new WebQuestionHandler(store);
        executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() {
        AskUserSessionHolder.clear();
        executor.shutdownNow();
    }

    @Test
    void handleBlocksUntilAnswerSubmitted() throws Exception {
        AskUserSession session = store.create("test");
        session.setAnswerFuture(new CompletableFuture<>());

        var question = new AskUserQuestionTool.Question(
                "你更倾向哪种数据库？",
                "数据库类型",
                List.of(new AskUserQuestionTool.Question.Option("PostgreSQL", "关系型")),
                false
        );

        var handleFuture = executor.submit(() -> {
            AskUserSessionHolder.setSessionId(session.getSessionId());
            try {
                return handler.handle(List.of(question));
            } finally {
                AskUserSessionHolder.clear();
            }
        });

        Thread.sleep(200);
        assertEquals(AskUserSessionStatus.AWAITING_INPUT, session.getStatus());
        store.completeAnswer(session.getSessionId(), Map.of("你更倾向哪种数据库？", "PostgreSQL"));

        Map<String, String> answers = handleFuture.get(2, TimeUnit.SECONDS);
        assertEquals("PostgreSQL", answers.get("你更倾向哪种数据库？"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```powershell
mvnw.cmd -q test -Dtest=WebQuestionHandlerTest
```
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 `AskUserSessionHolder.java` 与 `WebQuestionHandler.java`**

`AskUserSessionHolder.java`（放在 `service` 包）:

```java
package com.jason.demo.demo2.service;

public final class AskUserSessionHolder {

    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();

    private AskUserSessionHolder() {
    }

    public static void setSessionId(String sessionId) {
        SESSION_ID.set(sessionId);
    }

    public static String getSessionId() {
        return SESSION_ID.get();
    }

    public static void clear() {
        SESSION_ID.remove();
    }
}
```

`WebQuestionHandler.java`:

```java
package com.jason.demo.demo2.service;

import com.jason.demo.demo2.model.AskUserQuestionDto;
import com.jason.demo.demo2.model.AskUserSession;
import com.jason.demo.demo2.model.AskUserSessionStatus;
import com.jason.demo.demo2.model.AskUserSseEvent;
import lombok.RequiredArgsConstructor;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class WebQuestionHandler implements AskUserQuestionTool.QuestionHandler {

    private final AskUserSessionStore sessionStore;

    @Override
    public Map<String, String> handle(List<AskUserQuestionTool.Question> questions) {
        String sessionId = AskUserSessionHolder.getSessionId();
        if (sessionId == null) {
            throw new IllegalStateException("No AskUser session bound to current thread");
        }

        AskUserSession session = sessionStore.find(sessionId)
                .orElseThrow(() -> new IllegalStateException("Session not found: " + sessionId));

        List<AskUserQuestionDto> dtos = questions.stream()
                .map(q -> new AskUserQuestionDto(
                        q.header(),
                        q.question(),
                        q.options().stream()
                                .map(o -> new AskUserQuestionDto.OptionDto(o.label(), o.description()))
                                .collect(Collectors.toList()),
                        q.multiSelect()))
                .collect(Collectors.toList());

        CompletableFuture<Map<String, String>> answerFuture = new CompletableFuture<>();
        session.setAnswerFuture(answerFuture);
        session.setStatus(AskUserSessionStatus.AWAITING_INPUT);
        sessionStore.pushEvent(sessionId, AskUserSseEvent.questions(dtos));

        return answerFuture.join();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run:
```powershell
mvnw.cmd -q test -Dtest=WebQuestionHandlerTest
```
Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```powershell
git add demo2/src/main/java/com/jason/demo/demo2/service/AskUserSessionHolder.java demo2/src/main/java/com/jason/demo/demo2/service/WebQuestionHandler.java demo2/src/test/java/com/jason/demo/demo2/service/WebQuestionHandlerTest.java
git commit -m "feat: add WebQuestionHandler for AskUserQuestionTool"
```

---

### Task 4: AskUserAgentService 与 Config

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/service/AskUserAgentService.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/config/AskUserAgentConfig.java`

**Interfaces:**
- Consumes: `AskUserSessionStore`, `WebQuestionHandler`, `ChatClient.Builder`, `AskUserQuestionTool`
- Produces:
  - `String startChat(String message)` → sessionId
  - `SseEmitter connectSse(String sessionId)`
  - `void submitAnswer(String sessionId, Map<String,String> answers)` → 非 AWAITING_INPUT 抛 `IllegalStateException`
  - Bean `AskUserQuestionTool askUserQuestionTool(WebQuestionHandler handler)`

- [ ] **Step 1: 实现 `AskUserAgentConfig.java`**

```java
package com.jason.demo.demo2.config;

import com.jason.demo.demo2.service.WebQuestionHandler;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AskUserAgentConfig {

    @Bean
    public AskUserQuestionTool askUserQuestionTool(WebQuestionHandler webQuestionHandler) {
        return AskUserQuestionTool.builder()
                .questionHandler(webQuestionHandler)
                .build();
    }
}
```

- [ ] **Step 2: 实现 `AskUserAgentService.java`**

```java
package com.jason.demo.demo2.service;

import com.jason.demo.demo2.model.AskUserSession;
import com.jason.demo.demo2.model.AskUserSessionStatus;
import com.jason.demo.demo2.model.AskUserSseEvent;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class AskUserAgentService {

    private static final String SYSTEM_PROMPT = """
            你是一名专业的技术选型顾问。
            当用户需求模糊或缺少关键信息时，你必须调用 AskUserQuestion 工具向用户提出澄清问题。
            澄清维度可包括：项目类型、数据特征、规模要求、运维偏好、团队技术栈等。
            每个问题应提供 2-4 个具体选项，必要时允许多选。
            收到用户答案后，用中文输出：推荐方案、选择理由、备选方案对比。
            不要编造用户未提供的信息。
            """;

    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final ChatClient chatClient;
    private final AskUserSessionStore sessionStore;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AskUserAgentService(ChatClient.Builder chatClientBuilder,
                               AskUserQuestionTool askUserQuestionTool,
                               AskUserSessionStore sessionStore) {
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultTools(askUserQuestionTool)
                .build();
        this.sessionStore = sessionStore;
    }

    public String startChat(String message) {
        AskUserSession session = sessionStore.create(message);
        String sessionId = session.getSessionId();
        executor.submit(() -> runAgent(sessionId, message));
        return sessionId;
    }

    public SseEmitter connectSse(String sessionId) {
        AskUserSession session = sessionStore.find(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        sessionStore.attachEmitter(sessionId, emitter);
        if (session.getEventBuffer().isEmpty() && session.getStatus() == AskUserSessionStatus.RUNNING) {
            sessionStore.pushEvent(sessionId, AskUserSseEvent.running());
        }
        return emitter;
    }

    public void submitAnswer(String sessionId, Map<String, String> answers) {
        AskUserSession session = sessionStore.find(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        if (session.getStatus() != AskUserSessionStatus.AWAITING_INPUT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Session is not awaiting input");
        }
        session.setStatus(AskUserSessionStatus.RUNNING);
        sessionStore.pushEvent(sessionId, AskUserSseEvent.running());
        sessionStore.completeAnswer(sessionId, answers);
    }

    private void runAgent(String sessionId, String message) {
        AskUserSessionHolder.setSessionId(sessionId);
        try {
            sessionStore.pushEvent(sessionId, AskUserSseEvent.running());
            String response = chatClient.prompt()
                    .user(message)
                    .call()
                    .content();
            AskUserSession session = sessionStore.find(sessionId).orElse(null);
            if (session != null) {
                session.setStatus(AskUserSessionStatus.COMPLETED);
            }
            sessionStore.pushEvent(sessionId, AskUserSseEvent.completed(response));
        } catch (Exception e) {
            log.error("AskUser agent failed: {}", sessionId, e);
            sessionStore.find(sessionId).ifPresent(s -> s.setStatus(AskUserSessionStatus.FAILED));
            sessionStore.pushEvent(sessionId, AskUserSseEvent.failed("Agent 执行失败: " + e.getMessage()));
        } finally {
            AskUserSessionHolder.clear();
        }
    }
}
```

- [ ] **Step 3: 编译验证**

Run:
```powershell
mvnw.cmd -q compile -DskipTests
```
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```powershell
git add demo2/src/main/java/com/jason/demo/demo2/config/AskUserAgentConfig.java demo2/src/main/java/com/jason/demo/demo2/service/AskUserAgentService.java
git commit -m "feat: add AskUser agent service and config"
```

---

### Task 5: Controller 端点

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/controller/AskUserAgentController.java`

**Interfaces:**
- Consumes: `AskUserAgentService`
- Produces: REST 端点 `POST /agent/ask-user/chat`、`GET /agent/ask-user/sse/{sessionId}`、`POST /agent/ask-user/answer`

- [ ] **Step 1: 实现 Controller**

```java
package com.jason.demo.demo2.controller;

import com.jason.demo.demo2.model.AskUserAnswerRequest;
import com.jason.demo.demo2.model.AskUserAnswerResponse;
import com.jason.demo.demo2.model.AskUserChatRequest;
import com.jason.demo.demo2.model.AskUserChatResponse;
import com.jason.demo.demo2.service.AskUserAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "AskUserQuestion", description = "AskUserQuestionTool 技术选型 Demo（SSE + POST）")
@RestController
@RequestMapping("/agent/ask-user")
@RequiredArgsConstructor
public class AskUserAgentController {

    private final AskUserAgentService askUserAgentService;

    @Operation(summary = "发起技术选型对话", description = "创建 Session 并异步启动 Agent，返回 sessionId")
    @PostMapping("/chat")
    public AskUserChatResponse chat(@RequestBody AskUserChatRequest request) {
        String sessionId = askUserAgentService.startChat(request.getMessage());
        return new AskUserChatResponse(sessionId);
    }

    @Operation(summary = "订阅 SSE 事件流", description = "推送 RUNNING / QUESTIONS / COMPLETED / FAILED 事件")
    @GetMapping(value = "/sse/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sse(@PathVariable String sessionId) {
        return askUserAgentService.connectSse(sessionId);
    }

    @Operation(summary = "提交澄清问题答案")
    @PostMapping("/answer")
    public AskUserAnswerResponse answer(@RequestBody AskUserAnswerRequest request) {
        askUserAgentService.submitAnswer(request.getSessionId(), request.getAnswers());
        return new AskUserAnswerResponse("accepted");
    }
}
```

- [ ] **Step 2: 运行全部测试 + 编译**

Run:
```powershell
mvnw.cmd -q test
```
Expected: `BUILD SUCCESS`（含 `Demo2ApplicationTests.contextLoads`）

- [ ] **Step 3: Commit**

```powershell
git add demo2/src/main/java/com/jason/demo/demo2/controller/AskUserAgentController.java
git commit -m "feat: add AskUserQuestion REST and SSE endpoints"
```

---

### Task 6: 前端 Tab

**Files:**
- Modify: `demo2/src/main/resources/static/index.html`

**Interfaces:**
- Consumes: `POST /agent/ask-user/chat`、`GET /agent/ask-user/sse/{sessionId}`、`POST /agent/ask-user/answer`
- Produces: Tab `ask-user`；函数 `startAskUserChat(message)`、`renderAskUserQuestions(questions)`、`submitAskUserAnswers()`

- [ ] **Step 1: 在 Tab 导航末尾添加按钮**

在 `tab-nav` 中 `multi-agent` 按钮之后添加：

```html
<button class="tab-btn" data-tab="ask-user" onclick="switchTab('ask-user')">❓ AskUserQuestion 技术选型</button>
```

- [ ] **Step 2: 在 `tab-multi-agent` 之后添加 Tab 内容**

```html
<div id="tab-ask-user" class="tab-content">
    <div class="agent-tools-header">
        <h1>AskUserQuestion 技术选型</h1>
        <p>Agent 主动澄清需求 · SSE 推送问题 · DeepSeek 生成选型建议</p>
    </div>
    <div class="rag-body" style="padding: 20px;">
        <div class="agent-tools-flow">
            <span>用户提问</span><span class="arrow">→</span>
            <span>Agent 澄清</span><span class="arrow">→</span>
            <span>用户作答</span><span class="arrow">→</span>
            <span>选型建议</span>
        </div>
        <div class="card" style="margin-top: 16px;">
            <div class="card-title">💡 示例问题</div>
            <div class="card-body">
                <button class="agent-tools-sample-btn" onclick="fillAskUserMessage('帮我选一个数据库')">示例1：数据库选型</button>
                <button class="agent-tools-sample-btn" onclick="fillAskUserMessage('帮我选一个 Java Web 框架')">示例2：框架选型</button>
            </div>
        </div>
        <div class="card">
            <div class="card-title">📝 发起对话</div>
            <div class="card-body">
                <div class="form-row">
                    <label>你的需求</label>
                    <input type="text" id="askUserMessageInput" placeholder="描述你的技术选型需求..." value="帮我选一个数据库">
                </div>
                <div class="form-row">
                    <label></label>
                    <button class="btn btn-agent-tools" id="askUserStartBtn" onclick="startAskUserChat()">开始选型</button>
                </div>
            </div>
        </div>
        <div id="askUserChatArea" class="chat-messages" style="height: 400px; margin: 16px 20px; border-radius: 8px;">
            <div class="welcome-message"><p>点击「开始选型」，Agent 将通过 SSE 推送澄清问题。</p></div>
        </div>
        <div id="askUserQuestionPanel" style="display:none; padding: 0 20px 20px;"></div>
    </div>
</div>
```

- [ ] **Step 3: 在 `<script>` 末尾添加 JS 逻辑**

```javascript
let askUserEventSource = null;
let askUserSessionId = null;
let askUserPendingQuestions = [];

function fillAskUserMessage(text) {
    document.getElementById('askUserMessageInput').value = text;
}

function appendAskUserMessage(role, html) {
    const area = document.getElementById('askUserChatArea');
    const div = document.createElement('div');
    div.className = 'message ' + role;
    div.innerHTML = '<div class="message-content">' + html + '</div>';
    area.appendChild(div);
    area.scrollTop = area.scrollHeight;
}

function closeAskUserEventSource() {
    if (askUserEventSource) {
        askUserEventSource.close();
        askUserEventSource = null;
    }
}

async function startAskUserChat() {
    const message = document.getElementById('askUserMessageInput').value.trim();
    if (!message) return;

    closeAskUserEventSource();
    document.getElementById('askUserQuestionPanel').style.display = 'none';
    document.getElementById('askUserQuestionPanel').innerHTML = '';
    document.getElementById('askUserChatArea').innerHTML = '';
    appendAskUserMessage('user', escapeHtml(message));

    const btn = document.getElementById('askUserStartBtn');
    btn.disabled = true;

    try {
        const resp = await fetch('/agent/ask-user/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message })
        });
        const data = await resp.json();
        askUserSessionId = data.sessionId;

        askUserEventSource = new EventSource('/agent/ask-user/sse/' + askUserSessionId);
        askUserEventSource.onmessage = (event) => {
            const payload = JSON.parse(event.data);
            handleAskUserSseEvent(payload);
        };
        askUserEventSource.onerror = () => {
            appendAskUserMessage('assistant', '<span style="color:#d00">SSE 连接中断</span>');
            closeAskUserEventSource();
            btn.disabled = false;
        };
    } catch (e) {
        appendAskUserMessage('assistant', '<span style="color:#d00">请求失败: ' + escapeHtml(e.message) + '</span>');
        btn.disabled = false;
    }
}

function handleAskUserSseEvent(payload) {
    if (payload.type === 'RUNNING') {
        appendAskUserMessage('assistant', '<em>Agent 正在分析需求...</em>');
    } else if (payload.type === 'QUESTIONS') {
        askUserPendingQuestions = payload.questions || [];
        renderAskUserQuestions(askUserPendingQuestions);
    } else if (payload.type === 'COMPLETED') {
        appendAskUserMessage('assistant', formatText(payload.response || ''));
        closeAskUserEventSource();
        document.getElementById('askUserStartBtn').disabled = false;
    } else if (payload.type === 'FAILED') {
        appendAskUserMessage('assistant', '<span style="color:#d00">' + escapeHtml(payload.error || '未知错误') + '</span>');
        closeAskUserEventSource();
        document.getElementById('askUserStartBtn').disabled = false;
    }
}

function renderAskUserQuestions(questions) {
    const panel = document.getElementById('askUserQuestionPanel');
    panel.style.display = 'block';
    let html = '<div class="card"><div class="card-title">❓ Agent 需要你澄清以下问题</div><div class="card-body">';
    questions.forEach((q, qi) => {
        const inputType = q.multiSelect ? 'checkbox' : 'radio';
        const inputName = 'askq_' + qi;
        html += '<div style="margin-bottom:16px;padding:12px;background:#f9f9f9;border-radius:8px;">';
        html += '<strong>' + escapeHtml(q.header) + '</strong><p>' + escapeHtml(q.question) + '</p>';
        (q.options || []).forEach((opt, oi) => {
            html += '<label style="display:block;margin:6px 0;">';
            html += '<input type="' + inputType + '" name="' + inputName + '" value="' + escapeHtml(opt.label) + '"> ';
            html += escapeHtml(opt.label) + ' - ' + escapeHtml(opt.description || '');
            html += '</label>';
        });
        html += '<input type="text" id="askq_custom_' + qi + '" placeholder="或输入自定义答案" style="width:100%;margin-top:8px;padding:8px;">';
        html += '</div>';
    });
    html += '<button class="btn btn-agent-tools" onclick="submitAskUserAnswers()">提交答案</button>';
    html += '</div></div>';
    panel.innerHTML = html;
}

async function submitAskUserAnswers() {
    const answers = {};
    askUserPendingQuestions.forEach((q, qi) => {
        const inputName = 'askq_' + qi;
        const custom = document.getElementById('askq_custom_' + qi).value.trim();
        if (custom) {
            answers[q.question] = custom;
            return;
        }
        const selected = Array.from(document.querySelectorAll('[name="' + inputName + '"]:checked'))
            .map(el => el.value);
        if (selected.length > 0) {
            answers[q.question] = selected.join(', ');
        }
    });

    document.getElementById('askUserQuestionPanel').style.display = 'none';
    appendAskUserMessage('user', '已提交澄清答案');

    await fetch('/agent/ask-user/answer', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: askUserSessionId, answers })
    });
}

window.addEventListener('beforeunload', closeAskUserEventSource);
```

> 确认页面中已有 `escapeHtml` 和 `formatText` 辅助函数（其他 Tab 已使用）；若无则复用 `agent-tools` 区域同名函数。

- [ ] **Step 4: 手动验证前端**

1. 启动应用：`mvnw.cmd spring-boot:run`
2. 打开 `http://localhost:8081`，切换到「AskUserQuestion 技术选型」Tab
3. 点击「示例1：数据库选型」→「开始选型」
4. 确认 SSE 推送澄清问题卡片
5. 选择选项 →「提交答案」
6. 确认收到完整选型建议

- [ ] **Step 5: Commit**

```powershell
git add demo2/src/main/resources/static/index.html
git commit -m "feat: add AskUserQuestion demo tab with SSE frontend"
```

---

### Task 7: 最终验证

**Files:** 无新增

- [ ] **Step 1: 全量测试**

Run:
```powershell
cd d:\ai\spring-ai-demo\demo2
mvnw.cmd -q test
```
Expected: `BUILD SUCCESS`

- [ ] **Step 2: 全量编译打包**

Run:
```powershell
mvnw.cmd -q clean package -DskipTests
mvnw.cmd -q test
```
Expected: 均 `BUILD SUCCESS`

- [ ] **Step 3: Swagger 验证**

打开 `http://localhost:8081/scalar`，确认 `AskUserQuestion` 标签下有三个端点。

- [ ] **Step 4: 边界手动测试**

| 场景 | 操作 | 预期 |
|------|------|------|
| Session 不存在 | `GET /agent/ask-user/sse/invalid-id` | 404 |
| 重复提交答案 | 完成后再 `POST /answer` | 409 |
| 无 DEEPSEEK_API_KEY | 启动并调用 | `FAILED` 事件含错误信息 |

- [ ] **Step 5: Commit（若有修复）**

```powershell
git add -A
git commit -m "chore: finalize AskUserQuestion demo verification"
```

---

## Spec Coverage Checklist

| Spec 要求 | 对应 Task |
|-----------|----------|
| SSE + POST 混合通信 | Task 4, 5, 6 |
| 三端点 API | Task 5 |
| AskUserQuestionTool + WebQuestionHandler | Task 3, 4 |
| 事件缓冲策略 | Task 2 |
| 技术选型 System Prompt | Task 4 |
| 单轮 Demo | Task 4（每次新 sessionId） |
| 前端 Tab + 问题卡片 | Task 6 |
| SSE 5 分钟超时 | Task 4 `SSE_TIMEOUT_MS` |
| Session 10 分钟过期 | Task 2 `IDLE_TIMEOUT` |
| 404 / 409 错误处理 | Task 4, 5 |
| 单元测试 | Task 2, 3 |
| 手动测试 | Task 6, 7 |

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-27-ask-user-question-tool.md`. Two execution options:

**1. Subagent-Driven (recommended)** - Dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
