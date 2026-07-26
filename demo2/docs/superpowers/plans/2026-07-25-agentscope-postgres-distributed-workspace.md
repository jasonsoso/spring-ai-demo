# AgentScope PostgresDistributedStore / 共享 Workspace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 `PostgresDistributedStore` 统一会话状态与远程 Workspace，使多副本下 Memory / Workspace 文件经 PostgreSQL 共享；开关关闭或 PG 不可达时降级为内存会话 + 本地盘。

**Architecture:** 新增 `app.agentscope.distributed.enabled`；Factory 产出 `AgentscopeDistributedBackend`（`local` / `remote`）。remote 用 `PostgresDistributedStore.create` 后经 `DistributedStore.builder()` **钉死**同一 `AgentStateStore` 与 `BaseStore` 实例（官方 `PostgresDistributedStore.agentStateStore()` 每次调用都会 `new`）。`AgentScopeConfig`：remote → `.distributedStore` + `.filesystem(RemoteFilesystemSpec.USER)`；local → `.stateStore(InMemory)`。`AgentStateStore` Bean 与 Harness 共用同一实例。

**Tech Stack:** Java 21、Spring Boot 4.x、AgentScope Java 2.0.0（`PostgresDistributedStore` / `RemoteFilesystemSpec` / `IsolationScope`）、HikariCP、JUnit、AssertJ。

**设计规范:** [docs/superpowers/specs/2026-07-25-agentscope-postgres-distributed-workspace-design.md](../specs/2026-07-25-agentscope-postgres-distributed-workspace-design.md)

## Global Constraints

- AgentScope 保持 `2.0.0`，**不新增** Maven 依赖（已有 `agentscope-extensions-postgresql`）。
- 本版只接线 **state + Remote Workspace**；不接 Sandbox，不演示 Snapshot / Lock。
- `IsolationScope.USER`；成功路径 **禁止** 同时 `.stateStore(pg)` 与 `.distributedStore(...)`。
- DataSource 复用 `app.agentscope.datasource.*`；**禁止**改 `spring.datasource`（MySQL）。
- 进程内启动定一次后端，不热切换。
- **不改** Controller / Service 主流程 / SSE / 前端 / MCP / Permission 规则（除装配方式）。
- 仍 `disableFilesystemTools()`（远程 Workspace ≠ 放开内置文件工具）。
- 测试默认 `app.agentscope.distributed.enabled=false`。
- 本地默认 `app.agentscope.distributed.enabled=true`。
- 编译门禁：`mvn -f demo2/pom.xml -DskipTests compile`
- 单测门禁：`mvn -f demo2/pom.xml -Dtest=AgentscopeDistributedBackendFactoryTest,AgentScopeMiddlewareConfigTest,DevAgentServiceTest test`

## File Map

**Create**

- `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentscopeDistributedProperties.java` — `enabled` 开关
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentscopeDistributedBackend.java` — sealed：`Local` / `Remote`
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentscopeDistributedBackendFactory.java` — 开关 + 探测 + 钉实例
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentscopeDistributedBackendFactoryTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentscopeDistributedPropertiesBindingTest.java`

**Modify**

- `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java` — Bean 与 Harness 装配
- `demo2/src/main/resources/application.properties` — `distributed.enabled=true`
- `demo2/src/test/resources/application-test.properties` — `distributed.enabled=false`
- `demo2/src/test/java/.../AgentScopeMiddlewareConfigTest.java` — 入参改为 backend
- `demo2/README.md` — Distributed / 共享 Workspace 小节；Memory 落盘说明补「物理后端」

**Delete**

- `demo2/src/main/java/.../AgentStateStoreFactory.java`（逻辑迁入新 Factory）
- `demo2/src/test/java/.../AgentStateStoreFactoryTest.java`（由新测试替代）

**已确认 API（AgentScope 2.0.0 jar）：**

```java
PostgresDistributedStore.create(DataSource);           // agentStateStore()/baseStore() 每次 new —— 必须钉住
DistributedStore.builder()
    .agentStateStore(state)
    .baseStore(base)
    .build();
store.agentStateStore();                               // 取钉住的实例给 Bean

HarnessAgent.builder()
    .distributedStore(store)
    .filesystem(new RemoteFilesystemSpec().isolationScope(IsolationScope.USER))
    // 成功路径不要再 .stateStore(...)
    .workspace(path)
    .disableFilesystemTools()
    ...
