# AgentScope Model Failover → HarnessAgent API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 去掉自定义 `FailoverAgentscopeModel`，改为 `HarnessAgent.maxRetries` + `fallbackModel`（DeepSeek → Kimi），与文章及修订后的设计规范一致。

**Architecture:** 拆成 `agentscopeDeepSeekModel` 与可选 `agentscopeKimiFallbackModel` 两个 Logging 包装 bean，共用 `OkHttpTransport`；仅在 `buildAgentscopeDevAgent` 里对 `HarnessAgent.Builder` 设置 `.maxRetries(...)` 与条件 `.fallbackModel(...)`。Risk Review / Memory Flush 本轮不改。

**Tech Stack:** Java 21、Spring Boot、AgentScope Java 2.0.0（`HarnessAgent.Builder.maxRetries` / `fallbackModel`）、JUnit 5。

**设计规范:** [docs/superpowers/specs/2026-07-30-agentscope-model-failover-design.md](../specs/2026-07-30-agentscope-model-failover-design.md)（2026-07-31 修订）

**Supersedes:** [2026-07-30-agentscope-model-failover.md](./2026-07-30-agentscope-model-failover.md)（初版 FailoverAgentscopeModel 路径）

## Global Constraints

- AgentScope 保持 `2.0.0`，不新增 Maven 依赖
- **只改** `HarnessAgent` 容错接线；**不改** Risk Review `ReActAgent`
- **删除** `FailoverAgentscopeModel` 及其单测，禁止双重容错
- 配置前缀不变：`app.agentscope.dev-agent.model-fallback.*`，默认模型 `kimi-k3`
- Kimi apiKey 空：不注册有效 fallback bean / 不调用 `.fallbackModel`；启动 warn
- 保留 middleware `configuredModel` / `actualModel`
- 编译：`demo2` 下 `.\mvnw.cmd -DskipTests compile`
- 门禁：`.\mvnw.cmd "-Dtest=DevAgentPropertiesBindingTest,AgentExecutionLoggingMiddlewareTest,AgentScopeMiddlewareConfigTest" test`

---

## File Map

**Delete**

- `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/FailoverAgentscopeModel.java`
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/model/FailoverAgentscopeModelTest.java`

**Modify**

- `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`：拆 Kimi bean；Harness 挂 maxRetries/fallbackModel；去掉 Failover 包装
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java`：若 `agentscopeDevAgent` 重载签名变化，同步传 `null` kimi
- `demo2/README.md`：多模型容错段改为框架 API 描述

**不改**

- `DevAgentProperties` / `application.properties`（配置已就绪）
- `RiskReviewAgentConfiguration`
- `AgentExecutionLoggingMiddleware`（观测已具备）

---

### Task 1: 拆双 Model bean + HarnessAgent 接线

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java`（仅当编译要求改签名时）

**Interfaces:**
- Produces:
  - `@Qualifier("agentscopeDeepSeekModel") Model` — 仅 DeepSeek + Logging
  - `@Qualifier("agentscopeKimiFallbackModel") Model` — apiKey 非空时才注册有效实例；空 key 时 `@Bean` 返回 `null`（Spring 跳过注册）或等价「无 bean」
  - `buildAgentscopeDevAgent(..., Model kimiFallbackOrNull, ...)` — `maxRetries` + 条件 `fallbackModel`
- Consumes: 现有 `DevAgentProperties.modelFallback()`、`OkHttpTransport`

- [ ] **Step 1: 重写 `agentscopeDeepSeekModel`（去掉 Failover）**

替换当前「主+备塞进 FailoverAgentscopeModel」方法体为：

```java
/** DeepSeek 对话模型，外包一层请求/响应日志 */
@Bean
@Qualifier("agentscopeDeepSeekModel")
Model agentscopeDeepSeekModel(
        DevAgentProperties properties,
        HttpTransport agentscopeModelHttpTransport) {
    DevAgentProperties.Model primaryCfg = properties.model();
    Model primary = OpenAIChatModel.builder()
            .apiKey(primaryCfg.apiKey() == null ? "" : primaryCfg.apiKey())
            .baseUrl(primaryCfg.baseUrl())
            .modelName(primaryCfg.name())
            .formatter(new DeepSeekFormatter())
            .httpTransport(agentscopeModelHttpTransport)
            .stream(true)
            .build();
    return new LoggingAgentscopeModel(primary, "agentscope-deepseek");
}
```

删除本方法内对 `FailoverAgentscopeModel` 的 import/引用。

- [ ] **Step 2: 新增 Kimi fallback bean**

紧接在 DeepSeek bean 之后：

```java
/**
 * Kimi 备用模型。apiKey 为空时返回 null（不注册 bean），启动时 warn。
 */
