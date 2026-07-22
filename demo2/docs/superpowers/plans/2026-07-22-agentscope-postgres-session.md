# AgentScope PostgreSQL 会话持久化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Dev Agent 的 `AgentStateStore` 从内存换成独立 PostgreSQL（可 Docker 一键启动）；PG 不可用时降级内存；confirm 从 store 读取 `ASKING` 工具调用以支持跨重启恢复。

**Architecture:** 新增 `app.agentscope.datasource.*` 独立连接（不碰 MySQL `spring.datasource`）。`AgentStateStore` Bean 启动时短超时探测；成功则 `PostgresAgentStateStore.createIfNotExist(true)`，失败则 WARN + `InMemoryAgentStateStore`。`DevAgentService` 删除内存 Map，统一从 store 加载 pending。

**Tech Stack:** Java 21, Spring Boot 4.x, AgentScope Java 2.0.0, `agentscope-extensions-postgresql`, HikariCP, PostgreSQL 16 (Docker), JUnit 5, Mockito, AssertJ, reactor-test

**设计规范:** [docs/superpowers/specs/2026-07-22-agentscope-postgres-session-design.md](../specs/2026-07-22-agentscope-postgres-session-design.md)

## Global Constraints

- **AgentScope 版本**：`2.0.0`
- **状态后端**：`PostgresAgentStateStore`（非 `PostgresDistributedStore`）
- **数据源**：独立 PG；**禁止**修改 `spring.datasource`（MySQL）
- **降级**：Bean 初始化探测一次；失败 → WARN + `InMemoryAgentStateStore`；进程内不热切换
- **建表**：开发默认 `createIfNotExist(true)`
- **state key**：`"agent_state"`；类型 `AgentState.class`
- **ASKING**：`ToolCallState.ASKING`；从 context 中最后一条 `MsgRole.ASSISTANT` 收集
- **不修改**：Controller API、SSE 事件模型、前端、工具/权限规则
- **编译门禁**：`mvn -f demo2/pom.xml -DskipTests compile` 必须 SUCCESS
- **单测门禁**：`mvn -f demo2/pom.xml -Dtest=DevAgentServiceTest,AgentStateStoreFactoryTest test` 必须 SUCCESS

---

## File Structure

| 文件 | 职责 |
|------|------|
| `demo2/docker/agentscope-postgres/docker-compose.yml` | PG16 容器 + 健康检查 + 命名卷 |
| `demo2/pom.xml` | `agentscope-extensions-postgresql` + `postgresql` runtime |
| `demo2/src/main/resources/application.properties` | `app.agentscope.datasource.*` |
| `.../agentscope/config/AgentScopeDataSourceProperties.java` | PG 连接配置 record |
| `.../agentscope/config/AgentStateStoreFactory.java` | 探测 + 构建 store（可单测） |
| `.../agentscope/config/AgentScopeConfig.java` | 注册 `AgentStateStore` Bean；HarnessAgent 注入 |
| `.../agentscope/service/DevAgentService.java` | 去掉 Map；confirm 从 store 读 ASKING |
| `.../AgentStateStoreFactoryTest.java` | 无效 URL → InMemory |
| `.../DevAgentServiceTest.java` | mock store：无/有 ASKING 的 confirm |

**已确认 API（AgentScope 2.0.0 jar）：**

```java
PostgresAgentStateStore.builder(DataSource)
    .createIfNotExist(true)
    .build();

Optional<T> get(String userId, String sessionId, String stateKey, Class<T> type);

ToolUseBlock.builder().id(...).name(...).input(...).state(ToolCallState.ASKING).build();
toolUseBlock.getState() == ToolCallState.ASKING

AgentState.builder().userId(...).sessionId(...).context(List.of(msg)).build();
msg.getRole() == MsgRole.ASSISTANT
msg.getContentBlocks(ToolUseBlock.class)
```

---

### Task 1: Docker Compose（PostgreSQL）

**Files:**
- Create: `demo2/docker/agentscope-postgres/docker-compose.yml`

**Interfaces:**
- Produces: 本机 `127.0.0.1:5432`，库/用户/密码均为 `agentscope`
- Consumes: 无

