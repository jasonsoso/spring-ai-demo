# AgentScope DistributedStore + Sandbox 跨实例接力设计规范

**日期**: 2026-07-31  
**项目**: spring-ai-demo / demo2  
**状态**: 已确认，待实现  
**前置**: [2026-07-25 PostgreSQL DistributedStore / 共享 Workspace](./2026-07-25-agentscope-postgres-distributed-workspace-design.md)；[2026-07-27 Docker Sandbox](./2026-07-27-agentscope-sandbox-design.md)；[2026-07-29 Plan Mode](./2026-07-29-agentscope-plan-mode-design.md)  
**参考**: [AgentScope Java 2.0 DistributedStore 实战（关掉实例还能接着执行）](https://mp.weixin.qq.com/s?__biz=MzcwMjA0Njk3Nw==&mid=2247484487&idx=1&sn=32ec33b9f4554ec367b595425dec6ce1)

---

## 1. 背景与目标

### 1.1 问题

当前装配：

- `distributed.enabled` + PG：会话 state + `RemoteFilesystemSpec`（无沙箱）已共享。
- `sandbox.enabled`：Docker Workspace + **`LocalSnapshotSpec`（本机目录）**；**不**挂 `.distributedStore(...)`。

因此实例 A 在 `plan_exit` 等待确认后退出，实例 B 即使能读到 PG 里的 AgentState，也**无法**还原 A 机器上的沙箱 `/workspace` tar，跨实例接力失败。

### 1.2 需求（本版）

1. 沙箱开启且 DistributedStore 为 remote 时：挂 `.distributedStore(...)`，去掉 Docker 上的 `LocalSnapshotSpec`，由框架注入 `PostgresSnapshotSpec` + 执行锁。
2. 沙箱 sessionId 含 `/`：继续用 `PathSafeAgentStateStore`；**允许**与 `.distributedStore` 同时设置（PathSafe 仅为编码层，底层仍是 DistributedStore 钉住的同一 `agentStateStore`）。
3. 验收场景：实例 A `/ask` → `plan_exit` HITL → 关 A → 实例 B `/confirm` → 恢复 workspace 并完成后续 `edit_file` / `execute`。
4. 快照落点本版用 PG `BYTEA`（官方 `PostgresSnapshotSpec`）；不接 S3/MinIO。
5. PG / distributed 关或降级时：沙箱行为保持现有本地快照路径，单机可演示。

### 1.3 已确认决策

| 维度 | 选择 |
|------|------|
| 范围 | **最小闭环**：双端口 curl 跨实例 HITL 接力；不改 Controller / SSE 协议 |
| 快照 | PG BYTEA（跟文章一致）；对象存储列为后续 |
| 装配例外 | 沙箱 + remote：**允许** `.distributedStore` + `.stateStore(PathSafe(delegate))` |
| Diff / apply-diff | **本版非目标**：仍绑 `LocalSnapshotSpec`；PG 快照会话 Diff 可能为空，文档标明 |
| RemoteFilesystem | 沙箱开时仍**不**挂（与 07-27 一致）；盘面靠 Docker + Snapshot |
| DataSource | 继续 `app.agentscope.datasource.*`，不改 `spring.datasource` |
| 中途崩溃 | 不承诺模型/工具执行中途 crash recovery；接力点 = `REQUIRE_USER_CONFIRM` |

### 1.4 非目标

- 改写 `WorkspaceDiffService` / `/apply-diff` 读 PG 快照
- `RemoteSnapshotSpec` + S3/OSS/MinIO
- 远程沙箱节点 / 非本机 Docker
- 旧本地 tar → PG 自动迁移
- 任务队列、幂等框架、生产级 LB 粘性拆除方案（README 简述即可）
- 热切换 postgres ↔ 本地快照

---

## 2. 架构

### 2.1 装配矩阵

```text
sandbox.enabled
    │
    ├─ false → 现有路径（distributed remote → RemoteFilesystem；local → stateStore）
    │
    └─ true
          │
          ├─ distributed Remote
          │     HarnessAgent
          │       .distributedStore(pinned)
          │       .stateStore(PathSafe(pinned.agentStateStore))   ← 编码例外
          │       .filesystem(DockerFilesystemSpec 无 LocalSnapshotSpec)
          │     → 自动 PostgresSnapshotSpec + advisory lock
          │
          └─ distributed Local（开关关或 PG 不可达）
                HarnessAgent
                  .stateStore(PathSafe(InMemory))
                  .filesystem(DockerFilesystemSpec + LocalSnapshotSpec)
                → 与现网单机沙箱一致
```

### 2.2 接力数据流

```text
实例 A: POST /ask
  → Plan Mode 调查 + plan_write
  → plan_exit → REQUIRE_USER_CONFIRM
  → PG: agent_state + _sandbox_state + agentscope_snapshots(tar)

关 A（容器与进程均消失）

实例 B: POST /confirm（同一 userId + sessionId）
  → 读 AgentState（待确认 plan_exit）
  → 读 SandboxState → 新 Docker 容器
  → 从 PG 拉快照还原 /workspace（含 plans/PLAN.md、project）
  → 继续执行；后续 edit_file / execute 仍走 Permission HITL
```

### 2.3 与既有「禁止双重注入」的关系

07-25 规范：无沙箱成功路径禁止同时 `.stateStore(pg)` 与 `.distributedStore(...)`（避免两套物理 store）。

本版例外仅适用于沙箱：

- `.distributedStore` 提供 Snapshot / Lock / 默认 state 能力；
- `.stateStore(PathSafe(x))` 中 `x` **必须**是 `distributedStore.agentStateStore()` 的同一实例（已由 Factory 钉住）；
- PathSafe 不新建 PG 连接、不新建表。

无沙箱路径规则不变。

---

## 3. 配置变更

| 项 | 行为 |
|----|------|
| `app.agentscope.distributed.enabled` | 不变 |
| `app.agentscope.dev-agent.sandbox.snapshot-root` | **仅**在「沙箱开且 distributed Local」时必填并使用；remote 时可不配 / 忽略，不向 Docker 挂 LocalSnapshot |
| 其它 sandbox 字段 | 不变 |

属性校验：`sandbox.enabled=true` 时，若 backend 将为 Local，仍校验 `snapshot-root`；若启动时已是 Remote，可不强制 blank 检查（实现时：校验逻辑改为「需要 LocalSnapshot 时才检查」或保留默认值但不使用）。

---

## 4. 文件与职责

| 组件 | 改动 |
|------|------|
| `AgentScopeConfig.dockerFilesystemSpec` | 增加「是否挂 LocalSnapshot」参数；remote 时不设 `snapshotSpec` |
| `AgentScopeConfig` 沙箱分支 | remote 时 `.distributedStore` + `.stateStore(pathSafe)` |
| `DevAgentProperties.Sandbox` | 放宽：remote 场景不因未用 snapshot-root 启动失败 |
| `WorkspaceDiffService` | **不改**；README 注明 PG 快照下 Diff 可能不可用 |
| `README.md` | 跨实例 curl 验收步骤 + 能力边界 |
| 单测 | 装配分支：remote 沙箱无 LocalSnapshot；local 沙箱有 LocalSnapshot |

---

## 5. 验收

### 5.1 自动化（门禁）

- 现有相关单测仍通过。
- 新增/扩展装配测试：沙箱 + Remote backend → filesystem **未**设置 LocalSnapshot（或等价可断言的 builder 状态）；沙箱分支调用了 `distributedStore`。
- 编译：`mvn -f demo2/pom.xml -DskipTests compile`

### 5.2 手工（跨实例）

前置：Docker、`agentscope-java-sandbox:17`、PG、`sandbox.enabled=true`、`distributed.enabled=true`。

1. 实例 A：`8080` `/ask`（Plan Mode 调查并等确认）
2. 确认 PG 有 session / snapshot 行（可选）
3. 实例 B：`8082`；关 A
4. B：`/confirm` 同一 userId/sessionId，多次确认直至完成
5. 期望：B 能继续改文件并跑测试，不依赖 A 的本机 snapshot 目录

### 5.3 明确不验收

- Diff Tab / apply-diff 跨实例
- 模型调用中途 kill -9 后自动续跑
- 大仓库快照性能与清理策略

---

## 6. 风险与缓解

| 风险 | 缓解 |
|------|------|
| AgentScope 仅在「未设 snapshotSpec」时才注入 PostgresSnapshot | 实现前对照 jar/文档；单测或手工确认表 `agentscope_snapshots` 有数据 |
| PathSafe + distributedStore 双重设置被框架忽略其一 | 对照 Harness builder 行为；HITL confirm 必须能读到 ASKING |
| BYTEA 胀库 | 本版仅小 demo project；README 提示后续对象存储 |
| Diff 静默失败 | README 标明；前端不改 |
| 旧会话本地 tar | 不迁移；新会话走新路径 |

---

## 7. 后续（不在本版）

1. Diff / apply-diff 读 `PostgresSnapshotSpec` 或活容器
2. `RemoteSnapshotClient` → MinIO
3. 远程沙箱执行池
4. 中途失败的任务状态机与幂等
