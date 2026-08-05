# lock4j + Redisson 分布式锁 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 demo2 引入 Redis + lock4j/Redisson，落地同步 `@Lock4j` demo 与 `DevAgentService.ask` 编程式 `tryLock` 防重复提交。

**Architecture:** Docker 单机 Redis；业务只走 lock4j 门面（注解或 `LockTemplate`），底层 `RedissonLockExecutor`。路径 A 同步接口用注解；路径 B SSE 用 `LockTemplate.lock` + Flux `doFinally` 释放（禁止在返回 Flux 的方法上打注解）。

**Tech Stack:** Spring Boot 4.1、Java 21、`lock4j-redisson-spring-boot-starter` 2.2.7+、`redisson-spring-boot-starter`、Redis 7 alpine、JUnit 5 + Mockito + StepVerifier

**Spec:** [2026-08-05-lock4j-redisson-design.md](../specs/2026-08-05-lock4j-redisson-design.md)

## Global Constraints

- 模块仅限 `demo2`；不改 `demo` 工程
- 注解类名是 **`@Lock4j`**（`com.baomidou.lock.annotation.Lock4j`），不是 `@Lock`
- 锁冲突：demo → HTTP 409；ask → SSE `DevAgentEvent.error(..., "duplicate_in_progress")`
- `acquireTimeout = 0`（立即失败）；不排队
- key 含 `message` 的 **短哈希**，不是原文
- 保留现有 `sandboxRequestLock`；不替换
- 不给 `/confirm`、`/apply-diff` 加锁
- Redis 不可用时 **禁止**静默跳过锁
- SSE 解锁可能不在加锁线程：`releaseLock` 失败时打 WARN，依赖 expire 兜底；可选对同一 `RLock` 做安全兜底释放（见 Task 5）

---

## File Structure

| 文件 | 职责 |
|------|------|
| `demo2/docker/redis/docker-compose.yml` | 本地 Redis |
| `demo2/pom.xml` | lock4j + redisson 依赖 |
| `demo2/src/main/resources/application.properties` | Redis / lock4j 配置 |
| `demo2/.../lock/LockKeys.java` | messageHash + key 拼接 |
| `demo2/.../model/LockDemoRequest.java` | demo 请求 |
| `demo2/.../model/LockDemoResponse.java` | demo 响应 |
| `demo2/.../service/LockDemoService.java` | `@Lock4j` 临界区 |
| `demo2/.../controller/LockDemoController.java` | `/demo/lock/submit` |
| `demo2/.../controller/LockDemoExceptionHandler.java` | 锁失败 → 409 |
| `demo2/.../agentscope/service/DevAgentService.java` | ask 编程式锁 |
| 对应 `src/test/java/...` | 单测 |

---

### Task 1: Redis Docker + Maven + 配置

**Files:**
- Create: `demo2/docker/redis/docker-compose.yml`
- Modify: `demo2/pom.xml`
- Modify: `demo2/src/main/resources/application.properties`

**Interfaces:**
- Produces: 本机 `127.0.0.1:6379` Redis；应用可注入 `LockTemplate` / `RedissonClient`

- [ ] **Step 1: 写 docker-compose**

```yaml
# 分布式锁用 Redis（lock4j + Redisson）
# 启动：docker compose -f demo2/docker/redis/docker-compose.yml up -d
# 停止：docker compose -f demo2/docker/redis/docker-compose.yml down
services:
  demo2-redis:
    container_name: demo2-redis
    image: redis:7-alpine
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 10
    restart: unless-stopped
```

- [ ] **Step 2: 启动 Redis 并 ping**

```bash
docker compose -f demo2/docker/redis/docker-compose.yml up -d
docker exec demo2-redis redis-cli ping
```

Expected: `PONG`

- [ ] **Step 3: 加 Maven 依赖**

在 `demo2/pom.xml` 的 `<properties>` 增加：

```xml
<lock4j.version>2.2.7</lock4j.version>
```

