# AgentScope DistributedStore + Sandbox 跨实例接力 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 沙箱开启且 PostgreSQL DistributedStore 可用时，去掉本机 `LocalSnapshotSpec`，挂上 `.distributedStore`，使实例 A 在 `plan_exit` HITL 后退出，实例 B 能从 PG 恢复沙箱 `/workspace` 并继续执行。

**Architecture:** 扩展 `AgentScopeConfig` 沙箱装配分支：`AgentscopeDistributedBackend.Remote` → `.distributedStore(pinned)` + `.stateStore(PathSafe(同一 stateStore))` + `DockerFilesystemSpec` **不**设 `snapshotSpec`（由 Harness 注入 `PostgresSnapshotSpec`）。Local 降级路径保持现有本机快照。Diff / apply-diff 本版不改。

**Tech Stack:** Java 21、Spring Boot 4.x、AgentScope Java 2.0.0（已有 `PostgresDistributedStore` / `DockerFilesystemSpec` / `PathSafeAgentStateStore`）、Docker、PostgreSQL、JUnit、AssertJ。

**设计规范:** [docs/superpowers/specs/2026-07-31-agentscope-distributed-sandbox-handoff-design.md](../specs/2026-07-31-agentscope-distributed-sandbox-handoff-design.md)

## Global Constraints

- **不新增** Maven 依赖。
- DataSource 继续 `app.agentscope.datasource.*`；**禁止**改 `spring.datasource`（MySQL）。
- 沙箱开时仍**不**挂 `RemoteFilesystemSpec`。
- 无沙箱 + remote：仍禁止「裸 PG stateStore + distributedStore」双重物理注入；本版例外仅：`PathSafe(distributedStore.agentStateStore())`。
- **不改** Controller / DevAgentService 主流程 / SSE 事件类型 / 前端。
- **不改** `WorkspaceDiffService`（文档标明 PG 快照下 Diff 可能不可用）。
- 不接 S3 / 远程沙箱 / 旧 tar 迁移。
- 接力验收点 = `REQUIRE_USER_CONFIRM`，不承诺中途 crash recovery。
- 编译门禁：`mvn -f demo2/pom.xml -DskipTests compile`
- 单测门禁：`mvn -f demo2/pom.xml -Dtest=DevAgentPropertiesBindingTest,AgentscopeDistributedBackendFactoryTest,AgentscopeSandboxFilesystemSpecTest test`

## File Map

**Create**

- `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentscopeSandboxFilesystemSpecTest.java` — 本地 vs 远程快照装配断言
- `demo2/docs/superpowers/specs/2026-07-31-agentscope-distributed-sandbox-handoff-design.md` —（已有则跳过）

**Modify**

- `demo2/src/main/java/.../AgentScopeConfig.java` — `dockerFilesystemSpec` 条件挂快照；沙箱分支挂 `distributedStore`
- `demo2/src/main/java/.../AgentscopeDistributedBackendFactory.java` — builder **必须**钉住 `sandboxSnapshotSpec` + `sandboxExecutionGuard`（否则默认为 Noop）
- `demo2/src/main/java/.../DevAgentProperties.java` — `snapshot-root` 校验放宽
- `demo2/src/test/java/.../DevAgentPropertiesBindingTest.java` — 对齐新校验
- `demo2/src/test/java/.../AgentscopeDistributedBackendFactoryLivePgTest.java` — 断言非 Noop Snapshot/Guard
- `demo2/src/main/resources/application.properties` — 注释说明 snapshot-root 仅 Local 降级使用
- `demo2/README.md` — 跨实例 curl 步骤 + 边界说明

**Delete**

- 无（本版不删 `snapshot-root` 配置项，保留给 Local 降级）

**已确认装配形状：**

