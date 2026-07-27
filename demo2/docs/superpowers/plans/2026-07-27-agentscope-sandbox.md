# AgentScope Docker Sandbox Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 AgentScope Dev Agent 上用 `sandbox.enabled` 开关接入 Docker Sandbox，使 RetryPolicy「测→读→改→复测」在容器内完成，默认关闭时保持现状。

**Architecture:** 单 `HarnessAgent`；`sandbox.enabled=false` 维持 `disableFilesystemTools` + 可选 `RemoteFilesystemSpec`；`true` 时改挂 `DockerFilesystemSpec(SESSION)` + `PathSafeAgentStateStore`，启用内置 `read_file`/`edit_file`/`execute` 并移除 `write_file`，自定义工具仍注册，靠 `AGENTS.md` 分流。镜像用 `demo2/docker/sandbox/docker-compose.yml` **build**，不 `up` 常驻沙箱。

**Tech Stack:** Java 21、Spring Boot 4.x、AgentScope Java 2.0.0（`DockerFilesystemSpec` / `LocalSnapshotSpec` / `WorkspaceSpec`）、Docker Compose、JUnit、AssertJ。

## Global Constraints

- 设计规范：`demo2/docs/superpowers/specs/2026-07-27-agentscope-sandbox-design.md`
- AgentScope 保持 `2.0.0`，**不新增** Maven 依赖
- **不改** `application-agentscope-prompts.yml` 的 `system-prompt`
- `sandbox.enabled` **默认 `false`**
- 沙箱开时：**不**挂 `RemoteFilesystemSpec`；用 `.stateStore(PathSafe(...))`，即使用户开了 `distributed.enabled`
- HITL 用的 `agentscopeAgentStateStore` Bean 与 Harness 的 stateStore **必须是同一 PathSafe 实例**（沙箱开时）
- Docker：**只 build 镜像**；compose / Dockerfile **必须**含用法注释；**不要**把 sandbox 做成常驻 `up -d` 服务
- 引导**仅**改 `workspace/AGENTS.md`
- 编译门禁：`mvn -f demo2/pom.xml -DskipTests compile`
- 单测门禁：`mvn -f demo2/pom.xml -Dtest=AgentscopeSandboxProjectAssetsTest,PathSafeAgentStateStoreTest,DevAgentPropertiesBindingTest,AgentScopeMiddlewareConfigTest test`

## File Map

**Create**

- `demo2/workspace/project/pom.xml`
- `demo2/workspace/project/src/main/java/com/example/retry/RetryPolicy.java`
- `demo2/workspace/project/src/test/java/com/example/retry/RetryPolicyTest.java`
- `demo2/docker/sandbox/Dockerfile`
- `demo2/docker/sandbox/python3-wrapper`
- `demo2/docker/sandbox/docker-compose.yml`
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/state/PathSafeAgentStateStore.java`
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/sandbox/AgentscopeSandboxProjectAssetsTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/state/PathSafeAgentStateStoreTest.java`

**Modify**

- `demo2/.gitignore`：放行 `workspace/project/**`；忽略 `.agentscope/sandbox-snapshots/`
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/DevAgentProperties.java`：嵌套 `Sandbox`
- `demo2/src/main/resources/application.properties`：默认 `sandbox.*`
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`：沙箱分支装配
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/DevAgentPropertiesBindingTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java`（若构造签名变更）
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/mcp/AgentscopeMcpClientRegistryTest.java`（若构造签名变更）
- `demo2/workspace/AGENTS.md`
- `demo2/README.md`

**Pinned Docker paths**

- Compose 文件：`demo2/docker/sandbox/docker-compose.yml`
- Build context：`demo2/`（compose 内写 `context: ../..`，相对 `demo2/docker/sandbox`）
- Dockerfile 相对 context：`docker/sandbox/Dockerfile`
- 镜像名：`agentscope-java-sandbox:17`
- **不用** `profiles`（与 postgres 一样靠注释约束；主推 `docker compose ... build`，勿 `up -d`）

---

### Task 1: RetryPolicy 样例项目 + gitignore + 资产测试

**Files:**
- Create: `demo2/workspace/project/pom.xml`
- Create: `demo2/workspace/project/src/main/java/com/example/retry/RetryPolicy.java`
- Create: `demo2/workspace/project/src/test/java/com/example/retry/RetryPolicyTest.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/agentscope/sandbox/AgentscopeSandboxProjectAssetsTest.java`
- Modify: `demo2/.gitignore`

**Interfaces:**
- Produces: 宿主样例路径 `workspace/project/**`（故意失败的测试契约）
- Consumes: 无

- [ ] **Step 1: 更新 `.gitignore`**