在 `<dependencies>` 增加（`redisson-spring-boot-starter` 需显式引入，lock4j 侧为 provided）：

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>lock4j-redisson-spring-boot-starter</artifactId>
    <version>${lock4j.version}</version>
</dependency>
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.45.1</version>
</dependency>
```

若与 Spring Boot 4.1 启动冲突：先查 Redisson 发行说明，换成当前可用的 `redisson-spring-boot-starter` 版本；**不要**为此回退 Boot 版本。

- [ ] **Step 4: 写 application.properties**

在文件末尾追加：

```properties
# ===== Redis / lock4j（分布式锁）=====
# Docker: docker compose -f demo2/docker/redis/docker-compose.yml up -d
spring.data.redis.host=127.0.0.1
spring.data.redis.port=6379
lock4j.acquire-timeout=0
lock4j.expire=30000
lock4j.lock-key-prefix=lock4j
```

- [ ] **Step 5: 编译验证依赖可解析**

```bash
mvn -f demo2/pom.xml -DskipTests compile
```

Expected: BUILD SUCCESS。若 Redisson 自动配置类找不到，按 Step 3 换版本后重试。

- [ ] **Step 6: Commit**

```bash
git add demo2/docker/redis/docker-compose.yml demo2/pom.xml demo2/src/main/resources/application.properties
git commit -m "chore(demo2): add Redis docker and lock4j-redisson dependencies"
```

---

### Task 2: LockKeys 工具（TDD）

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/lock/LockKeys.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/lock/LockKeysTest.java`

**Interfaces:**
- Produces:
  - `LockKeys.messageHash(String message) -> String`（SHA-256 hex 前 16 字符）
  - `LockKeys.demoSubmitKey(String userId, String sessionId, String message) -> String`
  - `LockKeys.devAgentAskKey(String userId, String sessionId, String message) -> String`
- Key 格式（无 lock4j 全局前缀；前缀由 lock4j 配置另加）：
  - `demo:lock:submit:{userId}:{sessionId}:{hash}`
  - `agentscope:dev-agent:ask:{userId}:{sessionId}:{hash}`

- [ ] **Step 1: 写失败测试**

```java
package com.jason.demo.demo2.lock;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LockKeysTest {

    @Test
    void messageHash_isStableAndShort() {
        String h1 = LockKeys.messageHash("hello");
        String h2 = LockKeys.messageHash("hello");
        assertThat(h1).isEqualTo(h2).hasSize(16);
        assertThat(LockKeys.messageHash("hello!")).isNotEqualTo(h1);
    }

    @Test
    void demoSubmitKey_usesNormalizedParts() {
        assertThat(LockKeys.demoSubmitKey("u1", "s1", "m"))
                .isEqualTo("demo:lock:submit:u1:s1:" + LockKeys.messageHash("m"));
    }

    @Test
    void devAgentAskKey_usesAskPrefix() {
        assertThat(LockKeys.devAgentAskKey("u1", "s1", "m"))
                .startsWith("agentscope:dev-agent:ask:u1:s1:");
    }
}
```

- [ ] **Step 2: 跑测确认失败**

```bash
mvn -f demo2/pom.xml -Dtest=LockKeysTest test
```

Expected: 编译失败或测试失败（类不存在）

- [ ] **Step 3: 实现 LockKeys**

```java
package com.jason.demo.demo2.lock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class LockKeys {

    private LockKeys() {}

    public static String messageHash(String message) {
        String raw = message == null ? "" : message;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String demoSubmitKey(String userId, String sessionId, String message) {
        return "demo:lock:submit:" + userId + ":" + sessionId + ":" + messageHash(message);
    }

    public static String devAgentAskKey(String userId, String sessionId, String message) {
        return "agentscope:dev-agent:ask:" + userId + ":" + sessionId + ":" + messageHash(message);
    }
}
```

- [ ] **Step 4: 跑测通过**

