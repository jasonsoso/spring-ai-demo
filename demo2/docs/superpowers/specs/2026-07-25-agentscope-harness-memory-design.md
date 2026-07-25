# AgentScope Memory 实战：跨会话长期记忆设计规范

**日期**：2026-07-25  
**项目**：spring-ai-demo / demo2  
**状态**：已确认，待实现  
**前置能力**：AgentScope Toolkit、AgentEvent SSE、Permission HITL、PostgreSQL AgentStateStore、Workspace、Compaction、Middleware、MCP  
**参考文章**：[12. AgentScope Java 2.0 Memory 实战：换个会话，Agent 还记得项目约定](https://mp.weixin.qq.com/s/7-fV-Q2RFYFTdaSYT6u1Mg)

---

## 1. 背景与目标

### 1.1 问题

同一 `sessionId` 下可追问（`AgentStateStore` 恢复会话）；换 `sessionId` 通常是新会话。项目约定（构建方式、测试命令、发布窗口等）不应随会话结束而丢失。

当前 Dev Agent 已具备 Workspace 与会话状态，但 `AgentScopeConfig` 显式调用了 `.disableMemoryTools()` / `.disableMemoryHooks()`，Harness 长期记忆未启用。

### 1.2 目标

1. 在现有 AgentScope Dev Agent 上打开 Harness Memory（不新建 Agent / Tab / API）。
2. 验证链路：会话 A `memory_save` → Workspace 写入 → 会话 B（同 `userId`、新 `sessionId`）仍能读到约定 → 换 `userId` 则隔离。
3. 提供 `memory.enabled` 总开关；测试 profile 默认 `false`。
4. 提供 `memory.save-requires-confirm`：控制 `memory_save` 是否走 HITL（默认 `true`）；`memory_search` / `memory_get` 始终 ALLOW。
5. 前端轻量增强：「新开会话（保留 userId）」按钮 + Memory 示例提示。
6. Controller / SSE / StateStore / Compaction / MCP 主流程不改。

### 1.3 已确认决策

| 维度 | 决策 |
|------|------|
| 接入位置 | 现有 AgentScope Dev Agent（非独立 Agent） |
| 方案 | 配置驱动启用 Memory + HITL 开关（方案 1） |
| 总开关 | `memory.enabled`；`false` 时保持 disable Tools/Hooks |
| 测试 | `application-test.properties` 中 `memory.enabled=false` |
| `memory_save` 权限 | `save-requires-confirm` 开关；默认 `true` → HITL；`false` → ALLOW |
| 只读工具 | `memory_search` / `memory_get` 始终 ALLOW |
| 前端 | 「新开会话（保留 userId）」；不做 MEMORY.md 只读面板 |
| Flush / Consolidation 间隔 | 与文章一致：`10m` / `30m`；`consolidation-max-tokens=4000` |
| 存储 | 沿用 `workspace-root=workspace` → `workspace/{userId}/MEMORY.md` |
| Maven | 不新增依赖；Harness 已含 Memory |

### 1.4 非目标

- 新建 Memory Demo Agent / Tab / 独立 Controller
- MEMORY.md 前端只读面板
- 向量 / 语义检索（`memory_search` 仍为关键词匹配）
- 生产级 `userId` 鉴权（demo 仍信任客户端；文档注明边界）
- 将订单、审批、余额等业务事务状态写入 Memory
- 改造 Spring AI AutoMemory / Session Memory Tab

---

## 2. 方案选择

### 2.1 采用方案：配置驱动启用 + HITL 开关

相对文章「直接移除 disable、memory_save 加入放行列表」，demo2 增加：

```text
memory.enabled                 # 总开关（测试默认 false）
memory.save-requires-confirm   # memory_save 是否 HITL（默认 true）
```

- `enabled=false`：继续 `.disableMemoryTools()` + `.disableMemoryHooks()`。
- `enabled=true`：装配 `MemoryConfig`，`.memory(...)`，按需注册权限规则。
- `save-requires-confirm=true`：不为 `memory_save` 加 ALLOW → 走现有 Permission HITL。
- `save-requires-confirm=false`：`memory_save` 与只读工具一并 ALLOW。

### 2.2 未采用方案

| 方案 | 原因 |
|------|------|
| 严格照文章（无开关） | 测试难关；与已选 HITL / 总开关决策冲突 |
| 独立 Memory Agent / Tab | 重复 Workspace/SSE/HITL；偏离文章「接进现有 Agent」 |

---

## 3. 总体架构

### 3.1 目标链路

```text
会话 A（userId 固定）
  → 模型调用 memory_save
  → [saveRequiresConfirm] 可选 HITL
  → Workspace/{userId}/MEMORY.md（及 memory/YYYY-MM-DD.md）
       ↓ 换 sessionId
会话 B → WorkspaceContextMiddleware 加载 MEMORY.md → 按约定回答
       ↓ 换 userId
会话 C → 另一份 Workspace → 不知道约定
```

### 3.2 三层记忆边界

| 机制 | 作用 | 隔离键 |
|------|------|--------|
| AgentStateStore | 恢复「这段会话聊到哪」 | userId + sessionId |
| Compaction | 缩短当前会话 context | 同一 session |
| Memory | 跨会话长期约定 / 偏好 / 决定 | **按 userId** 落在 Workspace |

三者并存、职责不重叠。本 Demo 重点演示 Memory 与 AgentStateStore 的差异，不改 Compaction 配置语义。

### 3.3 写入与读取路径（Harness 内置）

**主动写入**（演示主路径）：

```text
memory_save → 追加 MEMORY.md + memory/YYYY-MM-DD.md（立即可见）
```

**自动 Flush**（请求结束、到达 `flush-min-gap`）：

```text
MemoryFlushMiddleware → 提取值得长期保留的信息 → 追加当日 memory/*.md
```

**Consolidation**（请求结束、到达 `consolidation-min-gap`）：

```text
MemoryMaintenanceMiddleware + MemoryConsolidator
  → 合并 MEMORY.md 与每日记录 → 重写 MEMORY.md
```

**读取**：

```text
新请求 → 按 userId 定位 Workspace → 加载 MEMORY.md 进上下文
内容被截断时 → memory_search / memory_get
```

`flush-min-gap` / `consolidation-min-gap` 仅在当前 JVM 内计时，非定时任务、非多实例分布式锁。

---

## 4. 配置设计

### 4.1 Properties 模型

`DevAgentProperties` 增加 `@Valid Memory memory`：

```java
public record Memory(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("true") boolean saveRequiresConfirm,
        @NotNull Duration flushMinGap,
        @NotNull Duration consolidationMinGap,
        @Min(1) int consolidationMaxTokens,
        @NotBlank String flushPrompt,
        @NotBlank String consolidationPrompt) {
}
```

绑定前缀：`app.agentscope.dev-agent.memory.*`。

构造时：`memory == null` 则视为 `enabled=false` 的默认对象（与 `mcp` 空值处理一致），避免未配置时启动失败。当 `enabled=false` 时，其余字段仍可绑定，但 **不装配进 HarnessAgent**。

校验策略：`enabled=false` 时 compact constructor 为缺失的 gap / prompt 填入与文章一致的默认值（`10m` / `30m` / `4000` / 简短默认 prompt），保证测试 profile 只写 `memory.enabled=false` 即可启动；`enabled=true` 时必须能组装合法 `MemoryConfig`（含 consolidation-prompt 的两个 `%d`）。

### 4.2 application 配置

`application.properties`（本地默认开启，便于演示）：

```properties
app.agentscope.dev-agent.memory.enabled=true
app.agentscope.dev-agent.memory.save-requires-confirm=true
app.agentscope.dev-agent.memory.flush-min-gap=10m
app.agentscope.dev-agent.memory.consolidation-min-gap=30m
app.agentscope.dev-agent.memory.consolidation-max-tokens=4000
```

Prompt 放 `application-agentscope-prompts.yml`（UTF-8）：

```yaml
app:
  agentscope:
    dev-agent:
      memory:
        flush-prompt: |
          从对话中提取以后仍然有用的项目约定、用户偏好、技术决定和待办事项。
          忽略寒暄、临时状态、工具调用细节以及已经存在的重复信息。
          不记录密码、令牌、密钥、手机号、邮箱等敏感信息。
          只输出 Markdown 列表；没有值得保存的信息时，只输出 NO_REPLY。
        consolidation-prompt: |
          把现有 MEMORY.md 和新增的每日记忆整理成一份完整的长期记忆。
          合并重复信息；新决定覆盖已经失效的旧决定；删除寒暄、临时状态和敏感信息。
          最终内容不超过 %d tokens，约 %d 个字符。
          只输出整理后的完整 MEMORY.md，不要解释整理过程。
```

`consolidation-prompt` 中的两个 `%d` **必须保留**；框架填入 token 与字符上限，数量不对会在创建 `MemoryConfig` 时失败。

### 4.3 System prompt 增量

在现有 `system-prompt` 末尾追加（不替换 MCP / 工具说明）：

```text
用户明确要求记住项目约定、个人偏好或长期决定时，
调用 memory_save 保存；查询过去的决定时，优先使用长期记忆。
```

### 4.4 测试配置

`src/test/resources/application-test.properties`：

```properties
app.agentscope.dev-agent.memory.enabled=false
```

---

## 5. Agent 装配与权限

### 5.1 AgentScopeConfig

`@Bean MemoryConfig`：由 properties 组装；`HarnessAgent` 仅在 `memory.enabled=true` 时调用 `.memory(config)`，否则走 disable 分支（Bean 可始终存在，避免条件 Bean 复杂度）。

`HarnessAgent` 组装逻辑：

| `memory.enabled` | 行为 |
|------------------|------|
| `false` | `.disableMemoryTools()` + `.disableMemoryHooks()`（现状） |
| `true` | 去掉上述两行；`.memory(memoryConfig)` |

`MemoryConfig` 组装（与文章一致）：

```java
MemoryConfig.builder()
    .flushTrigger(MemoryConfig.FlushTrigger.throttled(config.flushMinGap()))
    .consolidationMinGap(config.consolidationMinGap())
    .consolidationMaxTokens(config.consolidationMaxTokens())
    .flushPrompt(config.flushPrompt())
    .consolidationPrompt(config.consolidationPrompt())
    .build();
```

### 5.2 Permission

仅 `enabled=true` 时追加规则：

| 工具 | `saveRequiresConfirm=true`（默认） | `=false` |
|------|-----------------------------------|----------|
| `memory_search` | ALLOW | ALLOW |
| `memory_get` | ALLOW | ALLOW |
| `memory_save` | **不加** ALLOW → HITL | ALLOW |

HITL 复用现有 `REQUIRE_USER_CONFIRM` → 前端确认卡 → `/agentscope/dev-agent/confirm`，不新增事件类型。

---

## 6. 前端

AgentScope Tab（`agentscope.js` / 对应 HTML）：

1. **「新开会话（保留 userId）」按钮**  
   - 生成新 `sessionId`（如 `memory-session-{短随机}`）  
   - 保留 `userId`  
   - 清空对话区并提示「已新开会话」，避免误以为仍在同一会话短期上下文中

2. **示例提示**  
   - 增加 Memory 场景：预填 `userId=memory-user-012`，消息为「请记住三条约定…」  
   - 可选第二条：换会话后询问构建/测试/发布（依赖用户先点「新开会话」）

3. **不做**：MEMORY.md 只读面板、独立 Memory Tab

---

## 7. 验证与测试

### 7.1 手工 / curl（对齐文章）

1. 会话 A：`userId=memory-user-012`，`sessionId=memory-session-a-012`，请记住三条项目约定；若 HITL 开则确认 `memory_save`。  
2. 磁盘出现 `workspace/memory-user-012/MEMORY.md`（及当日 `memory/*.md`）。  
3. 会话 B：同 `userId`，新 `sessionId`；询问约定且要求不调用项目文件工具 → 能答出。  
4. 会话 C：换 `userId` → 回答不知道。  
5. `memory.enabled=false` → Toolkit 无 memory 工具、无自动 flush。

### 7.2 自动化

- `DevAgentPropertiesBindingTest`：绑定 `enabled` / `saveRequiresConfirm` / Duration / prompts。  
- 可选：断言 `AgentScopeConfig` 在不同开关下对 `memory_save` 是否加入 ALLOW 白名单（若现有测试风格易做则做，不强制引入重型集成）。

### 7.3 文档

README 增加「AgentScope Memory」小节：对照文章三条 curl；明确与 Spring AI AutoMemory / Session Memory Tab **无关**；注明 demo 信任客户端 `userId`。

---

## 8. 错误处理与边界

- Memory 工具失败：由框架 TOOL_RESULT 返回；SSE 沿用现有 `ERROR` / 工具失败展示。  
- 不新增 `DevAgentEventType`。  
- Prompt 降低敏感信息误存概率；本 Demo 不做写入前审计过滤器。  
- Memory 不替代业务系统对事务状态的权威判断。

---

## 9. 实现落点（文件清单）

| 文件 | 变更 |
|------|------|
| `DevAgentProperties.java` | 增加 `Memory` record |
| `AgentScopeConfig.java` | 条件启用 Memory + 权限规则 |
| `application-agentscope-prompts.yml` | flush / consolidation / system-prompt 增量 |
| `application.properties` | memory 开关与间隔 |
| `application-test.properties` | `memory.enabled=false` |
| `static/.../agentscope` JS/HTML | 新开会话按钮 + 示例 |
| `DevAgentPropertiesBindingTest.java` | 绑定测试 |
| `README.md` | Memory 小节 |

**明确不改**：`DevAgentController`、`DevAgentService` 主流程、SSE 模型、MCP、Compaction 触发语义。

---

## 10. 成功标准

1. 同 `userId`、不同 `sessionId` 能读到会话 A 写入的项目约定。  
2. 不同 `userId` 读不到对方约定。  
3. `save-requires-confirm=true` 时 `memory_save` 出现 HITL；`false` 时直接执行。  
4. `memory.enabled=false`（含测试）不注册 Memory 工具、不挂 Memory Hooks。  
5. 前端可一键新开会话并保留 userId，完成跨会话演示。
