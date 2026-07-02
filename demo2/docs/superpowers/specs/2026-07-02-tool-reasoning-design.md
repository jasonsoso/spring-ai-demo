# Tool Argument Augmentation · 工具推理捕获 Demo 设计规范

**日期**: 2026-07-02  
**项目**: spring-ai-demo / demo2  
**状态**: 待审阅

---

## 1. 背景与目标

### 1.1 需求

在 `demo2` 中新增 **Tool Argument Augmentation（工具参数增强器）** 演示 Tab：通过 Spring AI 2.0 的 `AugmentedToolCallbackProvider`，在 LLM 选择工具时捕获 `innerThought` 与 `confidence`，以 **SSE 对话式** UI 实时展示推理过程，并与现有「Agent Tools」Tab 形成能力对比。

参考：
- [Spring AI Tool Calling — Tool Argument Augmentation](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Baeldung: Capture LLM Tool Call Reasoning with Spring AI](https://www.baeldung.com/spring-ai-explainable-agents-capture-llm-tool-call-reasoning)

### 1.2 已确认决策

| 维度 | 选择 |
|------|------|
| 演示场景 | 复用行程规划（`WeatherTool` + `AttractionTool`），与 `agent-tools` 对照 |
| 交互方式 | **SSE 对话式**（聊天气泡 + 底部输入栏，对齐 `session-memory`） |
| 多轮对话 | **支持**（`sessionId` + 内存 `ChatMemory`，窗口 20 条） |
| 推理字段 | `innerThought`（required）+ `confidence`（low/medium/high） |
| 后端方案 | Bridge 模式：`AugmentedToolCallbackProvider` + `ToolReasoningSseBridge` |
| 通信协议 | `POST /chat/stream` 直接返回 SSE（对齐 `SessionMemoryAgentController`） |
| 模型 | `agent.tool-reasoning.chat.model=deepseek-chat`（避免 thinking 模型多轮 ToolCall 的 `reasoning_content` 回放问题） |

### 1.3 依赖

- Spring AI 2.0.0（已引入，`AugmentedToolCallbackProvider` 内置）
- 现有 `WeatherTool`、`AttractionTool`（**不修改**）
- 现有 `@Primary` `ChatMemory`（`MemoryConfig`，内存窗口 20 条）

无需新增 Maven 依赖。

### 1.4 成功标准

1. 用户在「🧠 工具推理捕获」Tab 以聊天气泡连续发送多轮消息（如先规划行程，再追问「刚才天气怎么样」）
2. 每轮中每次工具调用实时推送 `TOOL_REASONING` SSE 事件，展示工具名、推理、置信度
3. 最终回复以 `TOKEN` 流式写入 assistant 气泡（与 session-memory 一致）
4. 相同需求在「Agent Tools」Tab 仅返回结果、不可见推理，形成直观对比
5. Swagger 可查看 REST 端点

### 1.5 不在范围

- 推理数据持久化到 DB
- MCP 远程工具的推理捕获
- Session API / Event Store 集成
- 生产级鉴权、限流

---

## 2. 架构设计

### 2.1 与 agent-tools 的对比

| 维度 | agent-tools（现有） | tool-reasoning（新增） |
|------|---------------------|------------------------|
| 工具 | `WeatherTool` + `AttractionTool` | 同一套，经 `AugmentedToolCallbackProvider` 包装 |
| 协议 | 同步 `GET /agent/tool/plan` | `POST /agent/tool-reasoning/chat/stream` SSE |
| UI | 表单 + 单次结果区 | 聊天气泡 + 推理卡片 |
| 多轮 | 无 | `sessionId` + `MessageChatMemoryAdvisor` |
| 推理可见性 | 无 | 每次 tool call 推送 `innerThought` + `confidence` |

### 2.2 时序（单轮消息）

```
用户发送消息
    → POST /agent/tool-reasoning/chat/stream  { sessionId, message }
    → SseEmitter 建立
    → ToolReasoningStreamContext 绑定 (emitter, sessionId, callIndex)
    → ChatClient + AugmentedTools + ChatMemoryAdvisor + ToolCallingAdvisor
    → LLM 选择工具（schema 含 innerThought / confidence）
    → argumentConsumer → TOOL_REASONING SSE
    → 原工具执行（增强字段已剥离）
    → （可能多轮 tool call，重复 TOOL_REASONING）
    → TOKEN SSE 流式推送最终文本
    → COMPLETED → emitter.complete()
    → 前端恢复输入框，可发送下一轮
```

### 2.3 核心组件

| 组件 | 包/路径 | 职责 |
|------|---------|------|
| `AgentThinking` | `model` | 增强参数字段 Record |
| `ToolReasoningSseEvent` | `model` | SSE 事件 DTO（独立于 `AgentSseEvent`，避免污染 ask-user/todo） |
| `ToolReasoningStreamContext` | `sse` | ThreadLocal 绑定当前请求的 emitter / callIndex |
| `ToolReasoningSseBridge` | `sse` | `argumentConsumer` 回调 → 推送 `TOOL_REASONING` |
| `ToolReasoningAgentConfig` | `config` | `AugmentedToolCallbackProvider` + 专用 `ChatClient.Builder` |
| `ToolReasoningAgentService` | `service` | `streamChat()`、`clearSession()` |
| `ToolReasoningAgentController` | `controller` | `POST /chat/stream`、`DELETE /clear` |
| 前端 Tab | `static` | `tool-reasoning.js` / `tool-reasoning.css` |

---

## 3. 后端详细设计

### 3.1 `AgentThinking` Record

```java
public record AgentThinking(
    @ToolParam(description = "调用此工具前的逐步推理：为何选该工具、期望获得什么、如何影响后续规划", required = true)
    String innerThought,
    @ToolParam(description = "置信度：low / medium / high", required = false)
    String confidence
) {}
```

### 3.2 `AugmentedToolCallbackProvider` 配置

```java
AugmentedToolCallbackProvider.<AgentThinking>builder()
    .toolObject(weatherTool, attractionTool)
    .argumentType(AgentThinking.class)
    .argumentConsumer(event -> ToolReasoningSseBridge.onToolReasoning(
            event.toolDefinition().name(), event.arguments()))
    .removeExtraArgumentsAfterProcessing(true)
    .build();
```

`removeExtraArgumentsAfterProcessing(true)` 确保 `getWeather(city)` 等方法签名不变。

### 3.3 `ToolReasoningStreamContext`

参照 `AgentSessionHolder`，在 `streamChat` 入口设置、finally 清除：

```java
public final class ToolReasoningStreamContext {
    // ThreadLocal: SseEmitter, JsonMapper, AtomicInteger callIndex
}
```

`ToolReasoningSseBridge.onToolReasoning` 从 Context 读取 emitter 并递增 `callIndex`。

### 3.4 SSE 事件类型（`ToolReasoningSseEvent`）

| type | 字段 | 说明 |
|------|------|------|
| `RUNNING` | — | 本轮开始（可选，前端亦可在发消息时本地展示 loading） |
| `TOOL_REASONING` | `toolName`, `innerThought`, `confidence`, `callIndex` | 每次工具被选中时推送 |
| `TOKEN` | `content` | 最终回复文本片段（流式） |
| `COMPLETED` | — | 本轮结束 |
| `FAILED` | `error` | 异常 |

### 3.5 `ToolReasoningAgentService`

**System Prompt**（在 agent-tools 规则基础上追加）：

```
每次调用工具时，必须在 innerThought 中说明：
1. 为何选择该工具（而非另一个）
2. 期望从工具获得什么信息
3. 该信息如何影响后续行程规划
confidence 如实填写。
```

**`streamChat(sessionId, message, emitter, jsonMapper)`**：

1. 校验 `sessionId`（字母数字下划线连字符，与 session-memory 一致）
2. `ToolReasoningStreamContext.set(...)`
3. `chatClient.prompt().user(message).advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId)).stream().content()`
4. 每个 chunk → `TOKEN` SSE；`argumentConsumer` 同步推送 `TOOL_REASONING`
5. 完成 → `COMPLETED`；异常 → `FAILED`
6. `finally` → `ToolReasoningStreamContext.clear()`

**ChatClient 构建**（`clone()`  per config bean）：

- `defaultTools(augmentedToolProvider)`
- `defaultAdvisors(MessageChatMemoryAdvisor, ToolCallingAdvisor.builder().build())`
- `defaultOptions(DeepSeekChatOptions.model(toolReasoningChatModel))`

**`clearSession(sessionId)`**：调用 `chatMemory.clear(sessionId)`。

### 3.6 Controller 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/agent/tool-reasoning/chat/stream` | Body: `{ "sessionId", "message" }`，`produces: text/event-stream` |
| `DELETE` | `/agent/tool-reasoning/clear?sessionId=` | 清除该会话 ChatMemory |

Request DTO：`ToolReasoningChatRequest`（`sessionId`, `message`）。

### 3.7 配置

```properties
agent.tool-reasoning.chat.model=deepseek-chat
```

### 3.8 错误处理

| 场景 | 行为 |
|------|------|
| LLM 未填 `innerThought` | consumer 收到空值，前端显示「（模型未提供推理）」 |
| 工具执行失败 | `FAILED` SSE + `emitter.completeWithError` |
| sessionId 非法 | HTTP 400 |
| SSE 超时 | 5 分钟（与 session-memory 一致） |

---

## 4. 前端详细设计

### 4.1 Tab 入口

- Tab ID: `tool-reasoning`
- 按钮: `🧠 工具推理捕获`
- 位置: 紧跟 `agent-tools` 之后

### 4.2 布局

```
┌─ Header + 流程说明 ─────────────────────────────┐
│  用户消息 → LLM 选工具 → 捕获推理 → 执行工具 → 回复  │
├───────────────────────┬───────────────────────────┤
│  聊天气泡区            │  推理侧栏 (#reasoningSidebar) │
│  #toolReasoningMessages│  按 callIndex 累积卡片      │
├───────────────────────┴───────────────────────────┤
│  sessionId（只读/可复制）+ 清除会话 + 输入栏 + 发送   │
└───────────────────────────────────────────────────┘
```

- 页面加载时 `sessionId = crypto.randomUUID()`
- 推理卡片同时出现在 **assistant 气泡内** 与 **右侧侧栏**（侧栏便于多轮累积查看）

### 4.3 SSE 客户端（对齐 session-memory）

使用 `fetch` + `ReadableStream` 解析 `data:` 行（**非 EventSource**，因 POST 返回 SSE）：

```javascript
// POST /agent/tool-reasoning/chat/stream
// 解析 TOOL_REASONING → 追加卡片
// 解析 TOKEN → 追加 response-text
// 解析 FAILED → 错误气泡
```

### 4.4 assistant 气泡结构

```html
<div class="tool-reasoning-assistant">
  <div class="reasoning-inline-cards"><!-- TOOL_REASONING --></div>
  <div class="response-text"><!-- TOKEN 累积 --></div>
</div>
```

### 4.5 快捷示例

复用 agent-tools 四个测试场景按钮，点击填入输入框（不自动发送）。

### 4.6 多轮验证场景

1. 「帮我规划北京周末游，先看天气再推荐人文景点」→ 期望 ≥2 条 `TOOL_REASONING`
2. 「刚才查到的天气怎么样？」→ 期望 LLM 基于 ChatMemory 回答，可能不再调工具
3. 「换一个室内景点」→ 期望调 `recommendAttractions` 并展示新推理

---

## 5. 文件清单

| 操作 | 路径 |
|------|------|
| 新增 | `model/AgentThinking.java` |
| 新增 | `model/ToolReasoningChatRequest.java` |
| 新增 | `model/ToolReasoningSseEvent.java` |
| 新增 | `sse/ToolReasoningStreamContext.java` |
| 新增 | `sse/ToolReasoningSseBridge.java` |
| 新增 | `config/ToolReasoningAgentConfig.java` |
| 新增 | `service/ToolReasoningAgentService.java` |
| 新增 | `controller/ToolReasoningAgentController.java` |
| 新增 | `static/js/tabs/tool-reasoning.js` |
| 新增 | `static/css/tabs/tool-reasoning.css` |
| 修改 | `static/index.html`（Tab 按钮 + 内容区 + script/css 引用） |
| 修改 | `application.properties`（模型配置） |
| 不修改 | `WeatherTool.java`、`AttractionTool.java`、`ToolTripAgentService.java` |

---

## 6. 测试计划

| # | 场景 | 预期 |
|---|------|------|
| 1 | 单工具·天气 | 1 条 `TOOL_REASONING` + 流式回复 |
| 2 | 多工具组合 | ≥2 条推理卡片，callIndex 递增 |
| 3 | 多轮追问 | 第二轮可引用第一轮上下文 |
| 4 | 清除会话 | `DELETE /clear` 后历史不再影响 |
| 5 | 与 agent-tools 对比 | 相同输入，仅新 Tab 展示推理 |
| 6 | 编译 | `mvn -pl demo2 -DskipTests compile` 通过 |

---

## 7. 修订记录

| 日期 | 变更 |
|------|------|
| 2026-07-02 | 初稿：SSE 对话式 + 多轮 ChatMemory；由 todo 式时间线面板修订为 session-memory 聊天气泡模式 |
