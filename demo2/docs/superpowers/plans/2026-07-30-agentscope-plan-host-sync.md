# AgentScope Plan Host Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `plan_write` 成功后立刻把沙箱内 `plans/PLAN.md` 覆盖同步到宿主 `workspace/plans/PLAN.md`，便于 `plan_exit` HITL 前用编辑器查看方案。

**Architecture:** 用 `ActiveSandboxRegistry` 在 `NewlineFlatteningDockerSandboxClient` 的 create/resume/delete 上跟踪活跃 `Sandbox`；`DevAgentService` 在 `TOOL_RESULT_END`（`plan_write` + `SUCCESS`）钩子调用 `PlanHostSyncService`；后者经 `LiveSandboxPlanReader` 对活跃沙箱 `exec("cat plans/PLAN.md")` 读出内容并原子写入宿主。失败只 WARN，不改 SSE 协议。

**Tech Stack:** Java 21、Spring Boot 4.x、AgentScope Java 2.0.0（`Sandbox` / `ExecResult` / `ToolResultEndEvent` / `ToolResultState`）、JUnit 5、Mockito、AssertJ。

**设计规范:** [docs/superpowers/specs/2026-07-30-agentscope-plan-host-sync-design.md](../specs/2026-07-30-agentscope-plan-host-sync-design.md)

## Global Constraints

- AgentScope 版本保持 `2.0.0`，**不新增** Maven 依赖
- **不新建** HTTP 端点；不改 ask/confirm 协议与 Diff HITL
- 固定覆盖宿主 `{projectRoot}/{workspaceRoot}/plans/PLAN.md`
- 读源必须是 **live 沙箱**（`ActiveSandboxRegistry` + `Sandbox.exec`）；**禁止**仅依赖回合结束 snapshot tar
- 同步失败：WARN、不抛、不向 SSE 发 error
- 沙箱关：钩子 / sync 均为 no-op
- 编译门禁：在 `demo2` 目录 `.\mvnw.cmd -DskipTests compile`
- 单测门禁示例：`.\mvnw.cmd "-Dtest=ActiveSandboxRegistryTest,PlanHostSyncServiceTest,DevAgentServicePlanHostSyncTest" test`

---

## File Map

**Create**

- `demo2/src/main/java/com/jason/demo/demo2/agentscope/sandbox/ActiveSandboxRegistry.java`
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/plan/SandboxPlanReader.java`
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/plan/LiveSandboxPlanReader.java`
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/plan/PlanHostSyncService.java`
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/sandbox/ActiveSandboxRegistryTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/plan/PlanHostSyncServiceTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServicePlanHostSyncTest.java`

**Modify**

- `demo2/src/main/java/com/jason/demo/demo2/agentscope/sandbox/NewlineFlatteningDockerSandboxClient.java`：create/resume/delete 登记/注销 registry
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`：把 `ActiveSandboxRegistry` Bean 传入 `dockerFilesystemSpec` / client
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java`：注入 `PlanHostSyncService`；`mapAgentEvents` 增加 `userId` 与钩子
- `demo2/README.md`：Plan Mode 段补充宿主 `PLAN.md` 同步说明与成功标准

**不改**

- `DevAgentController` / Diff 回写范围 / 前端 / `plan_write` 工具本身

---

### Task 1: ActiveSandboxRegistry + Client 接线

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/sandbox/ActiveSandboxRegistry.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/agentscope/sandbox/ActiveSandboxRegistryTest.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/sandbox/NewlineFlatteningDockerSandboxClient.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`

**Interfaces:**
- Produces:
  - `ActiveSandboxRegistry#register(Sandbox)` / `#unregister(Sandbox)` / `#findByAppSessionId(String) -> Optional<Sandbox>`
  - `NewlineFlatteningDockerSandboxClient(ActiveSandboxRegistry)`（保留无参构造：内部 `new ActiveSandboxRegistry()` 仅供旧调用；生产路径走带 registry 构造）
- Consumes: 无

- [ ] **Step 1: 写失败的 Registry 单测**

