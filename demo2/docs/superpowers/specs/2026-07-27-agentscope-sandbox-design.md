# AgentScope Sandbox 实战：执行环境隔离设计规范

**日期**：2026-07-27  
**项目**：spring-ai-demo / demo2  
**状态**：已确认，待实现  
**前置能力**：AgentScope Toolkit、AgentEvent SSE、Permission HITL、PostgreSQL AgentStateStore / DistributedStore、Workspace、Compaction、Middleware、MCP filesystem、Memory、Dynamic Skills、SubAgent  
**参考文章**：[15. AgentScope Java 2.0 Sandbox 实战：Agent 改代码前，先把执行环境隔离起来](https://mp.weixin.qq.com/s/MjubCyS5EyTWMthtiFfmeQ)  
**相关规范**：[2026-07-25 PostgreSQL DistributedStore / 共享 Workspace](./2026-07-25-agentscope-postgres-distributed-workspace-design.md)；[2026-07-27 SubAgent 代码审查](./2026-07-27-agentscope-subagent-code-review-design.md)

---

## 1. 背景与目标

### 1.1 问题

代码审查 Agent 可以指出哪一行有问题；让它直接改代码、跑测试是另一回事。Permission 只决定「能不能做」；批准之后，若工具仍操作宿主机，Agent 改到的就是当前开发目录。

demo2 已具备 HITL、`request_file_change`（仅 `notes/`）、MCP 只读、Skill / SubAgent 审查，以及可选的 `RemoteFilesystemSpec` 远程 Workspace。此前分布式 Workspace 设计将 **Sandbox / Snapshot 演示** 标为非目标。本版补上 Docker Sandbox 执行隔离。

### 1.2 目标

1. 增加 `app.agentscope.dev-agent.sandbox.enabled`（默认 `false`）：开则走 Docker 沙箱，关则完全保持现状。
2. 沙箱开启时：`DockerFilesystemSpec` + `IsolationScope.SESSION` + `LocalSnapshotSpec`；启用内置 `read_file` / `edit_file` / `execute`；移除 `write_file`。
3. 新增有 bug 的样例项目 `workspace/project`（`RetryPolicy` + 测试），用于「测 → 读 → 改 → 复测」演示。
4. 新增 `PathSafeAgentStateStore`，编码含 `/` 的 sandbox 内部状态 ID，再交给底层 PG / 内存 store。
5. 镜像与构建约定对齐现有 `demo2/docker/*/docker-compose.yml` 习惯：用 compose **build 镜像**，不 `up` 常驻沙箱容器。
6. 引导**仅**写在 `workspace/AGENTS.md`；**不改** `system-prompt`。
7. 与现有工具并存（项目只读、`request_file_change`、MCP、Skill、SubAgent），靠 `AGENTS.md` 分流；不新建 API / Tab。

### 1.3 已确认决策

| 维度 | 决策 |
|------|------|
| 落地方案 | **增强适配**：单 Agent + 配置开关；默认关 |
| 与 Remote Workspace | 沙箱开时用 `DockerFilesystemSpec`，**不再**挂 `RemoteFilesystemSpec`（互斥）；关沙箱后恢复现状 |
| 工具集 | **全部并存**：内置三件套 + 现有自定义工具；`AGENTS.md` 路由 |
| 引导位置 | **仅** `workspace/AGENTS.md`；不改 `system-prompt` |
| 隔离粒度 | `IsolationScope.SESSION`（每 `sessionId` 独立工作区） |
| Docker 交付 | `demo2/docker/sandbox/docker-compose.yml` 负责 **build 镜像**；Harness 运行时按 session 拉起临时容器 |
| 默认开关 | `sandbox.enabled=false` |
| 内置写工具 | 启用 FS 工具后 **移除** `write_file`；改代码走 `edit_file`（ASK） |

### 1.4 非目标

- 自动 `docker build` / K8s / E2B
- 生产隔离加固（特权模式、Docker Socket、宿主网络、密钥、可写挂载审计）
- 把沙箱内修复结果写回宿主或开 PR
- 新建 Controller / 前端 Tab
- 修改 `system-prompt`
- 强制关闭 MCP / Skill / SubAgent
- 真多机共享 Docker 快照
- 将 sandbox 常驻服务化（`docker compose up` 常驻沙箱）

---

## 2. 方案选择

### 2.1 采用方案：单 Agent + `sandbox.enabled` 覆盖 filesystem

```text
sandbox.enabled=false（默认）
  → 现状：disableFilesystemTools + disableShellTool
  → distributed 开：RemoteFilesystemSpec(USER) + distributedStore
  → 工具：项目只读 + request_file_change + MCP + Skill/SubAgent

sandbox.enabled=true
  → DockerFilesystemSpec(SESSION) + LocalSnapshotSpec
  → 启用 read_file / edit_file / execute；remove write_file
  → PathSafeAgentStateStore(底层 PG 或内存) + .stateStore(...)
  → 不挂 RemoteFilesystemSpec
  → 自定义工具仍注册；AGENTS.md 分流
```

相对文章：引导挪到 `AGENTS.md`；工具不缩成三件套；Docker 用 compose build 对齐仓库习惯；默认关以保护现有演示路径。

### 2.2 未采用方案

| 方案 | 原因 |
|------|------|
| 严格照文章（摘工具 + 改 system-prompt） | 与「并存」「只改 AGENTS.md」冲突 |
| 双 HarnessAgent Bean | 配置/测试翻倍，超出本篇范围 |
| 最小可跑（无 RetryPolicy 全链路） | 演示不出 Snapshot 跨确认价值 |
| Spring Profile 整包切换 | 不如显式 boolean 开关清晰 |
| 运行时话术分流选 Docker | 路由不稳定 |
| 常驻 sandbox 容器（compose up） | 与 Harness 按 session 管容器冲突；职责不同于 postgres |

---

## 3. 总体架构

### 3.1 三层职责

| 层 | 管什么 |
|----|--------|
| Permission | 危险动作能不能做（`edit_file` / `execute` → ASK） |
| Sandbox | 批准后在哪里做（Docker `/workspace/project`） |
| Snapshot | 多次 confirm 之间工作区是否还在 |

### 3.2 宿主 vs 容器

```text
宿主 workspace/project/     ← 只提供初始有 bug 的样例（不被 Agent 直接改）
        │ 投影复制（workspaceProjectionRoots）
        ▼
容器 /workspace/project/   ← Agent 真正 read / edit / mvn test 的地方
```

### 3.3 装配分支

```text
sandbox.enabled
    │
    ├─ false ─→ 现有 AgentScopeConfig 路径（含 distributed ↔ RemoteFilesystem）
    │
    └─ true ──→ DockerFilesystemSpec
                + PathSafeAgentStateStore
                + 启用内置 FS/Shell 工具（去 write_file）
                + 保留自定义工具注册
```

**不改**：`/agentscope/dev-agent/ask`、`/confirm`、SSE 协议、前端 Tab。

---

## 4. 配置与组件

### 4.1 YAML（`app.agentscope.dev-agent.sandbox`）

```yaml
app:
  agentscope:
    dev-agent:
      sandbox:
        enabled: false
        image: agentscope-java-sandbox:17
        network: none
        workspace-root: /workspace
        snapshot-root: .agentscope/sandbox-snapshots
        memory-size-bytes: 536870912
        cpu-count: 1
```

`DevAgentProperties` 增加嵌套 `Sandbox` record。`enabled=true` 时校验 image、路径、资源非空且合法（`memory-size-bytes > 0`，`cpu-count > 0`）。

### 4.2 新增 / 调整文件

| 路径 | 作用 |
|------|------|
| `docker/sandbox/Dockerfile` | Maven 17 + Python + 预拉依赖（`maven.test.failure.ignore=true`） |
| `docker/sandbox/python3-wrapper` | `edit_file` 字面量 `\n` 转义兼容 |
| `docker/sandbox/docker-compose.yml` | **仅用于 build 镜像**；注释写清用法（见 §5） |
| `workspace/project/**` | `pom.xml` + `RetryPolicy`（bug）+ `RetryPolicyTest` |
| `.../PathSafeAgentStateStore.java` | 编码 / 解码含 `/` 的状态 ID |
| `DevAgentProperties.java` | 绑定 `sandbox.*` |
| `AgentScopeConfig.java` | 按开关组装 Docker filesystem / 工具 / PathSafe |
| `workspace/AGENTS.md` | 沙箱修 bug 路由与边界 |
| `README.md` | 镜像构建、开关、与 distributed 互斥说明 |
| `.gitignore` | 忽略 `.agentscope/sandbox-snapshots/`（若尚未忽略） |

### 4.3 `DockerFilesystemSpec` 要点

- `image` / `network` / `workspaceRoot` / `memorySizeBytes` / `cpuCount`
- `snapshotSpec(new LocalSnapshotSpec(snapshotPath))`；路径相对 `projectRoot` 解析
- `isolationScope(SESSION)`
- `workspaceProjectionRoots`：`AGENTS.md`、`skills`、`subagents`、`knowledge`、`.skills-cache`、`project`
- 交给 `HarnessAgent.builder().filesystem(...)`

### 4.4 状态存储

Sandbox 内部状态 ID 形如 `sandbox/session/<sessionId>`（含 `/`）。对底层 store（`InMemoryAgentStateStore` 或 PG 的 `agentStateStore`）包一层 `PathSafeAgentStateStore`。

沙箱开启时走 `.stateStore(pathSafe)`，**不**再与 `RemoteFilesystemSpec` 同时使用。若 `distributed.enabled=true` 且 PG 可用，仍可用 PG 作为 PathSafe 的底层实现，但 Workspace **文件**由 Docker 投影提供，不走远程 KV。

### 4.5 工具与权限

| 工具 | 沙箱开时 |
|------|----------|
| `read_file` | 启用；Permission **ALLOW** |
| `edit_file` | 启用；默认 **ASK**（现有 `/confirm`） |
| `execute` | 启用；默认 **ASK** |
| `write_file` | **移除** |
| `request_file_change` | 保留（仍仅 `notes/`） |
| 项目只读 / MCP / SubAgent 协作 / Memory | 保留现有规则 |

沙箱关闭时：继续 `disableFilesystemTools` + `disableShellTool`，与现网一致。

### 4.6 `AGENTS.md` 增补要点

- 用户要求在沙箱修代码 / 跑 `mvn test` / 修 `RetryPolicy`：只用 `read_file` / `edit_file` / `execute`；`working_directory` 为 `project`；顺序：先测 → 失败则读源码与测试 → `edit_file` → 再测；不要声称修改了宿主机源码。
- `notes/` 写入仍走 `request_file_change`。
- 代码审查仍走 Skill / SubAgent 既有规则。
- MCP 仍读 `mcp-files` 样例，与沙箱 `project` 路径区分。

---

## 5. Docker 约定与用法（必读）

### 5.1 和 postgres compose 的差别

| | `docker/agentscope-postgres` | `docker/sandbox` |
|--|------------------------------|------------------|
| 目的 | **常驻服务**：应用连接 PG | **构建镜像**：给 Harness 按需起临时容器 |
| 常用命令 | `docker compose ... up -d` | `docker compose ... build` |
| 谁管容器生命周期 | 你 `up`/`down` | AgentScope Harness（按 `sessionId`） |

不要对 sandbox 目录执行 `up -d` 当作「沙箱服务」；那不是本设计的运行方式。

### 5.2 目录与文件注释要求

实现时 `docker/sandbox/docker-compose.yml` **必须**在文件头写清注释，至少包含：

1. 本文件用途：构建 `agentscope-java-sandbox:17`，不是常驻沙箱。
2. 构建命令（从仓库根或说明相对路径）。
3. 构建成功后如何打开应用开关。
4. 与 `agentscope-postgres` 的关系（可并存；职责不同）。
5. 提醒：应用运行时由 Harness 拉起临时容器，无需对本 compose `up`。

`Dockerfile` 顶部同样用简短注释说明：基础镜像、为何预跑 `mvn test`（离线拉依赖）、`python3-wrapper` 用途。

### 5.3 推荐操作步骤（写入 README + compose 注释）

在仓库根目录（或按 README 写明的 cwd）：

```bash
# 1. 构建沙箱镜像（只需在镜像变更或首次使用时执行）
docker compose -f demo2/docker/sandbox/docker-compose.yml build

# 2.（可选）确认镜像存在
docker images agentscope-java-sandbox:17

# 3. 若要用 PG 存会话：照旧启动 postgres（与沙箱无关，可并存）
docker compose -f demo2/docker/agentscope-postgres/docker-compose.yml up -d

# 4. 打开沙箱开关后启动应用
#    app.agentscope.dev-agent.sandbox.enabled=true
#    （application.yml / 环境变量 / 本地覆盖配置均可）

# 5. 演示：同一 userId + sessionId 走 ask → confirm ×3
#    消息示例：请在沙箱中运行测试，修复 RetryPolicy 首次重试延迟翻倍的问题，并重新运行测试。
```

等价的直接 build（可选文档备选，主推仍是 compose）：

```bash
docker build -f demo2/docker/sandbox/Dockerfile -t agentscope-java-sandbox:17 demo2
```

（具体 build context 以最终 Dockerfile 的 `COPY` 路径为准，实现计划中钉死；compose 的 `context` / `dockerfile` 必须与之一致。）

### 5.4 compose 结构示意

```yaml
# AgentScope Dev Agent Docker Sandbox 镜像（非常驻服务）
# 构建：docker compose -f demo2/docker/sandbox/docker-compose.yml build
# 说明：Harness 在工具调用时按 session 创建临时容器；不要对本文件 up -d 当沙箱用。
# 打开应用开关：app.agentscope.dev-agent.sandbox.enabled=true
# 可选并存：demo2/docker/agentscope-postgres/docker-compose.yml（会话 PG）
services:
  agentscope-sandbox-image:
    image: agentscope-java-sandbox:17
    build:
      context: ../..   # 或实现时钉死的 context
      dockerfile: docker/sandbox/Dockerfile
    profiles: ["build-only"]  # 可选：避免误 up；若采用需在注释写清 build 时加 --profile
```

实现时可选用 `profiles: ["build-only"]` 防止误 `up`，或仅靠注释约束；计划阶段二选一并写清命令。

---

## 6. 数据流与确认

### 6.1 Happy path

```text
POST /ask（同一 userId + sessionId）
  → 创建/恢复 SESSION 沙箱，投影 project 等
  → execute: mvn -q test（working_directory: project）
      → REQUIRE_USER_CONFIRM #1
  → POST /confirm approved=true
      → 失败：expected 1000 but was 2000
  → read_file（ALLOW）
  → edit_file RetryPolicy.java
      → REQUIRE_USER_CONFIRM #2
  → confirm → 只改容器内文件；宿主 project 不变
  → execute: mvn -q test
      → REQUIRE_USER_CONFIRM #3
  → confirm → 通过 → AGENT_RESULT
```

续跑依赖：**AgentState**（对话与待确认工具）+ **Sandbox Snapshot**（容器文件）。confirm 必须同一 `userId` / `sessionId`。

### 6.2 错误与边界

- Docker 不可用 / 镜像不存在：工具失败并在 SSE 给出明确错误；**禁止**静默落到宿主机执行。
- 用户拒绝 confirm：按现有 Permission 拒绝语义续流，不改沙箱文件。
- 换 `sessionId`：新 SESSION 工作区，再次从有 bug 的初始 project 开始。
- `sandbox.enabled=true` 且 `distributed.enabled=true`：会话可用 PG（经 PathSafe）；Workspace 文件走 Docker，不走 RemoteFilesystem。文档写明互斥点。

### 6.3 RetryPolicy bug（样例契约）

错误实现：`baseDelayMillis * (1L << attempt)`（`attempt` 从 1 起导致首次 2000ms）。  
正确：`baseDelayMillis * (1L << (attempt - 1))`。  
测试钉死：`delayMillis(1/2/3) == 1000/2000/4000`。

---

## 7. 测试与验收

### 7.1 自动化（不依赖真 Docker 全链路）

| 用例 | 断言 |
|------|------|
| `DevAgentProperties` 绑定 | `sandbox.*` 默认值；`enabled=true` 时非法配置失败 |
| `PathSafeAgentStateStore` | 含 `/` 的 ID 可 save/load 并还原 |
| Config：sandbox off | 仍禁用内置 FS/Shell；回归现状 |
| Config：sandbox on | 配置被读取；toolkit 无 `write_file`；`read_file` 在 ALLOW 规则中 |

### 7.2 手工验收

1. 先 `docker compose -f demo2/docker/sandbox/docker-compose.yml build`，再开 `sandbox.enabled=true`。
2. ask 修 RetryPolicy → 三次 confirm → 测试通过。
3. 宿主 `workspace/project` 中 `RetryPolicy` **仍为错误实现**。
4. 新 `sessionId` 可再演示失败→修复。
5. `sandbox.enabled=false` 时审查 / notes / MCP 路径不受影响。

### 7.3 文档验收

- `docker/sandbox/docker-compose.yml` 与 `Dockerfile` 含 §5 要求的注释。
- README 含构建命令、开关、与 postgres compose 并存说明、演示 curl 示例。

---

## 8. 实现顺序建议

1. `workspace/project` 样例 + 单元可本地 `mvn test` 验证失败/修复契约  
2. `docker/sandbox`（Dockerfile、wrapper、compose + 注释）  
3. `PathSafeAgentStateStore` + 单测  
4. `DevAgentProperties.Sandbox` + 绑定测试  
5. `AgentScopeConfig` 分支装配 + 权限/`write_file` 处理  
6. `AGENTS.md` + README + `.gitignore`  
7. 手工全链路验收  

---

## 9. 成功标准

1. 默认关闭沙箱时，现有 Dev Agent 行为与本版前一致。  
2. 开启沙箱并完成镜像构建后，可在隔离环境内完成 RetryPolicy「失败→修改→复测」，且不改宿主样例源码。  
3. Permission HITL 与 Snapshot 跨 confirm 续跑可用。  
4. Docker 用法通过 compose 注释 + README 可独立照做，不与 postgres 常驻模式混淆。
