# TodoWriteTool Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]` / `- [ ]`) syntax for tracking.

**Goal:** 在 demo2 中实现 TodoWriteTool 学习计划 Demo，并抽取通用 SSE 基础设施供 AskUser / TodoWrite 共用：SSE 推送 Todo 进度，前端 Tab 实时看板 + 最终计划。

**Architecture:** 新增 `sse` 包（`AgentSseSessionStore` + `AbstractSseAgentService` + `AgentSseEvent`）；AskUser 迁移到通用层；`TodoWriteTool.todoEventHandler` 经 `AgentSessionHolder` 桥接推送 `TODOS` 事件；`POST /agent/todo/chat` + `GET /agent/todo/sse/{sessionId}` 双端点；前端 `index.html` 新 Tab。

**Tech Stack:** Java 21, Spring Boot 4.1, Spring AI 2.0.0, spring-ai-agent-utils 0.10.0, SseEmitter, Lombok, JUnit 5

**设计规范:** [docs/superpowers/specs/2026-06-29-todo-write-tool-design.md](../specs/2026-06-29-todo-write-tool-design.md)

## Global Constraints

- 不新增 Maven 依赖；使用已有 `spring-ai-agent-utils:0.10.0`
- 服务端口 **8081**；虚拟线程已启用（`spring.threads.virtual.enabled=true`）
- 单轮 Demo：无 `POST /answer`；Session 仅内存存储
- SSE 超时 **5 分钟**；Session 空闲过期 **10 分钟**
- TodoWrite `ChatClient` 仅注册 `TodoWriteTool`，不叠加其他工具
- API 前缀：`/agent/todo`；AskUser 前缀不变：`/agent/ask-user`
- 使用中文 System Prompt 与前端文案

---

## File Structure

| 文件 | 职责 |
|------|------|
| `sse/AgentSessionStatus.java` | 通用 Session 状态枚举 |
| `sse/AgentSseSession.java` | 通用 Session 实体（含 answerFuture 供 AskUser） |
| `sse/AgentSseEvent.java` | 统一 SSE 事件（RUNNING / QUESTIONS / TODOS / COMPLETED / FAILED） |
| `sse/AgentSseSessionStore.java` | 内存 Session、事件缓冲 flush、completeAnswer |
| `sse/AgentSessionHolder.java` | ThreadLocal sessionId |
| `sse/AbstractSseAgentService.java` | startChat / connectSse / runWithSession 模板 |
| `model/TodoItemDto.java` | Todo 看板元素 |
| `model/TodoProgressDto.java` | 进度摘要 |
| `model/TodoChatRequest.java` | POST /chat 请求体 |
| `model/TodoChatResponse.java` | POST /chat 响应体 |
| `config/TodoAgentConfig.java` | TodoWriteTool Bean + todoEventHandler |
| `service/TodoAgentService.java` | 学习计划 Agent（继承 AbstractSseAgentService） |
| `controller/TodoAgentController.java` | REST + SSE 端点 |
| `service/AskUserAgentService.java` | 重构：继承 AbstractSseAgentService |
| `service/WebQuestionHandler.java` | 重构：使用 AgentSseSessionStore |
| `test/sse/AgentSseSessionStoreTest.java` | 通用 Session 存储测试 |
| `test/.../WebQuestionHandlerTest.java` | Handler 阻塞/唤醒测试（更新依赖） |
| `static/index.html` | TodoWrite Tab + JS |
| `README.md` | TodoWrite 章节 + AskUser 引用更新 |

**删除（AskUser 专用 SSE 类，由 sse 包替代）：**

- `model/AskUserSession.java`
- `model/AskUserSessionStatus.java`
- `model/AskUserSseEvent.java`
- `service/AskUserSessionStore.java`
- `service/AskUserSessionHolder.java`
- `test/.../AskUserSessionStoreTest.java`

---

### Task 1: 通用 SSE 基础设施

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/sse/AgentSessionStatus.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/sse/AgentSseSession.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/sse/AgentSseEvent.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/sse/AgentSessionHolder.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/sse/AgentSseSessionStore.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/sse/AbstractSseAgentService.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/sse/AgentSseSessionStoreTest.java`

**Interfaces:**
- Consumes: `JsonMapper`（Spring Boot 注入）
- Produces: `AgentSseSessionStore.create(message)` → `AgentSseSession`；`pushEvent(sessionId, AgentSseEvent)`；`attachEmitter(sessionId, emitter)`；`completeAnswer(sessionId, answers)`；`AbstractSseAgentService.startChat(message)` → `sessionId`；`connectSse(sessionId)` → `SseEmitter`；`runWithSession(sessionId, Supplier<String>)`

- [x] **Step 1: 创建 `AgentSessionStatus` 枚举**

```java
package com.jason.demo.demo2.sse;