```java
package com.jason.demo.demo2.agentscope.sandbox;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActiveSandboxRegistryTest {

    @Test
    void findsByExactAndPrefixedSessionId() {
        ActiveSandboxRegistry registry = new ActiveSandboxRegistry();
        Sandbox sandbox = mock(Sandbox.class);
        SandboxState state = mock(SandboxState.class);
        when(sandbox.getState()).thenReturn(state);
        when(state.getSessionId()).thenReturn("sandbox/session/plan-session-017");

        registry.register(sandbox);

        assertThat(registry.findByAppSessionId("plan-session-017")).contains(sandbox);
        assertThat(registry.findByAppSessionId("sandbox/session/plan-session-017"))
                .contains(sandbox);
        assertThat(registry.findByAppSessionId("other")).isEmpty();

        registry.unregister(sandbox);
        assertThat(registry.findByAppSessionId("plan-session-017")).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测确认失败**

Run: `.\mvnw.cmd "-Dtest=ActiveSandboxRegistryTest" test`（在 `demo2`）  
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 ActiveSandboxRegistry**

```java
package com.jason.demo.demo2.agentscope.sandbox;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 跟踪 create/resume 后尚未 delete 的活跃沙箱，供 plan 宿主同步 live 读取。 */
public final class ActiveSandboxRegistry {

    private final ConcurrentHashMap<Sandbox, String> sessionIdsBySandbox = new ConcurrentHashMap<>();

    public void register(Sandbox sandbox) {
        if (sandbox == null) {
            return;
        }
        SandboxState state = sandbox.getState();
        String sessionId = state == null ? null : state.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        sessionIdsBySandbox.put(sandbox, sessionId);
    }

    public void unregister(Sandbox sandbox) {
        if (sandbox != null) {
            sessionIdsBySandbox.remove(sandbox);
        }
    }

