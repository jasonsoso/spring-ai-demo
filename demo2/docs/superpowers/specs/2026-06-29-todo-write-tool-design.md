# TodoWriteTool Demo 设计规范

**日期**: 2026-06-29  
**项目**: spring-ai-demo / demo2  
**状态**: 待审阅

---

## 1. 背景与目标

### 1.1 需求

在 `demo2` 中新增 **TodoWriteTool** 演示：当 AI Agent 面对多步骤任务时，主动创建并更新待办列表（`pending` / `in_progress` / `completed`），前端通过 SSE 实时展示任务看板，最终输出完整学习计划。

参考：

- [spring-ai-agent-utils todo-demo Application.java](https://github.com/spring-ai-community/spring-ai-agent-utils/blob/main/examples/todo-demo/src/main/java/org/springaicommunity/agent/Application.java)

### 1.2 已确认决策

| 维度 | 选择 |
|------|------|
| 工具范围 | **仅 TodoWriteTool**（不叠加 BraveWebSearchTool 或项目内置 WeatherTool 等） |
| 前端展示 | **SSE 实时推送** Todo 进度 |
| 业务场景 | **学习计划拆解**（如「7 天 Spring AI 2.0 学习计划」） |
| 交互方式 | `static/index.html` 新增 Tab |
| 会话模式 | 单轮 Demo（发起 → 观察 Todo 进度 → 收到最终计划，无需用户二次提交） |
| 通信方案 | **SSE + HTTP POST**（对齐现有 `AskUserAgent` 模式，但无 `POST /answer` 端点） |
| 实现方案 | **方案 A**：独立 `TodoSessionStore` + `TodoAgentService`，不复用 AskUser 代码、不抽象泛型基类 |

### 1.3 依赖

项目已引入 `spring-ai-agent-utils:0.10.0`，其中包含：

- `org.springaicommunity.agent.tools.TodoWriteTool`
- `org.springaicommunity.agent.tools.TodoWriteTool.Todos`
- `org.springaicommunity.agent.tools.TodoWriteTool.Todos.TodoItem`
- `org.springaicommunity.agent.tools.TodoWriteTool.Todos.Status`
- `org.springaicommunity.agent.tools.TodoWriteTool.TodoEventHandler`

无需新增 Maven 依赖。

### 1.4 成功标准

1. `mvn compile` 编译通过
2. 用户在 `index.html` 新 Tab 发起学习计划请求
3. Agent 调用 `TodoWriteTool` 时，前端通过 SSE 实时刷新 Todo 看板
4. 执行完成后 SSE 推送完整学习计划
5. Swagger 可查看两个 REST 端点文档

### 1.5 不在范围

- `BraveWebSearchTool`、WeatherTool、SkillsTool 等工具叠加
- 多轮持续对话、Session 持久化
- WebSocket 实现
- 生产级鉴权、限流、分布式 Session
- 单元测试（实现阶段按需补充）

---

## 2. 架构设计

### 2.1 通信模型

| 方向 | 协议 | 用途 |
|------|------|------|
| 服务端 → 客户端 | SSE (`text/event-stream`) | 推送运行状态、Todo 列表更新、最终结果、错误 |
| 客户端 → 服务端 | HTTP POST | 发起对话 |

浏览器 `EventSource` 仅支持 GET，因此采用「POST 发起 + GET 订阅 SSE」双端点模式。

### 2.2 时序

```
用户输入消息
    → POST /agent/todo/chat（创建 session，异步启动 Agent）
    → 返回 { sessionId }
    → GET /agent/todo/sse/{sessionId}（EventSource 连接）
    → SSE: { type: "RUNNING" }
    → Agent 调用 TodoWriteTool
    → todoEventHandler → pushEvent: { type: "TODOS", todos: [...], progress: {...} }
    → （Agent 继续执行，多次推送 TODOS）
    → SSE: { type: "COMPLETED", response: "..." }
    → 前端关闭 EventSource
```

与 AskUser 的区别：TodoWrite 不需要用户中途提交答案，因此**无 `POST /answer` 端点**。

### 2.3 核心组件

| 组件 | 包路径（建议） | 职责 |
|------|---------------|------|
| `TodoSession` | `model` | Session 数据：sessionId、用户消息、状态、SseEmitter、事件缓冲队列 |
| `TodoSessionStatus` | `model` | 枚举：`RUNNING` / `COMPLETED` / `FAILED` |
| `TodoSseEvent` | `model` | SSE 事件 DTO |
| `TodoItemDto` | `model` | Todo 列表元素 DTO（content、status、activeForm） |
| `TodoProgressDto` | `model` | 进度摘要（completed、total、percent） |
| `TodoSessionStore` | `service` | 内存 ConcurrentHashMap 管理 Session 生命周期、事件缓冲与 flush |
| `TodoSessionHolder` | `service` | ThreadLocal 传递当前 sessionId（对齐 `AskUserSessionHolder`） |
| `TodoAgentConfig` | `config` | 注册 `TodoWriteTool` Bean，注入 `todoEventHandler` |
| `TodoAgentService` | `service` | 编排 ChatClient + 异步 Agent 执行、SSE 连接注册 |
| `TodoAgentController` | `controller` | 暴露 REST + SSE 端点，Swagger 注解 |

### 2.4 TodoEventHandler 桥接

```java
TodoWriteTool.builder()
    .todoEventHandler(todos -> {
        String sessionId = TodoSessionHolder.getSessionId();
        if (sessionId != null) {
            todoSessionStore.pushEvent(sessionId, TodoSseEvent.todos(todos));
        }
    })
    .build();
```

`TodoAgentService.runAgent()` 在虚拟线程中执行前设置 `TodoSessionHolder.setSessionId(sessionId)`，`finally` 块中 `clear()`。

### 2.5 SSE 事件缓冲策略

`POST /chat` 创建 Session 后立即在虚拟线程中启动 Agent。若此时 SSE 尚未连接，事件写入 Session 缓冲队列；`GET /sse/{sessionId}` 连接建立后立刻 flush 缓冲，确保不丢失 `TODOS` 事件。

### 2.6 Agent 配置

`ChatClient` 仅注册 `TodoWriteTool`（首版不叠加其他工具，聚焦 Demo）。

**System Prompt 要点**：

- 角色：学习规划导师
- 面对多步骤任务时，**必须**使用 `TodoWrite` 拆解任务并跟踪进度
- 执行过程中及时更新 Todo 状态：`pending` → `in_progress` → `completed`
- 每完成一个子任务后更新 Todo 列表
- 最终输出完整、结构化的中文学习计划（含每日主题、学习内容、实践建议）
- 不要编造用户未提供的信息；若信息不足，基于合理假设并说明

---

## 3. API 设计

### 3.1 POST `/agent/todo/chat`

**请求**：

```json
{ "message": "帮我制定一个 7 天 Spring AI 2.0 学习计划" }
```

**响应**：

```json
{ "sessionId": "550e8400-e29b-41d4-a716-446655440000" }
```

**行为**：创建 Session，保存 message，异步启动 Agent。

### 3.2 GET `/agent/todo/sse/{sessionId}`

**响应**：`Content-Type: text/event-stream`

**事件 data 格式**（JSON 字符串）：

| type | 字段 | 说明 |
|------|------|------|
| `RUNNING` | — | Agent 正在执行 |
| `TODOS` | `todos`, `progress` | Todo 列表更新 |
| `COMPLETED` | `response` | 最终学习计划 |
| `FAILED` | `error` | 错误信息 |

**TODOS 事件示例**：

```json
{
  "type": "TODOS",
  "todos": [
    { "content": "调研 Spring AI 2.0 核心概念", "status": "completed", "activeForm": "调研 Spring AI 2.0 核心概念" },
    { "content": "编写 ChatClient 实践代码", "status": "in_progress", "activeForm": "编写 ChatClient 实践代码" },
    { "content": "总结学习成果", "status": "pending", "activeForm": "总结学习成果" }
  ],
  "progress": { "completed": 1, "total": 3, "percent": 33 }
}
```

`status` 枚举值：`pending` / `in_progress` / `completed`（与 `TodoWriteTool.Todos.Status` 对齐，序列化为小写字符串）。

---

## 4. 前端设计

### 4.1 集成方式

在 `demo2/src/main/resources/static/index.html` 新增 Tab：**「📋 TodoWrite 学习计划」**，风格与现有 Tab（尤其 AskUser Tab）一致。

### 4.2 布局

- 顶部：标题 + 说明 + 流程图（用户提问 → Agent 拆 Todo → 实时看板 → 输出计划）
- 预设示例按钮：
  - 「7 天 Spring AI 2.0 学习计划」
  - 「Java 21 新特性 5 天速成计划」
- 输入框 +「开始学习规划」按钮
- **Todo 看板区**：进度条（`3/5 (60%)`）+ 任务列表（`[ ]` pending / `[→]` in_progress / `[✓]` completed）
- **回复区**：最终 AI 学习计划（`COMPLETED` 后展示）

### 4.3 交互流程

1. 用户输入或点击示例 → `fetch POST /agent/todo/chat`
2. 拿到 `sessionId` → `new EventSource('/agent/todo/sse/' + sessionId)`
3. 监听 `message` 事件，按 `type` 分支处理：
   - `RUNNING`：显示「Agent 正在规划...」
   - `TODOS`：刷新 Todo 看板和进度条
   - `COMPLETED`：展示最终计划，关闭 EventSource
   - `FAILED`：展示错误，关闭 EventSource
4. 页面 `beforeunload` 时关闭 EventSource

---

## 5. 错误处理

| 场景 | 处理 |
|------|------|
| SSE 超时 | `SseEmitter` 超时 5 分钟；超时后推送 `FAILED` 并清理 Session |
| Session 不存在 | `GET /sse/{id}` 返回 404 |
| Agent 执行异常 | 捕获异常，推送 `FAILED`，记录日志 |
| DeepSeek API Key 未配置 | `FAILED` 提示检查 `DEEPSEEK_API_KEY` |
| 用户关闭页面 | 前端 `beforeunload` 关闭 EventSource；服务端 Session 10 分钟无活动自动过期 |

---

## 6. 文件清单

| 操作 | 文件 |
|------|------|
| 新增 | `config/TodoAgentConfig.java` |
| 新增 | `controller/TodoAgentController.java` |
| 新增 | `service/TodoAgentService.java` |
| 新增 | `service/TodoSessionStore.java` |
| 新增 | `service/TodoSessionHolder.java` |
| 新增 | `model/TodoSession.java` |
| 新增 | `model/TodoSessionStatus.java` |
| 新增 | `model/TodoChatRequest.java` |
| 新增 | `model/TodoChatResponse.java` |
| 新增 | `model/TodoSseEvent.java` |
| 新增 | `model/TodoItemDto.java` |
| 新增 | `model/TodoProgressDto.java` |
| 修改 | `static/index.html`（新增 Tab + JS 逻辑） |
| 修改 | `README.md`（新增 TodoWrite 章节，实现阶段补充） |

---

## 7. 测试计划

### 7.1 编译验证

```bash
cd demo2 && mvn compile -q
```

### 7.2 手动测试

1. 启动应用（端口 8081），确认 `DEEPSEEK_API_KEY` 已配置
2. 打开 `http://localhost:8081`，切换到 TodoWrite Tab
3. 点击「7 天 Spring AI 2.0 学习计划」
4. 验证 Todo 看板实时更新（出现多个 Todo 项，状态逐步变化）
5. 验证最终收到完整学习计划

### 7.3 边界测试

- SSE 连接前 Agent 已推送 TODOS → 连接后应 flush 缓冲，不丢事件
- 重复点击「开始学习规划」→ 关闭旧 EventSource，创建新 Session
- API Key 缺失 → `FAILED` 事件含可读错误信息

---

## 8. 方案对比记录

| 方案 | 结论 |
|------|------|
| **A. 独立 SSE Session 模式** | **选用**，对齐 AskUser 已验证模式，边界清晰 |
| B. 抽象通用 SseSessionStore | 过度设计，首版 Demo 不值得 |
| C. ApplicationEventPublisher 桥接 | 多一层间接，Web 场景不如直接回调 SessionStore |
| D. 同步 GET（SkillsAgent 模式） | 无法展示 Todo 实时进度，未选用 |
| E. TodoWrite + BraveWebSearchTool | 需 BRAVE_API_KEY，用户明确不选 |
