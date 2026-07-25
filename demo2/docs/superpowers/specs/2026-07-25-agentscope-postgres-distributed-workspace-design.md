# AgentScope PostgreSQL DistributedStore / 共享 Workspace 设计规范

**日期**: 2026-07-25  
**项目**: spring-ai-demo / demo2  
**状态**: 已确认，待实现  
**前置**: [2026-07-22-agentscope-postgres-session-design.md](./2026-07-22-agentscope-postgres-session-design.md)（已实现）；[2026-07-23-agentscope-workspace-agents-md-design.md](./2026-07-23-agentscope-workspace-agents-md-design.md)；[2026-07-25-agentscope-harness-memory-design.md](./2026-07-25-agentscope-harness-memory-design.md)  
**官方**: [Filesystem（Shared store / RemoteFilesystemSpec）](https://java.agentscope.io/v2/en/docs/harness/filesystem.html)；[Going to Production](https://java.agentscope.io/v2/en/docs/others/going-to-production.html)

---

## 1. 背景与目标

### 1.1 问题

会话状态已通过独立 PostgreSQL 的 `PostgresAgentStateStore` 跨机/跨重启恢复。Workspace（`AGENTS.md`、`MEMORY.md`、`knowledge/` 等）仍落在各机本地盘。

多副本部署时：

- 无法也不应依赖文件同步；
- 即将启用的 Harness Memory 按 `userId` 写入 Workspace，若仍走本地盘，则换机会话读不到约定。

此前 Postgres 会话设计将「共享 Workspace / `PostgresDistributedStore`」标为非目标；本版补上这一能力。

### 1.2 需求

1. 用官方 **`PostgresDistributedStore`** 统一接入分布式后端（替代单独装配 `PostgresAgentStateStore`）。
2. 本版接线：**会话 state + 远程 Workspace KV**（`RemoteFilesystemSpec`，`IsolationScope.USER`）。
3. 提供独立开关 `app.agentscope.distributed.enabled`（默认 `true`）：开则跟 PG 绑定；关则强制内存会话 + 本地 Workspace。
4. PG 不可用时应用仍可启动，降级为内存 + 本地盘，并打清晰 WARN（与现有会话降级一致）。
5. Controller / SSE / HITL / MCP / FileChange 主流程不改；仍 `disableFilesystemTools`（远程 Workspace ≠ 放开内置文件工具）。

### 1.3 已确认决策

| 维度 | 选择 |
|------|------|
| 落地方案 | **方案 1**：扩展现有 Factory（升级探测与装配），产出后端描述后由 `AgentScopeConfig` 接线 |
| 能力范围 | **实用版**：只接线 state + `RemoteFilesystemSpec`；Snapshot / Lock 随 `create` 存在但不演示、不接 Sandbox |
| 隔离 | `IsolationScope.USER`（同 `userId` 跨 `sessionId` 共享 MEMORY） |
| 开关 | `app.agentscope.distributed.enabled`，默认 `true`；`false` → 不探测、内存 + 本地盘 |
| PG 不可用 | 开关为 `true` 时探测失败 → WARN + 与「开关关」相同降级 |
| DataSource | 复用 `app.agentscope.datasource.*`；与 MySQL `spring.datasource` 互不影响 |
| 成功路径装配 | 只调 `.distributedStore(...)`，**不再**单独 `.stateStore(...)` |
| 降级路径装配 | 只调 `.stateStore(InMemoryAgentStateStore)`，不设 `RemoteFilesystemSpec` |
| API / 前端 | **不改** |

### 1.4 非目标（本版不做）

- 接入 Sandbox（Docker/K8s/E2B 等）或演示 Snapshot / advisory Lock
- 将 `FileChangeTool` / MCP filesystem 的项目源码读写迁到 PG
- Redis / MySQL 作为 DistributedStore 后端（本版仅 PostgreSQL）
- 运行中在 postgres ↔ 本地之间热切换
- 改 API、SSE、前端 Tab；改造 Spring AI AutoMemory
- 真多 Pod 联调（文档说明即可；手工验收以「重启后同 userId 仍见 MEMORY」为主）

### 1.5 `PostgresDistributedStore` 能力科普（文档保留）

| 能力 | 回答的问题 | 本版 |
|------|------------|------|
| stateStore | 这场会话进行到哪？ | 接线（替代原单独 `PostgresAgentStateStore`） |
| baseStore + RemoteFilesystem | 这个用户的 MEMORY / 工作区文件放哪？ | 接线 |
| Snapshot | 沙箱磁盘怎么跨机还原？ | 不接线、不验收 |
| Lock | 谁在跑这个沙箱？ | 不接线、不验收 |

---

## 2. 架构

```text
app.agentscope.distributed.enabled  (默认 true)
        │
        ├─ false ──────────────────────────────┐
        │                                      ▼
        │                         InMemoryAgentStateStore
        │                         本地 Workspace（默认 LocalFilesystem）
        │
        └─ true → 探测 app.agentscope.datasource
                    │
                    ├─ 成功 → PostgresDistributedStore
                    │         HarnessAgent
                    │           .distributedStore(store)
                    │           .filesystem(RemoteFilesystemSpec
                    │               .isolationScope(USER))
                    │           .workspace(本地模板路径)
                    │
                    └─ 失败 → WARN + 与「开关关」相同降级
```

调用链不变：

```text
POST /agentscope/dev-agent/ask|confirm
  → DevAgentController → DevAgentService → HarnessAgent
  → WorkspaceContext / Memory（若启用）读写走 AbstractFilesystem
       ├─ remote：PG KV（按 USER 隔离）
       └─ local：workspace-root 磁盘
```

**职责边界**

| 组件 | 负责 |
|------|------|
| Factory（由 `AgentStateStoreFactory` 升级） | 读开关、探测 PG、产出后端描述（remote 或 local） |
| `AgentScopeConfig` | 按描述装配 `HarnessAgent`；并注册 **`AgentStateStore` Bean** 供 `DevAgentService` confirm / 读 ASKING（remote 时取 DistributedStore 内的 stateStore，勿另建第二套 PG store） |
| 本地 `demo2/workspace/` | 只读模板种子（`AGENTS.md` 等）；远程无用户覆盖时回落本地 |
| Snapshot / Lock | store 内自带；本版不演示 |

进程内启动时定一次后端，不热切换。

---

## 3. 与 Memory / AGENTS.md 的关系

三层仍正交，仅 Workspace 物理后端可变：

| 机制 | 隔离键 | distributed 开且 PG 通 | 降级 / 开关关 |
|------|--------|------------------------|---------------|
| AgentStateStore | userId + sessionId | PG（DistributedStore 内） | 内存 |
| Memory（`MEMORY.md` / `memory/`） | userId（USER scope） | PG KV | 本地 `workspace/` |
| Compaction | 当前 session | 不变 | 不变 |

- **`AGENTS.md` 等模板**：继续随仓库/镜像部署到各机；作为远程层下方的只读种子。
- **Memory 写入**：`memory_save` / Flush / Consolidation 经远程 Filesystem 进 PG；任意副本同 `userId` 可读。
- Memory 设计中的 HITL / `memory.enabled` / 前端示例 **不改**；仅补充说明：多机需本能力 + PG。
- Memory 设计原文「存储 = 本地 workspace」语义更新为：**逻辑路径不变，物理后端由 distributed 决定**。

实现可与 Memory 同迭代接线；验收优先单机 Memory，再在有 PG 时验重启后跨会话读取。

---

## 4. 配置设计

### 4.1 新增开关

```properties
# 默认 true：开 → 跟 PG 绑定；关 → 强制内存会话 + 本地 Workspace
app.agentscope.distributed.enabled=true
```

属性建议挂在现有 `AgentScopeDataSourceProperties` 旁，或新建薄包装（如 `AgentscopeDistributedProperties(boolean enabled)` + 复用 datasource）。绑定前缀：`app.agentscope.distributed.enabled`。

### 4.2 复用（不改语义）

```properties
app.agentscope.datasource.url=...
app.agentscope.datasource.username=...
app.agentscope.datasource.password=...
app.agentscope.datasource.connection-timeout-ms=...
app.agentscope.dev-agent.workspace-root=workspace
```

### 4.3 决策表

| `distributed.enabled` | PG 探测 | 会话 | Workspace | 日志 |
|----------------------|---------|------|-----------|------|
| `false` | 不探测 | `InMemory` | 本地盘 | INFO：distributed=off |
| `true` | 成功 | DistributedStore.state | `RemoteFilesystemSpec` + USER | INFO：distributed=postgres |
| `true` | 失败 | `InMemory` | 本地盘 | WARN：降级原因 |

### 4.4 测试配置

`src/test/resources/application-test.properties`：

```properties
app.agentscope.distributed.enabled=false
```

单测不依赖 PostgreSQL / Testcontainers。

---

## 5. 组件与文件改动

| 动作 | 路径 | 说明 |
|------|------|------|
| 升级 | `.../AgentStateStoreFactory.java`（可重命名为 `AgentscopeDistributedBackendFactory`） | 入参增加 enabled；成功创建 `PostgresDistributedStore`；失败/关闭 → local 描述 |
| 新增（建议） | 后端描述类型（record / sealed） | 例如 `remote(DistributedStore)` vs `local(AgentStateStore)`，避免 Config 里散落 if |
| 修改 | `AgentScopeConfig.java` | 按描述：remote → `.distributedStore` + `.filesystem(...)`；local → `.stateStore(...)`；`AgentStateStore` Bean 与 Harness 共用同一实例（remote 从 DistributedStore 取出） |
| 修改 | `application.properties` / test properties | `distributed.enabled` |
| 修改 | Factory 相关单测 | 关→本地；开+不可达 URL→降级 |
| 修改 | README AgentScope 小节 | 开关、降级、与 Memory 多机关系；注明 Snapshot/Lock 本版不验 |
| 依赖 | `pom.xml` | 原则上不新增；实现时核对 `PostgresDistributedStore`、`RemoteFilesystemSpec` 已在 2.0.0 classpath |
| 不改 | Controller / Service / 前端 / MCP / Permission 主规则 | — |

成功路径 **禁止** 同时 `.stateStore(pg)` 与 `.distributedStore(store)`，以免双重注入。

建表：沿用扩展默认（`createIfNotExist` / schema 自动创建策略以官方 `PostgresDistributedStore` API 为准；实现时与现有 session 表兼容或迁移说明写进 plan）。

---

## 6. 错误处理

- `enabled=false`：跳过探测，避免无意义连库。
- `enabled=true` 且探测失败：关闭 Hikari 池、WARN、降级；应用照常启动。
- 运行中 PG 中断：不热切换；本版不另做重连中间件（与现有 stateStore 行为一致）。
- 远程读写失败：沿用框架/工具错误路径进入现有 ERROR SSE；不新增专用事件类型。

---

## 7. 测试与验收

1. **单测**：`enabled=false` → local；`enabled=true` + 坏 JDBC URL → 降级为 `InMemory` + local 语义。
2. **手工（Docker PG）**：`enabled=true`，启用 Memory 后写入约定 → **重启应用** → 同 `userId`、新 `sessionId` 仍能读到约定。
3. **回归**：`enabled=false` 时行为与改造前一致（本地 Workspace + 内存或原降级路径）。
4. **不验**：Snapshot、Lock、Sandbox、真实多 Pod 文件一致性（文档说明「多机依赖共享 PG」即可）。

---

## 8. 风险与边界

| 风险 | 处理 |
|------|------|
| 本地已有 `workspace/{userId}/MEMORY.md`，切远程后「像丢了」 | 文档说明：远程优先；迁移需人工拷贝或接受从空开始；本版不做自动迁移工具 |
| `PostgresDistributedStore` API 与单独 `PostgresAgentStateStore` 表结构差异 | 实现前对照官方；必要时清空开发库或注明兼容策略 |
| 误以为远程 Workspace = 放开 filesystem 工具 | 配置与 README 强调仍 `disableFilesystemTools` |
| 测试误开 distributed 连不上 PG | test profile 默认 `enabled=false` |

生产 `userId` 仍信任客户端传入（与现有一致）；文档注明应从鉴权上下文注入。

---

## 9. 实现顺序建议

1. Factory 升级 + 后端描述 + 单测（开关关 / 开+不可达）
2. `AgentScopeConfig` 接线 `distributedStore` + `RemoteFilesystemSpec`
3. 配置项与 test 默认关
4. README +（可选）在 Memory 设计/README 交叉引用一句多机依赖
5. 有 PG 时手工验收「重启后 MEMORY 仍在」

---

## 10. 成功标准

- 决策表三种路径行为与日志符合第 4.3 节
- 成功路径不再单独使用 `PostgresAgentStateStore.builder`，改为 `PostgresDistributedStore`
- `DevAgentService` 与 `HarnessAgent` 使用**同一** `AgentStateStore` 实例（HITL confirm 仍可用）
- Memory（若已启用）在 PG 可达时，重启后同 `userId` 跨会话仍可读约定
- 单测在无 PG 环境下稳定通过（test 关闭 distributed）
- API / SSE / 前端无破坏性变更