public enum AgentSessionStatus {
    RUNNING,
    AWAITING_INPUT,
    COMPLETED,
    FAILED
}
```

- [x] **Step 2: 创建 `AgentSseSession`**

含 `sessionId`、`message`、`eventBuffer`、`status`、`sseEmitter`、`answerFuture`、`lastActivityAt`。

- [x] **Step 3: 创建 `AgentSseEvent`**

工厂方法：`running()`、`questions(List<AskUserQuestionDto>)`、`todos(TodoWriteTool.Todos)`、`completed(String)`、`failed(String)`。使用 `@JsonInclude(NON_NULL)` 避免多余字段。

- [x] **Step 4: 创建 `AgentSessionHolder`**

ThreadLocal 传递 `sessionId`：`setSessionId` / `getSessionId` / `clear`。

- [x] **Step 5: 创建 `AgentSseSessionStore`**

对齐原 `AskUserSessionStore` 逻辑：create、find、remove、pushEvent、attachEmitter（含缓冲 flush）、completeAnswer、expireIdleSessions（10 分钟）。

- [x] **Step 6: 创建 `AbstractSseAgentService`**

`startChat` 创建 Session 并虚拟线程异步执行；`connectSse` 注册 emitter；`runWithSession` 设置 Holder、推送 RUNNING/COMPLETED/FAILED。

- [x] **Step 7: 写 `AgentSseSessionStoreTest`**

覆盖：createAndFindSession、buffersEventsUntilEmitterAttached、completeAnswerResolvesFuture。

- [x] **Step 8: 编译与测试**

Run: `cd demo2 && mvn -q compile test "-Dtest=AgentSseSessionStoreTest"`
Expected: BUILD SUCCESS, 3 tests pass

- [x] **Step 9: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/sse/ demo2/src/test/java/com/jason/demo/demo2/sse/
git commit -m "refactor: extract shared Agent SSE session infrastructure"
```

---

### Task 2: 重构 AskUser 迁移到通用 SSE 层

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/service/AskUserAgentService.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/service/WebQuestionHandler.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/service/WebQuestionHandlerTest.java`
- Delete: `AskUserSession.java`, `AskUserSessionStatus.java`, `AskUserSseEvent.java`, `AskUserSessionStore.java`, `AskUserSessionHolder.java`, `AskUserSessionStoreTest.java`

**Interfaces:**
- Consumes: `AgentSseSessionStore`, `AgentSessionHolder`, `AgentSseEvent`, `AgentSessionStatus`, `AbstractSseAgentService`
- Produces: `AskUserAgentService extends AbstractSseAgentService`；保留 `submitAnswer(sessionId, answers)`；`WebQuestionHandler` 使用 `AgentSseEvent.questions(dtos)`

- [x] **Step 1: 重构 `WebQuestionHandler`**

将 `AskUserSessionHolder` → `AgentSessionHolder`，`AskUserSessionStore` → `AgentSseSessionStore`，`AskUserSseEvent` → `AgentSseEvent`，`AskUserSessionStatus` → `AgentSessionStatus`。

- [x] **Step 2: 重构 `AskUserAgentService`**

继承 `AbstractSseAgentService`；构造函数注入 `AgentSseSessionStore`；`runAgent` 调用 `runWithSession`；保留 `submitAnswer` 业务逻辑。

- [x] **Step 3: 更新 `WebQuestionHandlerTest`**

使用 `AgentSseSessionStore` 与 `AgentSessionHolder`。

- [x] **Step 4: 删除旧 AskUser SSE 类**

- [x] **Step 5: 编译与测试**

Run: `cd demo2 && mvn -q compile test "-Dtest=AgentSseSessionStoreTest,WebQuestionHandlerTest"`
Expected: BUILD SUCCESS, 4 tests pass

- [x] **Step 6: Commit**

```bash
git add -A demo2/src/main/java/com/jason/demo/demo2/service/ demo2/src/test/
git commit -m "refactor: migrate AskUser agent to shared SSE infrastructure"
```

---

### Task 3: TodoWrite 后端

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/TodoItemDto.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/TodoProgressDto.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/TodoChatRequest.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/TodoChatResponse.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/config/TodoAgentConfig.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/service/TodoAgentService.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/controller/TodoAgentController.java`

**Interfaces:**
- Consumes: `AgentSseSessionStore`, `AgentSessionHolder`, `AgentSseEvent`, `AbstractSseAgentService`, `TodoWriteTool`
- Produces: `POST /agent/todo/chat` → `{ sessionId }`；`GET /agent/todo/sse/{sessionId}` → SSE 流

- [x] **Step 1: 创建 Todo DTO**

`TodoItemDto`（content, status, activeForm）；`TodoProgressDto`（completed, total, percent）；`TodoChatRequest` / `TodoChatResponse`。

- [x] **Step 2: 创建 `TodoAgentConfig`**

```java
@Bean
public TodoWriteTool todoWriteTool(AgentSseSessionStore sessionStore) {
    return TodoWriteTool.builder()
            .todoEventHandler(todos -> {
                String sessionId = AgentSessionHolder.getSessionId();
                if (sessionId != null) {
                    sessionStore.pushEvent(sessionId, AgentSseEvent.todos(todos));
                }
            })
            .build();
}
```

- [x] **Step 3: 创建 `TodoAgentService`**

继承 `AbstractSseAgentService`；System Prompt 引导使用 TodoWrite 拆解学习计划；`ChatClient` 仅注册 `todoWriteTool`。

