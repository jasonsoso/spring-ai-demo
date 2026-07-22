# AgentScope LLM 请求/响应日志设计规范

**日期**: 2026-07-22  
**项目**: spring-ai-demo / demo2  
**状态**: 已确认，待实现  
**前置**: [2026-07-16-agentscope-harness-web-design.md](./2026-07-16-agentscope-harness-web-design.md)（已实现）  
**对齐**: `LoggingConfig` / `LoggingChatModel`（业务 ChatClient + Embabel）

---

## 1. 背景与目标

### 1.1 问题

AgentScope Dev Agent 使用 `io.agentscope.extensions.model.openai.OpenAIChatModel`，走 AgentScope 自有 `Model` 接口，**不经过** Spring AI 的 `SimpleLoggerAdvisor` 与 `LoggingChatModel`。因此请求大模型时几乎没有请求/响应日志，开发调试困难。

### 1.2 需求

对齐现有 `LoggingConfig` 体系：在 DEBUG 下打印 AgentScope 调用大模型的**请求参数**与**响应内容**。

### 1.3 已确认决策

| 维度 | 选择 |
|------|------|
| 落地方式 | **方案 1**：`LoggingAgentscopeModel` 装饰 `Model`（对齐 `LoggingChatModel`） |
| 流式响应 | **请求打一次**；流式**只打聚合后的完整响应**（含 usage / finishReason），**不打**每个 chunk |
| 开关 | `logging.level.com.jason.demo.demo2.config.LoggingAgentscopeModel=DEBUG` |
| 接线 | `AgentScopeConfig.agentscopeDeepSeekModel`：`OpenAIChatModel.build()` 后包一层 |

### 1.4 非目标（本版不做）

- 打开 `enableAgentTracingLog`
- Hook（Pre/PostReasoning）打日志
- HTTP 原始 request/response JSON / SSE chunk 日志
- 逐 token / 逐 chunk 的 DEBUG
- 改前端 / SSE 事件模型
- 生产级结构化审计落库

---

## 2. 架构

```
HarnessAgent
  → Model.stream(messages, tools, options)
       ↑
  LoggingAgentscopeModel（装饰器，DEBUG）
       → OpenAIChatModel（真实调用，stream=true）
```

与现有日志入口并列：

| 入口 | 机制 |
|------|------|
| 业务 ChatClient | `SimpleLoggerAdvisor`（`LoggingConfig`） |
| Embabel | `LoggingChatModel` 包装 Spring AI `ChatModel` |
| **AgentScope** | **`LoggingAgentscopeModel` 包装 AgentScope `Model`** |

---

## 3. 组件与行为

### 3.1 `LoggingAgentscopeModel`

- 包：`com.jason.demo.demo2.config`
- 实现：`io.agentscope.core.model.Model`
- 构造：`(Model delegate, String label)`，label 默认如 `agentscope-deepseek`
- 委托：`getModelName()` 及默认方法透传

**`stream(messages, tools, options)`**

1. 若 `log.isDebugEnabled()`：打一条请求日志（见 §4）
2. 调用 `delegate.stream(...)`，**透传**下游 `Flux`（不改变 chunk 语义）
3. 仅在 DEBUG 开启时挂载副作用：
   - 累积 content / 记录末次非空 `finishReason` / `usage`
   - `doOnComplete`：打一条聚合响应日志
   - `doOnError`：WARN（异常类型 + message），**不**打「完整响应」
4. 流被 cancel：不打完整响应（避免半截当成功）
5. DEBUG 关闭：直接返回 `delegate.stream(...)`，不做序列化与聚合字符串

### 3.2 接线与配置

| 文件 | 动作 |
|------|------|
| `config/LoggingAgentscopeModel.java` | 新建 |
| `agentscope/config/AgentScopeConfig.java` | Bean 返回装饰后的 `Model` |
| `config/LoggingConfig.java` | 注释补上 AgentScope 第三种入口 |
| `application.properties` | `logging.level.com.jason.demo.demo2.config.LoggingAgentscopeModel=DEBUG` |
| `.../LoggingAgentscopeModelTest.java` | 新建单元测试 |

---

## 4. 日志字段与脱敏

### 4.1 请求（一条 DEBUG）

| 字段 | 内容 |
|------|------|
| label | 如 `agentscope-deepseek` |
| modelName | `delegate.getModelName()` |
| messages | 每条：`role` + content 可读摘要（含 text / tool_use / tool_result） |
| tools | 每个：`name`、`description`、`parameters` |
| options | temperature / topP / maxTokens / stream / toolChoice 等**非密钥**字段 |

格式风格对齐 `LoggingChatModel`：

```text
LLM request [agentscope-deepseek]: ...
LLM response [agentscope-deepseek]: ...
```

使用**结构化摘要字符串**，不强依赖对象默认 `toString()`（避免泄漏密钥或不可读输出）。

### 4.2 响应（流 onComplete，一条 DEBUG）

| 字段 | 内容 |
|------|------|
| label | 同上 |
| content | 流式过程合并的全部 `ContentBlock`（文本 + tool_calls） |
| finishReason | 最后一次非空 finishReason |
| usage | 最后一次非空 input/output/total tokens（若有） |

### 4.3 脱敏（硬性）

- `GenerateOptions.getApiKey()` **永不写入日志**
- `additionalHeaders` 中 `Authorization` / `api-key` 等敏感头打成 `***`
- 不记录 baseUrl 上的 query token（若有）

### 4.4 边界

| 场景 | 行为 |
|------|------|
| messages / tools 为 null | 日志写 `null` / `[]`，不 NPE |
| 流 error | WARN；不打完整响应 |
| 流 cancel | 不打完整响应 |
| DEBUG 关闭 | 零额外序列化开销 |

---

## 5. 测试

- Mock `Model` 返回已知 `Flux<ChatResponse>`
- DEBUG 开：请求日志含 messages/tools；完成后响应含聚合 content
- options 摘要**不含** apiKey
- 流 error：副作用不向下游抛二次异常
- 不强制真实 DeepSeek 调用；不改现有 SSE 集成测

---

## 6. 成功标准

1. 调用 `/agentscope/dev-agent/ask` 时，日志中能看到 DEBUG 的 LLM request（messages / tools / 安全 options）与一条聚合 LLM response
2. 关闭 `LoggingAgentscopeModel` 的 DEBUG 后，不再输出上述日志
3. 现有 AgentScope SSE / HITL 行为不变
4. 单元测试覆盖装饰器请求摘要、响应聚合与 apiKey 脱敏