```

---

### Task 1: Properties + Backend 模型 + Factory 单测（TDD）

**Files:**
- Create: `AgentscopeDistributedProperties.java`
- Create: `AgentscopeDistributedBackend.java`
- Create: `AgentscopeDistributedBackendFactory.java`
- Create: `AgentscopeDistributedBackendFactoryTest.java`
- Create: `AgentscopeDistributedPropertiesBindingTest.java`
- Delete: `AgentStateStoreFactory.java`、`AgentStateStoreFactoryTest.java`（本 Task 末尾或 Task 2 接线后删除；若编译依赖仍引用则先保留旧 Factory 到 Task 2）

**Interfaces:**
- Produces:
  - `AgentscopeDistributedProperties(boolean enabled)`，前缀 `app.agentscope.distributed`，缺省 `enabled=true`（compact constructor：无参绑定注意默认值）
  - ```java
    public sealed interface AgentscopeDistributedBackend
            permits AgentscopeDistributedBackend.Local, AgentscopeDistributedBackend.Remote {
        AgentStateStore stateStore();
        record Local(AgentStateStore stateStore) implements AgentscopeDistributedBackend {}
        record Remote(DistributedStore distributedStore, AgentStateStore stateStore)
                implements AgentscopeDistributedBackend {}
    }
    ```
  - `AgentscopeDistributedBackendFactory.create(AgentscopeDistributedProperties distributed, AgentScopeDataSourceProperties ds)`
- Consumes: 现有 `AgentScopeDataSourceProperties`

- [ ] **Step 1: 写失败的 Factory 测试**

```java
package com.jason.demo.demo2.agentscope.config;

import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.DistributedStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentscopeDistributedBackendFactoryTest {

    private static AgentScopeDataSourceProperties unreachableDs() {
        return new AgentScopeDataSourceProperties(
                "jdbc:postgresql://127.0.0.1:1/agentscope",
                "agentscope",
                "agentscope",
                1000L);
    }

    @Test
    void create_disabled_returnsLocalInMemory_withoutNeedingReachablePg() {
        AgentscopeDistributedBackend backend = AgentscopeDistributedBackendFactory.create(
                new AgentscopeDistributedProperties(false),
                unreachableDs());

        assertThat(backend).isInstanceOf(AgentscopeDistributedBackend.Local.class);
        assertThat(backend.stateStore()).isInstanceOf(InMemoryAgentStateStore.class);
    }

    @Test
    void create_enabled_unreachableHost_fallsBackToLocalInMemory() {
        AgentscopeDistributedBackend backend = AgentscopeDistributedBackendFactory.create(
                new AgentscopeDistributedProperties(true),
                unreachableDs());

        assertThat(backend).isInstanceOf(AgentscopeDistributedBackend.Local.class);
        assertThat(backend.stateStore()).isInstanceOf(InMemoryAgentStateStore.class);
    }

    @Test
    void create_remote_pinsSameStateStoreInstance() {
        // 仅在本机 Docker PG 可用时启用；默认用假设或跳过。
        // 实现阶段：若无 PG，用 Mockito spy 不可行（create 真连库）。
        // 改为单元级：通过 package-visible pin 辅助，或本测试标记 @EnabledIfEnvironmentVariable。
        // 最小可合并断言：Remote 时 stateStore() == distributedStore.agentStateStore()
        // —— 见 Step 3 实现后的固定断言写法。
    }
}
```

将第三个测试在实现后写成（不依赖真 PG 时用 Factory 内部可测的 pin 逻辑——见 Step 3；若暂时无法无 PG 测 Remote，可删该测试，保留前两个）：

```java
@Test
void remoteBackend_stateStoreMatchesDistributedStore() {
    // 使用已 pin 的假 DistributedStore（不经 PG）
    AgentStateStore state = new InMemoryAgentStateStore();
    DistributedStore store = DistributedStore.builder()
            .agentStateStore(state)
            .baseStore(org.mockito.Mockito.mock(
                    io.agentscope.harness.agent.filesystem.remote.store.BaseStore.class))
            .build();
    AgentscopeDistributedBackend.Remote remote =
            new AgentscopeDistributedBackend.Remote(store, state);

    assertThat(remote.stateStore()).isSameAs(store.agentStateStore());
    assertThat(remote.stateStore()).isSameAs(state);
}
```

- [ ] **Step 2: 写 Properties 绑定测试**

```java
package com.jason.demo.demo2.agentscope.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AgentscopeDistributedPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableCfg.class);

    @Test
    void defaultEnabledIsTrueWhenPropertyMissing() {
        runner.run(ctx -> assertThat(ctx.getBean(AgentscopeDistributedProperties.class).enabled())
                .isTrue());
    }

    @Test
    void bindsEnabledFalse() {
        runner.withPropertyValues("app.agentscope.distributed.enabled=false")
                .run(ctx -> assertThat(ctx.getBean(AgentscopeDistributedProperties.class).enabled())
                        .isFalse());
    }

    @EnableConfigurationProperties(AgentscopeDistributedProperties.class)
    static class EnableCfg {
    }
}
```

注意：若 `@DefaultValue("true")` / compact 默认不生效导致 missing 属性失败，改用：

```java
public record AgentscopeDistributedProperties(Boolean enabled) {
    public AgentscopeDistributedProperties {
        if (enabled == null) {
            enabled = true;
        }
    }
    public boolean enabled() {
        return enabled;
    }
}
```

或 `boolean enabled` + `@DefaultValue("true")`（与项目其它 properties 风格对齐，优先查 `DevAgentProperties.Memory` 写法）。

- [ ] **Step 3: 跑测试确认失败**

Run: `mvn -f demo2/pom.xml -Dtest=AgentscopeDistributedBackendFactoryTest,AgentscopeDistributedPropertiesBindingTest test`

Expected: FAIL（类不存在）

- [ ] **Step 4: 实现 Properties / Backend / Factory**

`AgentscopeDistributedProperties.java`:

```java
package com.jason.demo.demo2.agentscope.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.agentscope.distributed")
public record AgentscopeDistributedProperties(@DefaultValue("true") boolean enabled) {
}
```

`AgentscopeDistributedBackend.java`:（Interfaces 块中的 sealed 定义原文）

`AgentscopeDistributedBackendFactory.java`:

```java
package com.jason.demo.demo2.agentscope.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.extensions.postgresql.PostgresDistributedStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;

public final class AgentscopeDistributedBackendFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentscopeDistributedBackendFactory.class);

    private AgentscopeDistributedBackendFactory() {
    }

    public static AgentscopeDistributedBackend create(
            AgentscopeDistributedProperties distributed,
            AgentScopeDataSourceProperties dsProps) {
        if (!distributed.enabled()) {
            log.info("AgentScope distributed=off (memory stateStore + local workspace)");
            return new AgentscopeDistributedBackend.Local(new InMemoryAgentStateStore());
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dsProps.url());
        config.setUsername(dsProps.username());
        config.setPassword(dsProps.password());
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(dsProps.connectionTimeoutMs());
        config.setPoolName("agentscope-postgres");
        config.setInitializationFailTimeout(-1);

        HikariDataSource dataSource = new HikariDataSource(config);
        try (Connection ignored = dataSource.getConnection()) {
            PostgresDistributedStore created = PostgresDistributedStore.create(dataSource);
            // 钉住实例：create() 上的 agentStateStore()/baseStore() 每次都会 new
            var stateStore = created.agentStateStore();
            BaseStore baseStore = created.baseStore();
            DistributedStore pinned = DistributedStore.builder()
                    .agentStateStore(stateStore)
                    .baseStore(baseStore)
                    .build();
            log.info("AgentScope distributed=postgres url={}", dsProps.url());
            return new AgentscopeDistributedBackend.Remote(pinned, stateStore);
        } catch (Exception ex) {
            log.warn(
                    "AgentScope PostgreSQL unreachable; distributed=local fallback. reason={}",
                    ex.toString());
            try {
                dataSource.close();
            } catch (Exception closeEx) {
                log.debug("Failed to close agentscope DataSource after probe failure", closeEx);
            }
            return new AgentscopeDistributedBackend.Local(new InMemoryAgentStateStore());
        }
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -f demo2/pom.xml -Dtest=AgentscopeDistributedBackendFactoryTest,AgentscopeDistributedPropertiesBindingTest test`

Expected: SUCCESS

- [ ] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentscopeDistributedProperties.java \
  demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentscopeDistributedBackend.java \
  demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentscopeDistributedBackendFactory.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentscopeDistributedBackendFactoryTest.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentscopeDistributedPropertiesBindingTest.java
git commit -m "feat(demo2): add AgentscopeDistributedBackend factory with enabled switch"
```

---

### Task 2: AgentScopeConfig 接线 + 删除旧 Factory

**Files:**
- Modify: `AgentScopeConfig.java`
- Modify: `AgentScopeMiddlewareConfigTest.java`
- Delete: `AgentStateStoreFactory.java`、`AgentStateStoreFactoryTest.java`
- Modify: `application.properties`、`application-test.properties`

**Interfaces:**
- Consumes: `AgentscopeDistributedBackendFactory.create(...)`
- Produces: Bean `AgentscopeDistributedBackend`；Bean `AgentStateStore` = `backend.stateStore()`；`HarnessAgent` 按类型分支装配

- [ ] **Step 1: 改配置文件**

`application.properties`（紧挨现有 `app.agentscope.datasource.*`）增加：

```properties
# true：跟 PG 绑定（PostgresDistributedStore + Remote Workspace）；false：内存会话 + 本地盘
app.agentscope.distributed.enabled=true
```

`application-test.properties` 增加：

```properties
app.agentscope.distributed.enabled=false
```

- [ ] **Step 2: 改 AgentScopeConfig Bean**

替换原：

```java
@Bean
AgentStateStore agentscopeAgentStateStore(AgentScopeDataSourceProperties dataSourceProperties) {
    return AgentStateStoreFactory.create(dataSourceProperties);
}
```

为：

```java
@Bean
AgentscopeDistributedBackend agentscopeDistributedBackend(
        AgentscopeDistributedProperties distributedProperties,
        AgentScopeDataSourceProperties dataSourceProperties) {
    return AgentscopeDistributedBackendFactory.create(distributedProperties, dataSourceProperties);
}

@Bean
AgentStateStore agentscopeAgentStateStore(AgentscopeDistributedBackend backend) {
    return backend.stateStore();
}
```

- [ ] **Step 3: 改 `agentscopeDevAgent` 装配**

将方法参数 `AgentStateStore agentscopeAgentStateStore` 改为 `AgentscopeDistributedBackend agentscopeDistributedBackend`。

构建处：

```java
HarnessAgent.Builder builder = HarnessAgent.builder()
        .name(properties.name())
        .sysPrompt(systemPrompt)
        .model(agentscopeDeepSeekModel)
        .workspace(Path.of(properties.workspaceRoot()))
        // 不要在这里无条件 .stateStore(...)
        .permissionContext(permissionContext(properties, agentscopeMcpClientRegistry))
        // ... 其余不变
        ;

if (agentscopeDistributedBackend instanceof AgentscopeDistributedBackend.Remote remote) {
    builder.distributedStore(remote.distributedStore())
            .filesystem(new RemoteFilesystemSpec()
                    .isolationScope(IsolationScope.USER));
} else {
    builder.stateStore(agentscopeDistributedBackend.stateStore());
}
```

补充 import：

```java
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
```

**禁止** remote 分支再调用 `.stateStore(...)`。

- [ ] **Step 4: 更新 AgentScopeMiddlewareConfigTest**

将 `store` 参数改为：

```java
new AgentscopeDistributedBackend.Local(store)
```

两处调用 `agentscopeDevAgent(...)`（`agentscopeDevAgent_registersCustomLogging...` 与 `buildAgent`）都改。

可选增强（本 Task 可不加）：remote 路径断言 `filesystem` 已设——若难从 HarnessAgent 反射读取，跳过。

- [ ] **Step 5: 删除旧 Factory 与旧测试**

确认无引用后删除：

- `AgentStateStoreFactory.java`
- `AgentStateStoreFactoryTest.java`

- [ ] **Step 6: 编译与单测**

Run:

```bash
mvn -f demo2/pom.xml -DskipTests compile
mvn -f demo2/pom.xml -Dtest=AgentscopeDistributedBackendFactoryTest,AgentscopeDistributedPropertiesBindingTest,AgentScopeMiddlewareConfigTest,DevAgentServiceTest test
```

Expected: SUCCESS

- [ ] **Step 7: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java \
  demo2/src/main/resources/application.properties \
  demo2/src/test/resources/application-test.properties \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java \
  demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentStateStoreFactory.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentStateStoreFactoryTest.java
git commit -m "feat(demo2): wire PostgresDistributedStore and RemoteFilesystemSpec"
```

（删除文件用 `git add` 后的删除状态一并提交。）

---

### Task 3: README 文档

**Files:**
- Modify: `demo2/README.md`（AgentScope 小节：会话持久化 + Workspace + Memory）

**Interfaces:**
- Consumes: 已落地开关与降级语义
- Produces: 运维可读说明

- [ ] **Step 1: 在「会话持久化」段落后插入 Distributed 说明**

在现有「会话持久化（PostgreSQL…）」段落后（约 `stateStore=memory` 说明之后）增加：

```markdown
**分布式后端（`PostgresDistributedStore` / 共享 Workspace）：**

- 开关：`app.agentscope.distributed.enabled`（本地默认 `true`；测试 `false`）
  - `true`：探测 `app.agentscope.datasource.*`；成功则 `PostgresDistributedStore` + `RemoteFilesystemSpec`（`IsolationScope.USER`）；失败 WARN 并降级
  - `false`：不探测；内存 `AgentStateStore` + 本地 `workspace-root`
- 成功路径用 `.distributedStore(...)`，不再单独装配 `PostgresAgentStateStore`
- `DevAgentService` 与 `HarnessAgent` 共用同一 `AgentStateStore` 实例（HITL confirm 仍可用）
- 本版**不**接 Sandbox，**不**演示 Snapshot / advisory Lock
- 启用远程 Workspace **不会**放开内置 filesystem / shell 工具
- 本地已有 `workspace/{userId}/MEMORY.md` 切到远程后不会自动迁移（远程优先；需人工拷贝或接受空起点）
```

- [ ] **Step 2: 微调 Workspace / Memory 落盘表述**

将 Memory「落盘：`workspace/{userId}/MEMORY.md`」改为类似：

```markdown
- 逻辑路径：`workspace/{userId}/MEMORY.md` 与 `memory/YYYY-MM-DD.md`（按 **userId** 隔离）
- 物理后端：`distributed.enabled=true` 且 PG 可达时写入 PostgreSQL KV；否则写本地盘
- 多机部署必须开 distributed 并共用同一 PG；单机本地盘即可演示
```

Workspace 段可补一句：`AGENTS.md` 等模板仍随仓库部署；用户运行时文件在 remote 模式下进 PG。

- [ ] **Step 3: 更新能力一句话（表格若有）**

若 README 能力表仍写「PostgreSQL 会话」，可改为「PostgreSQL DistributedStore（会话 + 共享 Workspace）」。

- [ ] **Step 4: Commit**

```bash
git add demo2/README.md
git commit -m "docs(demo2): document AgentScope distributed Workspace and switch"
```

---

### Task 4: 手工验收（有 Docker PG）

**Files:** 无代码改动

**Interfaces:**
- Consumes: 运行中的 demo2、`DEEPSEEK_API_KEY`、`demo2/docker/agentscope-postgres`

- [ ] **Step 1: 启动 PG 与应用**

```bash
docker compose -f demo2/docker/agentscope-postgres/docker-compose.yml up -d
# 从 demo2 目录启动应用；确认日志含：
# AgentScope distributed=postgres
```

- [ ] **Step 2: Memory 写入（若 `memory.enabled=true`）**

使用 README 中 Memory 示例 curl（`memory-user-012`）：会话 A 让 Agent `memory_save` 一条约定；若 HITL 开则 `/confirm` 批准。

- [ ] **Step 3: 重启应用后跨会话读取**

重启 JVM → 同 `userId`、**新** `sessionId` 提问该约定 → 应仍能答出（不必读项目文件）。

- [ ] **Step 4: 开关关回归**

临时设 `app.agentscope.distributed.enabled=false` 启动 → 日志 `distributed=off`；应用可问 Workspace `AGENTS.md` 规则；不要求跨机。

- [ ] **Step 5: 确认不需要代码 commit**（仅验收清单勾选）

---

## Spec Coverage Checklist

| Spec 项 | Task |
|---------|------|
| `PostgresDistributedStore` 替代单独 state store | Task 2 |
| `RemoteFilesystemSpec` + `USER` | Task 2 |
| `distributed.enabled` 默认 true / test false | Task 1–2 |
| 关 → 内存+本地；开+失败 → 降级 WARN | Task 1 |
| 成功路径不双重 `.stateStore` | Task 2 |
| 同一 `AgentStateStore` Bean | Task 1 pin + Task 2 |
| 不改 API/SSE/前端/MCP | 全计划未引入 |
| 不验 Snapshot/Lock/Sandbox | Task 3 文档声明 |
| README + Memory 物理后端说明 | Task 3 |
| 手工重启验收 | Task 4 |
| 钉住 `agentStateStore()` 实例（API 陷阱） | Task 1 Factory |

## Self-Review Notes

- 无 TBD；Factory 必须 `DistributedStore.builder()` pin，否则 HITL confirm 与 Harness 可能各持一个 `PostgresAgentStateStore`。
- `PostgresDistributedStore` 内部 state 仍 `createIfNotExist=true`，与旧会话表兼容（同 `PostgresAgentStateStore`）；BaseStore 另表 `initializeSchema(true)`。
- `AgentScopeMiddlewareConfigTest` 继续用 `Local(mock)`，避免测试连 PG。
