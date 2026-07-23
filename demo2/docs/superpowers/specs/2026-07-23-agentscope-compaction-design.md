# AgentScope Compaction 长会话上下文压缩设计规范

**日期**: 2026-07-23  
**项目**: spring-ai-demo / demo2  
**状态**: 已确认，待实现  
**前置**: [2026-07-22-agentscope-postgres-session-design.md](./2026-07-22-agentscope-postgres-session-design.md)（已实现）；[2026-07-23-agentscope-workspace-agents-md-design.md](./2026-07-23-agentscope-workspace-agents-md-design.md)（Workspace / AGENTS.md）  
**参考**: [9. AgentScope Java 2.0 Compaction 实战：长会话怎么保住重点继续跑](https://mp.weixin.qq.com/s/WBkDkYHBl-Kg1DhH4uLm1w)

---

## 1. 背景与目标

### 1.1 问题

PostgreSQL `AgentStateStore` 解决的是重启后能否恢复会话；会话可持续增长后，用户消息、助手回复、工具调用与结果会堆进 `AgentState.context`。每轮推理携带更长历史，延迟与 token 成本上升，最终可能超过模型上下文窗口。

当前 `AgentScopeConfig` 显式调用 `.disableCompaction()`，框架不会在推理前压缩历史。

说明：demo2 另有 Spring AI Session Memory Tab 的 `RecursiveSummarizationCompactionStrategy`，与 AgentScope Harness Compaction **不是同一条链路**，本版不复用、不打通。

### 1.2 需求

1. 启用 AgentScope Harness **Compaction**：较早消息整理为一条摘要，最近消息保留原文
2. 压缩参数可配置；Demo 默认偏低阈值，便于同会话四轮 curl / 前端验证
3. 保留现有 Workspace、PostgreSQL、工具、HITL、SSE 主链路；API 路径不变
4. 因框架不向 `streamEvents()` 发射专用压缩事件，Service 用状态快照启发式探测，并向前端推送 `COMPACTION`（含前后条数）

### 1.3 已确认决策

| 维度 | 选择 |
|------|------|
| 落地方案 | **方案 1**：Harness 原生 `CompactionConfig` + Service 快照对比发 SSE |
| 阈值 | 可配置；默认 `trigger-messages=6`、`keep-messages=2`；配置旁注释正式环境建议上调 |
| 周边开关 | **严格三件套**：仅配置 trigger / keep / summaryPrompt；`keepTokens(0)`、`flushBeforeCompact(false)`、`offloadBeforeCompact(false)` 写死 |
| ToolResultEviction | **不碰**（沿用 Harness 默认，约 8 万字符） |
| API 路径 | **不改** |
| 前端 | 聊天区系统提示 + 条数（压缩前 N → 1 条摘要 + 约 M 条原文） |
| 摘要 prompt | 放 `application-agentscope-prompts.yml`，结构与文章一致 |

### 1.4 非目标（本版不做）

- 自定义 `ToolResultEvictionConfig`
- `flushBeforeCompact` / `offloadBeforeCompact` 可配置化或开启
- Memory 写入、自定义 Compaction Middleware
- 前端展示完整摘要正文或压缩前原文回放
- 与 Spring AI Session Memory Tab 打通
- SSE「上下文超限后自动压缩重试」（框架流式路径不支持；靠阈值留余量）

---

## 2. 架构

### 2.1 职责边界

| 组件 | 负责什么 |
|------|----------|
| `app.agentscope.dev-agent.compaction.*` | 阈值与摘要 prompt |
| `DevAgentProperties.Compaction` | 绑定与校验（`@Min` / `@NotBlank`） |
| `CompactionConfig` Bean | 组装框架配置；写死 keepTokens / flush / offload |
| `HarnessAgent` | `.compaction(config)` 替换 `.disableCompaction()` |
| `CompactionMiddleware`（框架） | `onReasoning` 达阈值时摘要旧消息 |
| `DevAgentService` | 快照对比 → 发 `COMPACTION` SSE |
| AgentScope Tab 前端 | 渲染系统提示 |

持久化与压缩职责分离（与文章一致）：

| 能力 | 负责 |
|------|------|
| `AgentStateStore` | 保存 / 恢复 `AgentState` |
| Compaction | 缩短 `AgentState` 内历史消息 |

### 2.2 数据流

```
POST /agentscope/dev-agent/ask|confirm
  → DevAgentService
       → 读 AgentState.context.size() → beforeCount
       → HarnessAgent.streamEvents(...)
            → 加入本轮 User 消息
            → onReasoning：达阈值则「摘要 + 最近 keepMessages」替换旧上下文（内存 AgentState）
            → 模型继续推理
            → 结束后 AgentState 写回 PostgreSQL（既有行为）
       → streamEvents 结束后再读 afterCount
            → 若判定已压缩 → 发 COMPACTION，再发 DONE
```

说明：压缩发生在推理前，但 **store 通常在整轮结束后才落库**；因此不在 `MODEL_CALL_START` 时读 store（易读到旧条数），而在流结束后探测。前端系统提示可能出现在本轮助手回复之后、`DONE` 之前。

要点：

- System / Workspace（`AGENTS.md`）不进入对话摘要内容侧的「旧消息」切割（框架分离 System 后再压缩）
- 切分会尽量避免拆开「工具调用 ↔ 工具结果」对；实际保留条数可能略异于 `keepMessages`
- 生成摘要会额外调用一次模型；阈值过低会增加摘要请求次数

### 2.3 改动面

| 位置 | 变更 |
|------|------|
| `application.properties` | 增加 compaction 数值配置 + 正式环境注释 |
| `application-agentscope-prompts.yml` | 增加 `compaction.summary-prompt` |
| `DevAgentProperties` | 嵌套 `Compaction` record |
| `AgentScopeConfig` | `CompactionConfig` Bean；Harness 启用 compaction |
| `DevAgentEventType` / `DevAgentEvent` | 新增 `COMPACTION` |
| `DevAgentService` | ask / confirm 共用探测逻辑 |
| `agentscope.js`（及必要 CSS） | 渲染系统提示 |
| 单测 | Properties 绑定、Config 组装、Service 探测 |

不改：Controller 路径、HITL 确认协议、Permission 规则、Workspace 文件内容（本版不要求改 `AGENTS.md`）。

---

## 3. 配置与 Bean

### 3.1 配置项

```properties
# Demo 默认偏低，便于四轮触发；正式环境请按上下文窗口 / 工具结果大小 / 平均轮数上调
app.agentscope.dev-agent.compaction.trigger-messages=6
app.agentscope.dev-agent.compaction.keep-messages=2
```

`summary-prompt`（YAML，多行）要点：

- 只保留用户目标、已确认事实、未完成事项、明确编号
- 不补充会话中未出现的信息
- 结构：`## 当前目标` / `## 已确认信息` / `## 待处理事项`
- 末尾：`会话内容：` + `{messages}`

语义（与文章一致）：

- `trigger-messages`：参与推理的**非 System 消息**条数阈值，不是「提问次数」
- `keep-messages`：普通情况下保留的最近原文条数；工具边界可能被框架调整

### 3.2 DevAgentProperties

```java
@Validated
@ConfigurationProperties(prefix = "app.agentscope.dev-agent")
public record DevAgentProperties(
        @NotBlank String name,
        @NotBlank String systemPrompt,
        @NotBlank String projectRoot,
        @NotBlank String workspaceRoot,
        @Valid Compaction compaction,
        @Valid Model model) {

    public record Compaction(
            @Min(2) int triggerMessages,
            @Min(1) int keepMessages,
            @NotBlank String summaryPrompt) {
    }

    public record Model(String apiKey, @NotBlank String baseUrl, @NotBlank String name) {
    }
}
```

### 3.3 CompactionConfig Bean

```java
@Bean
CompactionConfig agentscopeCompactionConfig(DevAgentProperties properties) {
    DevAgentProperties.Compaction c = properties.compaction();
    return CompactionConfig.builder()
            .triggerMessages(c.triggerMessages())
            .keepMessages(c.keepMessages())
            .keepTokens(0)
            .summaryPrompt(c.summaryPrompt())
            .flushBeforeCompact(false)
            .offloadBeforeCompact(false)
            .build();
}
```

HarnessAgent：注入该 Bean，`.compaction(agentscopeCompactionConfig)` 替换 `.disableCompaction()`。  
其余 `disableFilesystemTools` / `disableShellTool` / `disableMemoryTools` / `disableMemoryHooks` / `disableSubagents` / … 保持不变。

写死含义：

| 项 | 值 | 含义 |
|----|-----|------|
| `keepTokens` | `0` | 按 `keepMessages` 保留尾部（非固定 token 预算；非窗口动态 `-1`） |
| `flushBeforeCompact` | `false` | 不在压缩前抽长期 Memory |
| `offloadBeforeCompact` | `false` | 不把压缩前原文归档到 Workspace JSONL |

---

## 4. SSE 与前端

### 4.1 事件模型

新增：

```java
DevAgentEventType.COMPACTION
```

工厂方法：`DevAgentEvent.compaction(sessionId, content)`。

`content` 示例：

```text
上下文已压缩：7 条 → 1 条摘要 + 2 条原文（共 3 条）
```

其中「2」取自配置 `keepMessages`；实际尾部条数可能因工具边界微调，文案不要求与框架日志 `keeping=` 逐字一致。

### 4.2 探测算法（ask / confirm 共用）

1. 流开始前：`agentStateStore.get(userId, sessionId, "agent_state", AgentState.class)` → `beforeCount = context.size()`（无状态则为 0）
2. `streamEvents` 映射为既有 SSE 事件（逻辑不变）
3. **流结束后**再读 store → `afterCount`（此时压缩结果应已落库）
4. 判定：`afterCount > 0 && afterCount < beforeCount`  
   （必须相对流开始前**真正变少**；未压缩时简单问答约 `before+2`，同条数不触发，避免误报）
5. 若成立：在 `DONE` **之前**插入一条 `COMPACTION`（本轮至多一次）
6. 读 store 异常：跳过探测，不阻断主 SSE；仍发 `DONE`（若主流程未已 ERROR）

文案数字：`beforeDisplay = beforeCount + 1`（含本轮 User 的「压缩前」口径）、`afterCount`、配置中的 `keepMessages`。

### 4.3 前端

- `agentscope.js` 的 `handleAgentscopeSsePayload` 识别 `COMPACTION`
- 在消息区插入系统行（独立样式类，如灰字系统提示），文案用 `payload.content`
- 状态栏同步显示 `COMPACTION`
- 可选：示例按钮引导同 session 四轮「只确认、不调工具」流程（实现阶段写入 plan）

---

## 5. 错误处理与边界

- 摘要模型调用失败：遵循框架默认行为；本版不另做重试包装
- 快照探测失败：静默跳过 `COMPACTION` 事件
- 缺 `DEEPSEEK_API_KEY`：与现有一致，提前返回 `ERROR`
- 阈值过高贴模型上限：流式路径不会在超限拒绝后自动压缩重试；Demo 默认 6/2 留余量，正式环境注释提醒勿贴上限
- Compaction 不替代业务库：订单号、审批状态等关键事实仍应在结构化存储；摘要只保任务继续所需的目标 / 事实 / 待办

信息归属（与文章对照，本版只实现 Compaction 列）：

| 信息 | 位置 |
|------|------|
| 当前目标、已完成步骤、下一步 | Compaction 摘要 |
| 项目规则、工具约定 | `AGENTS.md`（已有） |
| 用户长期偏好 | Memory（本版不做） |
| 审批 / 订单等业务事实 | 业务表（本版不做） |
| 压缩前完整原文 | 会话归档 / offload（本版关闭） |

---

## 6. 测试与验收

### 6.1 自动化

- `DevAgentProperties` 绑定：compaction 三项可读
- `CompactionConfig` 组装：trigger / keep / prompt 正确；flush/offload 为 false；keepTokens 为 0
- `DevAgentService`：mock store 模拟压缩前后条数，断言流中出现一次 `COMPACTION` 且位于 `DONE` 之前；未压缩时不出现

### 6.2 手工验收

1. 同 `userId` + `sessionId` 发送四轮「只确认收到、不要调用工具」类消息（对照文章 CTX-009 流程，session 前缀可用 `context-session-009`）
2. 第四轮前日志出现 `Compaction triggered` / `Compaction complete`
3. 第四轮 SSE 含 `COMPACTION`，文案含前后条数
4. 前端出现对应系统提示
5. 第四轮回答仍能汇总已确认信息与待办（任务编号等未丢）
6. Controller 路径仍为 `/agentscope/dev-agent/ask` 与 `/confirm`；`mvn test` 相关用例通过

---

## 7. 实现顺序建议

1. Properties + YAML prompt + 绑定单测  
2. `CompactionConfig` Bean + Harness 启用  
3. `COMPACTION` 事件类型与工厂方法  
4. `DevAgentService` 探测（ask + confirm）+ 单测  
5. 前端系统提示  
6. README / 示例按钮（可选）与手工四轮验收  

实现计划在本 spec 用户确认后，由 writing-plans 产出。
