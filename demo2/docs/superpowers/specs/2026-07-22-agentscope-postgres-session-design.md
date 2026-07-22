# AgentScope PostgreSQL 会话状态持久化设计规范

**日期**: 2026-07-22  
**项目**: spring-ai-demo / demo2  
**状态**: 已确认，待实现  
**前置**: [2026-07-16-agentscope-harness-web-design.md](./2026-07-16-agentscope-harness-web-design.md)（已实现）；权限/HITL 能力已落地  
**参考**: [7. AgentScope Java 2.0 Agent状态实战：把 Agent 会话存进 PostgreSQL](https://mp.weixin.qq.com/s/qvfip2nZYcu3vznj4Hp1Jg)  
**官方**: [Agent 状态存储（AgentStateStore）](https://java.agentscope.io/v2/zh/integration/session/overview.html)

---

## 1. 背景与目标

### 1.1 问题

当前 Dev Agent 使用 `InMemoryAgentStateStore`，会话状态仅在进程内；应用重启后同一 `(userId, sessionId)` 无法恢复对话与执行上下文。  
人工确认待办存在 `DevAgentService` 的内存 `ConcurrentHashMap` 中，重启后 `/confirm` 无法找回 `ASKING` 工具调用。

### 1.2 需求

1. 将 Agent 运行状态（对话、工具调用/结果、权限与上下文摘要等可恢复快照）写入 **PostgreSQL**
2. 保留现有 SSE、工具调用与人工确认主链路；Controller / 事件协议不变
3. 确认接口从同一 `AgentStateStore` 读取 `ASKING` 状态，支持跨重启批准/拒绝
4. 提供 `demo2/docker` 下可一键启动的 PostgreSQL 容器
5. PostgreSQL 不可用时：**应用仍可启动**，降级为内存 store，并打清晰 WARN

### 1.3 已确认决策

| 维度 | 选择 |
|------|------|
| 落地方案 | **方案 1**：`PostgresAgentStateStore` + 独立 DataSource（非 `PostgresDistributedStore`） |
| 与现有 MySQL | **互不影响**：保留 `spring.datasource`（MySQL）；AgentScope 使用独立 PG 连接 |
| Docker | `demo2/docker/agentscope-postgres/docker-compose.yml`，镜像 `postgres:16` |
| 人工确认 | **一起改**：去掉内存 Map，从 store 读 `ASKING` 的 `ToolUseBlock` |
| PG 不可用 | **降级 memory**：Bean 初始化时探测一次；失败则 WARN + `InMemoryAgentStateStore` |
| 建表 | `createIfNotExist(true)`（开发默认）；正式环境可改为提前 SQL + `false`（本版不强制） |
| API / 前端 | **不改**路径与 SSE 事件模型 |

### 1.4 非目标（本版不做）

- `PostgresDistributedStore` / 共享 Workspace / Sandbox 快照与并发锁
- 业务审批审计表（审批人、时间、意见、超时）
- 从登录态/网关强制注入 `userId`（仍可请求体传入；文档注明生产应从鉴权上下文取）
- 改前端 Tab、改 API 路径
- 将现有 MySQL 业务库迁到 PostgreSQL
- 运行中动态在 postgres ↔ memory 之间切换

---

## 2. 架构

```
Docker: demo2/docker/agentscope-postgres/
  → PostgreSQL :5432（独立实例）

Spring Boot
  spring.datasource          → MySQL（现有，不动）
  app.agentscope.datasource  → 独立 PostgreSQL DataSource
       ↓ 探测成功
  PostgresAgentStateStore（createIfNotExist=true）
       ↓ 探测失败
  InMemoryAgentStateStore + WARN
       ↓
  HarnessAgent.stateStore(...)
       ↓
  DevAgentService
    ask / confirm → RuntimeContext(userId, sessionId)
    confirm 待确认工具 ← AgentStateStore.get(..., "agent_state", AgentState.class)
```

调用链不变：

```
POST /agentscope/dev-agent/ask|confirm
  → DevAgentController
  → DevAgentService
  → HarnessAgent.streamEvents + RuntimeContext
  → Flux：SESSION / MESSAGE / 工具与确认相关事件 / DONE / ERROR
```

表用途（与 PgVector / Spring AI RAG 无关）：

| 场景 | 存什么 | 典型表 |
|------|--------|--------|
| AgentScope AgentStateStore | 对话与执行状态快照 | `agentscope.agentscope_sessions` |
| Spring AI PgVector（若有） | embedding | `vector_store` |

本版使用普通 PostgreSQL，**不依赖** `vector` 扩展。表存的是**当前可恢复快照**（`ON CONFLICT` 更新），不是聊天明细流水。

---

## 3. 组件与文件改动

| 动作 | 路径 | 说明 |
|------|------|------|
| 新增 | `demo2/docker/agentscope-postgres/docker-compose.yml` | PG16；健康检查；命名卷 |
| 新增 | compose 顶部注释（或同目录短 README） | `up -d` / `down` 说明 |
| 依赖 | `demo2/pom.xml` | `agentscope-extensions-postgresql`；`postgresql`（runtime）；JDBC starter 已有则不重复 |
| 配置属性 | `DevAgentProperties` 旁新建或扩展 | `app.agentscope.datasource.url/username/password` |
| 配置 | `AgentScopeConfig` | `@Qualifier("agentscopeDataSource")` DataSource Bean（可选：仅在选用 PG 时创建）；`AgentStateStore` Bean（探测 + 降级）；HarnessAgent 注入该 store |
| 修改 | `DevAgentService` | 删除 `pendingConfirmations`；confirm 从 store 加载 ASKING |
| 修改 | `application.properties` | agentscope 专用 PG 默认连接 |
| 测试 | `DevAgentService` 等 | mock `AgentStateStore`：无 pending / 有 ASKING 可 confirm；可选：store Bean 降级行为单测 |

**不改**：Controller API、`DevAgentEvent*`、前端、MySQL 主数据源、工具注册与权限规则。

---

## 4. Docker 与配置

### 4.1 Docker Compose

路径：`demo2/docker/agentscope-postgres/docker-compose.yml`

| 项 | 值 |
|----|-----|
| 镜像 | `postgres:16` |
| 容器名 | `demo2-agentscope-postgres` |
| 端口 | `5432:5432` |
| 用户 / 密码 / 库 | `agentscope` / `agentscope` / `agentscope` |
| 卷 | 命名卷，重启不丢数据 |
| 健康检查 | `pg_isready` |

启动：

```bash
docker compose -f demo2/docker/agentscope-postgres/docker-compose.yml up -d
```

### 4.2 应用配置

不修改 `spring.datasource`（MySQL）。新增：

```properties
app.agentscope.datasource.url=jdbc:postgresql://127.0.0.1:5432/agentscope
app.agentscope.datasource.username=agentscope
app.agentscope.datasource.password=${AGENTSCOPE_PG_PASSWORD:agentscope}
```

独立 Hikari DataSource，**仅**供 AgentScope state store 使用。

### 4.3 建表

开发默认：

```java
PostgresAgentStateStore.builder(dataSource)
        .createIfNotExist(true)
        .build();
```

自动创建 `agentscope` schema 与 `agentscope_sessions`。  
库内 `session_id` 由框架按 `userId:sessionId` 组合寻址；`state_key` 典型为 `agent_state`；同一会话更新而非追加行。

生产可提前建表后设 `createIfNotExist(false)`（本版可不实现配置开关，文档注明即可）。

---

## 5. PG 不可用时的降级

| 步骤 | 行为 |
|------|------|
| 1 | 按 `app.agentscope.datasource.*` 创建/打开连接（短超时探测，如 validation query / `getConnection`） |
| 2 | 成功 → 构建 `PostgresAgentStateStore`，INFO 日志标明 `stateStore=postgres` |
| 3 | 失败 → **不阻止启动**；WARN 标明原因与 `stateStore=memory`；使用 `InMemoryAgentStateStore` |
| 4 | 判定仅在 **Bean 初始化时一次**；进程生命周期内不热切换 |

降级后：多轮对话在同进程内仍可用；**重启丢失**；confirm 逻辑与 PG 路径相同（都读 store），只是数据在内存。

---

## 6. 确认恢复逻辑

1. `REQUIRE_USER_CONFIRM`：不再写入进程内 Map；依赖框架将 `ASKING` 的 `ToolUseBlock` 写入 `AgentState` 并由 store 持久化（或内存 store 保存）
2. `confirm(userId, sessionId, approved)`：
   - `agentStateStore.get(userId, sessionId, "agent_state", AgentState.class)`
   - 从最后一条 Assistant 消息中收集状态为 `ASKING` 的工具调用
   - 为空 → 返回「没有待确认的工具调用」类 ERROR 事件（与现行为一致）
   - 非空 → 组装 `ConfirmResult`，同 `(userId, sessionId)` `streamEvents` 恢复
3. 应用停在等待确认阶段后重启（且当时为 postgres 模式）→ `/confirm` 仍可从 PG 恢复工具名、参数与 call id

`userId` 规范化：继续沿用现有 `normalizeUserId`（空 → `_anonymous`）。

---

## 7. 测试与验证

### 7.1 自动化

- `DevAgentService`：mock store 返回含 ASKING 的 `AgentState` → confirm 能恢复；空 Optional / 无 ASKING → 错误事件
- （可选）`AgentStateStore` 装配：模拟连接失败 → 得到 `InMemoryAgentStateStore` 类型；成功路径可用 Testcontainers 或集成测（不强制本版）
- 不强制 CI 打真实 DeepSeek

### 7.2 手工（PG 模式）

1. `docker compose ... up -d`，启动应用，日志含 `stateStore=postgres`
2. `ask` 一轮后查 `agentscope.agentscope_sessions` 有对应 `session_id` 且 `has_state`
3. 停应用再启，同 `userId`+`sessionId` 续问，能接上上下文
4. 不同 `userId`、相同 `sessionId` → 两行独立槽位
5. HITL：停在确认态重启后，`confirm` 仍可用
6. 停 Docker 再启应用 → 能启动，WARN + `stateStore=memory`

---

## 8. 成功标准

1. Docker 一键起 PG；配置独立于 MySQL
2. PG 可用时会话与 ASKING 确认可跨重启恢复
3. PG 不可用时应用可启动并降级 memory，日志明确当前模式
4. API / SSE / 前端行为契约不变
5. 去掉 `pendingConfirmations` 内存 Map，确认统一走 `AgentStateStore`