    public Optional<Sandbox> findByAppSessionId(String appSessionId) {
        if (appSessionId == null || appSessionId.isBlank()) {
            return Optional.empty();
        }
        for (var entry : sessionIdsBySandbox.entrySet()) {
            String sid = entry.getValue();
            if (appSessionId.equals(sid)
                    || sid.endsWith("/" + appSessionId)
                    || ("sandbox/session/" + appSessionId).equals(sid)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 4: 接线 Client + Config**

`NewlineFlatteningDockerSandboxClient`：

```java
private final DockerSandboxClient delegate;
private final ActiveSandboxRegistry registry;

public NewlineFlatteningDockerSandboxClient() {
    this(new DockerSandboxClient(), new ActiveSandboxRegistry());
}

NewlineFlatteningDockerSandboxClient(DockerSandboxClient delegate) {
    this(delegate, new ActiveSandboxRegistry());
}

public NewlineFlatteningDockerSandboxClient(
        DockerSandboxClient delegate, ActiveSandboxRegistry registry) {
    this.delegate = delegate;
    this.registry = registry == null ? new ActiveSandboxRegistry() : registry;
}

public NewlineFlatteningDockerSandboxClient(ActiveSandboxRegistry registry) {
    this(new DockerSandboxClient(), registry);
}

@Override
public Sandbox create(...) {
    Sandbox sandbox = new NewlineFlatteningSandbox(
            delegate.create(workspaceSpec, snapshotSpec, options));
    registry.register(sandbox);
    return sandbox;
}

@Override
public Sandbox resume(SandboxState state) {
    Sandbox sandbox = new NewlineFlatteningSandbox(delegate.resume(state));
    registry.register(sandbox);
    return sandbox;
}

@Override
public void delete(Sandbox sandbox) {
    try {
        delegate.delete(sandbox);
    } finally {
        registry.unregister(sandbox);
    }
}
```

`AgentScopeConfig`：

```java
@Bean
ActiveSandboxRegistry activeSandboxRegistry() {
    return new ActiveSandboxRegistry();
}

static DockerFilesystemSpec dockerFilesystemSpec(
        DevAgentProperties properties, ActiveSandboxRegistry registry) {
    // ... 现有逻辑 ...
    DockerFilesystemSpec filesystem = new DockerFilesystemSpec()
            .client(new NewlineFlatteningDockerSandboxClient(registry))
            // ...
}
```

把所有调用 `dockerFilesystemSpec(properties)` 改为 `dockerFilesystemSpec(properties, activeSandboxRegistry)`（在 `@Bean` 方法参数中注入 `ActiveSandboxRegistry`）。若静态辅助测里直接调，传入 `new ActiveSandboxRegistry()`。

- [ ] **Step 5: 跑测通过**

Run: `.\mvnw.cmd "-Dtest=ActiveSandboxRegistryTest" test`  
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/sandbox/ActiveSandboxRegistry.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/sandbox/ActiveSandboxRegistryTest.java \
  demo2/src/main/java/com/jason/demo/demo2/agentscope/sandbox/NewlineFlatteningDockerSandboxClient.java \
  demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java
git commit -m "$(cat <<'EOF'
feat(demo2): track active sandboxes for plan host sync

EOF
)"
```

---

### Task 2: PlanHostSyncService + LiveSandboxPlanReader

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/plan/SandboxPlanReader.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/plan/LiveSandboxPlanReader.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/plan/PlanHostSyncService.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/agentscope/plan/PlanHostSyncServiceTest.java`

**Interfaces:**
- Consumes: `ActiveSandboxRegistry#findByAppSessionId`（Task 1）
- Produces:
  - `SandboxPlanReader#readPlanMarkdown(String userId, String sessionId) -> Optional<String>`
  - `PlanHostSyncService#syncAfterPlanWrite(String userId, String sessionId)`（void，永不抛业务异常）

- [ ] **Step 1: 写失败的 PlanHostSyncService 单测**

```java
package com.jason.demo.demo2.agentscope.plan;

import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanHostSyncServiceTest {

    @TempDir
    Path temp;

    DevAgentProperties properties;
    SandboxPlanReader reader;
    PlanHostSyncService service;

    @BeforeEach
    void setUp() {
        properties = new DevAgentProperties(
                "dev-task-agent",
                "prompt",
                temp.toString(),
                "workspace",
                new DevAgentProperties.Compaction(6, 2, "请整理会话：{messages}"),
                new DevAgentProperties.Model("sk-test", "https://api.deepseek.com", "deepseek-v4-pro"),
                new DevAgentProperties.McpSettings(false, java.util.List.of()),
                null,
                new DevAgentProperties.Sandbox(
                        true,
                        "agentscope-java-sandbox:17",
                        "none",
                        "/workspace",
                        ".agentscope/sandbox-snapshots",
                        536870912L,
                        1L));
        reader = mock(SandboxPlanReader.class);
        service = new PlanHostSyncService(properties, reader);
    }

    @Test
    void writesHostPlanOnSuccess() throws Exception {
        when(reader.readPlanMarkdown(eq("u1"), eq("s1")))
                .thenReturn(Optional.of("# Plan\n\ndo thing\n"));

        service.syncAfterPlanWrite("u1", "s1");

        Path hostPlan = temp.resolve("workspace/plans/PLAN.md");
        assertThat(hostPlan).exists();
        assertThat(Files.readString(hostPlan, StandardCharsets.UTF_8))
                .isEqualTo("# Plan\n\ndo thing\n");
    }

    @Test
    void overwritesExistingHostPlan() throws Exception {
        Path hostPlan = temp.resolve("workspace/plans/PLAN.md");
        Files.createDirectories(hostPlan.getParent());
        Files.writeString(hostPlan, "old", StandardCharsets.UTF_8);
        when(reader.readPlanMarkdown(any(), any()))
                .thenReturn(Optional.of("new-content"));

        service.syncAfterPlanWrite("u1", "s1");

        assertThat(Files.readString(hostPlan, StandardCharsets.UTF_8)).isEqualTo("new-content");
    }

    @Test
    void readMissDoesNotThrowAndKeepsOldFile() throws Exception {
        Path hostPlan = temp.resolve("workspace/plans/PLAN.md");
        Files.createDirectories(hostPlan.getParent());
        Files.writeString(hostPlan, "keep-me", StandardCharsets.UTF_8);
        when(reader.readPlanMarkdown(any(), any())).thenReturn(Optional.empty());

        assertThatCode(() -> service.syncAfterPlanWrite("u1", "s1")).doesNotThrowAnyException();
        assertThat(Files.readString(hostPlan, StandardCharsets.UTF_8)).isEqualTo("keep-me");
    }

    @Test
    void sandboxDisabledIsNoOp() {
        DevAgentProperties off = new DevAgentProperties(
                "dev-task-agent",
                "prompt",
                temp.toString(),
                "workspace",
                new DevAgentProperties.Compaction(6, 2, "请整理会话：{messages}"),
                new DevAgentProperties.Model("sk-test", "https://api.deepseek.com", "deepseek-v4-pro"),
                new DevAgentProperties.McpSettings(false, java.util.List.of()),
                null,
                null);
        PlanHostSyncService disabled = new PlanHostSyncService(off, reader);
        assertThatCode(() -> disabled.syncAfterPlanWrite("u1", "s1")).doesNotThrowAnyException();
        assertThat(temp.resolve("workspace/plans/PLAN.md")).doesNotExist();
    }
}
```

- [ ] **Step 2: 跑测确认失败**

Run: `.\mvnw.cmd "-Dtest=PlanHostSyncServiceTest" test`  
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 Reader + Service**

```java
package com.jason.demo.demo2.agentscope.plan;

import java.util.Optional;

public interface SandboxPlanReader {
    Optional<String> readPlanMarkdown(String userId, String sessionId);
}
```

```java
package com.jason.demo.demo2.agentscope.plan;

import com.jason.demo.demo2.agentscope.sandbox.ActiveSandboxRegistry;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class LiveSandboxPlanReader implements SandboxPlanReader {

    private static final Logger log = LoggerFactory.getLogger(LiveSandboxPlanReader.class);
    private static final String PLAN_RELATIVE = "plans/PLAN.md";

    private final ActiveSandboxRegistry registry;

    public LiveSandboxPlanReader(ActiveSandboxRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Optional<String> readPlanMarkdown(String userId, String sessionId) {
        Optional<Sandbox> sandbox = registry.findByAppSessionId(sessionId);
        if (sandbox.isEmpty()) {
            log.warn("No active sandbox for plan sync. userId={}, sessionId={}", userId, sessionId);
            return Optional.empty();
        }
        try {
            RuntimeContext ctx = RuntimeContext.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .build();
            ExecResult result = sandbox.get().exec(ctx, "cat " + PLAN_RELATIVE, 15);
            if (result == null || !result.ok()) {
                log.warn(
                        "Sandbox cat plan failed. userId={}, sessionId={}, exit={}, stderr={}",
                        userId,
                        sessionId,
                        result == null ? null : result.exitCode(),
                        result == null ? null : result.stderr());
                return Optional.empty();
            }
            String stdout = result.stdout();
            if (stdout == null || stdout.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(stdout);
        } catch (Exception ex) {
            log.warn(
                    "Sandbox plan read threw. userId={}, sessionId={}, err={}",
                    userId,
                    sessionId,
                    ex.toString());
            return Optional.empty();
        }
    }
}
```

```java
package com.jason.demo.demo2.agentscope.plan;

import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

@Service
public class PlanHostSyncService {

    private static final Logger log = LoggerFactory.getLogger(PlanHostSyncService.class);

    private final DevAgentProperties properties;
    private final SandboxPlanReader reader;

    public PlanHostSyncService(DevAgentProperties properties, SandboxPlanReader reader) {
        this.properties = properties;
        this.reader = reader;
    }

    public void syncAfterPlanWrite(String userId, String sessionId) {
        if (!properties.sandbox().enabled()) {
            return;
        }
        try {
            Optional<String> content = reader.readPlanMarkdown(userId, sessionId);
            if (content.isEmpty()) {
                log.warn(
                        "Skip host plan sync: empty/missing sandbox plan. userId={}, sessionId={}",
                        userId,
                        sessionId);
                return;
            }
            Path hostPlan = Path.of(properties.projectRoot())
                    .resolve(properties.workspaceRoot())
                    .resolve("plans")
                    .resolve("PLAN.md")
                    .toAbsolutePath()
                    .normalize();
            Files.createDirectories(hostPlan.getParent());
            Path tmp = hostPlan.resolveSibling("PLAN.md.tmp-" + Thread.currentThread().threadId());
            Files.writeString(tmp, content.get(), StandardCharsets.UTF_8);
            Files.move(tmp, hostPlan, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("Synced sandbox plan to host. path={}, userId={}, sessionId={}",
                    hostPlan, userId, sessionId);
        } catch (Exception ex) {
            log.warn(
                    "Host plan sync failed. userId={}, sessionId={}, err={}",
                    userId,
                    sessionId,
                    ex.toString());
        }
    }
}
```

若 Windows 上 `ATOMIC_MOVE` 跨卷失败，在 `catch` 同级对 `AtomicMoveNotSupportedException` 回退为仅 `REPLACE_EXISTING`（仍包在同一 try/catch 的内层即可）：

```java
try {
    Files.move(tmp, hostPlan, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
} catch (java.nio.file.AtomicMoveNotSupportedException ex) {
    Files.move(tmp, hostPlan, StandardCopyOption.REPLACE_EXISTING);
}
```

- [ ] **Step 4: 跑测通过**

Run: `.\mvnw.cmd "-Dtest=PlanHostSyncServiceTest" test`  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/plan \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/plan/PlanHostSyncServiceTest.java
git commit -m "$(cat <<'EOF'
feat(demo2): add PlanHostSyncService to mirror plans/PLAN.md to host

EOF
)"
```

---

### Task 3: DevAgentService 钩子

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServicePlanHostSyncTest.java`

**Interfaces:**
- Consumes: `PlanHostSyncService#syncAfterPlanWrite(String, String)`
- Produces: `mapAgentEvents(userId, sessionId, Flux)` 在 `plan_write`+`SUCCESS` 时调用 sync（在 `sink.next` 之前）

- [ ] **Step 1: 写失败的钩子单测**

```java
package com.jason.demo.demo2.agentscope.service;

import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import com.jason.demo.demo2.agentscope.model.DevAgentEventType;
import com.jason.demo.demo2.agentscope.model.DevAgentRequest;
import com.jason.demo.demo2.agentscope.plan.PlanHostSyncService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DevAgentServicePlanHostSyncTest {

    @Mock HarnessAgent harnessAgent;
    @Mock AgentStateStore agentStateStore;
    @Mock Tracer tracer;
    @Mock CurrentTraceContext currentTraceContext;
    @Mock TraceContext traceContext;
    @Mock PlanHostSyncService planHostSyncService;

    DevAgentProperties properties;
    DevAgentService service;

    @BeforeEach
    void setUp() {
        properties = new DevAgentProperties(
                "dev-task-agent",
                "prompt",
                ".",
                "workspace",
                new DevAgentProperties.Compaction(6, 2, "请整理会话：{messages}"),
                new DevAgentProperties.Model("sk-test", "https://api.deepseek.com", "deepseek-v4-pro"),
                new DevAgentProperties.McpSettings(false, java.util.List.of()),
                null,
                new DevAgentProperties.Sandbox(
                        true,
                        "agentscope-java-sandbox:17",
                        "none",
                        "/workspace",
                        ".agentscope/sandbox-snapshots",
                        536870912L,
                        1L));
        lenient().when(tracer.currentTraceContext()).thenReturn(currentTraceContext);
        lenient().when(currentTraceContext.context()).thenReturn(traceContext);
        lenient().when(traceContext.traceId()).thenReturn("trace-test");
        lenient().when(traceContext.spanId()).thenReturn("span-test");
        lenient()
                .when(agentStateStore.get(any(), any(), eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.empty());
        service = new DevAgentService(
                harnessAgent, properties, agentStateStore, tracer, null, planHostSyncService);
    }

    @Test
    void ask_syncsHostPlanAfterSuccessfulPlanWrite() {
        ToolResultEndEvent planWriteOk = mock(ToolResultEndEvent.class);
        when(planWriteOk.getType()).thenReturn(AgentEventType.TOOL_RESULT_END);
        when(planWriteOk.getId()).thenReturn("e1");
        when(planWriteOk.getSource()).thenReturn("agent");
        when(planWriteOk.getToolCallId()).thenReturn("tc1");
        when(planWriteOk.getToolCallName()).thenReturn("plan_write");
        when(planWriteOk.getState()).thenReturn(ToolResultState.SUCCESS);
        when(harnessAgent.streamEvents(eq("hi"), any(RuntimeContext.class)))
                .thenReturn(Flux.just(planWriteOk));

        StepVerifier.create(service.ask(new DevAgentRequest("u1", "s1", "hi")))
                .expectNextMatches(e -> e.type() == DevAgentEventType.SESSION)
                .expectNextMatches(e -> e.type() == DevAgentEventType.REQUEST_CONTEXT)
                .expectNextMatches(e -> e.type() == DevAgentEventType.TOOL_RESULT_END)
                .expectNextMatches(e -> e.type() == DevAgentEventType.DONE)
                .verifyComplete();

        verify(planHostSyncService).syncAfterPlanWrite("u1", "s1");
    }

    @Test
    void ask_doesNotSyncOnOtherToolsOrErrors() {
        ToolResultEndEvent readOk = mock(ToolResultEndEvent.class);
        when(readOk.getType()).thenReturn(AgentEventType.TOOL_RESULT_END);
        when(readOk.getId()).thenReturn("e1");
        when(readOk.getSource()).thenReturn("agent");
        when(readOk.getToolCallId()).thenReturn("tc1");
        when(readOk.getToolCallName()).thenReturn("read_file");
        when(readOk.getState()).thenReturn(ToolResultState.SUCCESS);

        ToolResultEndEvent planErr = mock(ToolResultEndEvent.class);
        when(planErr.getType()).thenReturn(AgentEventType.TOOL_RESULT_END);
        when(planErr.getId()).thenReturn("e2");
        when(planErr.getSource()).thenReturn("agent");
        when(planErr.getToolCallId()).thenReturn("tc2");
        when(planErr.getToolCallName()).thenReturn("plan_write");
        when(planErr.getState()).thenReturn(ToolResultState.ERROR);

        when(harnessAgent.streamEvents(eq("hi"), any(RuntimeContext.class)))
                .thenReturn(Flux.just(readOk, planErr));

        StepVerifier.create(service.ask(new DevAgentRequest("u1", "s1", "hi")))
                .thenConsumeWhile(e -> true)
                .verifyComplete();

        verify(planHostSyncService, never()).syncAfterPlanWrite(any(), any());
    }
}
```

补 import：`AgentEventType`、`mock`（与现有 `DevAgentServiceTest` 对 `ToolResultEndEvent` 的 mock 方式一致）。

- [ ] **Step 2: 跑测确认失败**

Run: `.\mvnw.cmd "-Dtest=DevAgentServicePlanHostSyncTest" test`  
Expected: FAIL（构造器 / 未调用 sync）

- [ ] **Step 3: 改 DevAgentService**

1. 字段 + 构造：与 `WorkspaceDiffService` 同模式，增加可空 `PlanHostSyncService`：

```java
private final PlanHostSyncService planHostSyncService;

@Autowired
public DevAgentService(
        HarnessAgent agentscopeDevAgent,
        DevAgentProperties properties,
        AgentStateStore agentStateStore,
        Tracer tracer,
        WorkspaceDiffService workspaceDiffService,
        PlanHostSyncService planHostSyncService) {
    // assign all
}

public DevAgentService(
        HarnessAgent agentscopeDevAgent,
        DevAgentProperties properties,
        AgentStateStore agentStateStore,
        Tracer tracer) {
    this(agentscopeDevAgent, properties, agentStateStore, tracer, null, null);
}

public DevAgentService(
        HarnessAgent agentscopeDevAgent,
        DevAgentProperties properties,
        AgentStateStore agentStateStore,
        Tracer tracer,
        WorkspaceDiffService workspaceDiffService) {
    this(agentscopeDevAgent, properties, agentStateStore, tracer, workspaceDiffService, null);
}

public DevAgentService(
        HarnessAgent agentscopeDevAgent,
        DevAgentProperties properties,
        AgentStateStore agentStateStore,
        Tracer tracer,
        WorkspaceDiffService workspaceDiffService,
        PlanHostSyncService planHostSyncService) {
    this.agentscopeDevAgent = agentscopeDevAgent;
    this.properties = properties;
    this.agentStateStore = agentStateStore;
    this.tracer = tracer;
    this.workspaceDiffService = workspaceDiffService;
    this.planHostSyncService = planHostSyncService;
}
```

避免重复构造体：只保留一个全参构造 + `@Autowired` 指向它，其它测试用重载委托到全参。

2. `askAfterContext` / `confirmAfterContext` 中：

```java
mapAgentEvents(userId, sessionId, agentscopeDevAgent.streamEvents(...))
```

3. 替换 `mapAgentEvents`：

```java
private Flux<DevAgentEvent> mapAgentEvents(
        String userId, String sessionId, Flux<AgentEvent> agentEvents) {
    return agentEvents.handle((event, sink) -> {
        maybeSyncPlanToHost(userId, sessionId, event);
        DevAgentEvent mapped = mapEvent(sessionId, event);
        if (mapped != null) {
            sink.next(mapped);
        }
    });
}

private void maybeSyncPlanToHost(String userId, String sessionId, AgentEvent event) {
    if (planHostSyncService == null || event.getType() != AgentEventType.TOOL_RESULT_END) {
        return;
    }
    ToolResultEndEvent e = (ToolResultEndEvent) event;
    if (!"plan_write".equals(e.getToolCallName()) || e.getState() != ToolResultState.SUCCESS) {
        return;
    }
    planHostSyncService.syncAfterPlanWrite(userId, sessionId);
}
```

需要 `import io.agentscope.core.event.AgentEventType;` 与 `ToolResultState`。

- [ ] **Step 4: 跑测通过（含既有 DevAgentServiceTest）**

Run:

```text
.\mvnw.cmd "-Dtest=DevAgentServiceTest,DevAgentServicePlanHostSyncTest" test
```

Expected: PASS（若既有测试用旧 4/5 参构造，保持重载兼容）

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServicePlanHostSyncTest.java
git commit -m "$(cat <<'EOF'
feat(demo2): sync host PLAN.md after successful plan_write

EOF
)"
```

---

### Task 4: README + 编译门禁

**Files:**
- Modify: `demo2/README.md`（Plan Mode 段）

**Interfaces:**
- Consumes: 无新增代码 API
- Produces: 文档与手工验收说明

- [ ] **Step 1: 更新 README Plan Mode 段**

在「计划写入工作区 `plans/PLAN.md`」附近追加一句：

```markdown
`plan_write` 成功后会自动覆盖同步到宿主 `workspace/plans/PLAN.md`（不经 HITL；权威副本仍在沙箱）。可在确认 `plan_exit` 前用编辑器打开该文件查看方案。
```

成功标准改为：

```markdown
成功标准：第一次确认（`plan_exit`）前源码未改，且宿主 `workspace/plans/PLAN.md` 已有本次计划内容；三次确认后 `mvn test` 在容器内通过。
```

并补上 host-sync spec/plan 链接：

```markdown
Spec / Plan：`docs/superpowers/specs/2026-07-29-agentscope-plan-mode-design.md`、`docs/superpowers/plans/2026-07-29-agentscope-plan-mode.md`；宿主同步：`docs/superpowers/specs/2026-07-30-agentscope-plan-host-sync-design.md`、`docs/superpowers/plans/2026-07-30-agentscope-plan-host-sync.md`
```

- [ ] **Step 2: 编译 + 相关单测**

Run:

```text
.\mvnw.cmd -DskipTests compile
.\mvnw.cmd "-Dtest=ActiveSandboxRegistryTest,PlanHostSyncServiceTest,DevAgentServicePlanHostSyncTest,DevAgentServiceTest" test
```

Expected: 全部 SUCCESS

- [ ] **Step 3: Commit**

```bash
git add demo2/README.md
git commit -m "$(cat <<'EOF'
docs(demo2): note plan_write host PLAN.md sync in README

EOF
)"
```

- [ ] **Step 4: 手工验收（可选，需沙箱环境）**

1. `sandbox.enabled=true` 启动应用  
2. Tab 示例 15 或 curl ask  
3. 出现 `plan_exit` 确认前，打开 `demo2/workspace/plans/PLAN.md`，内容非空且像本次方案  
4. 批准后续流程仍正常

---

## Self-Review (plan vs spec)

| Spec 要求 | 对应 Task |
|-----------|-----------|
| `TOOL_RESULT_END` + `plan_write` + 成功立即同步 | Task 3 |
| 固定覆盖宿主 `workspace/plans/PLAN.md` | Task 2 |
| live 读沙箱，不依赖回合结束快照 | Task 1 registry + Task 2 `LiveSandboxPlanReader` |
| 失败 WARN 不阻断 | Task 2 / 3 |
| 沙箱关 no-op | Task 2 |
| 不改 Diff / API / 前端 | File Map「不改」 |
| 单测：sync / 钩子过滤 | Task 2、3 |
| README / 手工验收 | Task 4 |

无 TBD；类型名在各 Task Interfaces 一致。
