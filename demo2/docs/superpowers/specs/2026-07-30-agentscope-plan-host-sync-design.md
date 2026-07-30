# AgentScope Plan Mode：计划文件宿主同步

**日期**：2026-07-30  
**项目**：spring-ai-demo / demo2  
**状态**：已确认，待实现  
**前置能力**：[Plan Mode](./2026-07-29-agentscope-plan-mode-design.md)、Docker Sandbox、`TOOL_RESULT_END` SSE 映射  
**相关规范**：[2026-07-29 Plan Mode](./2026-07-29-agentscope-plan-mode-design.md)；[2026-07-27 Sandbox](./2026-07-27-agentscope-sandbox-design.md)

---

## 1. 背景与目标

### 1.1 问题

`plan_write` 把方案写在沙箱会话内的 `plans/PLAN.md`。宿主 `demo2/workspace/plans/` 仅有 `.gitkeep`；现有 Diff 回写只覆盖 `workspace/project`，不包含 `plans`。用户在 `plan_exit` HITL 前无法用编辑器直接打开宿主上的计划文件。

### 1.2 目标

1. `plan_write` **成功后立刻**自动把沙箱内 `plans/PLAN.md` 同步到宿主。
2. 固定覆盖写入 `workspace/plans/PLAN.md`（无会话后缀、无额外 HITL）。
3. 同步失败不阻断 Agent / `plan_exit` 确认流。

### 1.3 已确认决策

| 维度 | 决策 |
|------|------|
| 时机 | `TOOL_RESULT_END` 且工具为 `plan_write`、结果成功时立即同步 |
| 文件名 | 固定覆盖 `workspace/plans/PLAN.md` |
| 实现路径 | 事件钩子 + `PlanHostSyncService`（方案 1）；不包装官方 `plan_write`；不依赖回合结束快照 |
| HITL | 不同步步骤单独确认 |
| 沙箱关 | 钩子 no-op |

### 1.4 非目标

- 按会话/按用户分文件名
- 前端内嵌展示 `PLAN.md`
- 把 `plans/` 纳入 `WorkspaceDiff` 回写
- 新增查看计划的 HTTP API
- 修改 `plan_write` / Plan Mode 中间件本身

---

## 2. 方案选择

### 2.1 采用方案：`TOOL_RESULT_END` 钩子 + live 读沙箱

在 `DevAgentService` 映射 `TOOL_RESULT_END` 时触发同步：从**当前活跃沙箱会话**读取 `plans/PLAN.md`，原子写入宿主固定路径。

相对「回合结束抽快照」：时机对齐「写完即可见」，且不依赖 SESSION 中途是否已落 tar。相对「包装 `plan_write`」：不侵入官方 Plan Mode 工具链。

### 2.2 未采用方案

| 方案 | 原因 |
|------|------|
| 回合结束从 snapshot tar 抽 `plans/` | 进行中快照可能尚未包含新文件，与「立刻」不对齐 |
| 自定义/包装 `plan_write` 双写 | 与官方能力分叉，升级与权限对齐成本高 |

---

## 3. 架构与数据流

```text
plan_write → 沙箱 plans/PLAN.md
  → AgentEvent TOOL_RESULT_END (name=plan_write, state=成功)
  → DevAgentService 钩子（副作用；SSE 仍映射原 TOOL_RESULT_END）
  → PlanHostSyncService.syncAfterPlanWrite(userId, sessionId)
       → SandboxPlanReader 读沙箱 plans/PLAN.md
       → 原子写入宿主 workspace/plans/PLAN.md
  → 后续事件（含 plan_exit REQUIRE_USER_CONFIRM）照常下发
```

权威副本仍在沙箱；宿主文件为**只读镜像**，供人眼查看，不参与 Diff 回写。

---

## 4. 组件与改动点

| 组件 | 职责 |
|------|------|
| `DevAgentService` | `mapAgentEvents` 增加 `userId`；在 `plan_write` + 成功时调用 sync；其它工具不调 |
| `PlanHostSyncService` | 编排读→写；仅 `sandbox.enabled=true`；失败 WARN、不抛 |
| `SandboxPlanReader` | 从活跃沙箱读 `plans/PLAN.md`；优先 live（`SandboxClient` / `docker cp` / `exec cat` 等，实现阶段以可用 API 为准）；**禁止**仅依赖回合结束快照 |
| 宿主路径 | `{projectRoot}/{workspaceRoot}/plans/PLAN.md`（与现有 `DevAgentProperties` 一致） |

**不改**：`DevAgentController` ask/confirm 协议、Diff HITL、前端、`plan_write` 工具注册与权限。

写入方式：先写同目录临时文件，再 `Files.move(..., REPLACE_EXISTING)`，避免半截内容。

---

## 5. 错误、并发与边界

| 场景 | 行为 |
|------|------|
| `plan_write` 非成功 | 不同步 |
| 沙箱关 | no-op |
| 读沙箱失败 / 文件缺失 / 写宿主失败 | WARN（含 userId/sessionId）；保留宿主旧文件（若有）；不向 SSE 发 error；不阻断后续 HITL |
| 多次成功的 `plan_write` | 后写覆盖先写（固定路径） |
| 同步耗时 | 在事件钩子线程同步执行，保证 `plan_exit` 弹出前文件已落盘；计划 Markdown 体积小，可接受短暂阻塞 |

与 Diff：`WorkspaceDiffService` 范围不变（仅 `project/`）；宿主 `PLAN.md` 不进 diff 事件。

---

## 6. 文档与验收

### 6.1 文档

- 本 spec；修订 Plan Mode design §5.1：沙箱权威 + 宿主镜像（见相关链接）。
- README / 手工验收补充：`plan_write` 成功后、批准 `plan_exit` 前，宿主可打开 `workspace/plans/PLAN.md`。

### 6.2 测试

| 类型 | 断言 |
|------|------|
| 单测 `PlanHostSyncService` | 成功覆盖写入；读失败不抛；沙箱关 no-op |
| 单测钩子 | 仅 `plan_write`+成功调用 sync；其它工具 / 失败状态不调 |
| 手工（示例 15） | `plan_write` 后宿主 `PLAN.md` 有内容，再确认 `plan_exit` |

### 6.3 成功标准

- `plan_exit` HITL 出现前，宿主 `workspace/plans/PLAN.md` 内容与本次沙箱计划一致（同步成功时）。
- 同步失败时 Agent 流与确认协议行为与改前一致。

---

## 7. 实现约束摘要

- 触发：`TOOL_RESULT_END` + `plan_write` + 成功 + `sandbox.enabled=true`。
- 路径：固定覆盖宿主 `plans/PLAN.md`。
- 读源：live 沙箱；不依赖回合结束快照作为唯一手段。
- 失败：可观测、不抛、不改 SSE 协议。
- 编译门禁：`demo2` 下 `mvn -DskipTests compile`。