```java
// sandbox + Remote
builder.distributedStore(remote.distributedStore())
       .stateStore(pathSafeSameAsBean)  // PathSafe(remote.stateStore())
       .filesystem(dockerWithoutLocalSnapshot)
       ...

// sandbox + Local
builder.stateStore(pathSafeSameAsBean)
       .filesystem(dockerWithLocalSnapshot)
       ...
```

---

### Task 1: 属性校验放宽 + 绑定测试

**Files:**
- Modify: `DevAgentProperties.java`
- Modify: `DevAgentPropertiesBindingTest.java`

**意图:** 沙箱开启时不再强制「必须有可用的 snapshot-root 语义」导致 remote 路径别扭；默认值可保留，blank 校验改为：仅当 `enabled` 且调用方需要 LocalSnapshot 时才严格——实现上最简单做法：

- `snapshotRoot` 允许 blank 时回落到默认 `.agentscope/sandbox-snapshots`（compact constructor 内补默认），**删除**「enabled 时 blank 抛异常」分支；或保留非 blank 校验但 properties 始终有默认值（当前已有 `@DefaultValue`，启用时 blank 几乎只来自显式清空）。

推荐实现：

```java
if (enabled) {
    // ... image / network / workspaceRoot / memory / cpu 校验不变 ...
    if (snapshotRoot == null || snapshotRoot.isBlank()) {
        snapshotRoot = ".agentscope/sandbox-snapshots"; // 仅 Local 降级用；Remote 忽略
    }
}
```

- [ ] **Step 1: 改失败/更新绑定测试**

在 `DevAgentPropertiesBindingTest` 增加或调整：

```java
@Test
void sandboxEnabled_blankSnapshotRoot_fallsBackToDefault() {
    // 若用 ApplicationContextRunner 设 snapshot-root=
    // 期望 enabled 成功且 snapshotRoot 为默认，或绑定失败策略与实现一致
}
```

保留现有「显式 `.agentscope/snaps`」测试。

- [ ] **Step 2: 改 `DevAgentProperties.Sandbox` 校验**

按上列推荐实现；确保 `disabledDefaults()` 仍编译通过。

- [ ] **Step 3: 跑测试**

```bash
mvn -f demo2/pom.xml -Dtest=DevAgentPropertiesBindingTest test
```

Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/DevAgentProperties.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/config/DevAgentPropertiesBindingTest.java
git commit -m "$(cat <<'EOF'
fix(demo2): relax sandbox snapshot-root validation for PG snapshots

EOF
)"
```

---

### Task 2: `dockerFilesystemSpec` 按后端决定是否挂 LocalSnapshot（TDD）

**Files:**
- Modify: `AgentScopeConfig.java`（`dockerFilesystemSpec` 签名）
- Create: `AgentscopeSandboxFilesystemSpecTest.java`

**意图:**

```java
static DockerFilesystemSpec dockerFilesystemSpec(
        DevAgentProperties properties,
        ActiveSandboxRegistry registry,
        boolean useLocalSnapshot) {
    // ...
    DockerFilesystemSpec filesystem = new DockerFilesystemSpec()
            .client(...)
            .image(...)
            // ...
            .workspaceSpec(workspace);
    if (useLocalSnapshot) {
        Path snapshotPath = Path.of(properties.projectRoot())
                .resolve(config.snapshotRoot()).normalize();
        filesystem.snapshotSpec(new LocalSnapshotSpec(snapshotPath));
    }
    filesystem.isolationScope(IsolationScope.SESSION);
    filesystem.workspaceProjectionRoots(sandboxWorkspaceProjectionRoots());
    return filesystem;
}
```

- [ ] **Step 1: 写失败的单测**（同包可调 package-private / public static）

```java
package com.jason.demo.demo2.agentscope.config;

class AgentscopeSandboxFilesystemSpecTest {

    @Test
    void localMode_setsLocalSnapshotSpec() {
        DevAgentProperties props = /* 构造或用最小 stub：sandbox enabled + snapshotRoot */;
        ActiveSandboxRegistry registry = new ActiveSandboxRegistry();
        DockerFilesystemSpec spec =
                AgentScopeConfig.dockerFilesystemSpec(props, registry, true);
        assertThat(spec.getSnapshotSpec())  // 若无 getter，用反射读字段
                .isInstanceOf(LocalSnapshotSpec.class);
    }