在 `workspace/**` 白名单段追加 project；并忽略沙箱快照：

```gitignore
!workspace/project/
!workspace/project/**

### AgentScope Sandbox snapshots ###
.agentscope/sandbox-snapshots/
```

- [ ] **Step 2: 写失败的资产测试**

```java
package com.jason.demo.demo2.agentscope.sandbox;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentscopeSandboxProjectAssetsTest {

    private static final Path MODULE = Path.of(".").toAbsolutePath().normalize();

    @Test
    void retryPolicySampleExistsWithIntentionalBug() throws Exception {
        Path pom = MODULE.resolve("workspace/project/pom.xml");
        Path policy = MODULE.resolve(
                "workspace/project/src/main/java/com/example/retry/RetryPolicy.java");
        Path test = MODULE.resolve(
                "workspace/project/src/test/java/com/example/retry/RetryPolicyTest.java");

        assertThat(pom).exists();
        assertThat(policy).exists();
        assertThat(test).exists();

        String src = Files.readString(policy);
        assertThat(src).contains("1L << attempt");
        assertThat(src).doesNotContain("1L << (attempt - 1)");

        String testSrc = Files.readString(test);
        assertThat(testSrc).contains("assertEquals(1000");
        assertThat(testSrc).contains("assertEquals(2000");
        assertThat(testSrc).contains("assertEquals(4000");
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `mvn -f demo2/pom.xml -Dtest=AgentscopeSandboxProjectAssetsTest test`  
Expected: FAIL（文件不存在）

- [ ] **Step 4: 创建样例项目**

`workspace/project/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>retry-policy-sample</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <properties>
        <maven.compiler.release>17</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <junit.version>5.11.4</junit.version>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.2</version>
            </plugin>
        </plugins>
    </build>
</project>
```

`RetryPolicy.java`（**故意错误**）：

```java
package com.example.retry;

public final class RetryPolicy {

    private final long baseDelayMillis;
    private final long maxDelayMillis;

    public RetryPolicy(long baseDelayMillis, long maxDelayMillis) {
        this.baseDelayMillis = baseDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
    }

    public long delayMillis(int attempt) {
        long delay = baseDelayMillis * (1L << attempt);
        return Math.min(delay, maxDelayMillis);
    }
}
```

`RetryPolicyTest.java`：

```java
package com.example.retry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetryPolicyTest {

    @Test
    void exponentialBackoffStartsAtBaseDelay() {
        RetryPolicy policy = new RetryPolicy(1000, 30_000);
        assertEquals(1000, policy.delayMillis(1));
        assertEquals(2000, policy.delayMillis(2));
        assertEquals(4000, policy.delayMillis(3));
    }
}
```

- [ ] **Step 5: 本地验证样例测试失败（契约）**

Run: `mvn -f demo2/workspace/project/pom.xml test`  
Expected: FAIL，失败信息含 `expected: <1000> but was: <2000>`（或等价 AssertJ/JUnit 文案）

- [ ] **Step 6: 跑资产测试通过**

Run: `mvn -f demo2/pom.xml -Dtest=AgentscopeSandboxProjectAssetsTest test`  
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add demo2/.gitignore demo2/workspace/project demo2/src/test/java/com/jason/demo/demo2/agentscope/sandbox/AgentscopeSandboxProjectAssetsTest.java
git commit -m "feat(demo2): add RetryPolicy sandbox sample project"
```

---

### Task 2: Docker 镜像（Dockerfile + wrapper + compose 注释）