- [ ] **Step 1: 写入 compose 文件**

```yaml
# AgentScope 会话状态用 PostgreSQL（与 MySQL spring.datasource 独立）
# 启动：docker compose -f demo2/docker/agentscope-postgres/docker-compose.yml up -d
# 停止：docker compose -f demo2/docker/agentscope-postgres/docker-compose.yml down
# 连接：jdbc:postgresql://127.0.0.1:5432/agentscope  user/password=agentscope
services:
  agentscope-postgres:
    container_name: demo2-agentscope-postgres
    image: postgres:16
    ports:
      - "5432:5432"
    environment:
      POSTGRES_USER: agentscope
      POSTGRES_PASSWORD: agentscope
      POSTGRES_DB: agentscope
    volumes:
      - agentscope_pg_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U agentscope -d agentscope"]
      interval: 5s
      timeout: 5s
      retries: 10
    restart: unless-stopped

volumes:
  agentscope_pg_data:
```

- [ ] **Step 2: 启动并验证健康**

Run:

```bash
docker compose -f demo2/docker/agentscope-postgres/docker-compose.yml up -d
docker inspect --format="{{.State.Health.Status}}" demo2-agentscope-postgres
```

Expected: 数秒后 `healthy`

- [ ] **Step 3: Commit**

```bash
git add demo2/docker/agentscope-postgres/docker-compose.yml
git commit -m "chore(demo2): add AgentScope PostgreSQL docker compose"
```

---

### Task 2: Maven 依赖 + application.properties

**Files:**
- Modify: `demo2/pom.xml`（AgentScope 依赖段，约 271–279 行后追加）
- Modify: `demo2/src/main/resources/application.properties`（`app.agentscope.dev-agent.*` 附近）

**Interfaces:**
- Produces: 编译期可用 `PostgresAgentStateStore`；runtime 有 PG 驱动
- Consumes: 现有 `agentscope-bom`

- [ ] **Step 1: 在 `pom.xml` AgentScope 段追加依赖**

在 `agentscope-extensions-model-openai` 之后加入：

```xml
        <dependency>
            <groupId>io.agentscope</groupId>
            <artifactId>agentscope-extensions-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
```

说明：版本由 BOM 管理。Hikari / JDBC 已由现有 ChatMemory JDBC 传递引入；若编译缺 `HikariDataSource`，再显式加 `spring-boot-starter-jdbc`。

- [ ] **Step 2: 追加配置到 `application.properties`**

```properties
# AgentScope 会话状态：独立 PostgreSQL（与 spring.datasource MySQL 无关）
# Docker: docker compose -f demo2/docker/agentscope-postgres/docker-compose.yml up -d
app.agentscope.datasource.url=jdbc:postgresql://127.0.0.1:5432/agentscope
app.agentscope.datasource.username=agentscope
app.agentscope.datasource.password=${AGENTSCOPE_PG_PASSWORD:agentscope}
app.agentscope.datasource.connection-timeout-ms=3000
```

- [ ] **Step 3: 编译验证依赖可解析**

Run: `mvn -f demo2/pom.xml -DskipTests compile`

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add demo2/pom.xml demo2/src/main/resources/application.properties
git commit -m "chore(demo2): add AgentScope PostgreSQL dependencies and config"
```

---

### Task 3: DataSource 属性 + AgentStateStoreFactory（含降级单测）

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeDataSourceProperties.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentStateStoreFactory.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentStateStoreFactoryTest.java`

**Interfaces:**
- Produces:
  ```java
  @ConfigurationProperties(prefix = "app.agentscope.datasource")
  public record AgentScopeDataSourceProperties(
      @NotBlank String url,
      @NotBlank String username,
      String password,
      long connectionTimeoutMs) { ... }

  public final class AgentStateStoreFactory {
      public static AgentStateStore create(AgentScopeDataSourceProperties props);
  }
  ```
- Consumes: HikariCP、`PostgresAgentStateStore`、`InMemoryAgentStateStore`

- [ ] **Step 1: 写失败测试 `AgentStateStoreFactoryTest`**

