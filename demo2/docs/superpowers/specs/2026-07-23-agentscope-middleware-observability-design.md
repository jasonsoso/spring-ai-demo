# AgentScope Middleware 请求关联可观测性设计规范

**日期**：2026-07-23  
**项目**：spring-ai-demo / demo2  
**状态**：已确认，待实现  
**前置能力**：AgentScope Toolkit、AgentEvent SSE、Permission HITL、PostgreSQL AgentStateStore、Workspace、Compaction  
**参考文章**：[AgentScope Java 2.0 Middleware 实战：一条 requestId 串起模型、工具和失败日志](https://mp.weixin.qq.com/s/zACGBjzQJsvDns_LVmpD-Q)

---

## 1. 背景与目标

### 1.1 问题

当前 AgentScope Dev Agent 已关闭默认 `AgentTraceMiddleware`，并通过 `DevAgentService` 向前端透传 Agent、模型和工具事件，但服务端缺少一条稳定的请求关联标识。一次请求变慢或失败时，只看 Controller 或零散模型日志，难以确认耗时位于推理、模型调用、工具执行还是其他环节。

项目已经接入 Micrometer Tracing 与 OpenTelemetry，HTTP 日志可包含 `traceId`、`spanId`，但 AgentScope 内部各阶段尚未统一携带这些标识，也没有面向前端错误定位的 requestId。

### 1.2 目标

1. 为每次 `/ask`、`/confirm` 调用生成独立 requestId。
2. 通过 AgentScope Middleware 记录 Agent、推理、模型、工具和失败阶段的结构化关联日志。
3. 日志同时携带 requestId、traceId、spanId，与现有 HTTP Trace 关联。
4. 通过新增 `REQUEST_CONTEXT` SSE 事件把请求上下文返回前端。
5. 正常请求不展示 requestId；失败时展示并允许复制，便于用户反馈问题。
6. 不改变现有 Agent 输出、SSE 错误转换、AgentState 持久化、HITL、Workspace 和 Compaction 行为。

### 1.3 已确认决策

| 维度 | 决策 |
|------|------|
| requestId 来源 | 服务端始终生成，不接收或复用调用方 requestId |
| requestId 生命周期 | 每次 `/ask`、`/confirm` 独立生成 |
| SSE 契约 | 新增独立 `REQUEST_CONTEXT` 事件 |
| 前端呈现 | 仅失败时展示 requestId |
| OpenTelemetry | 只做日志关联，不创建 Agent、reasoning、model、tool Span |
| 日志级别 | Agent/推理/模型/工具 INFO；Prompt 长度 DEBUG；失败 WARN |
| 默认 Agent Trace | 继续 `enableAgentTracingLog(false)` |
| 现有模型明细日志 | 保留 `LoggingAgentscopeModel` 当前 DEBUG 行为 |

### 1.4 非目标

- 接收、校验或透传外部 `X-Request-ID`
- 为 AgentScope 阶段创建 OpenTelemetry 子 Span
- 将 requestId 写入 PostgreSQL `AgentState`
- 用同一 requestId 串联 ask 与后续 confirm
- 修改模型重试、Compaction 重试或 SSE 异常恢复策略
- 将这套 requestId 机制扩展到 demo2 的其他 Controller
- 在 Middleware 中记录完整 Prompt、工具参数或工具结果

---

## 2. 方案选择

### 2.1 采用方案：Service 创建执行上下文

`DevAgentService` 在每次 `ask`、`confirm` 调用开始时：

1. 生成无连字符 UUID requestId。
2. 从 Micrometer `Tracer` 读取当前 `traceId`、`spanId`；不存在时使用 `-`。
3. 将三者写入本次 `RuntimeContext.extra`。
4. 在 `SESSION` 后发送 `REQUEST_CONTEXT`。
5. 使用同一 `RuntimeContext` 调用 `HarnessAgent.streamEvents(...)`。

`AgentExecutionLoggingMiddleware` 只读取上下文、记录日志和计时，不负责生成请求标识。

### 2.2 未采用方案

- **Middleware 延迟生成**：`REQUEST_CONTEXT` 必须等待 Middleware 开始执行，响应式事件顺序和职责边界更复杂。
- **Servlet Filter 统一生成**：会扩大到全应用范围，并增加 Reactor/MDC 上下文传播问题，不符合本次只增强 AgentScope 的范围。

---

## 3. 总体架构

```text
POST /agentscope/dev-agent/ask|confirm
  → DevAgentController
  → DevAgentService
       ├─ 生成 requestId
       ├─ 捕获 traceId / spanId
       ├─ 构建 RuntimeContext.extra
       └─ SSE：SESSION → REQUEST_CONTEXT → ...
  → HarnessAgent
       ├─ AgentExecutionLoggingMiddleware
       │    ├─ onAgent
       │    ├─ onReasoning
       │    ├─ onModelCall
       │    ├─ onActing
       │    └─ onSystemPrompt
       ├─ Compaction
       ├─ Toolkit / Permission / Workspace
       └─ AgentStateStore
  → DevAgentService 映射现有 AgentEvent
  → SSE：... → COMPACTION? → DONE | ERROR
  → agentscope.js：失败时显示 requestId
```

职责边界：

- Controller 保持请求校验与 SSE API，不生成或拼接日志。
- Service 负责请求上下文创建、SSE 编排和现有异常转换。
- Middleware 负责 Agent 执行阶段观测。
- 前端只保存请求上下文并在错误时展示 requestId。
- PostgreSQL 继续只保存可恢复 AgentState，不保存本次调用标识。

---

## 4. 请求上下文设计

### 4.1 上下文字段

新增统一的上下文键定义，供 Service 与 Middleware 共享：

| 字段 | RuntimeContext extra 键 | 规则 |
|------|--------------------------|------|
| requestId | `observability.request_id` | 32 位无连字符 UUID |
| traceId | `observability.trace_id` | 当前 Micrometer Trace ID；缺失为 `-` |
| spanId | `observability.span_id` | 当前 Micrometer Span ID；缺失为 `-` |
| reasoning round | `observability.reasoning_round` | Middleware 内从 0 原子递增 |

上下文键应集中在一个小型常量/辅助类中，避免 Service 与 Middleware 各自硬编码。

### 4.2 创建时机

请求上下文在 Service 方法进入后立即创建，早于 API Key 和待确认工具检查。因此以下短路路径也能返回 requestId：

- 缺少 `DEEPSEEK_API_KEY`
- `/confirm` 没有待确认工具
- 调用 Agent 前发生的业务错误
- Agent 事件流执行异常

对于缺少 API Key、confirm 无待确认工具等尚未进入 Middleware 的短路，Service 使用同一组 requestId/traceId/spanId 记录一条 WARN 拒绝日志。否则前端虽然能拿到 requestId，服务端却没有可按该 ID 检索的记录。

### 4.3 并发安全

`AgentExecutionLoggingMiddleware` 是 Spring 单例，不保存当前请求的成员状态。requestId、Trace 标识和 reasoning 轮次均位于本次 `RuntimeContext`；每次订阅的计时器、计数器和事件聚合对象在 `Flux.defer` 内创建。

---

## 5. SSE 协议

### 5.1 事件模型

`DevAgentEventType` 新增：

```text
REQUEST_CONTEXT
```

`DevAgentEvent` 增加三个可空字段：

```text
requestId
traceId
spanId
```

新增 `DevAgentEvent.requestContext(sessionId, requestId, traceId, spanId)` 工厂方法。只有 `REQUEST_CONTEXT` 填充这三个字段；其他事件保持为空，避免每个分片重复传输。

示例：

```json
{
  "type": "REQUEST_CONTEXT",
  "sessionId": "toolkit-session-001",
  "content": "",
  "requestId": "8f82e03a81ab4fb08dcbd84b724af670",
  "traceId": "4f8b29d48e476f4b9fa09b5b64318d11",
  "spanId": "2ba6b35c392cbe92"
}
```

### 5.2 事件顺序

正常执行：

```text
SESSION
→ REQUEST_CONTEXT
→ AGENT_START / MODEL_CALL_START / TOOL_* / MESSAGE* / ...
→ COMPACTION（可选）
→ DONE
```

短路或执行失败：

```text
SESSION
→ REQUEST_CONTEXT
→ 已产生的其他事件（可选）
→ ERROR
```

异常路径不发送 `DONE`，沿用当前 `DevAgentService.onErrorResume` 行为。

### 5.3 向后兼容

新增事件类型和可空字段不改变已有事件含义。旧客户端若忽略未知事件，可以继续消费原有 `SESSION`、`MESSAGE`、`DONE`、`ERROR` 等事件；仓库内前端同步增加 `REQUEST_CONTEXT` 处理。

---

## 6. Middleware 日志设计

### 6.1 通用约束

所有 Middleware 日志包含：

```text
requestId, traceId, spanId
```

日志采用参数化 SLF4J 调用。Middleware 不记录完整 Prompt、用户消息、工具参数、工具结果正文或模型回答正文。

### 6.2 onAgent

开始日志（INFO）：

- agent 名称
- userId、sessionId
- requestId、traceId、spanId

结束日志（INFO）：

- 总耗时 `durationMs`
- 回答字符数（仅累计 `TextBlockDeltaEvent`，不重复累计 `AgentResultEvent`）
- 状态 `SUCCESS`

异常或取消日志（WARN）：

- 总耗时
- 状态 `ERROR` 或 `CANCELLED`
- 异常类型；不输出潜在敏感正文

### 6.3 onReasoning

每轮推理记录：

- reasoning round
- 输入消息数
- 可用工具数
- 耗时
- `SUCCESS`、`ERROR` 或 `CANCELLED`

轮次从 1 开始，保存在 `RuntimeContext.extra`，不同并发请求互不影响。

### 6.4 onModelCall

模型调用记录：

- 模型名
- 耗时
- input tokens
- output tokens
- 状态

从 `ModelCallEndEvent` 提取 usage。模型未返回 usage 时记录 `-`，不能将未知误写为 `0`。失败日志只记录异常类型。

`onModelCall` 只覆盖 ReAct 推理链中的模型调用。Compaction 为摘要直接发起的模型调用不保证经过此入口；因此 reasoning 耗时可能明显大于 model 耗时。

### 6.5 onActing

工具阶段记录：

- 本组工具数量
- 每个工具的名称、toolCallId
- `ToolResultEndEvent` 的结果状态
- 本组总耗时

同一轮可执行多个工具，因此允许输出多条工具结果日志。未进入工具阶段时不输出 acting 日志。

### 6.6 onSystemPrompt

仅在 DEBUG 级别记录当前系统 Prompt 字符数，并原样返回 Prompt。后续 Middleware 仍可能修改 Prompt，因此此值只表示当前处理阶段长度。

### 6.7 响应式语义

- 使用 `Flux.defer` 创建每次订阅独立的开始时间和聚合状态。
- 始终调用 `next.apply(input)`。
- 不吞掉异常，不在 Middleware 中转换为业务 SSE。
- 正常完成、失败和取消分别记录一次终态，避免重复终态日志。
- Middleware 注册到 Harness 内置 Middleware 之外时，reasoning 耗时可包含 Compaction 等下游处理。

---

## 7. OpenTelemetry 关联

项目继续使用现有 Micrometer Tracing、OTLP Profile、日志格式和 `TraceIdFilter`。

本功能不创建新 Span，也不改变采样策略。Service 在构建 Agent `RuntimeContext` 时捕获当前 Trace 上下文，Middleware 后续直接读取已捕获的 traceId/spanId，避免依赖 Reactor 回调执行线程上的 MDC 是否仍然存在。

结果：

- requestId：定位一次 Agent 执行。
- traceId：在 Grafana Tempo 中定位对应 HTTP Trace。
- spanId：定位捕获请求上下文时的 HTTP Span。
- userId + sessionId：关联多轮业务会话。

四者语义不同，不互相复用。

---

## 8. 前端设计

`static/js/tabs/agentscope.js` 为每次 ask/confirm 请求维护当前请求上下文：

1. 发起请求前清空上一请求的上下文。
2. 收到 `REQUEST_CONTEXT` 后保存 requestId、traceId、spanId。
3. 正常 `DONE` 时不展示这些标识。
4. 收到 `ERROR` 时，在错误提示下显示 `请求编号：{requestId}`。
5. 提供复制按钮，只复制 requestId。
6. traceId/spanId 保存在前端状态和原始 SSE 数据中，但不直接展示。

若在收到 `REQUEST_CONTEXT` 前发生浏览器网络级错误，则沿用当前网络错误提示，不伪造 requestId。

样式沿用 AgentScope Tab 现有错误提示和按钮设计，不新增独立调试面板。

Service 对进入 Agent 后的流异常继续交给 Middleware 记录失败阶段，并由现有 `onErrorResume` 转成 SSE `ERROR`；不再额外输出一条同义 Service WARN，避免重复。Service WARN 仅覆盖未进入 Middleware 的短路。

---

## 9. 现有能力兼容

### 9.1 AgentStateStore 与 HITL

requestId 不进入 `AgentState`。一次 ask 停在 `REQUIRE_USER_CONFIRM` 后，后续 confirm 是新的 HTTP/Agent 执行并生成新的 requestId；两次执行通过相同 `userId + sessionId` 和持久化状态关联。

### 9.2 Compaction

Middleware 不修改 Compaction 配置或事件探测。Compaction 耗时可能计入 reasoning，但其摘要模型调用不保证计入 `onModelCall`，README 应明确该差异。

### 9.3 LoggingAgentscopeModel

按已确认决策保留现有 `LoggingAgentscopeModel` DEBUG 明细行为。新增 Middleware 自身严格不输出 Prompt、消息正文、工具参数和工具结果。文档需注明：开启现有 DEBUG 模型日志仍可能输出明细，应仅用于受控本地开发环境。

### 9.4 默认 Agent Trace

继续设置：

```java
.enableAgentTracingLog(false)
```

避免默认 Agent Trace 与自定义执行日志重复。

---

## 10. 组件与文件改动

预计改动：

| 动作 | 文件/位置 | 说明 |
|------|-----------|------|
| 新增 | `agentscope/middleware/AgentExecutionLoggingMiddleware.java` | 五类 Middleware 入口、计时和关联日志 |
| 新增 | `agentscope/observability/AgentExecutionContext.java` | 上下文键、ID 读取和规范化辅助 |
| 修改 | `agentscope/config/AgentScopeConfig.java` | 声明并注册 Middleware，继续关闭默认 Trace |
| 修改 | `agentscope/service/DevAgentService.java` | 生成上下文、注入 Tracer、发送 REQUEST_CONTEXT |
| 修改 | `agentscope/model/DevAgentEventType.java` | 新增 REQUEST_CONTEXT |
| 修改 | `agentscope/model/DevAgentEvent.java` | 新增三个可空字段和工厂方法 |
| 修改 | `static/js/tabs/agentscope.js` | 保存上下文，错误时展示与复制 |
| 修改 | `static/css/tabs/agentscope.css` | 错误请求编号与复制按钮样式 |
| 修改 | `demo2/README.md`、根 README AgentScope 条目 | 说明 Middleware、SSE 和日志检索 |
| 新增/修改 | AgentScope 测试 | Middleware、Service、事件模型及配置覆盖 |

Controller API、请求 DTO、PostgreSQL Schema、Docker Compose 和应用端口不变。

---

## 11. 测试设计

### 11.1 Middleware 单元测试

- 同一 `RuntimeContext` 的各阶段使用同一 requestId/traceId/spanId。
- 两个上下文并发或交错执行时不串号。
- reasoning round 从 1 递增。
- `next.apply(input)` 的事件和异常保持原样。
- 正常、异常、取消分别走正确终态。
- 模型 usage 缺失时按未知处理。
- 多工具结果逐条处理。
- `onSystemPrompt` 原样返回输入。

日志内容优先通过可测试的字段提取/格式辅助方法验证；避免测试依赖完整日志句子和空格。

### 11.2 Service 测试

- `SESSION` 后紧跟 `REQUEST_CONTEXT`。
- 捕获传入 HarnessAgent 的 `RuntimeContext`，验证三项上下文标识。
- ask 与 confirm 分别生成不同 requestId。
- 缺 API Key 仍返回 `SESSION → REQUEST_CONTEXT → ERROR`。
- confirm 无待确认工具仍返回请求上下文。
- 未进入 Middleware 的短路使用同一请求上下文记录拒绝日志。
- 流异常返回带同一执行上下文的 `ERROR` 序列。
- 现有 MESSAGE、工具、HITL、COMPACTION、DONE 顺序不回归。

### 11.3 事件模型与配置测试

- `REQUEST_CONTEXT` 工厂和 JSON 序列化包含三个字段。
- 其他事件的新增字段为 null，并由 `@JsonInclude` 省略。
- HarnessAgent 注册自定义 Middleware。
- `enableAgentTracingLog(false)` 保持不变。

### 11.4 前端手工验证

- 正常完成不显示 requestId。
- 模型失败或业务 ERROR 时显示 requestId。
- 复制按钮复制值正确。
- 连续请求不会显示上一请求的 requestId。
- confirm 使用新 requestId。

---

## 12. 验收标准

1. 一次正常工具调用的 Agent、reasoning、model、acting 日志具有相同 requestId。
2. 日志同时包含当前 traceId/spanId；无 Trace 时明确记录 `-`。
3. Tempo 中不新增 AgentScope 自定义 Span。
4. SSE 严格按 `SESSION → REQUEST_CONTEXT → ...` 输出。
5. 缺 API Key、无待确认调用和流异常也能获得 requestId。
6. 正常页面不显示 requestId；错误页面显示并可复制。
7. Middleware 日志不包含完整 Prompt、工具参数和工具结果。
8. ask 与 confirm 使用不同 requestId，但保持同一业务会话状态。
9. AgentStateStore、HITL、Workspace、Compaction 和现有 SSE 事件测试通过。
10. README 给出按 requestId 查日志、再按 traceId 查 Tempo 的排查步骤。

---

## 13. 运行示例

请求：

```bash
curl -sN -X POST "http://localhost:8081/agentscope/dev-agent/ask" \
  -H "Content-Type: application/json" \
  -d '{"userId":"middleware-user-010","sessionId":"middleware-session-010","message":"请调用只读工具确认 Java、Spring Boot、启动类和源码目录。"}'
```

预期关键 SSE：

```text
SESSION
REQUEST_CONTEXT(requestId, traceId, spanId)
AGENT_START
MODEL_CALL_START
TOOL_CALL_START...
TOOL_RESULT_END...
MESSAGE...
AGENT_RESULT
AGENT_END
DONE
```

预期日志顺序：

```text
Agent execution started
Reasoning started, round=1
Model call started
Model call completed
Reasoning completed, round=1
Tool execution started
Tool execution result...
Tool execution completed
Reasoning started, round=2
Model call started
Model call completed
Reasoning completed, round=2
Agent execution completed
```

每条日志均可用同一 requestId 检索，并通过其中的 traceId 跳转到现有 HTTP Trace。