**Files:**
- Create: `demo2/docker/sandbox/python3-wrapper`
- Create: `demo2/docker/sandbox/Dockerfile`
- Create: `demo2/docker/sandbox/docker-compose.yml`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/sandbox/AgentscopeSandboxProjectAssetsTest.java`（追加 docker 资产断言）

**Interfaces:**
- Produces: 可 `docker compose ... build` 的镜像 `agentscope-java-sandbox:17`
- Consumes: Task 1 的 `workspace/project/**`

- [ ] **Step 1: 扩展资产测试（docker 文件存在 + compose 含用法关键词）**

在 `AgentscopeSandboxProjectAssetsTest` 增加：

```java
@Test
void sandboxDockerAssetsExistWithUsageComments() throws Exception {
    Path dockerfile = MODULE.resolve("docker/sandbox/Dockerfile");
    Path wrapper = MODULE.resolve("docker/sandbox/python3-wrapper");
    Path compose = MODULE.resolve("docker/sandbox/docker-compose.yml");

    assertThat(dockerfile).exists();
    assertThat(wrapper).exists();
    assertThat(compose).exists();

    String composeText = Files.readString(compose);
    assertThat(composeText).contains("agentscope-java-sandbox:17");
    assertThat(composeText).contains("docker compose -f demo2/docker/sandbox/docker-compose.yml build");
    assertThat(composeText).contains("不要对本文件 up -d");
    assertThat(composeText).contains("sandbox.enabled=true");
    assertThat(composeText).contains("agentscope-postgres");

    String dockerfileText = Files.readString(dockerfile);
    assertThat(dockerfileText).contains("python3-wrapper");
    assertThat(dockerfileText).contains("maven.test.failure.ignore");
}
```

- [ ] **Step 2: 运行确认新断言失败**

Run: `mvn -f demo2/pom.xml -Dtest=AgentscopeSandboxProjectAssetsTest#sandboxDockerAssetsExistWithUsageComments test`  
Expected: FAIL

- [ ] **Step 3: 写 `python3-wrapper`**

`demo2/docker/sandbox/python3-wrapper`（Unix LF，可执行意图；Windows 上由 Docker 构建阶段 `chmod`）：

```sh
#!/bin/sh
# AgentScope edit_file 兼容：把 -c 代码里的字面量 \n 转成真正换行后再交给系统 python3。

if [ "$1" = "-c" ] && [ "$#" -ge 2 ]; then
    code=$(printf '%b' "$2")
    shift 2
    exec /usr/bin/python3 -c "$code" "$@"
fi

exec /usr/bin/python3 "$@"
```

- [ ] **Step 4: 写 `Dockerfile`（含顶部注释）**

```dockerfile
# AgentScope Dev Agent 沙箱镜像：Java17 + Maven + Python（供 edit_file）
# 构建请用：docker compose -f demo2/docker/sandbox/docker-compose.yml build
# 为何预跑 mvn test：运行时 network=none，构建期先把依赖拉进本地仓库缓存层。
# maven.test.failure.ignore=true：样例 RetryPolicy 故意失败，只为下载依赖。
# python3-wrapper：兼容 AgentScope 2.0.0 edit_file 的 \n 字面量。

FROM maven:3.9.11-eclipse-temurin-17

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 \
    && rm -rf /var/lib/apt/lists/*

COPY docker/sandbox/python3-wrapper /usr/local/bin/python3
RUN chmod +x /usr/local/bin/python3

WORKDIR /opt/dependency-cache

COPY workspace/project/pom.xml pom.xml
COPY workspace/project/src src

RUN mvn -q -Dmaven.test.failure.ignore=true test

RUN rm -rf /opt/dependency-cache

WORKDIR /workspace
```

- [ ] **Step 5: 写 `docker-compose.yml`（必须含完整用法注释）**

```yaml
# =============================================================================
# AgentScope Dev Agent — Docker Sandbox 镜像（非常驻服务）
# =============================================================================
# 用途：
#   只负责构建镜像 agentscope-java-sandbox:17，供 Harness 在工具调用时
#   按 sessionId 拉起【临时】容器。这和 agentscope-postgres 的「常驻 up -d」不同。
#
# 怎么用（在仓库根目录 spring-ai-demo 执行）：
#   1) 构建镜像（首次或 Dockerfile/样例变更后）：
#        docker compose -f demo2/docker/sandbox/docker-compose.yml build
#   2) 确认镜像：
#        docker images agentscope-java-sandbox:17
#   3)（可选）会话 PG，与沙箱无关、可并存：
#        docker compose -f demo2/docker/agentscope-postgres/docker-compose.yml up -d
#   4) 打开应用开关后启动 demo2：
#        app.agentscope.dev-agent.sandbox.enabled=true
#   5) 演示：同一 userId+sessionId，ask → confirm（execute / edit_file / execute）
#
# 不要对本文件 up -d 当「沙箱服务」用；容器生命周期由 AgentScope Harness 管理。
# 等价备选（一般不必）：
#   docker build -f demo2/docker/sandbox/Dockerfile -t agentscope-java-sandbox:17 demo2
# =============================================================================
services:
  agentscope-sandbox-image:
    image: agentscope-java-sandbox:17
    build:
      context: ../..
      dockerfile: docker/sandbox/Dockerfile
```

- [ ] **Step 6: 构建镜像（本机需 Docker）**

Run（仓库根目录）：

```bash
docker compose -f demo2/docker/sandbox/docker-compose.yml build
```

Expected: 成功打出 `agentscope-java-sandbox:17`（构建期测试失败被 ignore）

- [ ] **Step 7: 资产测试通过**

Run: `mvn -f demo2/pom.xml -Dtest=AgentscopeSandboxProjectAssetsTest test`  
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add demo2/docker/sandbox demo2/src/test/java/com/jason/demo/demo2/agentscope/sandbox/AgentscopeSandboxProjectAssetsTest.java
git commit -m "feat(demo2): add AgentScope sandbox Docker image build"
```

---

### Task 3: PathSafeAgentStateStore

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/state/PathSafeAgentStateStore.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/agentscope/state/PathSafeAgentStateStoreTest.java`

**Interfaces:**
- Produces: `PathSafeAgentStateStore(AgentStateStore delegate)` — 对 **sessionId**（及带 key 的重载中的 key 若含 `/` 也编码）做 URL Base64 编解码
- Consumes: `io.agentscope.core.state.AgentStateStore`

说明：Sandbox 内部状态 ID 形如 `sandbox/session/<id>`，落在 `AgentStateStore` 的 sessionId 参数上；PG store 不接受 `/`。

- [ ] **Step 1: 写失败单测**

```java
package com.jason.demo.demo2.agentscope.state;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.State;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PathSafeAgentStateStoreTest {

    public static final class DummyState implements State {
        private String value;

        public DummyState() {
        }

        public DummyState(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    @Test
    void roundTripsSessionIdContainingSlash() {
        AgentStateStore memory = new InMemoryAgentStateStore();
        PathSafeAgentStateStore store = new PathSafeAgentStateStore(memory);

        String userId = "u1";
        String sessionId = "sandbox/session/s-015";
        String key = "agent";
        store.save(userId, sessionId, key, new DummyState("ok"));

        Optional<DummyState> loaded = store.get(userId, sessionId, key, DummyState.class);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getValue()).isEqualTo("ok");

        assertThat(store.exists(userId, sessionId)).isTrue();
        Set<String> ids = store.listSessionIds(userId);
        assertThat(ids).contains(sessionId);

        store.delete(userId, sessionId);
        assertThat(store.exists(userId, sessionId)).isFalse();
    }

    @Test
    void encodeDecodeAreReversible() {
        String raw = "sandbox/session/abc";
        String encoded = PathSafeAgentStateStore.encode(raw);
        assertThat(encoded).doesNotContain("/");
        assertThat(PathSafeAgentStateStore.decode(encoded)).isEqualTo(raw);
    }
}
```

若 `State` 接口与上列 Dummy 不兼容，按 `agentscope-core` 实际 `State` 定义改成最小可序列化实现（查 `javap io.agentscope.core.state.State`）；原则不变：含 `/` 的 sessionId 能 save/get/list/delete。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -f demo2/pom.xml -Dtest=PathSafeAgentStateStoreTest test`  
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 PathSafeAgentStateStore**

```java
package com.jason.demo.demo2.agentscope.state;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 将含 {@code /} 的 sessionId（如 {@code sandbox/session/...}）编码后再交给底层 store。
 */