```java
package com.jason.demo.demo2.agentscope.config;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentStateStoreFactoryTest {

    @Test
    void create_unreachableHost_fallsBackToInMemory() {
        AgentScopeDataSourceProperties props = new AgentScopeDataSourceProperties(
                "jdbc:postgresql://127.0.0.1:1/agentscope",
                "agentscope",
                "agentscope",
                1000L);

        AgentStateStore store = AgentStateStoreFactory.create(props);

        assertThat(store).isInstanceOf(InMemoryAgentStateStore.class);
    }
}
```

- [ ] **Step 2: 运行测试确认失败（类尚不存在）**

Run: `mvn -f demo2/pom.xml -Dtest=AgentStateStoreFactoryTest test`

Expected: FAIL（找不到类）

- [ ] **Step 3: 实现 `AgentScopeDataSourceProperties`**

```java
package com.jason.demo.demo2.agentscope.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.agentscope.datasource")
public record AgentScopeDataSourceProperties(
        @NotBlank String url,
        @NotBlank String username,
        String password,
        long connectionTimeoutMs) {

    public AgentScopeDataSourceProperties {
        if (connectionTimeoutMs <= 0) {
            connectionTimeoutMs = 3000L;
        }
        if (password == null) {
            password = "";
        }
    }
}
```

（项目已有 `@ConfigurationPropertiesScan`。）

- [ ] **Step 4: 实现 `AgentStateStoreFactory`**

```java
package com.jason.demo.demo2.agentscope.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.extensions.postgresql.state.PostgresAgentStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;

public final class AgentStateStoreFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentStateStoreFactory.class);

    private AgentStateStoreFactory() {
    }

    public static AgentStateStore create(AgentScopeDataSourceProperties props) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(props.url());
        config.setUsername(props.username());
        config.setPassword(props.password());
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(props.connectionTimeoutMs());
        config.setPoolName("agentscope-postgres");
        config.setInitializationFailTimeout(-1);

        HikariDataSource dataSource = new HikariDataSource(config);
        try (Connection ignored = dataSource.getConnection()) {
            AgentStateStore store = PostgresAgentStateStore.builder(dataSource)
                    .createIfNotExist(true)
                    .build();
            log.info("AgentScope stateStore=postgres url={}", props.url());
            return store;
        } catch (Exception ex) {
            log.warn(
                    "AgentScope PostgreSQL unreachable; stateStore=memory. reason={}",
                    ex.toString());
            try {
                dataSource.close();
            } catch (Exception closeEx) {
                log.debug("Failed to close agentscope DataSource after probe failure", closeEx);
            }
            return new InMemoryAgentStateStore();
        }
    }
}
```

注意：
- **不要**把该 DataSource 注册成第二个全局 `@Bean DataSource`，避免干扰 MySQL 自动配置。
- `initializationFailTimeout(-1)`：避免 Hikari 构造期硬失败，改由 `getConnection()` 探测并降级。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -f demo2/pom.xml -Dtest=AgentStateStoreFactoryTest test`

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeDataSourceProperties.java \
  demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentStateStoreFactory.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentStateStoreFactoryTest.java
git commit -m "feat(demo2): add AgentScope PG store factory with memory fallback"
```

---

### Task 4: 接线 AgentScopeConfig

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`

**Interfaces:**
- Consumes: `AgentScopeDataSourceProperties`、`AgentStateStoreFactory.create(...)`
- Produces: `@Bean AgentStateStore agentscopeAgentStateStore(...)`；HarnessAgent 注入该 Bean

- [ ] **Step 1: 增加 store Bean，并改 HarnessAgent**

新增：

```java
    @Bean
    AgentStateStore agentscopeAgentStateStore(AgentScopeDataSourceProperties dataSourceProperties) {
        return AgentStateStoreFactory.create(dataSourceProperties);
    }