@Bean
@Qualifier("agentscopeKimiFallbackModel")
Model agentscopeKimiFallbackModel(
        DevAgentProperties properties,
        HttpTransport agentscopeModelHttpTransport) {
    DevAgentProperties.Model f = properties.modelFallback().fallback();
    String key = f.apiKey();
    if (key == null || key.isBlank()) {
        log.warn(
                "AgentScope model fallback disabled: set KIMI_API_KEY or MOONSHOT_API_KEY to enable");
        return null;
    }
    Model kimi = OpenAIChatModel.builder()
            .apiKey(key)
            .baseUrl(f.baseUrl())
            .modelName(f.name())
            .httpTransport(agentscopeModelHttpTransport)
            .stream(true)
            .build();
    return new LoggingAgentscopeModel(kimi, "agentscope-kimi");
}
```

- [ ] **Step 3: 把 kimi 传入 `buildAgentscopeDevAgent` 并挂框架 API**

1. 给 `@Bean HarnessAgent agentscopeDevAgent(...)` 增加参数：

```java
@Qualifier("agentscopeKimiFallbackModel") ObjectProvider<Model> agentscopeKimiFallbackModel,
```

在调用 `buildAgentscopeDevAgent` 时传入 `agentscopeKimiFallbackModel.getIfAvailable()`。

2. 给 `private HarnessAgent buildAgentscopeDevAgent(...)` 增加参数 `Model kimiFallbackOrNull`（放在 deepSeek 参数之后）。

3. 在 `HarnessAgent.Builder builder = HarnessAgent.builder()...` 处，在已有 `.model(agentscopeDeepSeekModel)` 之后增加：

```java
builder.maxRetries(properties.modelFallback().maxAttempts());
if (kimiFallbackOrNull != null) {
    builder.fallbackModel(kimiFallbackOrNull);
}
```

注意：当前代码是链式赋给 `builder` 再 `if (sandbox)` 继续改；把 `maxRetries` / `fallbackModel` 加在首次 builder 创建链上，或在链创建后立刻调用上述两行（在 sandbox 分支之前即可）。

4. 更新所有包内重载 `agentscopeDevAgent(...)`（测试用、无 Spring `ObjectProvider` 的那些）：向 `buildAgentscopeDevAgent` **多传一个 `null`**（表示无 Kimi），保证现有单测不强制要 Kimi。

示例（参数列表以文件现状为准，只在 deepSeek 后插入 `null`）：

```java
return buildAgentscopeDevAgent(
        agentscopeDeepSeekModel,
        null, // kimiFallbackOrNull
        properties,
        ...
);
```

- [ ] **Step 4: 编译 + MiddlewareConfig 单测**

Run:

```powershell
cd d:\ai\spring-ai-demo\demo2
.\mvnw.cmd -DskipTests compile
.\mvnw.cmd "-Dtest=AgentScopeMiddlewareConfigTest" test
```

Expected: BUILD SUCCESS；若签名遗漏 `null`，编译失败并按报错补齐。

- [ ] **Step 5: Commit**

```powershell
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java `
  demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java
git commit -m "refactor(demo2): wire HarnessAgent maxRetries and fallbackModel"
```

---

### Task 2: 删除 FailoverAgentscopeModel

**Files:**
- Delete: `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/FailoverAgentscopeModel.java`
- Delete: `demo2/src/test/java/com/jason/demo/demo2/agentscope/model/FailoverAgentscopeModelTest.java`