public final class PathSafeAgentStateStore implements AgentStateStore {

    private final AgentStateStore delegate;

    public PathSafeAgentStateStore(AgentStateStore delegate) {
        this.delegate = delegate;
    }

    static String encode(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static String decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return encoded;
        }
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    @Override
    public void save(String userId, String sessionId, String key, State state) {
        delegate.save(userId, encode(sessionId), key, state);
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> states) {
        delegate.save(userId, encode(sessionId), key, states);
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        return delegate.get(userId, encode(sessionId), key, type);
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> type) {
        return delegate.getList(userId, encode(sessionId), key, type);
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return delegate.exists(userId, encode(sessionId));
    }

    @Override
    public void delete(String userId, String sessionId) {
        delegate.delete(userId, encode(sessionId));
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        delegate.delete(userId, encode(sessionId), key);
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        return delegate.listSessionIds(userId).stream()
                .map(PathSafeAgentStateStore::decode)
                .collect(Collectors.toSet());
    }

    @Override
    public void close() {
        delegate.close();
    }
}
```

- [ ] **Step 4: 测试通过**

Run: `mvn -f demo2/pom.xml -Dtest=PathSafeAgentStateStoreTest test`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/state/PathSafeAgentStateStore.java demo2/src/test/java/com/jason/demo/demo2/agentscope/state/PathSafeAgentStateStoreTest.java
git commit -m "feat(demo2): add PathSafeAgentStateStore for sandbox session ids"
```

---

### Task 4: DevAgentProperties.Sandbox + 默认配置绑定

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/DevAgentProperties.java`
- Modify: `demo2/src/main/resources/application.properties`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/DevAgentPropertiesBindingTest.java`
- Modify: 所有 `new DevAgentProperties(...)` 测试调用点（`AgentScopeMiddlewareConfigTest`、`DevAgentServiceTest`、`AgentscopeMcpClientRegistryTest`）——构造器增加 `Sandbox` 参数，或靠 compact 对 `null` 赋默认

