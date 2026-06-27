# AskUserQuestionTool Demo 设计规范

**日期**: 2026-06-27  
**项目**: spring-ai-demo / demo2  
**状态**: 待审阅

---

## 1. 背景与目标

### 1.1 需求

在 `demo2` 中新增 **AskUserQuestionTool** 演示：当 AI Agent 信息不足时，主动向用户提出澄清问题（单选 / 多选 / 自定义文本），收集答案后继续执行并给出最终建议。

### 1.2 已确认决策

| 维度 | 选择 |
|------|------|
| 交互方式 | 简单前端页面（`static/index.html` 新 Tab） |
| 业务场景 | 技术选型（数据库 / 框架选择等） |
| 会话模式 | 单轮 Demo（提问 → 澄清 → 给出建议，会话结束） |
| 通信方案 | **SSE + HTTP POST 混合**（对齐现有 `ChatController` 的 `SseEmitter` 模式） |

### 1.3 依赖

项目已引入 `spring-ai-agent-utils:0.10.0`，其中包含：

- `org.springaicommunity.agent.tools.AskUserQuestionTool`
- `org.springaicommunity.agent.tools.AskUserQuestionTool.QuestionHandler`
- `org.springaicommunity.agent.utils.CommandLineQuestionHandler`（本 Demo 不使用，改用 Web 自定义 Handler）

无需新增 Maven 依赖。

### 1.4 成功标准

1. 用户在 `index.html` 新 Tab 输入「帮我选一个数据库」类问题
2. Agent 通过 `AskUserQuestionTool` 推送澄清问题到前端（SSE）
3. 用户在前端选择或输入答案并提交
4. Agent 继续执行，通过 SSE 推送最终技术选型建议
5. Swagger 可查看三个 REST 端点文档

### 1.5 不在范围

- 多轮持续对话（接口可预留 `sessionId`，首版不实现历史延续）
- Session 持久化（内存存储，重启丢失）
- WebSocket 实现
- 生产级鉴权、限流、分布式 Session

---

## 2. 架构设计

### 2.1 通信模型

| 方向 | 协议 | 用途 |
|------|------|------|
| 服务端 → 客户端 | SSE (`text/event-stream`) | 推送运行状态、澄清问题、最终结果、错误 |
| 客户端 → 服务端 | HTTP POST | 发起对话、提交用户答案 |

浏览器 `EventSource` 仅支持 GET，因此采用「POST 发起 + GET 订阅 SSE + POST 提交答案」三端点模式。

### 2.2 时序

```
用户输入消息
    → POST /agent/ask-user/chat（创建 session，异步启动 Agent）
    → 返回 { sessionId }
    → GET /agent/ask-user/sse/{sessionId}（EventSource 连接）
    → SSE: { type: "RUNNING" }
    → Agent 调用 AskUserQuestionTool
    → WebQuestionHandler 推送 SSE: { type: "QUESTIONS", questions: [...] }
    → Handler 阻塞在 CompletableFuture，等待答案
    → 用户在前端作答
    → POST /agent/ask-user/answer（完成 Future）
    → Agent 继续执行
    → SSE: { type: "COMPLETED", response: "..." }
    → 前端关闭 EventSource
```

### 2.3 核心组件

| 组件 | 包路径（建议） | 职责 |
|------|---------------|------|
| `AskUserSession` | `model` | Session 数据：sessionId、用户消息、状态、SseEmitter、答案 Future、事件缓冲队列 |
| `AskUserSessionStore` | `service` | 内存 ConcurrentHashMap 管理 Session 生命周期 |
| `WebQuestionHandler` | `service` | 实现 `QuestionHandler`；通过 ThreadLocal 绑定 sessionId；推送问题并阻塞等待 |
| `AskUserAgentConfig` | `config` | 注册 `AskUserQuestionTool` Bean，注入 `WebQuestionHandler` |
| `AskUserAgentService` | `service` | 编排 Agent 执行、SSE 连接注册、事件推送与缓冲 flush |
| `AskUserAgentController` | `controller` | 暴露 REST + SSE 端点，Swagger 注解 |

### 2.4 SSE 事件缓冲策略

`POST /chat` 创建 Session 后立即在虚拟线程中启动 Agent。若此时 SSE 尚未连接，事件写入 Session 缓冲队列；`GET /sse/{sessionId}` 连接建立后立刻 flush 缓冲，确保不丢失 `QUESTIONS` 事件。

### 2.5 Agent 配置

```java
AskUserQuestionTool.builder()
    .questionHandler(webQuestionHandler)
    .build();
```

`ChatClient` 仅注册 `AskUserQuestionTool`（首版不叠加其他工具，聚焦 Demo）。

**System Prompt 要点**：

- 角色：技术选型顾问
- 当用户需求模糊时，**必须**调用 `AskUserQuestionTool` 澄清
- 澄清维度示例：项目类型、数据特征、规模要求、运维偏好
- 收到答案后输出：推荐方案 + 理由 + 备选对比
- 使用中文回复

---

## 3. API 设计

### 3.1 POST `/agent/ask-user/chat`

**请求**：

```json
{ "message": "帮我选一个数据库" }
```

**响应**：