```

`agentscopeDevAgent` 增加参数 `AgentStateStore agentscopeAgentStateStore`，并将：

```java
.stateStore(new InMemoryAgentStateStore())
```

改为：

```java
.stateStore(agentscopeAgentStateStore)
```

方法头：

```java
    @Bean
    HarnessAgent agentscopeDevAgent(
            @Qualifier("agentscopeDeepSeekModel") Model agentscopeDeepSeekModel,
            DevAgentProperties properties,
            ProjectInfoTools projectInfoTools,
            FileChangeTool fileChangeTool,
            AgentStateStore agentscopeAgentStateStore) throws IOException {
```

补 import `io.agentscope.core.state.AgentStateStore`；删除无用的 `InMemoryAgentStateStore` import。

- [ ] **Step 2: 编译**

Run: `mvn -f demo2/pom.xml -DskipTests compile`

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java
git commit -m "feat(demo2): wire HarnessAgent to Postgres-capable AgentStateStore"
```

---

### Task 5: DevAgentService 从 store 恢复 ASKING + 更新单测

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java`

**Interfaces:**
- Consumes: `AgentStateStore.get(userId, sessionId, "agent_state", AgentState.class)`
- Produces: private `loadPendingToolCalls` / `findAskingToolCalls`；构造器增加 `AgentStateStore`

- [ ] **Step 1: 先改测试（TDD）**

`setUp` 增加 `@Mock AgentStateStore agentStateStore`，构造改为：

```java
service = new DevAgentService(harnessAgent, properties, agentStateStore);
```

`confirm_withoutPending_emitsError`：

```java
    @Test
    void confirm_withoutPending_emitsError() {
        when(agentStateStore.get(eq("u1"), eq("s-missing"), eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.empty());

        StepVerifier.create(service.confirm(new DevAgentConfirmRequest("u1", "s-missing", true)))
                .expectNext(DevAgentEvent.session("s-missing"))
                .expectNextMatches(e ->
                        e.type() == DevAgentEventType.ERROR
                                && e.content().contains("待确认"))
                .verifyComplete();
    }
```

`confirm_approved_resumesWithConfirmResultsMetadata`：不再先 `ask`；stub store：

```java
    @Test
    void confirm_approved_resumesWithConfirmResultsMetadata() {
        ToolUseBlock asking = ToolUseBlock.builder()
                .id("call-9")
                .name("request_file_change")
                .input(Map.of("operation", "create", "path", "notes/a.txt", "content", "x"))
                .state(ToolCallState.ASKING)
                .build();
        Msg assistant = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(asking)
                .build();
        AgentState state = AgentState.builder()
                .userId("u1")
                .sessionId("s1")
                .context(List.of(assistant))
                .build();
        when(agentStateStore.get(eq("u1"), eq("s1"), eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.of(state));

        ToolResultEndEvent toolEnd = mock(ToolResultEndEvent.class);
        when(toolEnd.getType()).thenReturn(AgentEventType.TOOL_RESULT_END);
        when(toolEnd.getId()).thenReturn("e-te");
        when(toolEnd.getToolCallId()).thenReturn("call-9");
        when(toolEnd.getToolCallName()).thenReturn("request_file_change");
        when(toolEnd.getState()).thenReturn(ToolResultState.SUCCESS);
        when(harnessAgent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(Flux.just(toolEnd));

        StepVerifier.create(service.confirm(new DevAgentConfirmRequest("u1", "s1", true)))
                .expectNext(DevAgentEvent.session("s1"))
                .expectNext(DevAgentEvent.toolResultEnd(
                        "s1", "e-te", "call-9", "request_file_change", "SUCCESS"))
                .expectNext(DevAgentEvent.done("s1"))
                .verifyComplete();

        ArgumentCaptor<Msg> msgCaptor = ArgumentCaptor.forClass(Msg.class);
        verify(harnessAgent).streamEvents(msgCaptor.capture(), any(RuntimeContext.class));
        Msg resume = msgCaptor.getValue();
        assertThat(resume.getTextContent()).isEqualTo("approved");
        @SuppressWarnings("unchecked")
        List<ConfirmResult> results =
                (List<ConfirmResult>) resume.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).isConfirmed()).isTrue();
        assertThat(results.get(0).getToolCall().getId()).isEqualTo("call-9");
    }
```

将 `ask_mapsRequireUserConfirmAndStoresPending` 重命名为 `ask_mapsRequireUserConfirm`（只断言 SSE 事件）。所有 `new DevAgentService(...)` 改为三参数。

补 import：`MsgRole`、`ToolCallState`、`AgentState`、`AgentStateStore`、`Optional`。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -f demo2/pom.xml -Dtest=DevAgentServiceTest test`

Expected: FAIL（构造器签名不匹配）

- [ ] **Step 3: 实现 `DevAgentService` 改造**

1. 删除 `pendingConfirmations`；新增 `private final AgentStateStore agentStateStore;`
2. 构造器三参数注入 store
3. `confirm` 使用 `loadPendingToolCalls`；空则 ERROR「没有待确认的工具调用」
4. `REQUIRE_USER_CONFIRM`：删除 `pendingConfirmations.put(...)`
5. 新增：

```java
    private List<ToolUseBlock> loadPendingToolCalls(String userId, String sessionId) {
        return agentStateStore
                .get(userId, sessionId, "agent_state", AgentState.class)
                .map(this::findAskingToolCalls)
                .orElseGet(List::of);
    }

    private List<ToolUseBlock> findAskingToolCalls(AgentState state) {
        List<Msg> context = state.getContext();
        if (context == null || context.isEmpty()) {
            return List.of();
        }
        for (int i = context.size() - 1; i >= 0; i--) {
            Msg msg = context.get(i);
            if (msg.getRole() == MsgRole.ASSISTANT) {
                return msg.getContentBlocks(ToolUseBlock.class).stream()
                        .filter(block -> block.getState() == ToolCallState.ASKING)
                        .toList();
            }
        }
        return List.of();
    }
```

6. 删除仅给 Map 用的 `confirmationKey`；保留 `normalizeUserId`
7. 清理无用 import

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -f demo2/pom.xml -Dtest=DevAgentServiceTest,AgentStateStoreFactoryTest test`

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java
git commit -m "feat(demo2): restore HITL confirmations from AgentStateStore"
```

---

### Task 6: 手工验收清单（实现者执行）

**Files:** 无强制代码变更（可选：`demo2/README.md` AgentScope 小节补 Docker 启动两行）

- [ ] **Step 1: PG 模式**

```bash
docker compose -f demo2/docker/agentscope-postgres/docker-compose.yml up -d
```

启动应用后日志应含 `stateStore=postgres`。`ask` 后查表：

```sql
SELECT session_id, state_key, item_index, (length(state_data) > 0) AS has_state
FROM agentscope.agentscope_sessions;
```

Expected: 有对应行且 `has_state=true`

- [ ] **Step 2: 重启恢复** — 停应用再启，同 `userId`+`sessionId` 续问能接上上下文

- [ ] **Step 3: 用户隔离** — 不同 `userId`、相同 `sessionId` → 两行独立槽位

- [ ] **Step 4: HITL 跨重启** — 停在确认态重启后 `/agentscope/dev-agent/confirm` 仍可批准

- [ ] **Step 5: 降级**

```bash
docker compose -f demo2/docker/agentscope-postgres/docker-compose.yml down
```

再启应用：应能启动，日志含 `stateStore=memory`

- [ ] **Step 6: 若改了 README，单独 commit**

```bash
git add demo2/README.md
git commit -m "docs(demo2): note AgentScope PostgreSQL docker startup"
```

---

## Spec coverage（自检）

| Spec 要求 | 对应 Task |
|-----------|-----------|
| Docker PG 一键启动 | Task 1 |
| 独立 DataSource 配置 / 依赖 | Task 2–3 |
| `PostgresAgentStateStore` + `createIfNotExist` | Task 3–4 |
| PG 不可用降级 memory + WARN | Task 3 |
| HarnessAgent 接线 | Task 4 |
| 去掉 Map，confirm 读 ASKING | Task 5 |
| 单测 mock store | Task 5 |
| 手工跨重启 / 隔离 / 降级 | Task 6 |
| 不改 API/前端/MySQL | 全局约束 |
| 非 DistributedStore | Task 3 |

## Placeholder scan

无 TBD/TODO；关键代码与命令已给出。