**Interfaces:**
- Produces: `DevAgentProperties.Sandbox` record：
  - `boolean enabled`（默认 false）
  - `String image`
  - `String network`
  - `String workspaceRoot`（容器内，默认 `/workspace`）
  - `String snapshotRoot`
  - `long memorySizeBytes`
  - `long cpuCount`
- Produces: `sandbox()` 访问器；`enabled=true` 时校验非 blank 与 `memorySizeBytes>0`、`cpuCount>0`

- [ ] **Step 1: 写失败绑定测试**

在 `DevAgentPropertiesBindingTest` 增加：

```java
@Test
void sandboxDefaultsToDisabledWhenAbsent() {
    runner.run(ctx -> {
        DevAgentProperties.Sandbox sandbox = ctx.getBean(DevAgentProperties.class).sandbox();
        assertThat(sandbox.enabled()).isFalse();
        assertThat(sandbox.image()).isEqualTo("agentscope-java-sandbox:17");
        assertThat(sandbox.network()).isEqualTo("none");
        assertThat(sandbox.workspaceRoot()).isEqualTo("/workspace");
        assertThat(sandbox.snapshotRoot()).isEqualTo(".agentscope/sandbox-snapshots");
        assertThat(sandbox.memorySizeBytes()).isEqualTo(536870912L);
        assertThat(sandbox.cpuCount()).isEqualTo(1L);
    });
}

@Test
void bindsSandboxSettings() {
    runner.withPropertyValues(
            "app.agentscope.dev-agent.sandbox.enabled=true",
            "app.agentscope.dev-agent.sandbox.image=custom-sandbox:1",
            "app.agentscope.dev-agent.sandbox.network=bridge",
            "app.agentscope.dev-agent.sandbox.workspace-root=/work",
            "app.agentscope.dev-agent.sandbox.snapshot-root=.agentscope/snaps",
            "app.agentscope.dev-agent.sandbox.memory-size-bytes=268435456",
            "app.agentscope.dev-agent.sandbox.cpu-count=2"
    ).run(ctx -> {
        DevAgentProperties.Sandbox sandbox = ctx.getBean(DevAgentProperties.class).sandbox();
        assertThat(sandbox.enabled()).isTrue();
        assertThat(sandbox.image()).isEqualTo("custom-sandbox:1");
        assertThat(sandbox.network()).isEqualTo("bridge");
        assertThat(sandbox.workspaceRoot()).isEqualTo("/work");
        assertThat(sandbox.snapshotRoot()).isEqualTo(".agentscope/snaps");
        assertThat(sandbox.memorySizeBytes()).isEqualTo(268435456L);
        assertThat(sandbox.cpuCount()).isEqualTo(2L);
    });
}

@Test
void sandboxEnabledWithBlankImageFails() {
    runner.withPropertyValues(
            "app.agentscope.dev-agent.sandbox.enabled=true",
            "app.agentscope.dev-agent.sandbox.image= "
    ).run(ctx -> assertThat(ctx).hasFailed());
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -f demo2/pom.xml -Dtest=DevAgentPropertiesBindingTest test`  
Expected: 新用例 FAIL 或编译失败（尚无 `sandbox()`）

- [ ] **Step 3: 扩展 DevAgentProperties**

在 record 组件列表末尾增加 `@Valid Sandbox sandbox`。

Compact 构造器：

```java
if (sandbox == null) {
    sandbox = Sandbox.disabledDefaults();
}
```

新增：

```java
public record Sandbox(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("agentscope-java-sandbox:17") String image,
        @DefaultValue("none") String network,
        @DefaultValue("/workspace") String workspaceRoot,
        @DefaultValue(".agentscope/sandbox-snapshots") String snapshotRoot,
        @DefaultValue("536870912") long memorySizeBytes,
        @DefaultValue("1") long cpuCount) {

    public Sandbox {
        if (enabled) {
            if (image == null || image.isBlank()) {
                throw new IllegalArgumentException("sandbox.image must not be blank when enabled");
            }
            if (network == null || network.isBlank()) {
                throw new IllegalArgumentException("sandbox.network must not be blank when enabled");
            }
            if (workspaceRoot == null || workspaceRoot.isBlank()) {
                throw new IllegalArgumentException("sandbox.workspace-root must not be blank when enabled");
            }
            if (snapshotRoot == null || snapshotRoot.isBlank()) {
                throw new IllegalArgumentException("sandbox.snapshot-root must not be blank when enabled");
            }
            if (memorySizeBytes <= 0) {
                throw new IllegalArgumentException("sandbox.memory-size-bytes must be > 0 when enabled");
            }
            if (cpuCount <= 0) {
                throw new IllegalArgumentException("sandbox.cpu-count must be > 0 when enabled");
            }
        }
    }

    static Sandbox disabledDefaults() {
        return new Sandbox(
                false,
                "agentscope-java-sandbox:17",
                "none",
                "/workspace",
                ".agentscope/sandbox-snapshots",
                536870912L,
                1L);
    }
}
```