    @Test
    void remoteMode_omitsLocalSnapshotSpec() {
        DockerFilesystemSpec spec =
                AgentScopeConfig.dockerFilesystemSpec(props, registry, false);
        assertThat(spec.getSnapshotSpec()).isNull(); // 或等价「非 LocalSnapshotSpec」
    }
}
```

若 `DockerFilesystemSpec` 无公开 getter：用反射读 `snapshotSpec` 字段；在测试类注释标明原因。

- [ ] **Step 2: 跑测确认红**

```bash
mvn -f demo2/pom.xml -Dtest=AgentscopeSandboxFilesystemSpecTest test
```

Expected: FAIL（签名未改或仍总是 Local）

- [ ] **Step 3: 实现条件挂载**

按上列改 `dockerFilesystemSpec`；暂时让 `buildAgentscopeDevAgent` 传入 `true`（保持行为），下一 Task 再接 Remote。

- [ ] **Step 4: 跑测绿**

```bash
mvn -f demo2/pom.xml -Dtest=AgentscopeSandboxFilesystemSpecTest test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentscopeSandboxFilesystemSpecTest.java
git commit -m "$(cat <<'EOF'
feat(demo2): make sandbox LocalSnapshotSpec optional by backend

EOF
)"
```

---

### Task 3: 沙箱分支挂 `distributedStore` + PathSafe 例外

**Files:**
- Modify: `AgentScopeConfig.java`（`buildAgentscopeDevAgent` 沙箱分支）

**意图:** 替换现有：

```java
if (sandbox.enabled()) {
    builder.stateStore(agentscopeAgentStateStore)
            .filesystem(dockerFilesystemSpec(properties, activeSandboxRegistry))
            ...
}
```

为：

```java
if (sandbox.enabled()) {
    boolean useLocalSnapshot =
            !(agentscopeDistributedBackend instanceof AgentscopeDistributedBackend.Remote);
    builder.stateStore(agentscopeAgentStateStore)
            .filesystem(dockerFilesystemSpec(
                    properties, activeSandboxRegistry, useLocalSnapshot))
            .disableCompaction()
            .disableMemoryTools()
            .disableMemoryHooks()
            .disableWorkspaceContext();
    if (agentscopeDistributedBackend instanceof AgentscopeDistributedBackend.Remote remote) {
        // PathSafe Bean 的 delegate 必须已是 remote.stateStore()（agentscopeAgentStateStore Bean 保证）
        builder.distributedStore(remote.distributedStore());
    }
}
```

注意顺序：先设 filesystem / stateStore，再 `distributedStore`（与文章示例一致；若官方要求顺序不同以实现期 jar 为准）。

**断言补充（可选，同 Task 2 测试类）：** 不强制集成启动 Spring；可用注释 + README 手工步骤覆盖 HITL。

- [ ] **Step 1: 改装配代码**

- [ ] **Step 2: 编译**

```bash
mvn -f demo2/pom.xml -DskipTests compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 跑单测门禁**

```bash
mvn -f demo2/pom.xml -Dtest=DevAgentPropertiesBindingTest,AgentscopeDistributedBackendFactoryTest,AgentscopeSandboxFilesystemSpecTest test
```

Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java
git commit -m "$(cat <<'EOF'
feat(demo2): wire DistributedStore into sandbox for PG snapshots