```bash
mvn -f demo2/pom.xml -Dtest=LockKeysTest test
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/lock/LockKeys.java \
        demo2/src/test/java/com/jason/demo/demo2/lock/LockKeysTest.java
git commit -m "feat(demo2): add LockKeys helpers for distributed lock names"
```

---

### Task 3: 路径 A — Lock Demo（注解）

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/LockDemoRequest.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/LockDemoResponse.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/service/LockDemoService.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/controller/LockDemoController.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/controller/LockDemoExceptionHandler.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/service/LockDemoServiceTest.java`

**Interfaces:**
- Consumes: `LockKeys.demoSubmitKey`
- Produces: `POST /demo/lock/submit` → `LockDemoResponse`
- `@Lock4j(keys = {"#key"}, acquireTimeout = 0, expire = 30000)` 打在 Service 方法上；**key 由 Controller/Service 先算好传入**，避免 SpEL 里再哈希

- [ ] **Step 1: 写模型**

```java
package com.jason.demo.demo2.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record LockDemoRequest(
        String userId,
        @NotBlank String sessionId,
        @NotBlank String message,
        @Min(1) @Max(20000) Integer workMs) {}
```

```java
package com.jason.demo.demo2.model;

public record LockDemoResponse(
        boolean locked,
        String key,
        Long elapsedMs,
        String echo,
        String reason) {

    public static LockDemoResponse ok(String key, long elapsedMs, String echo) {
        return new LockDemoResponse(true, key, elapsedMs, echo, null);
    }

    public static LockDemoResponse conflict() {
        return new LockDemoResponse(false, null, null, null, "duplicate_in_progress");
    }
}
```

- [ ] **Step 2: 写 Service（含 @Lock4j）**

```java
package com.jason.demo.demo2.service;

import com.baomidou.lock.annotation.Lock4j;
import com.jason.demo.demo2.model.LockDemoResponse;
import org.springframework.stereotype.Service;

@Service
public class LockDemoService {

    @Lock4j(keys = {"#key"}, acquireTimeout = 0, expire = 30000)
    public LockDemoResponse submitLocked(String key, String echo, int workMs) {
        long start = System.nanoTime();
        try {
            Thread.sleep(workMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        return LockDemoResponse.ok(key, elapsedMs, echo);
    }
}
```

- [ ] **Step 3: Controller + 异常映射**

```java
package com.jason.demo.demo2.controller;

import com.jason.demo.demo2.lock.LockKeys;
import com.jason.demo.demo2.model.LockDemoRequest;
import com.jason.demo.demo2.model.LockDemoResponse;
import com.jason.demo.demo2.service.LockDemoService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/demo/lock")
public class LockDemoController {

    private final LockDemoService lockDemoService;

    public LockDemoController(LockDemoService lockDemoService) {
        this.lockDemoService = lockDemoService;
    }

    @PostMapping("/submit")
    public LockDemoResponse submit(@Valid @RequestBody LockDemoRequest request) {
        String userId = (request.userId() == null || request.userId().isBlank())
                ? "anonymous"
                : request.userId().strip();
        int workMs = request.workMs() == null ? 3000 : request.workMs();
        String key = LockKeys.demoSubmitKey(userId, request.sessionId(), request.message());
        return lockDemoService.submitLocked(key, request.message(), workMs);
    }
}
```

```java
package com.jason.demo.demo2.controller;

import com.baomidou.lock.exception.LockFailureException;
import com.jason.demo.demo2.model.LockDemoResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = LockDemoController.class)
public class LockDemoExceptionHandler {