注意：若 `@DefaultValue` 对 `long` 的字符串形式在本项目 Spring Boot 版本不生效，改为 compact 里对 `0`/缺省做回填，并保证绑定测试通过。

- [ ] **Step 4: application.properties 增加默认项**

```properties
# AgentScope Docker Sandbox（默认关；开启前先 build 镜像，见 docker/sandbox/docker-compose.yml）
app.agentscope.dev-agent.sandbox.enabled=false
app.agentscope.dev-agent.sandbox.image=agentscope-java-sandbox:17
app.agentscope.dev-agent.sandbox.network=none
app.agentscope.dev-agent.sandbox.workspace-root=/workspace
app.agentscope.dev-agent.sandbox.snapshot-root=.agentscope/sandbox-snapshots
app.agentscope.dev-agent.sandbox.memory-size-bytes=536870912
app.agentscope.dev-agent.sandbox.cpu-count=1
```

- [ ] **Step 5: 修复所有 `new DevAgentProperties(...)`**

对测试中的构造调用：要么追加 `null`（走 compact 默认），要么追加 `Sandbox.disabledDefaults()`。保证编译通过。

- [ ] **Step 6: 测试通过**

Run: `mvn -f demo2/pom.xml -Dtest=DevAgentPropertiesBindingTest,AgentScopeMiddlewareConfigTest,DevAgentServiceTest,AgentscopeMcpClientRegistryTest test`  
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/DevAgentProperties.java demo2/src/main/resources/application.properties demo2/src/test/java/com/jason/demo/demo2/agentscope/config/DevAgentPropertiesBindingTest.java demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java demo2/src/test/java/com/jason/demo/demo2/agentscope/mcp/AgentscopeMcpClientRegistryTest.java
git commit -m "feat(demo2): bind AgentScope sandbox configuration properties"
```

---

### Task 5: AgentScopeConfig 沙箱装配分支

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java`

**Interfaces:**
- Consumes: `DevAgentProperties.Sandbox`、`PathSafeAgentStateStore`、`DockerFilesystemSpec`、`LocalSnapshotSpec`、`WorkspaceSpec`
- Produces: 沙箱开时 Harness 具备 `read_file`/`edit_file`/`execute`，无 `write_file`；`read_file` ALLOW；stateStore 为 PathSafe；filesystem 为 Docker SESSION
- Produces: `agentscopeAgentStateStore` Bean 在沙箱开时返回 PathSafe 包装（与 Harness 共用同一逻辑）

- [ ] **Step 1: 写失败/待扩展的配置测试**

在 `AgentScopeMiddlewareConfigTest` 增加辅助构造 `propertiesWithSandbox(boolean enabled)`，并增加：

```java
@Test
void agentscopeDevAgent_sandboxDisabled_keepsFilesystemToolsOff() throws Exception {
    try (HarnessAgent agent = buildAgent(propertiesWithSandbox(false))) {
        assertThat(agent.getToolkit().getToolNames())
                .doesNotContain("read_file", "edit_file", "execute", "write_file");
    }
}

@Test
void agentscopeDevAgent_sandboxEnabled_exposesSandboxToolsWithoutWriteFile() throws Exception {
    try (HarnessAgent agent = buildAgent(propertiesWithSandbox(true))) {
        assertThat(agent.getToolkit().getToolNames())
                .contains("read_file", "edit_file", "execute")
                .doesNotContain("write_file");
    }
}
```

`propertiesWithSandbox`：`DevAgentProperties` 全字段 + `new Sandbox(enabled, "agentscope-java-sandbox:17", "none", "/workspace", tempDir.resolve("snaps").toString(), 536870912L, 1L)`。  
`workspaceRoot` / `projectRoot` 仍指向 `tempDir`，并在 tempDir 下放最小 `AGENTS.md`（若 build 需要）。

- [ ] **Step 2: 运行 — sandboxEnabled 用例应失败**

Run: `mvn -f demo2/pom.xml -Dtest=AgentScopeMiddlewareConfigTest test`  
Expected: `sandboxEnabled_exposesSandboxToolsWithoutWriteFile` FAIL（仍 disable FS）

- [ ] **Step 3: 实现装配**

1. `agentscopeAgentStateStore` 改为：

```java
@Bean
AgentStateStore agentscopeAgentStateStore(
        AgentscopeDistributedBackend backend,
        DevAgentProperties properties) {
    AgentStateStore base = backend.stateStore();
    if (properties.sandbox().enabled()) {
        return new PathSafeAgentStateStore(base);
    }
    return base;
}
```