EOF
)"
```

---

### Task 4: README + properties 注释

**Files:**
- Modify: `application.properties`（sandbox.snapshot-root 旁注释）
- Modify: `README.md`（Sandbox / Distributed 相关小节）

**README 应写清：**

1. 能力：`sandbox=true` + `distributed=true` + PG → 跨实例在 HITL 断点接力。
2. 装配：Remote 时无 LocalSnapshot；自动 PostgresSnapshot + advisory lock。
3. Diff / apply-diff：本版仍读本机 tar，PG 快照会话可能无 Diff。
4. 手工验收 curl（端口 8080 / 8082），强调同一 `userId` + `sessionId`。
5. 边界：模型/工具中途崩溃不自动续跑。

示例 curl（按现有 API 路径改，demo2 一般为 `/agentscope/dev-agent/ask|confirm`——**以当前 Controller 为准，勿照抄文章 `/dev-agent`**）：

```bash
# 实例 A
curl -sN -X POST "http://localhost:8080/agentscope/dev-agent/ask" \
  -H "Content-Type: application/json" \
  -d '{"userId":"distributed-user-019","sessionId":"<uuid>","message":"请先调查并整理修复计划，等我确认后再修改。"}'

# 实例 B（关 A 后）
curl -sN -X POST "http://localhost:8082/agentscope/dev-agent/confirm" \
  -H "Content-Type: application/json" \
  -d '{"userId":"distributed-user-019","sessionId":"<uuid>","approved":true}'
```

可选 SQL：

```sql
SELECT session_id, state_key FROM agentscope.agentscope_sessions;
SELECT snapshot_id, octet_length(data) FROM agentscope_snapshots;
```

- [ ] **Step 1: 改 properties 注释与 README**

- [ ] **Step 2: Commit**

```bash
git add demo2/src/main/resources/application.properties demo2/README.md
git commit -m "$(cat <<'EOF'
docs(demo2): document sandbox cross-instance handoff via DistributedStore

EOF
)"
```

---

### Task 5: 手工跨实例验收（执行者勾选）

前置：

- [ ] Docker 运行中；镜像 `agentscope-java-sandbox:17` 已 build
- [ ] PostgreSQL 可达；`app.agentscope.distributed.enabled=true`
- [ ] `app.agentscope.dev-agent.sandbox.enabled=true`
- [ ] DeepSeek（及可选 Kimi）密钥已配

步骤：

- [ ] 终端 1：`mvn -f demo2/pom.xml spring-boot:run`（8080）
- [ ] `/ask` 跑到 `plan_exit` / `REQUIRE_USER_CONFIRM`
- [ ] （可选）查 PG 有 snapshot 行；确认 **没有**依赖 `.agentscope/sandbox-snapshots` 新文件（或可有旧文件但不被 B 使用）
- [ ] 终端 2：同配置 `--server.port=8082` 启动实例 B
- [ ] 停止实例 A
- [ ] B 上 `/confirm` 多次直至改文件 + 测试完成
- [ ] 记录结果：成功 / 失败原因（若 `distributedStore` 未注入 Snapshot，对照 Harness 源码补 `snapshotSpec` 显式绑定）

若框架**不会**在仅设 `distributedStore` 时自动注入 `PostgresSnapshotSpec`，回退方案（仍属本版）：

```java
// 查阅 io.agentscope.extensions.postgresql 包内 PostgresSnapshotSpec 构造
filesystem.snapshotSpec(PostgresSnapshotSpec.from(remote.distributedStore())); // API 名以实现为准
```

并补一条单测或 README 说明「显式绑定」。

---

## 风险登记

| 项 | 处理 |
|----|------|
| Harness 不自动换 PostgresSnapshot | Task 5 回退：显式 `snapshotSpec` |
| PathSafe + distributedStore 冲突 | 确认 HITL confirm 可读 ASKING；Bean 与 Harness 同一 PathSafe |
| Diff 静默空 | 文档声明，不修 |
| UPSERT typo patch | 已有 Factory patch，快照写入若走 BaseStore 需确认仍生效 |

## 完成定义

- [ ] Task 1–4 代码与文档合并到工作区
- [ ] 单测门禁 PASS
- [ ] Task 5 手工接力至少成功一次，或已记录阻塞并落地回退方案