- [x] **Step 4: 创建 `TodoAgentController`**

`POST /agent/todo/chat`、`GET /agent/todo/sse/{sessionId}`；Swagger `@Tag(name = "TodoWrite")`。

- [x] **Step 5: 编译**

Run: `cd demo2 && mvn -q compile`
Expected: BUILD SUCCESS

- [x] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/model/Todo*.java \
        demo2/src/main/java/com/jason/demo/demo2/config/TodoAgentConfig.java \
        demo2/src/main/java/com/jason/demo/demo2/service/TodoAgentService.java \
        demo2/src/main/java/com/jason/demo/demo2/controller/TodoAgentController.java
git commit -m "feat: add TodoWriteTool learning plan agent with SSE endpoints"
```

---

### Task 4: 前端 TodoWrite Tab

**Files:**
- Modify: `demo2/src/main/resources/static/index.html`

- [x] **Step 1: 新增 Tab 按钮**

```html
<button class="tab-btn" data-tab="todo-write" onclick="switchTab('todo-write')">📋 TodoWrite 学习计划</button>
```

- [x] **Step 2: 新增 Tab 内容区**

含流程说明、两个示例按钮、输入框、`todoBoard`（进度 + 列表）、`todoResult` 回复区。

- [x] **Step 3: 新增 JS 逻辑**

`startTodoChat()` → `POST /agent/todo/chat` → `EventSource /agent/todo/sse/{sessionId}`；`handleTodoSseEvent` 分支 RUNNING / TODOS / COMPLETED / FAILED；`renderTodoBoard` 用 `[ ]` / `[→]` / `[✓]` 图标。

- [x] **Step 4: Commit**

```bash
git add demo2/src/main/resources/static/index.html
git commit -m "feat: add TodoWrite tab with SSE todo board UI"
```

---

### Task 5: 文档

**Files:**
- Modify: `demo2/README.md`
- Modify: `demo2/docs/superpowers/specs/2026-06-29-todo-write-tool-design.md`

- [x] **Step 1: README 新增 §12 TodoWrite**

端点表、SSE 示例、核心类表、前端 Tab 入口、设计文档链接；AskUser 核心类引用更新为 `AgentSseSessionStore`。

- [x] **Step 2: 设计 spec 更新为 A+ 方案**

反映 sse 包重构后的组件与文件清单。

- [x] **Step 3: Commit**

```bash
git add demo2/README.md demo2/docs/superpowers/specs/2026-06-29-todo-write-tool-design.md
git commit -m "docs: add TodoWrite demo section and update design spec for SSE refactor"
```

---

### Task 6: 端到端验证

- [x] **Step 1: 全量单元测试**

Run: `cd demo2 && mvn -q test "-Dtest=AgentSseSessionStoreTest,WebQuestionHandlerTest"`
Expected: BUILD SUCCESS, 4 tests pass

- [x] **Step 2: 启动应用**

Run: `cd demo2 && mvn spring-boot:run`
Expected: 应用在 **8081** 启动，无 Bean 冲突

- [x] **Step 3: API 冒烟测试**

```bash
# 创建 Session
curl -s -X POST http://localhost:8081/agent/todo/chat \
  -H "Content-Type: application/json" \
  -d "{\"message\":\"帮我制定一个 3 天 Spring AI 学习计划\"}"

# 订阅 SSE（替换 {sessionId}）
curl -s -N http://localhost:8081/agent/todo/sse/{sessionId}
```

Expected: 收到 `RUNNING`；若 `DEEPSEEK_API_KEY` 已配置则后续有 `TODOS` 和 `COMPLETED`；未配置则 `FAILED` 含可读错误。

- [x] **Step 4: AskUser 回归冒烟**

```bash
curl -s -X POST http://localhost:8081/agent/ask-user/chat \
  -H "Content-Type: application/json" \
  -d "{\"message\":\"帮我选一个数据库\"}"
```

Expected: 返回 `sessionId`；SSE 连接可收到 `RUNNING`。

- [x] **Step 5: 最终 Commit（若有修复）**

```bash
git add -A
git commit -m "fix: address TodoWrite demo e2e issues"
```

仅在有修复时执行。

---

## 依赖关系

```
Task 1 (sse 包) → Task 2 (AskUser 迁移)
Task 1 → Task 3 (TodoWrite 后端)
Task 3 → Task 4 (前端)
Task 4 → Task 5 (文档)
Task 5 → Task 6 (E2E)
```

Task 1 必须先完成；Task 2 与 Task 3 可并行（均依赖 Task 1）。

---

## Spec 覆盖自检

| Spec 要求 | 对应 Task |
|-----------|-----------|
| 仅 TodoWriteTool | Task 3 |
| SSE 实时 TODOS | Task 1 + 3 + 4 |
| 学习计划场景 | Task 3 System Prompt + 4 示例按钮 |
| 无 POST /answer | Task 3（仅 chat + sse） |
| 通用 sse 包共用 | Task 1 + 2 |
| mvn compile 通过 | Task 6 Step 1 |
| Swagger 两个端点 | Task 3 |
| 前端 Tab | Task 4 |
| README | Task 5 |