2. `agentscopeDevAgent` 注入该 `AgentStateStore agentscopeAgentStateStore`。

3. 构建逻辑伪代码：

```java
DevAgentProperties.Sandbox sandbox = properties.sandbox();
HarnessAgent.Builder builder = HarnessAgent.builder()
        // ... 现有 name/sysPrompt/model/toolkit/workspace/permission/middleware/compaction/...
        ;
if (sandbox.enabled()) {
    // 不要调用 disableFilesystemTools / disableShellTool
    builder.stateStore(agentscopeAgentStateStore)
            .filesystem(dockerFilesystemSpec(properties));
} else {
    builder.disableFilesystemTools().disableShellTool();
    if (agentscopeDistributedBackend instanceof AgentscopeDistributedBackend.Remote remote) {
        builder.distributedStore(remote.distributedStore())
                .filesystem(new RemoteFilesystemSpec().isolationScope(IsolationScope.USER));
    } else {
        builder.stateStore(agentscopeAgentStateStore);
    }
}
// memory 分支同现有
HarnessAgent agent = builder.build();
agent.getToolkit().removeTool("wait_async_results");
if (sandbox.enabled()) {
    agent.getToolkit().removeTool("write_file");
}
return agent;
```

4. `dockerFilesystemSpec`：

```java
static /* or private */ DockerFilesystemSpec dockerFilesystemSpec(DevAgentProperties properties) {
    DevAgentProperties.Sandbox config = properties.sandbox();
    WorkspaceSpec workspace = new WorkspaceSpec();
    workspace.setRoot(config.workspaceRoot());

    Path snapshotPath = Path.of(properties.projectRoot()).resolve(config.snapshotRoot()).normalize();

    DockerFilesystemSpec filesystem = new DockerFilesystemSpec()
            .image(config.image())
            .network(config.network())
            .workspaceRoot(config.workspaceRoot())
            .memorySizeBytes(config.memorySizeBytes())
            .cpuCount(config.cpuCount())
            .snapshotSpec(new LocalSnapshotSpec(snapshotPath))
            .workspaceSpec(workspace);

    filesystem.isolationScope(IsolationScope.SESSION);
    filesystem.workspaceProjectionRoots(List.of(
            "AGENTS.md",
            "skills",
            "subagents",
            "knowledge",
            ".skills-cache",
            "project"));
    return filesystem;
}
```

Import：

- `io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec`
- `io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec`
- `io.agentscope.harness.agent.sandbox.WorkspaceSpec`
- `com.jason.demo.demo2.agentscope.state.PathSafeAgentStateStore`

5. Permission：沙箱开时对 `read_file` 增加 ALLOW（`edit_file`/`execute` 保持默认 ASK，不要加 ALLOW）。

```java
if (properties.sandbox().enabled()) {
    builder.addAllowRule("read_file", allowRule("read_file"));
}
```

- [ ] **Step 4: 测试通过**

Run: `mvn -f demo2/pom.xml -Dtest=AgentScopeMiddlewareConfigTest,PathSafeAgentStateStoreTest,DevAgentPropertiesBindingTest test`  
Expected: PASS

- [ ] **Step 5: 全量相关编译**

Run: `mvn -f demo2/pom.xml -DskipTests compile`  
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java
git commit -m "feat(demo2): wire DockerFilesystemSpec when sandbox enabled"
```

---

### Task 6: AGENTS.md + README 用法文档

**Files:**
- Modify: `demo2/workspace/AGENTS.md`
- Modify: `demo2/README.md`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/sandbox/AgentscopeSandboxProjectAssetsTest.java`（断言 AGENTS.md 含沙箱关键词）

**Interfaces:**
- Produces: 沙箱修 bug 路由文案；README 构建/开关/互斥/curl 说明

- [ ] **Step 1: 扩展资产测试**

```java
@Test
void agentsMdContainsSandboxRouting() throws Exception {
    String agents = Files.readString(MODULE.resolve("workspace/AGENTS.md"));
    assertThat(agents).contains("RetryPolicy");
    assertThat(agents).contains("read_file");
    assertThat(agents).contains("edit_file");
    assertThat(agents).contains("execute");
    assertThat(agents).contains("/workspace/project");
}
```

- [ ] **Step 2: 更新 AGENTS.md**

在「工作方式」中，把「当前没有…Shell 工具」改为区分场景，并新增一节，例如：