    @ExceptionHandler(LockFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public LockDemoResponse onLockFailure(LockFailureException ex) {
        return LockDemoResponse.conflict();
    }
}
```

若实际异常类名不是 `LockFailureException`，打开 lock4j 源码/依赖确认后替换（常见为 `LockFailureException`）。

- [ ] **Step 4: 轻量单测（不启 Redis：测 userId/key 拼装可通过 Spy；或仅测 Conflict response 工厂）**

```java
package com.jason.demo.demo2.service;

import com.jason.demo.demo2.lock.LockKeys;
import com.jason.demo.demo2.model.LockDemoResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LockDemoServiceTest {

    @Test
    void submitLocked_returnsElapsedWithoutLockAspect() {
        // 直接调目标方法（无 Spring AOP）；验证业务体
        LockDemoService service = new LockDemoService();
        String key = LockKeys.demoSubmitKey("u", "s", "hi");
        LockDemoResponse resp = service.submitLocked(key, "hi", 50);
        assertThat(resp.locked()).isTrue();
        assertThat(resp.key()).isEqualTo(key);
        assertThat(resp.elapsedMs()).isGreaterThanOrEqualTo(50);
        assertThat(resp.echo()).isEqualTo("hi");
    }
}
```

完整 409 行为靠手工 curl（Step 6）；本测不依赖 Redis。

- [ ] **Step 5: 跑测**

```bash
mvn -f demo2/pom.xml -Dtest=LockKeysTest,LockDemoServiceTest test
```

Expected: BUILD SUCCESS

- [ ] **Step 6: 手工并发验收（需 Redis + 应用已启动）**

```bash
# 终端 1
curl -s -X POST http://localhost:8081/demo/lock/submit \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"u1\",\"sessionId\":\"s1\",\"message\":\"same\",\"workMs\":5000}"

# 终端 2（立即）
curl -s -o - -w "\nHTTP:%{http_code}\n" -X POST http://localhost:8081/demo/lock/submit \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"u1\",\"sessionId\":\"s1\",\"message\":\"same\",\"workMs\":5000}"
```

Expected: 终端 2 `HTTP:409` 且 body 含 `duplicate_in_progress`；终端 1 最终 200。

- [ ] **Step 7: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/model/LockDemoRequest.java \
        demo2/src/main/java/com/jason/demo/demo2/model/LockDemoResponse.java \
        demo2/src/main/java/com/jason/demo/demo2/service/LockDemoService.java \
        demo2/src/main/java/com/jason/demo/demo2/controller/LockDemoController.java \
        demo2/src/main/java/com/jason/demo/demo2/controller/LockDemoExceptionHandler.java \
        demo2/src/test/java/com/jason/demo/demo2/service/LockDemoServiceTest.java
git commit -m "feat(demo2): add Lock4j demo submit endpoint"
```

---

### Task 4: 路径 B — ask 编程式锁（先单测失败分支）

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java`（及所有构造 `DevAgentService` 的测试，补 `LockTemplate` 参数）
- Create: `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceAskLockTest.java`

**Interfaces:**
- Consumes: `LockTemplate.lock(String key, Long expire, Long acquireTimeout)` → `LockInfo`；`null` 表示失败
- Consumes: `LockKeys.devAgentAskKey(userId, sessionId, message)`
- Produces: ask 冲突时 SSE error `duplicate_in_progress`；成功时 `doFinally` → `lockTemplate.releaseLock(lockInfo)`

**构造函数策略：** 增加可选/重载构造：生产路径注入 `LockTemplate`；既有单测构造传入 `null` 时 **跳过分布式锁**（保持单测不依赖 Redis），或传入 mock。推荐：**mock `LockTemplate` 的测试显式覆盖锁逻辑；其它测试传 null = 关闭锁。**

- [ ] **Step 1: 写 AskLock 失败/成功单测（mock LockTemplate）**

```java
package com.jason.demo.demo2.agentscope.service;

import com.baomidou.lock.LockInfo;
import com.baomidou.lock.LockTemplate;
import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import com.jason.demo.demo2.agentscope.model.DevAgentEvent;
import com.jason.demo.demo2.agentscope.model.DevAgentEventType;
import com.jason.demo.demo2.agentscope.model.DevAgentRequest;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DevAgentServiceAskLockTest {

    @Mock HarnessAgent harnessAgent;
    @Mock AgentStateStore agentStateStore;
    @Mock Tracer tracer;
    @Mock CurrentTraceContext currentTraceContext;
    @Mock TraceContext traceContext;
    @Mock LockTemplate lockTemplate;

    DevAgentProperties properties;
    DevAgentService service;

    @BeforeEach
    void setUp() {
        properties = new DevAgentProperties(
                "dev-task-agent", "prompt", ".", "workspace",
                new DevAgentProperties.Compaction(6, 2, "请整理会话：{messages}"),
                new DevAgentProperties.Model("sk-test", "https://api.deepseek.com", "deepseek-v4-pro"),
                null,
                new DevAgentProperties.McpSettings(false, java.util.List.of()),
                null, null);
        lenient().when(tracer.currentTraceContext()).thenReturn(currentTraceContext);
        lenient().when(currentTraceContext.context()).thenReturn(traceContext);
        lenient().when(traceContext.traceId()).thenReturn("t");
        lenient().when(traceContext.spanId()).thenReturn("s");
        // 关闭沙箱，避免 Semaphore 干扰：properties.sandbox() 需为 disabled
        // 若 DevAgentProperties 无 sandbox 默认，测试里用现有构造；必要时 mock sandbox.enabled=false
        service = new DevAgentService(
                harnessAgent, properties, agentStateStore, tracer, null, null, lockTemplate);
    }

    @Test
    void ask_whenLockBusy_emitsDuplicateError() {
        when(lockTemplate.lock(anyString(), anyLong(), anyLong())).thenReturn(null);

        StepVerifier.create(service.ask(new DevAgentRequest("u1", "sid", "same-msg")))
                .expectNextMatches(e -> e.type() == DevAgentEventType.SESSION)
                .expectNextMatches(e -> e.type() == DevAgentEventType.REQUEST_CONTEXT
                        || "duplicate_in_progress".equals(e.content()))
                .thenConsumeWhile(e -> e.type() != DevAgentEventType.ERROR
                        || !"duplicate_in_progress".equals(e.content()))
                // 更稳妥：收集全部后断言含 ERROR duplicate
                .verifyComplete();

        verify(lockTemplate, never()).releaseLock(any());
    }
}
```

实现时按真实 `withRequestContext` 事件顺序调整 StepVerifier（通常先 SESSION / REQUEST_CONTEXT，再 ERROR）。核心断言：**出现 content 为 `duplicate_in_progress` 的 ERROR，且未 `releaseLock`。**

另写成功路径：`when(lockTemplate.lock(...)).thenReturn(mock(LockInfo.class))`，`harnessAgent.streamEvents` 返回短 Flux，verify `releaseLock` 被调用一次。

- [ ] **Step 2: 跑测确认失败（构造器尚未增加 lockTemplate）**

```bash
mvn -f demo2/pom.xml -Dtest=DevAgentServiceAskLockTest test
```

Expected: 编译失败

- [ ] **Step 3: 扩展 DevAgentService 构造并实现 ask 加锁**

在字段中增加：

```java
private final LockTemplate lockTemplate; // nullable = 锁关闭（单测）
```

所有现有构造链最终落到一个主构造，`lockTemplate` 默认 `null`。

在 `ask` 中，API key 校验通过后、进入 `withRequestContext` 的业务 Flux 前：

```java
public Flux<DevAgentEvent> ask(DevAgentRequest request) {
    String sessionId = request.sessionId();
    String userId = normalizeUserId(request.userId());
    Invocation invocation = newInvocation(userId, sessionId);
    // ... existing missing apiKey branch unchanged ...

    if (lockTemplate == null) {
        return withRequestContext(
                sessionId, invocation,
                Flux.defer(() -> askAfterContext(request, userId, invocation)));
    }

    String lockKey = LockKeys.devAgentAskKey(userId, sessionId, request.message());
    long expireMs = 600_000L; // 10m
    LockInfo lockInfo = lockTemplate.lock(lockKey, expireMs, 0L);
    if (lockInfo == null) {
        logRejected(invocation, "duplicate_in_progress");
        return withRequestContext(
                sessionId,
                invocation,
                Flux.just(DevAgentEvent.error(sessionId, "duplicate_in_progress")));
    }

    return withRequestContext(
            sessionId,
            invocation,
            Flux.defer(() -> askAfterContext(request, userId, invocation))
                    .doFinally(signal -> {
                        try {
                            boolean ok = lockTemplate.releaseLock(lockInfo);
                            if (!ok) {
                                log.warn("ask lock release returned false, key={}, signal={}",
                                        lockKey, signal);
                            }
                        } catch (RuntimeException ex) {
                            log.warn("ask lock release failed, key={}, signal={}",
                                    lockKey, signal, ex);
                        }
                    }));
}
```

注意：`lock` 的 overload 若需指定 `RedissonLockExecutor.class`，按 lock4j API 使用四参数版本。

- [ ] **Step 4: 修复既有测试编译（构造器签名）**

所有 `new DevAgentService(...)` 保持可编译；新增参数用重载，旧测试零改或仅加 `null`。

- [ ] **Step 5: 跑相关测试**

```bash
mvn -f demo2/pom.xml -Dtest=DevAgentServiceTest,DevAgentServiceAskLockTest,DevAgentServiceRagRoutingTest,DevAgentServicePlanHostSyncTest test
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java \
        demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceAskLockTest.java \
        demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java \
        demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceRagRoutingTest.java \
        demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServicePlanHostSyncTest.java
git commit -m "feat(demo2): guard DevAgent ask with LockTemplate tryLock"
```

---

### Task 5: 接线 Spring 注入 + 文档片段 + 手工 ask 验收

**Files:**
- Modify: `DevAgentService` 生产构造 — 确保 `@Autowired` 构造包含 `LockTemplate`
- Modify: `demo2/README.md` 或 `demo2/docs/superpowers/specs/2026-08-05-lock4j-redisson-design.md` 末尾加「实现备注」：启动 Redis 命令 + 两条 curl
- 若 Boot 启动因 Redisson 失败：在本 Task 内修版本/排除冲突直至 `mvn spring-boot:run` 或现有启动方式可用

- [ ] **Step 1: 确认生产 `@Autowired` 构造注入 LockTemplate**

主构造参数列表包含 `LockTemplate lockTemplate`（非 null）。Spring 有 bean 时注入。

- [ ] **Step 2: 启动应用（Redis 已 up）**

按项目惯用方式启动 demo2，确认无 Redisson/lock4j 启动错误。

- [ ] **Step 3: 在 README 或 spec 追加验收命令**

```bash
# Redis
docker compose -f demo2/docker/redis/docker-compose.yml up -d

# Demo 锁
curl -X POST http://localhost:8081/demo/lock/submit -H "Content-Type: application/json" \
  -d "{\"userId\":\"u1\",\"sessionId\":\"s1\",\"message\":\"x\",\"workMs\":3000}"
```

- [ ] **Step 4: Commit**

```bash
git add -u demo2
git commit -m "docs(demo2): document Redis lock demo and ask duplicate guard"
```

---

## Spec coverage checklist

| Spec 项 | Task |
|---------|------|
| Redis Docker | 1 |
| lock4j + Redisson 依赖/配置 | 1 |
| LockKeys / messageHash | 2 |
| 路径 A demo `@Lock4j` + 409 | 3 |
| 路径 B ask tryLock + doFinally | 4 |
| 保留 sandboxRequestLock | 4（不改 Semaphore 逻辑） |
| 不锁 confirm/apply-diff | 4（只改 ask） |
| 手工验收 | 3 Step6、5 |

## Placeholder / risk notes（实现时处理，非 TBD）

1. **Boot 4.1 × Redisson starter**：Task 1 允许换 Redisson 版本；以能启动为准。
2. **异常类名**：Task 3 以依赖内真实 `LockFailure*` 为准。
3. **跨线程 unlock**：Task 4 已要求 release 失败只 WARN + 依赖 10m expire；不要在未持有时盲目 `forceUnlock` 抢别人的锁。
