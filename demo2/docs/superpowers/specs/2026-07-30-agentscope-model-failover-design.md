# AgentScope 多模型容错设计规范

**日期**: 2026-07-30  
**项目**: spring-ai-demo / demo2  
**状态**: 已确认，待实现  
**参考文章**: [AgentScope Java 2.0 多模型容错实战：DeepSeek 失败后，自动切到备用模型](https://mp.weixin.qq.com/s?__biz=MzcwMjA0Njk3Nw==&mid=2247484481&idx=1&sn=c7bfbf753cff1d862bbde643c36a6f9a)  
**前置**: [2026-07-22-agentscope-llm-logging-design.md](./2026-07-22-agentscope-llm-logging-design.md)、[2026-07-23-agentscope-middleware-observability-design.md](./2026-07-23-agentscope-middleware-observability-design.md)

---

## 1. 背景与目标

### 1.1 问题

模型服务偶发失败（网络抖动、上游 5xx、限流）时，Agent 任务可能进行到一半。当前 demo2 的 AgentScope 聊天几乎全部走单一 DeepSeek 客户端，失败即中断整次 `/dev-agent/ask`。文章指出 AgentScope 内置 `HarnessAgent.maxRetries` + `fallbackModel` 可切备用模型，但该能力**不会**自动覆盖 Memory Flush 等直接调用 `Model` 的路径。

### 1.2 需求

DeepSeek 在可继续的失败耗尽后，自动切到 Kimi `kimi-k3`；凡注入共享 AgentScope `Model` bean 的调用（HarnessAgent、SubAgent、A2A Risk Review、Memory Flush）都受益；日志能区分配置模型与实际完成模型。对外接口不变。

### 1.3 已确认决策

| 维度 | 选择 |
|------|------|
| 产品范围 | 文章能力 + 可观测（configuredModel / actualModel） |
| 备用模型 | Kimi / Moonshot，默认 `kimi-k3` |
| 覆盖面 | 所有使用 AgentScope `Model` 的注入点 |
| Memory Flush | 一并兜住 |
| 实现路径 | **方案 1**：自定义 `FailoverAgentscopeModel` 包装层（单一机制） |
| 框架 API | **不**启用 `HarnessAgent.fallbackModel` / 不为容错单独抬高 `maxRetries`，避免双重重试 |
| 配置风格 | 继续 `application.properties`（不引入 yml） |

### 1.4 非目标（本版不做）

- Spring AI / Embabel 聊天 failover
- 多级备用模型链（3+）
- 业务级错误过滤（仅 429/5xx 才切备；本版：首 chunk 前任意失败都可重试/切备）
- 流式半路断流后改用备用模型拼接答案
- 指数退避库、生产级熔断/健康探针
- 改前端 / SSE 事件模型

---

## 2. 架构

### 2.1 装配链（由内到外）

```text
OpenAIChatModel(DeepSeek + DeepSeekFormatter + OkHttpTransport)
  + OpenAIChatModel(Kimi kimi-k3 + OkHttpTransport)
      → FailoverAgentscopeModel（重试 + 切备）
          → LoggingAgentscopeModel（已有 LLM 请求/响应日志）
              → 注入 HarnessAgent / SubAgent / Risk Review / Memory
```

### 2.2 主链路（对外不变）

```text
DevAgentController
  → DevAgentService
  → HarnessAgent.streamEvents(...)
  → FailoverAgentscopeModel
       → DeepSeek（必要时重试）
       → 必要时切到 Kimi
  → Flux<DevAgentEvent>
```

### 2.3 职责划分

| 组件 | 职责 |
|------|------|
| `FailoverAgentscopeModel` | 主模型最多 `maxAttempts` 次；仍失败且 fallback 启用则切备用（同样 `maxAttempts`）；流式仅在首个有效 chunk 前失败才切换 |
| `LoggingAgentscopeModel` | 保持现有 DEBUG 请求/聚合响应日志；`getModelName()` 透传活跃委托 |
| `AgentExecutionLoggingMiddleware` | 记录 `configuredModel`（开始）与 `actualModel`（结束）；两者不同时标明已 fallback |
| `OkHttpTransport` | DeepSeek / Kimi 共用；保证同模型重试真正发出新 HTTP 请求 |
| `HarnessAgent` | 不设 `.fallbackModel()`；不为本特性单独抬高 `.maxRetries()` |

### 2.4 与文章差异（刻意）

| 点 | 文章 | 本设计 |
|----|------|--------|
| 容错位置 | `HarnessAgent.fallbackModel` | `FailoverAgentscopeModel` |
| Memory Flush | 不覆盖 | 覆盖（同 bean） |
| 双重重试 | 仅框架一层 | 包装层单一机制，框架侧不叠加 |

---

## 3. 配置与装配

### 3.1 `application.properties`

```properties
# 主模型：base-url / name 可环境变量覆盖，便于把 DeepSeek 指到假地址测 fallback
app.agentscope.dev-agent.model.api-key=${DEEPSEEK_API_KEY:}
app.agentscope.dev-agent.model.base-url=${DEEPSEEK_BASE_URL:https://api.deepseek.com}
app.agentscope.dev-agent.model.name=${DEEPSEEK_MODEL_NAME:deepseek-v4-pro}

# 容错：max-attempts = 单次调用该模型最多尝试次数（含首次，与文章一致）
app.agentscope.dev-agent.model-fallback.max-attempts=2
app.agentscope.dev-agent.model-fallback.fallback.api-key=${KIMI_API_KEY:${MOONSHOT_API_KEY:}}
app.agentscope.dev-agent.model-fallback.fallback.base-url=${KIMI_BASE_URL:https://api.moonshot.cn/v1}
app.agentscope.dev-agent.model-fallback.fallback.name=${KIMI_MODEL_NAME:kimi-k3}
```

### 3.2 `DevAgentProperties`

- 新增字段：`@Valid ModelFallback modelFallback`
- `ModelFallback` record：`@Valid Model fallback`，`@Min(1) int maxAttempts`
- 复用现有 `Model(apiKey, baseUrl, name)`；主/备字段形状一致
- `modelFallback == null` 时提供默认：`maxAttempts=2`，fallback 的 baseUrl/name 取上表默认，apiKey 可空

### 3.3 Bean 装配（`AgentScopeConfig`）

1. `@Bean(destroyMethod = "close") HttpTransport modelHttpTransport()` → `new OkHttpTransport()`
2. 构建 primary：`OpenAIChatModel` + `DeepSeekFormatter` + 共享 transport + `stream(true)`
3. 构建 fallback：`OpenAIChatModel` + 默认 formatter + 共享 transport + `stream(true)`（无 `DeepSeekFormatter`）
4. `new FailoverAgentscopeModel(primary, fallbackOrNull, maxAttempts)` → `new LoggingAgentscopeModel(..., "agentscope-deepseek")`
5. 仍以 `@Qualifier("agentscopeDeepSeekModel")` 对外暴露（**bean 名不变**）

### 3.4 密钥缺失策略

| 密钥 | 行为 |
|------|------|
| 缺 `DEEPSEEK_API_KEY` | 应用可启动；`/ask` 时由现有 `DevAgentService` 返回 ERROR（保持现状） |
| 缺 Kimi / Moonshot key | 仍装配 Failover 包装，但 **fallback 未启用**（仅主模型重试）；启动 warn 一次；主模型耗尽后抛最后异常 |

判定「fallback 启用」：fallback `apiKey` 非 null 且非 blank。

### 3.5 对外 API

`/dev-agent/ask`、`/dev-agent/confirm`、Plan Mode、Permission、Sandbox、A2A 入口均不变；变化仅在模型调用层。

---

## 4. `FailoverAgentscopeModel` 行为

### 4.1 接口

- 包：`com.jason.demo.demo2.agentscope.model`
- 实现：`io.agentscope.core.model.Model`
- 构造：`(Model primary, Model fallbackOrNull, int maxAttempts)`
  - `fallbackOrNull == null` 表示未启用备用
  - `maxAttempts >= 1`
  - 装配时：Kimi apiKey 空白则传入 `null`，不创建空密钥的 OpenAIChatModel

### 4.2 `stream()` 语义

对单次 `stream(messages, tools, options)`：

1. 对 primary 尝试最多 `maxAttempts` 次
2. 任一次在**已发出有效输出**后失败：不再切换，错误原样传播
3. 任一次在**首个有效 chunk 前**失败：记为可继续（同模型重试或切备）
4. 所有 primary 尝试都在首 chunk 前失败：
   - fallback 已启用 → 切到备用，同样最多 `maxAttempts` 次
   - fallback 未启用 → 抛出最后一次 primary 异常

**有效 chunk**：`Flux` 已 `onNext` 且 chunk 非 null。

**重试间隔**：demo 级；固定短延迟或不延迟；不引入指数退避库。

**Reactor**：用 `onErrorResume` / 重订阅实现，避免阻塞业务线程。

### 4.3 `getModelName()`

包装层维护当前活跃委托：

- 调用开始前 / 未切换时：primary 名 → middleware 的 `configuredModel`
- 成功走完且实际用了备用：返回 fallback 名 → middleware 的 `actualModel`

### 4.4 非流式 `generate`（若接口需要实现）

与 `stream` 同一套「尝试次数 + 切备」策略：在产生可用结果前失败则可重试/切备；已得到部分/完整结果后失败则不切备。

---

## 5. 观测

### 5.1 `FailoverAgentscopeModel`（info）

- 主模型重试：`Primary model {name} attempt {i}/{n} failed: {errorType}`
- 切备：`Primary model {primary} failed, switching to fallback {fallback}`
- 备用也耗尽：warn/error，带最后异常类型

### 5.2 `AgentExecutionLoggingMiddleware.onModelCall`

对齐文章字段：

- 开始：`configuredModel=...`（调用开始时读取 `input.model().getModelName()`）
- 成功结束：再读 `actualModel=...`；与 configured 不同时额外 info 标明已 fallback
- 失败：带 `configuredModel`（及若可读到的 `actualModel`）

Memory Flush 不经 middleware 时，仍可靠 Failover 包装层日志验证切换。

---

## 6. 测试与验证

### 6.1 单元测试

| 用例 | 期望 |
|------|------|
| primary 首次成功 | 不调用 fallback；`getModelName()` 为 primary |
| primary 首 chunk 前失败 × maxAttempts，fallback 成功 | 切到 fallback；流内容来自备用 |
| primary 已发出 chunk 后失败 | 不切备，错误传播 |
| fallback key 为空 / fallbackOrNull | 仅重试 primary，耗尽后失败 |
| `DevAgentProperties` 绑定 | `model-fallback.max-attempts` 与 `fallback.name=kimi-k3` |
| middleware | started 含 `configuredModel`，completed 含 `actualModel` |

用假 `Model` 桩驱动，不强制真调外部 API。

### 6.2 手工验证（非 CI 必须）

```bash
export DEEPSEEK_BASE_URL=http://127.0.0.1:65535
export KIMI_API_KEY=<real-key>
# 启动应用后
curl -sN -X POST "http://localhost:8080/dev-agent/ask" ...
```

期望日志出现 switching，且 `actualModel=kimi-k3`；SSE 仍能看到回答。

### 6.3 成功标准

1. DeepSeek 正常时行为与现网一致  
2. 主模型不可达且有 Kimi key 时，`/ask` 仍能出 SSE 答案  
3. 日志能区分 configured vs actual  
4. `@Qualifier("agentscopeDeepSeekModel")` bean 名不变，Risk Review / SubAgent 无需改注入点  

---

## 7. 实现触及文件（预期）

| 文件 | 变更 |
|------|------|
| `application.properties` | 主模型 env 覆盖 + `model-fallback` |
| `DevAgentProperties.java` | `ModelFallback` + 字段 |
| `AgentScopeConfig.java` | OkHttpTransport、双模型、Failover 包装 |
| 新建 `FailoverAgentscopeModel.java` | 重试 + 切备 |
| `AgentExecutionLoggingMiddleware.java` | configured / actual |
| 对应 `*Test.java` | 绑定、Failover、middleware |

---

## 8. 参考

- AgentScope Java 2.0：`maxRetries` 表示「最多尝试次数（含首次）」，不是「额外重试几次」
- 文章默认：内置 fallback **不**按 HTTP 状态过滤是否值得切备；本版包装层同样采用「首 chunk 前失败即可切」
- 已有装饰器先例：`LoggingAgentscopeModel`