```markdown
## 沙箱修复（Docker Sandbox）

- 仅当用户明确要求在沙箱中运行测试、修复 `RetryPolicy`、或执行 `mvn test` 修复代码时启用本流程。
- 只使用内置工具：`read_file`、`edit_file`、`execute`；`working_directory` 使用 `project`。
- 推荐顺序：先 `execute` 运行 `mvn -q test` → 失败则 `read_file` 读源码与测试 → `edit_file` 修改 → 再 `execute` 复测。
- 修改与测试都发生在容器内 `/workspace/project`；不要修改或声称修改了宿主机源码。
- `edit_file` / `execute` 需要 Permission 确认；确认前不要声称已经改文件或测试已通过。
- 不要用 `request_file_change` 改 `project/`；`request_file_change` 仅用于 `notes/`。
- 代码审查仍走上方「代码审查」Skill / SubAgent 规则；MCP 样例仍在 `mcp-files`，与 `project` 区分。
```

「工作方式」改为：非沙箱修复场景下仍不要声称查了日志/数据库或在宿主执行了 Shell；沙箱场景按上一节使用 `execute`。

- [ ] **Step 3: 更新 README**

在 AgentScope Dev Agent 相关章节增加小节「Docker Sandbox」，至少包含：

1. 与 postgres compose 对照表（常驻 vs build 镜像）
2. 构建命令：`docker compose -f demo2/docker/sandbox/docker-compose.yml build`
3. 开关：`app.agentscope.dev-agent.sandbox.enabled=true`
4. 与 `distributed`：沙箱开时不用 RemoteFilesystem；会话仍可用 PG（PathSafe）
5. curl 演示（ask + 三次 confirm 的同 session 说明）
6. 成功标准：宿主 `workspace/project` 的 RetryPolicy 仍为错误实现

curl 示例：

```bash
curl -sN -X POST "http://localhost:8080/agentscope/dev-agent/ask" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"sandbox-user-015\",\"sessionId\":\"sandbox-session-015\",\"message\":\"请在沙箱中运行测试，修复 RetryPolicy 首次重试延迟翻倍的问题，并重新运行测试。\"}"
```

confirm（同一 userId/sessionId，`approved:true`）重复至链路结束。以实际 Controller 路径为准（现有为 `/agentscope/dev-agent/...`）。

- [ ] **Step 4: 测试通过**

Run: `mvn -f demo2/pom.xml -Dtest=AgentscopeSandboxProjectAssetsTest test`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/workspace/AGENTS.md demo2/README.md demo2/src/test/java/com/jason/demo/demo2/agentscope/sandbox/AgentscopeSandboxProjectAssetsTest.java
git commit -m "docs(demo2): document AgentScope sandbox usage and AGENTS routing"
```

---

### Task 7: 回归门禁 + 手工验收清单

**Files:**
- 无必须代码改动（发现问题则回修对应 Task）

- [ ] **Step 1: 跑计划门禁测试**

```bash
mvn -f demo2/pom.xml -Dtest=AgentscopeSandboxProjectAssetsTest,PathSafeAgentStateStoreTest,DevAgentPropertiesBindingTest,AgentScopeMiddlewareConfigTest test
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 手工验收（sandbox on）**

1. `docker compose -f demo2/docker/sandbox/docker-compose.yml build`
2. 设置 `app.agentscope.dev-agent.sandbox.enabled=true`，启动应用
3. ask 修 RetryPolicy → 三次 confirm → 测试通过
4. 确认宿主 `workspace/project/.../RetryPolicy.java` **仍是** `1L << attempt`
5. 换新 `sessionId` 可再跑失败→修复
6. 设回 `sandbox.enabled=false`，确认审查 / notes / MCP 仍可用

- [ ] **Step 3: 若有修复则提交；否则无需空提交**

---

## Spec coverage checklist（自检）

| Spec 项 | Task |
|---------|------|
| `sandbox.enabled` 默认 false | Task 4 |
| DockerFilesystemSpec + SESSION + Snapshot | Task 5 |
| PathSafeAgentStateStore | Task 3、5 |
| RetryPolicy 样例 | Task 1 |
| compose build + 注释用法 | Task 2、6 |
| AGENTS.md 引导，不改 system-prompt | Task 6 |
| 工具并存 + remove write_file | Task 5 |
| 与 RemoteFilesystem 互斥 | Task 5 |
| HITL 同 store | Task 5（Bean + Harness 共用 PathSafe） |
| README / 手工三次 confirm | Task 6、7 |
| .gitignore project + snapshots | Task 1 |

## Placeholder / 一致性钉死项

- 镜像名全程：`agentscope-java-sandbox:17`
- Build context：`demo2/`（compose `context: ../..`）
- API：`DockerFilesystemSpec.memorySizeBytes(Long)` / `cpuCount(Long)`
- Controller 路径：`/agentscope/dev-agent/ask` 与 `/confirm`（以代码为准，勿写成文章里的 `/dev-agent/...`）
