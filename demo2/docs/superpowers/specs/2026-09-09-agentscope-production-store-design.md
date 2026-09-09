# AgentScope 生产化存储与观测设计规范

**日期**: 2026-09-09  
**项目**: spring-ai-demo / demo2  
**状态**: 已确认，待实现  
**前置**: [2026-07-25-agentscope-postgres-distributed-workspace-design.md](./2026-07-25-agentscope-postgres-distributed-workspace-design.md)；[2026-07-31-agentscope-distributed-sandbox-handoff-design.md](./2026-07-31-agentscope-distributed-sandbox-handoff-design.md)；[2026-07-23-agentscope-middleware-observability-design.md](./2026-07-23-agentscope-middleware-observability-design.md)；[2026-07-26-agentscope-skills-code-reviewer-design.md](./2026-07-26-agentscope-skills-code-reviewer-design.md)  
**官方**: [上生产（Going to Production）](https://java.agentscope.io/v2/zh/docs/others/going-to-production.html)

---

## 1. 背景与目标

### 1.1 问题

demo2 已用 `PostgresDistributedStore` 把 `AgentStateStore`、`BaseStore`、沙箱快照、执行锁绑在同一套 PostgreSQL 上。这能跨重启续聊，但与官网生产分工不一致：

- 高频会话与小 KV 走关系库，延迟和连接压力差于 Redis；
- 沙箱 tar 进 PG BYTEA，库会被大对象打满；
- Skill 仍来自 `workspace/skills/`，没有平台只读仓库；
- 可观测已有 HTTP OTel + `AgentExecutionLoggingMiddleware`，Harness 未挂 `OtelTracingMiddleware`，model/tool/沙箱不进同一条 trace。

官网五项能力（`AgentStateStore`、`BaseStore`、`SandboxSnapshotSpec`、`SandboxExecutionGuard`、Skill 仓库）**不是五选一**，必须按职责组合。

### 1.2 目标

1. 按官网混合 `DistributedStore` 上生产：热路径 Redis，大快照 OSS，Skill 只读 MySQL。
2. 沙箱保持可开关；**生产配置按「开沙箱」配齐四件套**，关沙箱时同一 Redis `BaseStore` 给 `RemoteFilesystemSpec`。
3. 会话 **只以 Redis 为恢复源**；每次成功 `save` 后经 RocketMQ **异步追加**到 PostgreSQL 审计表。
4. 叠加 `OtelTracingMiddleware`，与现有 requestId 日志、RocketMQ trace 传播共存。
5. 生产（`fail-fast=true`）Redis / OSS / Skill MySQL / 审计 PG / RocketMQ 任一不可用则 **拒绝启动**。本地默认 `fail-fast=false`：Redis 失败则 WARN + InMemory，不发审计。

### 1.3 已确认决策

| 维度 | 选择 |
|------|------|
| 总体方案 | 方案 1：官网混合 Store + 审计旁路 |
| 沙箱 | 可开关；生产按开沙箱装配 Snapshot + Guard |
| IsolationScope | `USER`（与现网 Remote 一致，上线后不改） |
| AgentState 热存储 | Redis（Lettuce，`spring-boot-starter-data-redis`） |
| AgentState 审计 | RocketMQ → 现有 AgentScope PostgreSQL，追加历史，不回填 Redis |
| BaseStore | Redis；禁止 OSS 当 KV |
| 沙箱快照 | 生产阿里云 `OssSnapshotSpec`；本地独立 MinIO（不复用 Milvus MinIO 桶） |
| 沙箱锁 | Redis `SandboxExecutionGuard` |
| Skill | `MysqlSkillRepository(writeable=false)`，业务 MySQL；禁止 `autoPromote` |
| Redis 客户端 | Lettuce，复用 Spring Data Redis 连接工厂 |
| 观测 | 保留 Logging Middleware + requestId；新增 `OtelTracingMiddleware` |
| 失败策略 | `app.agentscope.distributed.fail-fast`：本地默认 `false`（Redis 失败 WARN + InMemory）；生产 profile `true`（缺依赖则启动失败） |
| 本地关分布式 | `app.agentscope.distributed.enabled=false` → 不探测，InMemory + 本地盘，不发审计 |
| HITL confirm | 只读 Redis 上的同一 `AgentStateStore` Bean |

### 1.4 非目标

- aistio / ControlPlane 托管 Store
- AgentRun NAS、Nacos / Git Skill 仓库
- 用 PG 或 MySQL 做热 `AgentStateStore` / `BaseStore`
- 审计补偿 Job、管理台 UI、技能审核流
- 互不信任多租户（租户仍拼进 `userId` / `sessionId`）
- 改现有 SSE / requestId 契约
- 升级 AgentScope BOM（PG 热路径移除后可删除 UPSERT 反射补丁）

---

## 2. 架构

HarnessAgent 单例、调用间无状态。每次 `call` / `streamEvents` 用 `RuntimeContext(userId, sessionId)` 寻址。空 `userId` 继续占位 `_anonymous`。

```text
DevAgentService
  → HarnessAgent（单例）
       .distributedStore(混合)
       .stateStore(Auditing → PathSafe → Redis)   // 与 HITL 同一 Bean
       .filesystem(沙箱开: Docker / 沙箱关: Remote)
       .skillRepository(MysqlSkillRepository writeable=false)
       .middlewares(Logging + OtelTracing)

混合 DistributedStore
  AgentStateStore        = RedisAgentStateStore（与上面 Redis 同一实例）
  BaseStore              = RedisStore
  SandboxExecutionGuard  = RedisSandboxExecutionGuard
  SandboxSnapshotSpec    = OssSnapshotSpec
```

| 组件 | 实现 | 职责 |
|------|------|------|
| AgentStateStore | Redis | 对话、压缩摘要、ASKING、Plan、tool state；**唯一恢复源** |
| BaseStore | Redis | 关沙箱时 `MEMORY.md` 等小 KV |
| SandboxExecutionGuard | Redis | 开沙箱时跨副本串行化同一 slot |
| SandboxSnapshotSpec | OSS | 沙箱 workspace tar |
| SkillRepository | 业务 MySQL 只读 | 平台技能分发 |
| 审计 | RocketMQ → PG | 报表 / 追责，禁止当热 store |

开关：

- **沙箱开**（`app.agentscope.dev-agent.sandbox.enabled=true`）：`DockerFilesystemSpec` + `IsolationScope.USER`；不挂 `RemoteFilesystemSpec`；快照与锁由 `distributedStore` 注入；去掉 `LocalSnapshotSpec` / `snapshot-root`。
- **沙箱关**：`RemoteFilesystemSpec` + 同一 Redis `BaseStore` + `WorkspaceIndex`（保持现有加速方式）。

`distributedStore.agentStateStore()` 必须是 **未包装** 的 Redis 实例。Harness `.stateStore(...)` 使用包装链：

`AuditingAgentStateStore` → `PathSafeAgentStateStore` → **同一** Redis 实例。

PathSafe 只编码含 `/` 的 sandbox 内部 sessionId。审计消息使用进入 `AuditingAgentStateStore` 的原始 `userId` / `sessionId`（便于报表）。

PostgreSQL 从运行时 store 改为 **仅审计库**。停用 `PostgresDistributedStore` 热路径与 PG BYTEA 快照。

---

## 3. 数据流

### 3.1 会话（Redis + 审计 MQ）

1. `DevAgentService` 传入 `userId` + `sessionId`。
2. Harness 将 `AgentState` **CAS 写入 Redis**。这是用户成功的唯一条件。
3. `AuditingAgentStateStore` 在 `delegate.save` 成功后，用现有 `BaseEventPublisher` **异步**发 RocketMQ（无 Spring 事务则立即发）。发送失败只打 error + 指标，**不回滚 Redis、不失败 SSE**。
4. 消息 `keys` = `{userId}|{sessionId}|{stateKey}|{version}`。body：`userId`、`sessionId`、`stateKey`（通常 `agent_state`）、`version`、`agentName`、`traceId`、`savedAt`、AgentState JSON。
5. 消费者向 PG **插入**审计行。主键 `(user_id, session_id, state_key, version)` 冲突视为重复，消费成功。同一 `(user_id, session_id, state_key)` 下入站 `version` 小于已有最大 version 的行丢弃（不覆盖）。
6. 审计是追加历史。进程重启续聊 **禁止** 读 PG 回填 Redis。HITL confirm 只 `get` Redis。

Producer 配置：`rocketmq.producers.agentscopeAgentStateAudit`（topic / tag 写入 `application.properties`）。Listener 继承 `RocketMessageConcurrentlyListener`，放在 `agentscope` 包内 `app.listener` 风格即可（与商品模块一致：发布在 infrastructure/audit，消费在 listener）。

### 3.2 超大消息

单条 body 超过 **1MB** 警戒：消息只带 Redis 定位字段（userId、sessionId、stateKey、version），消费者再 GET Redis。若 key 已不存在：记 `agentscope.audit.gap`，消费成功以免打转（缺口只告警）。

### 3.3 工作区 / 沙箱（不进审计 MQ）

- 关沙箱：读写 Redis `BaseStore`。
- 开沙箱：Docker 内执行 → 结束打 tar → OSS；跨节点 `exec` 先拿 Redis Guard。
- Diff / apply-diff：OSS 快照 + Redis state 元数据，不读审计表。

### 3.4 Skill

启动时 `MysqlSkillRepository` 只读加载。Agent 运行不写回。变更走 SQL / 后续管理台，不经本 MQ。`enableSkillManageTool` 若开启必须配 promotion gate；本版 **不开启** autoPromote，默认不打开 manage tool。

---

## 4. 失败处理与降级

**Redis 成功 = 用户成功。** 审计、OSS、Skill、OTel 失败不得把对话打成失败。

| 故障 | 行为 |
|------|------|
| Redis 不可用 | `fail-fast=true`：启动失败。`fail-fast=false` 且 `enabled=true`：WARN + InMemory，不装配 OSS/审计。`enabled=false`：不探测 |
| OSS 不可用 | `fail-fast=true`：启动失败。运行中快照失败：本轮仍可执行，打 error，下次可能冷启动。关沙箱不依赖 OSS |
| RocketMQ 发送失败 | Redis 已保存；`agentscope.audit.publish.fail`；本版不做补偿 Job。`fail-fast=false` 且启动时无 MQ：Auditing 改为 no-op + WARN |
| PG 消费失败 | `RECONSUME_LATER`；超重试进死信并告警 |
| Skill MySQL 不可用 | `fail-fast=true`：启动失败。否则 WARN，仅 `workspace/skills/` |
| Guard 超时 | 本轮 exec 失败走现有错误 SSE；无成功 save 则不发 MQ |

**禁止**

- 用 PG 审计行恢复会话
- OSS 当 BaseStore；完整 AgentState 或沙箱 tar 写入 Redis
- Skill `writeable=true` / `autoPromote=true`
- 生产使用 `JsonFileAgentStateStore` / `LocalSnapshotSpec` / 本地 `snapshot-root`

优雅停机：Harness `GracefulShutdownManager` + SIGTERM；inflight `call` 结束后再停 Redis / MQ 客户端。

---

## 5. 可观测

1. 保留 `AgentExecutionLoggingMiddleware`、requestId、SSE `REQUEST_CONTEXT`。
2. 增加 `OtelTracingMiddleware`，与 HTTP `traceId` 同一条链；为 model / tool / sandbox 出子 Span。这是对 2026-07-23 规范「不为 AgentScope 建 Span」的 **生产化修订**：日志 Middleware 仍不建 Span；官方 tracing middleware 单独负责 Span。
3. 审计消息走现有 `RocketMqTracePropagator`。
4. Micrometer 指标：`agentscope.state.save` 耗时、`agentscope.audit.publish` 成功/失败、消费成功/重复/死信、`agentscope.audit.gap`、OSS 快照耗时、Guard 等待。
5. 日志带 userId、sessionId、version、requestId；INFO 不打完整 Prompt / 工具结果。

---

## 6. 配置与表结构

### 6.1 配置（新增，现有 `app.agentscope.datasource` 改为审计库）

| Key | 含义 |
|-----|------|
| `app.agentscope.distributed.enabled` | 总开关；`false` 内存 + 本地，不审计 |
| `app.agentscope.distributed.fail-fast` | 默认 `false`；生产 profile 设 `true` |
| `app.agentscope.store.redis.key-prefix` | Redis key 前缀，默认 `agentscope:` |
| `app.agentscope.oss.endpoint` / `bucket` / `prefix` / 凭证 | 快照；本地指向独立 MinIO |
| `app.agentscope.skill.mysql.enabled` | 默认与 distributed 同开 |
| `spring.data.redis.*` | Lettuce 连接（已有则复用） |
| `spring.datasource.*` | Skill 表所在业务 MySQL |
| `app.agentscope.datasource.*` | 审计 PG（沿用现连接） |
| `rocketmq.producers.agentscopeAgentStateAudit.topic` | 审计 topic |

生产 profile：`distributed.enabled=true` 且 `fail-fast=true`，Redis、OSS、Skill DataSource、审计 PG、RocketMQ 均必须可用，否则失败启动。

### 6.2 PG 审计表

库：现有 AgentScope PostgreSQL。表名：`agentscope_agent_state_audit`。

| 列 | 类型 | 说明 |
|----|------|------|
| user_id | text | 非空 |
| session_id | text | 非空 |
| version | bigint | CAS version |
| state_key | text | 默认 `agent_state` |
| agent_name | text | |
| trace_id | text | 可空 |
| saved_at | timestamptz | |
| state_json | jsonb | 可空（超大消息走 Redis GET 失败时为 null） |
| created_at | timestamptz | 默认 now() |

主键：`(user_id, session_id, state_key, version)`。  
索引：`(user_id, session_id, created_at desc)` 便于按会话拉历史。

启动时 `createIfNotExist`（与现 PG store 风格一致）。Skill 表由 `MysqlSkillRepository.builder(...).createIfNotExist(true)` 在非生产可建表；生产 `createIfNotExist=false`，表由 DBA / 迁移脚本提供。本版提供 SQL 文件于 `demo2/src/main/resources/db/`，不强制 Flyway。

---

## 7. 代码落点（实现时）

| 位置 | 职责 |
|------|------|
| `AgentscopeDistributedBackendFactory` | 探测 Redis + OSS；钉住混合 `DistributedStore`；删除 PG create / UPSERT 补丁 |
| `AgentScopeConfig` | 包装链、filesystem 开关、`skillRepository`、`OtelTracingMiddleware` |
| `AuditingAgentStateStore` | save 成功后发 MQ |
| `agentscope` 下 Publisher + Listener | 审计事件 |
| `pom.xml` | `agentscope-extensions-redis`、`agentscope-extensions-oss`、`agentscope-extensions-skill-mysql-repository`（artifact 以 BOM 为准） |
| 装配测试 | 见第 8 节 |

`userId` / `sessionId` 拼租户规则不变：`RuntimeContext` 每次必传，禁止默认 session 串台。

OSS 凭证：配置 RAM/STS 或 MinIO AK；禁止把生产 AK 写入仓库。本地 MinIO 桶名例如 `agentscope-sandbox-snapshots`，前缀 `local/`；生产 prefix `prod/`。

---

## 8. 测试

单测为主，不强制真 OSS 集群。

| 项 | 断言 |
|----|------|
| 装配 | remote 注入 Redis state/base/guard + OSS snapshot；`fail-fast=true` 且缺 Redis 则失败；`fail-fast=false` 则降级 InMemory |
| 审计 Store | save 成功后发 MQ；publisher 抛错不影响 save 返回 |
| 消费 | 同 version 插入一次；旧 version 不覆盖；重复 keys 成功 |
| PathSafe | sessionId 含 `/` 时 confirm 能从 Redis 读 ASKING |
| Skill | `writeable=false`；测试不调用写 API |
| 沙箱开关 | 开：无 RemoteFilesystemSpec；关：无 Docker spec |
| 观测 | `OtelTracingMiddleware` 出现在 middleware 列表（可用装配单测） |

手工验收（实现后 README 补一段）：重启后同 `(userId, sessionId)` 从 Redis 续聊；PG 有对应 version 行；开沙箱换实例能从 OSS hydrate（有 MinIO 时）。

---

## 9. 与旧规范关系

| 旧规范 | 本版 |
|-------|------|
| 07-22 / 07-25 PG 为热 AgentStateStore | 热路径改为 Redis；PG 仅审计 |
| 07-31 沙箱快照 PG BYTEA | 改为 OSS；Guard 改为 Redis |
| 07-23 不为 Agent 建 OTel Span | Logging Middleware 仍不建 Span；另挂官方 `OtelTracingMiddleware` |
| 07-26 workspace skills | 保留为低优先级层；Marketplace 增加 MySQL 只读仓库 |

旧 `PostgresDistributedStore` 装配与 `patchPostgresBaseStoreUpsertSql` 随热路径移除删除。
