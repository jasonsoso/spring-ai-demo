# AgentScope 多模型容错设计规范

**日期**: 2026-07-30（初版） / **2026-07-31 修订**  
**项目**: spring-ai-demo / demo2  
**状态**: 已确认，待实现（修订：改用框架 API）  
**参考文章**: [AgentScope Java 2.0 多模型容错实战：DeepSeek 失败后，自动切到备用模型](https://mp.weixin.qq.com/s?__biz=MzcwMjA0Njk3Nw==&mid=2247484481&idx=1&sn=c7bfbf753cff1d862bbde643c36a6f9a)  
**前置**: [2026-07-22-agentscope-llm-logging-design.md](./2026-07-22-agentscope-llm-logging-design.md)、[2026-07-23-agentscope-middleware-observability-design.md](./2026-07-23-agentscope-middleware-observability-design.md)

---

## 0. 相对初版的变更（2026-07-31）

| 项 | 初版（已实现） | 本修订 |
|----|----------------|--------|
| 容错位置 | 自定义 `FailoverAgentscopeModel` | `HarnessAgent.maxRetries` + `fallbackModel`（与文章一致） |
| Memory Flush | 同 bean 覆盖 | **不覆盖**（框架行为；已接受） |
| Risk Review | 同 bean 间接受益 | **本轮不改** `ReActAgent` |
| 自定义包装 | 新增并装配 | **删除**类与单测 |

切备日志来源：框架 `io.agentscope.core.ReActAgent`（匿名 Model 包装，日志文案 `Primary model {} failed, switching to fallback {}`）。`HarnessAgent.fallbackModel` 最终落到该逻辑。

---

## 1. 背景与目标

### 1.1 问题

初版用 Model 层包装实现 DeepSeek → Kimi 切备，与系列文章示范不一致，且与框架内置能力重复。现改为文章写法，便于对照学习与维护。

### 1.2 需求

主 `HarnessAgent` 在 DeepSeek 可重试失败耗尽后，由 Kimi `kimi-k3` 接手；对外 `/dev-agent/ask` 不变；日志仍能区分 `configuredModel` / `actualModel`。

### 1.3 已确认决策

| 维度 | 选择 |
|------|------|
| 实现路径 | 文章原样：双 `Model` bean + `HarnessAgent.maxRetries` / `fallbackModel` |
| 自定义包装 | **删除** `FailoverAgentscopeModel` |
| Risk Review | 本轮不动 |
| Memory Flush | 不切备（接受） |
| 配置 | 继续 `app.agentscope.dev-agent.model-fallback.*`，默认 `kimi-k3` |
| 观测 | 保留 middleware `configuredModel` / `actualModel`；切备 info 依赖框架 `ReActAgent` |

### 1.4 非目标（本版不做）

- Risk Review `ReActAgent.fallbackModel`
- Memory Flush / 自定义 Model 层再包一层切备
- Spring AI / Embabel failover
- 多级备用链、业务级 HTTP 状态过滤
- 改 SSE / 前端

---

## 2. 架构

```text
OpenAIChatModel(DeepSeek + DeepSeekFormatter + OkHttpTransport)
  → LoggingAgentscopeModel → @Qualifier("agentscopeDeepSeekModel")

OpenAIChatModel(Kimi kimi-k3 + OkHttpTransport)   // apiKey 非空时
  → LoggingAgentscopeModel → @Qualifier("agentscopeKimiFallbackModel")

HarnessAgent.builder()
  .model(deepSeek)
  .maxRetries(modelFallback.maxAttempts)   // 含首次，默认 2
  .fallbackModel(kimi)                     // 仅 Kimi 已启用时
  ...
```

主链路：

```text
DevAgentController → DevAgentService → HarnessAgent
  → DeepSeek（框架按 maxRetries 重试）
  → 必要时 Kimi（ReActAgent 内置 fallback）
  → Flux<DevAgentEvent>
```

SubAgent 由 Harness 创建时跟随主 Agent 的模型/容错配置，不单独再配一套。

---

## 3. 配置与装配

### 3.1 配置（保持现有）

```properties
app.agentscope.dev-agent.model-fallback.max-attempts=2
app.agentscope.dev-agent.model-fallback.fallback.api-key=${KIMI_API_KEY:${MOONSHOT_API_KEY:}}
app.agentscope.dev-agent.model-fallback.fallback.base-url=${KIMI_BASE_URL:https://api.moonshot.cn/v1}
app.agentscope.dev-agent.model-fallback.fallback.name=${KIMI_MODEL_NAME:kimi-k3}
```

主模型 `model.*` 的 env 覆盖（`DEEPSEEK_BASE_URL` / `DEEPSEEK_MODEL_NAME`）保持不变。

### 3.2 `AgentScopeConfig`

1. 保留共享 `OkHttpTransport` bean（`destroyMethod = "close"`）
2. `agentscopeDeepSeekModel`：仅 DeepSeek + Logging（**去掉** Failover 包装）
3. 新增 `agentscopeKimiFallbackModel`：
   - apiKey 非空：Kimi `OpenAIChatModel`（无 `DeepSeekFormatter`）+ Logging（label 如 `agentscope-kimi`）
   - apiKey 空：不注册可用 bean / 返回可选空；启动 **warn**「fallback disabled」
4. `HarnessAgent.builder()`：

```java
builder.model(agentscopeDeepSeekModel)
       .maxRetries(properties.modelFallback().maxAttempts());
if (kimiFallback != null) {
    builder.fallbackModel(kimiFallback);
}
```

注入用 `ObjectProvider<Model>` 或 `@Autowired(required = false) @Qualifier("agentscopeKimiFallbackModel")`，避免无 Kimi key 时启动失败。

### 3.3 Risk Review

继续 `@Qualifier("agentscopeDeepSeekModel")`，不挂 `fallbackModel`。

### 3.4 删除

- `demo2/src/main/java/.../FailoverAgentscopeModel.java`
- `demo2/src/test/java/.../FailoverAgentscopeModelTest.java`
- 一切对该类的引用

---

## 4. 框架行为要点（对齐文章）

- `maxRetries` / 配置里的 `max-attempts`：**含首次**调用（例如 `2` = 首次 + 1 次重试）
- 流式：通常仅在**首个响应块之前**失败才切备（框架 `ReActAgent` 实现）
- 内置 fallback **不**按业务过滤 400/401；与文章描述一致，本轮不自定义过滤
- Memory Flush **不会**自动走 `fallbackModel`

---

## 5. 观测

| 来源 | 内容 |
|------|------|
| `ReActAgent` | `Primary model {} failed, switching to fallback {}` |
| `AgentExecutionLoggingMiddleware` | `configuredModel`（开始）/ `actualModel`（结束）；不同则 `Model fallback observed` |

---

## 6. 测试与验证

| 项 | 期望 |
|----|------|
| `DevAgentPropertiesBindingTest` | 仍通过 |
| `FailoverAgentscopeModelTest` | 随类删除 |
| `AgentExecutionLoggingMiddlewareTest` | 保持 configured/actual |
| 手工 | `DEEPSEEK_BASE_URL=http://127.0.0.1:65535` + `KIMI_API_KEY` → `/ask` 成功且 `actualModel=kimi-k3` |

### 成功标准

1. 代码无 `FailoverAgentscopeModel`  
2. DeepSeek 正常时行为与现网一致  
3. DeepSeek 不可达且有 Kimi 时 `/ask` 仍出 SSE 答案  
4. 无 Kimi key 时应用可启动；Risk Review 不受影响  

---

## 7. 实现触及文件（预期）

| 文件 | 变更 |
|------|------|
| `AgentScopeConfig.java` | 拆 Kimi bean；Harness 挂 maxRetries/fallbackModel；去掉 Failover 包装 |
| 删除 `FailoverAgentscopeModel*.java` | — |
| `README.md` | 容错段改为框架 API 描述 |
| 本 spec | 本修订 |

配置属性类与 `application.properties` 可不动。

---

## 8. 参考

- AgentScope Java 2.0.0：`HarnessAgent.Builder.maxRetries(int)` / `fallbackModel(Model)`
- 切备实现与日志：`io.agentscope.core.ReActAgent`
- 初版实现计划：`docs/superpowers/plans/2026-07-30-agentscope-model-failover.md`（实现路径已被本修订 supersede；实现时另写新 plan）
