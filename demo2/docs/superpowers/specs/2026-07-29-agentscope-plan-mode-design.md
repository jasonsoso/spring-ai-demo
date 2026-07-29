# AgentScope Plan Mode 实战：改代码前先确认方案

**日期**：2026-07-29  
**项目**：spring-ai-demo / demo2  
**状态**：已确认，待实现  
**前置能力**：AgentScope Toolkit、AgentEvent SSE、Permission HITL、PostgreSQL AgentStateStore、Workspace、Docker Sandbox（RetryPolicy 样例）、Compaction / Middleware / Memory（非沙箱路径）  
**参考文章**：[17. AgentScope Java 2.0 Plan Mode 实战：Agent 改代码前，先把方案交给你确认](https://mp.weixin.qq.com/s?__biz=MzcwMjA0Njk3Nw==&mid=2247484465&idx=1&sn=7a5371648c73ad92d08d42c6d6756a3a)  
**相关规范**：[2026-07-27 Sandbox](./2026-07-27-agentscope-sandbox-design.md)；[2026-07-22 Permission HITL](./2026-07-22-agentscope-permission-hitl-design.md)

---

## 1. 背景与目标

### 1.1 问题

复杂改动时，Agent 容易一边调查一边改代码。用户本意是先看问题，结果读了两个文件就开始改文件、跑命令；方向不对时改动已经发生。

Plan Mode 把调查与执行拆成两阶段：先只读调查并写出工作区计划文件，用户确认方案后才进入执行；执行阶段仍按 Permission 对每次危险操作分别确认。

### 1.2 目标

1. 在现有 Dev Agent 上打开 AgentScope Plan Mode 与 TaskList（`enablePlanMode()` + `enableTaskList()`）。
2. 对齐文章演示流：`plan_enter` → 只读调查 → `plan_write`（`plans/PLAN.md`）→ `plan_exit`（HITL）→ `todo_write` → `edit_file`（HITL）→ `execute`（HITL）。
3. 请求仍走现有 `POST /agentscope/dev-agent/ask` 与 `/confirm`，不新建「进入 Plan Mode」接口。
4. Plan Mode 流程提示在 `sandbox.enabled=true` 时注入 `systemPrompt`；`workspace/AGENTS.md` 常驻一条工作区规则。
5. Tab 增加 Plan Mode 自然语言示例（示例 15），确认 UI 复用现有 HITL 卡片。

### 1.3 已确认决策

| 维度 | 决策 |
|------|------|
| 落地程度 | 严格对齐文章三段确认演示（方案 1） |
| 引导位置 | `system-prompt`（沙箱开时注入完整流程）+ `AGENTS.md`（常驻一句） |
| 能力开关 | Plan Mode / TaskList **始终开启**；不另加 `plan-mode.enabled` |
| 流程提示 | **仅当** `sandbox.enabled=true` 时注入完整 Plan Mode 流程段 |
| 前端 | 新增示例按钮/文案；确认 UI 不单独增强（不内嵌 `PLAN.md`） |
| Shell in Plan | 不启用 `allowShellInPlanMode()` |
| 沙箱只读工具 | 规划阶段保留并放行 `list_files` / `glob_files` / `grep_files`（不再从 toolkit 移除） |
| `execute` 权限 | 沙箱下**取消**自动 ALLOW，改为与 `edit_file` 一样走 HITL |
| API | 不新增端点；不改 `DevAgentConfirmRequest` 语义 |

### 1.4 非目标

- 专用「进入 Plan Mode」HTTP 接口
- 前端内嵌展示 `plans/PLAN.md` 全文/摘要
- 按 `toolCallId` 逐条审批、计划版本管理、改计划专用接口
- 无用户明示时强制进入 Plan Mode
- 独立 `plan-mode.enabled` 配置开关
- 修改 A2A / Diff 回写 / 非沙箱 MCP 主路径语义（仅文档说明示例 13 在沙箱下 `execute` 也需确认）

---

## 2. 方案选择

### 2.1 采用方案：文章对齐增强

在现有 `HarnessAgent` + Sandbox + Permission HITL 上增强：始终注册 Plan Mode / TaskList；沙箱开时注入流程提示并调整工具裁剪与 `execute` 权限，使三段确认可稳定演示。

相对「只挂工具不改权限」：能对齐文章；相对「双配置分叉」：少一套开关与测试矩阵（YAGNI）。

### 2.2 未采用方案

| 方案 | 原因 |
|------|------|
| 最小挂载（不改工具裁剪 / `execute` ALLOW） | 规划工具不全；测命令可能不再弹确认 |
| 独立 `plan-mode.strict-hitl` 开关 | 配置与测试矩阵过重 |

---

## 3. 架构与流程

```text
用户（先调查、方案确认后再改 RetryPolicy）
  → POST /agentscope/dev-agent/ask
  → plan_enter（ALLOW）
  → 只读调查：read_file + list_files / glob_files / grep_files（ALLOW）
  → plan_write → plans/PLAN.md（ALLOW）
  → plan_exit → REQUIRE_USER_CONFIRM     ← 确认① 方案
  → POST /confirm approved=true
  → todo_write（ALLOW）
  → edit_file → REQUIRE_USER_CONFIRM     ← 确认② 改文件
  → /confirm
  → execute（如 mvn test）→ REQUIRE_USER_CONFIRM  ← 确认③ 跑测
  → /confirm → 最终结果
```

| 层 | 职责 |
|----|------|
| Plan Mode | 控制「只调查」vs「可执行」；退出规划前确认整案（`plan_exit`） |
| Permission | 控制单次危险操作；`plan_exit` / `edit_file` / `execute` 均为 ASK |
| Sandbox | 改代码与测试仍在 Docker 内；投影增加 `plans` |

Plan Mode 与 Permission 都会停任务等确认，但对象不同：前者是整案阶段门，后者是单次工具调用。批准 `plan_exit` **不等于**放行后续写文件或执行命令。

`todo_write` 只记录执行清单与进度，不负责权限。

---

## 4. 组件与配置改动

主要改动集中在 `AgentScopeConfig`、提示词、`AGENTS.md`、工作区 `plans/`、Tab 示例与 README。**不改** `DevAgentController` / `DevAgentService` 的 ask-confirm 协议。

| 改动点 | 内容 |
|--------|------|
| `HarnessAgent.builder()` | 始终 `.enablePlanMode()` + `.enableTaskList()`；不调用 `allowShellInPlanMode()` |
| 投影根 | `workspaceProjectionRoots` 增加 `"plans"` |
| 权限 ALLOW | 新增 `plan_enter`、`plan_write`、`todo_write`；**不加** `plan_exit`；沙箱下保留 `read_file` ALLOW，并对 `list_files` / `glob_files` / `grep_files` 加 ALLOW；**去掉** 沙箱下对 `execute` 的 ALLOW |
| 沙箱工具裁剪 | 仍移除 `write_file`；**不再移除** `list_files` / `glob_files` / `grep_files` |
| `systemPrompt` | `sandbox.enabled=true` 时追加 Plan Mode 流程段（对齐文章：用户要求先规划时第一项须 `plan_enter`；只读调查；`plan_write` → `plans/PLAN.md`；立刻 `plan_exit`；批准后 `todo_write`；执行用 `edit_file` / `execute`，`working_directory=project`；禁止 Shell 绕过文件工具）。同时修订现有「只许三件套」沙箱硬约束，允许规划阶段使用只读文件工具与 `plan_*` / `todo_write` |
| `workspace/AGENTS.md` | 常驻一条：用户要求先写计划再修复时，进入 Plan Mode，只读调查，`plan_write` 写 `plans/PLAN.md`，再经 `plan_exit` 申请执行 |
| 工作区 | 增加 `workspace/plans/`（`.gitkeep`）；运行时生成的 `PLAN.md` 纳入 gitignore（若尚无规则则补充） |
| 前端 | 示例 **15**：文案对齐文章「先调查、整理方案、确认前不改代码」；预填 `plan-user-017` / `plan-session-017` |
| README | 说明三段确认、依赖 `sandbox.enabled=true`、示例 13 在沙箱下 `execute`/`edit_file` 均需 HITL 的行为变化 |

`DevAgentService` 已有的 pending + `ConfirmResult` 恢复逻辑可直接承接三次停顿，无需改协议。

---

## 5. 数据流、错误与边界

### 5.1 持久化

- Plan Mode 会话阶段状态：现有 `AgentStateStore`（沙箱开时为 `PathSafeAgentStateStore`）。
- 计划正文：沙箱工作区内 `plans/PLAN.md`；通过 `workspaceProjectionRoots` 与 SESSION 快照在多次 `/confirm` 间保留。
- 同一 `userId` + `sessionId` 串起 ask 与多次 confirm；换会话不继承未完成规划。

### 5.2 确认语义

- SSE 仍为 `REQUIRE_USER_CONFIRM` + `pendingToolCalls`；整批 `approved`。
- 拒绝任一批：沿用现有拒绝恢复；不新增「改计划再试」接口。用户可新会话或再次 `/ask` 要求重写计划。

### 5.3 规划阶段拦截

- Plan Mode 中间件对规划阶段的 `edit_file` / `write_file` / `execute` 返回 DENY（未开 `allowShellInPlanMode`）。
- Agent 收到 DENY 后可继续只读调查与 `plan_write`。

### 5.4 与沙箱路径关系

- **沙箱开**：注入 Plan Mode 流程提示；工具与权限按第 4 节调整。
- **沙箱关**：Plan Mode / TaskList 工具仍注册，**不注入**完整流程提示；现有 notes / MCP / Memory 等演示不受影响。

---

## 6. 测试与验收

### 6.1 自动化

优先配置/装配单测，不绑定真实模型：

| 用例 | 断言 |
|------|------|
| Plan Mode 装配 | toolkit 含 `plan_enter` / `plan_write` / `plan_exit` / `todo_write` |
| ALLOW 规则 | 有 `plan_enter` / `plan_write` / `todo_write`；无 `plan_exit`；沙箱开时无 `execute` ALLOW；有 `read_file` 与 list/glob/grep ALLOW |
| 投影根 | 含 `plans` |
| 提示注入 | `sandbox.enabled=true` 时 systemPrompt 含 Plan Mode 关键句；`false` 时不含 |
| 工具裁剪 | 沙箱开时无 `write_file`，仍有 `list_files` / `glob_files` / `grep_files` |

### 6.2 手工验收

前置：`sandbox.enabled=true`、PostgreSQL、沙箱镜像已构建。

1. Tab 示例 15 或等价 curl：`ask` → 出现针对 `plan_exit` 的 `REQUIRE_USER_CONFIRM`，此时 `RetryPolicy` 源码未改。
2. 批准 → 出现 `edit_file` 确认。
3. 批准 → 出现 `execute`（如 `mvn test`）确认。
4. 批准 → 测试通过；回复可说明计划文件与改动摘要。
5. 文档写明：示例 13 在沙箱下也会对 `edit_file` / `execute` 走 HITL。

### 6.3 成功标准

- 三段确认可稳定演示。
- 非沙箱路径不因「未注入流程提示」而破坏现有示例。

---

## 7. 实现约束摘要

- AgentScope 能力：`enablePlanMode()`、`enableTaskList()`；不 `allowShellInPlanMode()`。
- 自动放行：`plan_enter`、`plan_write`、`todo_write`；**绝不**自动放行 `plan_exit`。
- 沙箱投影必须包含 `plans`。
- 流程提示仅沙箱开启时追加；`AGENTS.md` 规则常驻。
- 编译门禁：在 `demo2` 下 `mvn -DskipTests compile`（或仓库根带 `demo2/.mvn/settings.xml`）。