**Interfaces:**
- Consumes: Task 1 已无引用
- Produces: 代码库中零匹配 `FailoverAgentscopeModel`

- [ ] **Step 1: 确认无残留引用**

Run:

```powershell
cd d:\ai\spring-ai-demo
rg "FailoverAgentscopeModel" demo2/src
```

Expected: 仅上述两个待删文件（或已无匹配）。若 `AgentScopeConfig` 仍有 import，先删干净再删文件。

- [ ] **Step 2: 删除两个文件**

```powershell
Remove-Item demo2/src/main/java/com/jason/demo/demo2/agentscope/model/FailoverAgentscopeModel.java
Remove-Item demo2/src/test/java/com/jason/demo/demo2/agentscope/model/FailoverAgentscopeModelTest.java
```

若 `agentscope/model` 目录已空，可保留空目录或删除目录（按仓库习惯；无 `.gitkeep` 则可删空包）。

- [ ] **Step 3: 再编译确认**

Run:

```powershell
cd d:\ai\spring-ai-demo\demo2
.\mvnw.cmd -DskipTests compile
```

Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```powershell
git add -u demo2/src/main/java/com/jason/demo/demo2/agentscope/model `
  demo2/src/test/java/com/jason/demo/demo2/agentscope/model
git commit -m "refactor(demo2): remove FailoverAgentscopeModel in favor of framework fallback"
```

---

### Task 3: README + 回归门禁

**Files:**
- Modify: `demo2/README.md`（约「多模型容错（DeepSeek → Kimi）」小节）

- [ ] **Step 1: 改 README 容错段**

将实现说明替换为：

```markdown
**多模型容错（DeepSeek → Kimi）：**

- 实现：`HarnessAgent.maxRetries` + `fallbackModel`（与 AgentScope 文章一致；切备日志在 `io.agentscope.core.ReActAgent`）
- 主模型 bean：`agentscopeDeepSeekModel`；备用：`agentscopeKimiFallbackModel`（需 `KIMI_API_KEY` / `MOONSHOT_API_KEY`）
- 配置：`app.agentscope.dev-agent.model-fallback.*`；默认备用名 `kimi-k3`；`max-attempts` 含首次（默认 `2`）
- 范围：主 HarnessAgent（含其 SubAgent 策略）；**不含** Memory Flush、本轮也不改 A2A Risk Review
- 未配置 Kimi 密钥：启动 WARN，不挂 `fallbackModel`
- 观测：middleware `configuredModel` / `actualModel`；框架日志 `switching to fallback`
```

手工验证 curl 块可保留（`DEEPSEEK_BASE_URL=http://127.0.0.1:65535`）。

功能表一行若仍写「FailoverAgentscopeModel」，改为「HarnessAgent fallbackModel」。

- [ ] **Step 2: 跑门禁测试**

Run:

```powershell
cd d:\ai\spring-ai-demo\demo2
.\mvnw.cmd "-Dtest=DevAgentPropertiesBindingTest,AgentExecutionLoggingMiddlewareTest,AgentScopeMiddlewareConfigTest" test
```

Expected: BUILD SUCCESS，Tests PASS。  
（`FailoverAgentscopeModelTest` 应已不存在，勿列入。）

- [ ] **Step 3: Commit**

```powershell
git add demo2/README.md
git commit -m "docs(demo2): document HarnessAgent-based DeepSeek to Kimi failover"
```

---

## Spec Coverage Checklist

| Spec 要求 | Task |
|-----------|------|
| DeepSeek / Kimi 双 bean + Logging + OkHttp | Task 1 |
| HarnessAgent maxRetries + fallbackModel | Task 1 |
| 无 Kimi key 时 warn、不挂 fallback | Task 1 |
| Risk Review 不动 | 全局约束 |
| 删除 FailoverAgentscopeModel | Task 2 |
| README 更新 | Task 3 |
| middleware configured/actual 保留 | 不改代码 / Task 3 门禁 |
| 配置属性不动 | 全局约束 |

## Out of Scope

- Risk Review `fallbackModel`
- Memory Flush 切备
- 重写旧 plan `2026-07-30-agentscope-model-failover.md` 正文（仅由本 plan supersede）