```json
{ "sessionId": "550e8400-e29b-41d4-a716-446655440000" }
```

**行为**：创建 Session，保存 message，异步启动 Agent。

### 3.2 GET `/agent/ask-user/sse/{sessionId}`

**响应**：`Content-Type: text/event-stream`

**事件 data 格式**（JSON 字符串）：

| type | 字段 | 说明 |
|------|------|------|
| `RUNNING` | — | Agent 正在执行 |
| `QUESTIONS` | `questions` | 待用户回答的澄清问题列表 |
| `COMPLETED` | `response` | 最终选型建议 |
| `FAILED` | `error` | 错误信息 |

**questions 元素结构**（对齐 `AskUserQuestionTool.Question` record）：

```json
{
  "header": "数据库类型",
  "question": "你更倾向哪种数据库？",
  "options": [
    { "label": "PostgreSQL", "description": "开源关系型，ACID 完备" },
    { "label": "MongoDB", "description": "文档型 NoSQL" }
  ],
  "multiSelect": false
}
```

**答案 Map 的 key**：使用 `question` 字段文本（与 `AskUserQuestionTool` 约定一致）。

### 3.3 POST `/agent/ask-user/answer`

**请求**：

```json
{
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "answers": {
    "你更倾向哪种数据库？": "PostgreSQL",
    "预计数据量级？": "百万级"
  }
}
```

**响应**：

```json
{ "status": "accepted" }
```

**行为**：完成 Session 中挂起的 `CompletableFuture`，Agent 线程恢复执行。

---

## 4. 前端设计

### 4.1 集成方式

在 `demo2/src/main/resources/static/index.html` 新增 Tab：**「❓ AskUserQuestion 技术选型」**，风格与现有 Tab 一致。

### 4.2 交互流程

1. 展示预设示例按钮：「帮我选一个数据库」「帮我选 Java Web 框架」
2. 用户输入或点击示例 → `fetch POST /agent/ask-user/chat`
3. 拿到 `sessionId` → `new EventSource('/agent/ask-user/sse/' + sessionId)`
4. 监听 `message` 事件，按 `type` 分支处理
5. `QUESTIONS`：在聊天区渲染问题卡片（单选 / 多选 / 自定义输入）
6. 用户点击「提交答案」→ `fetch POST /agent/ask-user/answer`
7. `COMPLETED`：展示最终建议，关闭 EventSource
8. `FAILED`：展示错误，关闭 EventSource

### 4.3 问题卡片 UI

每个 question 渲染为一张卡片：

- 标题：`header`
- 问题文本：`question`
- 选项列表：`options[].label` + `options[].description`
- `multiSelect: true` 时用 checkbox，否则 radio
- 底部提供自定义文本输入框（对应官方 demo 的 "Other" 能力）
- 「提交答案」按钮收集所有问题的答案后一次性 POST

---

## 5. 错误处理

| 场景 | 处理 |
|------|------|
| SSE 超时 | `SseEmitter` 超时 5 分钟；超时后推送 `FAILED` 并清理 Session |
| Session 不存在 | `GET /sse/{id}` 返回 404；`POST /answer` 返回 404 |
| 重复提交答案 | Session 非 `AWAITING_INPUT` 状态时返回 409 |
| Agent 执行异常 | 捕获异常，推送 `FAILED`，清理 Session |
| 用户关闭页面 | 前端 `beforeunload` 关闭 EventSource；服务端 Session 10 分钟无活动自动过期 |
| 答案校验失败 | `AskUserQuestionTool` 开启 `answersValidation(true)` 时，校验失败推送 `FAILED` 并提示重试 |

---

## 6. 文件清单

| 操作 | 文件 |
|------|------|
| 新增 | `config/AskUserAgentConfig.java` |
| 新增 | `controller/AskUserAgentController.java` |
| 新增 | `service/AskUserAgentService.java` |
| 新增 | `service/AskUserSessionStore.java` |
| 新增 | `service/WebQuestionHandler.java` |
| 新增 | `model/AskUserSession.java` |
| 新增 | `model/AskUserChatRequest.java` |
| 新增 | `model/AskUserAnswerRequest.java` |
| 新增 | `model/AskUserSseEvent.java` |
| 修改 | `static/index.html`（新增 Tab + JS 逻辑） |

---

## 7. 测试计划

### 7.1 手动测试

1. 启动应用（端口 8081）
2. 打开 `http://localhost:8081`，切换到 AskUserQuestion Tab
3. 点击「帮我选一个数据库」
4. 验证 SSE 推送澄清问题
5. 选择选项并提交
6. 验证收到完整技术选型建议

### 7.2 边界测试

- 不连接 SSE 直接 POST answer → 404 或 409
- SSE 连接后长时间不提交答案 → 5 分钟超时
- 提交空 answers → 校验失败或 Agent 重试

---

## 8. 方案对比记录

| 方案 | 结论 |
|------|------|
| A. Session + 轮询 | 可行，实时性略差，未选用 |
| B. WebSocket | 可行，需新依赖，复杂度高，未选用 |
| **C. SSE + POST** | **选用**，与 `ChatController` 一致，无新依赖，实时性足够 |
