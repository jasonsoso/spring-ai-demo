# Parallel Query Aggregate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 demo2 落地可复用的并行查询聚合工具，并用虚拟线程 / JDK8 `ThreadPoolExecutor` 两个 Demo 接口演示部分成功与墙钟超时。

**Architecture:** `ParallelQuerySupport` 接收命名 `Supplier` + 墙钟超时 + `Executor`，按路返回结果或 `null`（异常/超时只打日志）。`ParallelProfileService` 用同一套业务拼 `{user, orders}`；两个 Controller 路径分别注入虚拟线程 Executor 与手写 `ThreadPoolExecutor`。Mock 查询支持 delay/fail query 参数以便演示。

**Tech Stack:** Spring Boot 4.1、Java 21、`CompletableFuture`、虚拟线程、`ThreadPoolExecutor`、JUnit 5 + AssertJ、可选 MockMvc

**Spec:** [2026-08-05-parallel-query-aggregate-design.md](../specs/2026-08-05-parallel-query-aggregate-design.md)

## Global Constraints

- 模块仅限 `demo2`；不改 `demo` 工程
- 失败策略：部分成功；失败/超时路为 JSON `null`，HTTP **200**
- 墙钟总预算默认 **3s**（`demo.parallel.timeout`）
- 超时/异常细节 **不返回前端**，只写日志
- 响应扁平：`{ "user": ...|null, "orders": ...|null }`
- 数据源仅 Mock；不做真实 DB/HTTP
- JDK8 Demo：**禁止** `Executors.newFixedThreadPool` / `newCachedThreadPool`；必须手写 `ThreadPoolExecutor`
- JDK8 Demo：**禁止**虚拟线程 Executor
- 不迁移 `MultiAgentService` 到新工具

---

## File Structure

| 文件 | 职责 |
|------|------|
| `demo2/.../parallel/ParallelQuerySupport.java` | 通用并行聚合（超时/异常 → null + 日志） |
| `demo2/.../parallel/ParallelProperties.java` | `demo.parallel.*` 配置绑定 |
| `demo2/.../parallel/ParallelExecutorConfig.java` | VT Executor + JDK8 `ThreadPoolExecutor` Bean，销毁时 shutdown |
| `demo2/.../parallel/MockUserQuery.java` | Mock 用户查询（delay/fail） |
| `demo2/.../parallel/MockOrderQuery.java` | Mock 订单查询（delay/fail） |
| `demo2/.../model/UserProfileDto.java` | 用户响应片段 |
| `demo2/.../model/OrderDto.java` | 订单响应片段 |
| `demo2/.../model/UserProfileAggregateResponse.java` | `{ user, orders }` |
| `demo2/.../service/ParallelProfileService.java` | 并行拉用户+订单并聚合 |
| `demo2/.../controller/ParallelProfileController.java` | `/demo/parallel/virtual|jdk8/user-profile` |
| `demo2/src/main/resources/application.properties` | 追加 `demo.parallel.*` |
| `demo2/src/test/java/.../parallel/ParallelQuerySupportTest.java` | Support 单测 |
| `demo2/src/test/java/.../service/ParallelProfileServiceTest.java` | Service 单测 |

包名统一：`com.jason.demo.demo2.parallel`（工具/配置/Mock）、模型放现有 `model`、Service/Controller 跟现有惯例。

---

### Task 1: `ParallelQuerySupport`（TDD）

**Files:**
- Create: `demo2/src/test/java/com/jason/demo/demo2/parallel/ParallelQuerySupportTest.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/parallel/ParallelQuerySupport.java`

**Interfaces:**
- Produces:
  - `public final class ParallelQuerySupport`（或 `@Component`）
  - `public Map<String, Object> run(Map<String, Supplier<?>> namedTasks, Duration timeout, Executor executor)`
  - 返回 Map：**每个输入 key 都存在**；成功为业务对象，失败/超时/拒绝为 `null`
  - 不向调用方抛出任务异常或超时异常

- [ ] **Step 1: 写失败单测**

```java
package com.jason.demo.demo2.parallel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ParallelQuerySupportTest {

    private ExecutorService executor;
    private ParallelQuerySupport support;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        support = new ParallelQuerySupport();
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void run_bothSucceed_returnsBothValues() {
        Map<String, Supplier<?>> tasks = new LinkedHashMap<>();
        tasks.put("user", () -> "alice");
        tasks.put("orders", () -> java.util.List.of("o1"));

        Map<String, Object> result = support.run(tasks, Duration.ofSeconds(3), executor);

        assertThat(result.get("user")).isEqualTo("alice");
        assertThat(result.get("orders")).isEqualTo(java.util.List.of("o1"));
    }

    @Test
    void run_oneThrows_returnsNullForThatKeyOnly() {
        Map<String, Supplier<?>> tasks = new LinkedHashMap<>();
        tasks.put("user", () -> "alice");
        tasks.put("orders", () -> {
            throw new IllegalStateException("order-down");
        });

        Map<String, Object> result = support.run(tasks, Duration.ofSeconds(3), executor);

        assertThat(result.get("user")).isEqualTo("alice");
        assertThat(result.get("orders")).isNull();
    }

    @Test
    void run_oneExceedsBudget_returnsNullForSlowKey() {
        AtomicBoolean slowStarted = new AtomicBoolean();
        Map<String, Supplier<?>> tasks = new LinkedHashMap<>();
        tasks.put("user", () -> {
            sleep(200);
            return "alice";
        });
        tasks.put("orders", () -> {
            slowStarted.set(true);
            sleep(5_000);
            return java.util.List.of("o1");
        });

        long start = System.nanoTime();
        Map<String, Object> result = support.run(tasks, Duration.ofMillis(800), executor);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(slowStarted).isTrue();
        assertThat(result.get("user")).isEqualTo("alice");
        assertThat(result.get("orders")).isNull();
        assertThat(elapsedMs).isLessThan(3_000L);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
cd demo2
mvn -q -Dtest=ParallelQuerySupportTest test
```

Expected: 编译失败或测试失败（类/方法不存在）。

- [ ] **Step 3: 实现 `ParallelQuerySupport`**

```java
package com.jason.demo.demo2.parallel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Component
public class ParallelQuerySupport {

    private static final Logger log = LoggerFactory.getLogger(ParallelQuerySupport.class);

    public Map<String, Object> run(
            Map<String, Supplier<?>> namedTasks,
            Duration timeout,
            Executor executor) {
        if (namedTasks == null || namedTasks.isEmpty()) {
            return Map.of();
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }

        Map<String, CompletableFuture<Object>> futures = new LinkedHashMap<>();
        namedTasks.forEach((name, supplier) -> {
            CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return supplier.get();
                } catch (RuntimeException ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }, executor);
            futures.put(name, future);
        });

        CompletableFuture<Void> all = CompletableFuture.allOf(
                futures.values().toArray(CompletableFuture[]::new));
        try {
            all.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            log.warn("Parallel query wall-clock timeout after {}", timeout);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Parallel query wait interrupted");
        } catch (Exception ex) {
            // 单路失败会导致 allOf 异常完成；下面按路收集
            log.debug("Parallel allOf ended with exception (collecting per-task): {}",
                    ex.toString());
        }

        Map<String, Object> results = new LinkedHashMap<>();
        futures.forEach((name, future) -> results.put(name, resolve(name, future)));
        return results;
    }

    private static Object resolve(String name, CompletableFuture<Object> future) {
        if (!future.isDone()) {
            future.cancel(true);
            log.warn("Parallel task '{}' timed out; returning null", name);
            return null;
        }
        if (future.isCompletedExceptionally()) {
            try {
                future.join();
            } catch (Exception ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                log.warn("Parallel task '{}' failed; returning null: {}", name, cause.toString());
            }
            return null;
        }
        if (future.isCancelled()) {
            log.warn("Parallel task '{}' cancelled; returning null", name);
            return null;
        }
        return future.join();
    }
}
```

注意：用 `all.get(timeout)` 做墙钟等待；单路失败时不要让整个 `run` 抛给 Service。超时后对未完成 future `cancel(true)`。

- [ ] **Step 4: 跑测试确认通过**

```bash
cd demo2
mvn -q -Dtest=ParallelQuerySupportTest test
```

Expected: `BUILD SUCCESS`，3 个测试通过。

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/parallel/ParallelQuerySupport.java \
        demo2/src/test/java/com/jason/demo/demo2/parallel/ParallelQuerySupportTest.java
git commit -m "feat(demo2): add ParallelQuerySupport with partial-success semantics"
```

---

### Task 2: 配置属性 + 双 Executor Bean

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/parallel/ParallelProperties.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/parallel/ParallelExecutorConfig.java`
- Modify: `demo2/src/main/resources/application.properties`
- Create: `demo2/src/test/java/com/jason/demo/demo2/parallel/ParallelPropertiesBindingTest.java`

**Interfaces:**
- Produces:
  - `@ConfigurationProperties(prefix = "demo.parallel")` → `ParallelProperties`
  - 内嵌 `Jdk8`：`corePoolSize`、`maxPoolSize`、`keepAlive`、`queueCapacity`、`rejectedPolicy`
  - Bean 名：`parallelVirtualExecutor`、`parallelJdk8Executor`
  - `@PreDestroy`：两池 `shutdown`；JDK8 池 `awaitTermination(5, SECONDS)`

- [ ] **Step 1: 写属性绑定测试**

```java
package com.jason.demo.demo2.parallel;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ParallelPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableParallelProps.class);

    @EnableConfigurationProperties(ParallelProperties.class)
    static class EnableParallelProps {
    }

    @Test
    void bindsDefaultsWhenUnset() {
        runner.run(ctx -> {
            ParallelProperties props = ctx.getBean(ParallelProperties.class);
            assertThat(props.getTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(props.getJdk8().getCorePoolSize()).isEqualTo(0);
            assertThat(props.getJdk8().getMaxPoolSize()).isEqualTo(0);
            assertThat(props.getJdk8().getKeepAlive()).isEqualTo(Duration.ofSeconds(60));
            assertThat(props.getJdk8().getQueueCapacity()).isEqualTo(200);
            assertThat(props.getJdk8().getRejectedPolicy()).isEqualTo("caller_runs");
        });
    }

    @Test
    void bindsOverrides() {
        runner.withPropertyValues(
                "demo.parallel.timeout=2s",
                "demo.parallel.jdk8.core-pool-size=4",
                "demo.parallel.jdk8.max-pool-size=8",
                "demo.parallel.jdk8.queue-capacity=50",
                "demo.parallel.jdk8.rejected-policy=abort"
        ).run(ctx -> {
            ParallelProperties props = ctx.getBean(ParallelProperties.class);
            assertThat(props.getTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(props.getJdk8().getCorePoolSize()).isEqualTo(4);
            assertThat(props.getJdk8().getMaxPoolSize()).isEqualTo(8);
            assertThat(props.getJdk8().getQueueCapacity()).isEqualTo(50);
            assertThat(props.getJdk8().getRejectedPolicy()).isEqualTo("abort");
        });
    }
}
```

- [ ] **Step 2: 实现 `ParallelProperties`**

```java
package com.jason.demo.demo2.parallel;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "demo.parallel")
public class ParallelProperties {

    private Duration timeout = Duration.ofSeconds(3);
    private final Jdk8 jdk8 = new Jdk8();

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Jdk8 getJdk8() {
        return jdk8;
    }

    public static class Jdk8 {
        private int corePoolSize = 0;
        private int maxPoolSize = 0;
        private Duration keepAlive = Duration.ofSeconds(60);
        private int queueCapacity = 200;
        private String rejectedPolicy = "caller_runs";

        public int getCorePoolSize() { return corePoolSize; }
        public void setCorePoolSize(int corePoolSize) { this.corePoolSize = corePoolSize; }
        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
        public Duration getKeepAlive() { return keepAlive; }
        public void setKeepAlive(Duration keepAlive) { this.keepAlive = keepAlive; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
        public String getRejectedPolicy() { return rejectedPolicy; }
        public void setRejectedPolicy(String rejectedPolicy) { this.rejectedPolicy = rejectedPolicy; }
    }
}
```

- [ ] **Step 3: 实现 `ParallelExecutorConfig`**

```java
package com.jason.demo.demo2.parallel;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class ParallelExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(ParallelExecutorConfig.class);

    private ExecutorService virtualExecutor;
    private ThreadPoolExecutor jdk8Executor;
    private final ParallelProperties properties;

    public ParallelExecutorConfig(ParallelProperties properties) {
        this.properties = properties;
    }

    @Bean(name = "parallelVirtualExecutor")
    public ExecutorService parallelVirtualExecutor() {
        virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        return virtualExecutor;
    }

    @Bean(name = "parallelJdk8Executor")
    public ExecutorService parallelJdk8Executor() {
        int n = Runtime.getRuntime().availableProcessors();
        ParallelProperties.Jdk8 jdk8 = properties.getJdk8();
        int core = jdk8.getCorePoolSize() > 0 ? jdk8.getCorePoolSize() : n;
        int max = jdk8.getMaxPoolSize() > 0 ? jdk8.getMaxPoolSize() : core * 2;
        if (max < core) {
            max = core;
        }
        long keepAliveSeconds = Math.max(1L, jdk8.getKeepAlive().toSeconds());
        int capacity = Math.max(1, jdk8.getQueueCapacity());

        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "parallel-jdk8-" + seq.getAndIncrement());
                t.setDaemon(false);
                return t;
            }
        };

        jdk8Executor = new ThreadPoolExecutor(
                core,
                max,
                keepAliveSeconds,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity),
                factory,
                resolveHandler(jdk8.getRejectedPolicy()));
        log.info("parallelJdk8Executor core={}, max={}, queue={}, policy={}",
                core, max, capacity, jdk8.getRejectedPolicy());
        return jdk8Executor;
    }

    static RejectedExecutionHandler resolveHandler(String policy) {
        if (policy == null) {
            return new ThreadPoolExecutor.CallerRunsPolicy();
        }
        return switch (policy.trim().toLowerCase()) {
            case "abort" -> new ThreadPoolExecutor.AbortPolicy();
            case "discard" -> new ThreadPoolExecutor.DiscardPolicy();
            case "discard_oldest" -> new ThreadPoolExecutor.DiscardOldestPolicy();
            default -> new ThreadPoolExecutor.CallerRunsPolicy();
        };
    }

    @PreDestroy
    public void shutdown() {
        if (virtualExecutor != null) {
            virtualExecutor.shutdown();
        }
        if (jdk8Executor != null) {
            jdk8Executor.shutdown();
            try {
                if (!jdk8Executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    jdk8Executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                jdk8Executor.shutdownNow();
            }
        }
    }
}
```

硬性要求：此处 **不得** 调用 `Executors.newFixedThreadPool` / `newCachedThreadPool`。

- [ ] **Step 4: 追加 `application.properties`**

在 `demo2/src/main/resources/application.properties` 末尾追加：

```properties
# 并行查询聚合 Demo（virtual / jdk8）
demo.parallel.timeout=3s
demo.parallel.jdk8.core-pool-size=0
demo.parallel.jdk8.max-pool-size=0
demo.parallel.jdk8.keep-alive=60s
demo.parallel.jdk8.queue-capacity=200
demo.parallel.jdk8.rejected-policy=caller_runs
```

- [ ] **Step 5: 跑绑定测试**

```bash
cd demo2
mvn -q -Dtest=ParallelPropertiesBindingTest test
```

Expected: `BUILD SUCCESS`。

- [ ] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/parallel/ParallelProperties.java \
        demo2/src/main/java/com/jason/demo/demo2/parallel/ParallelExecutorConfig.java \
        demo2/src/main/resources/application.properties \
        demo2/src/test/java/com/jason/demo/demo2/parallel/ParallelPropertiesBindingTest.java
git commit -m "feat(demo2): add parallel executor config and properties"
```

---

### Task 3: Mock 查询 + DTO + `ParallelProfileService`

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/UserProfileDto.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/OrderDto.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/UserProfileAggregateResponse.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/parallel/MockUserQuery.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/parallel/MockOrderQuery.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/service/ParallelProfileService.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/service/ParallelProfileServiceTest.java`

**Interfaces:**
- Consumes: `ParallelQuerySupport.run(...)`；`ParallelProperties.getTimeout()`
- Produces:
  - `UserProfileAggregateResponse load(String userId, long userDelayMs, boolean userFail, long orderDelayMs, boolean orderFail, Executor executor)`
  - `record UserProfileAggregateResponse(UserProfileDto user, List<OrderDto> orders)`
  - `record UserProfileDto(String userId, String name)`
  - `record OrderDto(String orderId, double amount)`

- [ ] **Step 1: 写 Service 单测**

```java
package com.jason.demo.demo2.service;

import com.jason.demo.demo2.model.UserProfileAggregateResponse;
import com.jason.demo.demo2.parallel.MockOrderQuery;
import com.jason.demo.demo2.parallel.MockUserQuery;
import com.jason.demo.demo2.parallel.ParallelProperties;
import com.jason.demo.demo2.parallel.ParallelQuerySupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class ParallelProfileServiceTest {

    private ExecutorService executor;
    private ParallelProfileService service;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        ParallelProperties props = new ParallelProperties();
        props.setTimeout(Duration.ofMillis(800));
        service = new ParallelProfileService(
                new ParallelQuerySupport(),
                new MockUserQuery(),
                new MockOrderQuery(),
                props);
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void load_success_returnsUserAndOrders() {
        UserProfileAggregateResponse resp = service.load(
                "u1", 50, false, 50, false, executor);
        assertThat(resp.user()).isNotNull();
        assertThat(resp.user().userId()).isEqualTo("u1");
        assertThat(resp.orders()).isNotNull().isNotEmpty();
    }

    @Test
    void load_orderFails_userStillPresent() {
        UserProfileAggregateResponse resp = service.load(
                "u1", 50, false, 50, true, executor);
        assertThat(resp.user()).isNotNull();
        assertThat(resp.orders()).isNull();
    }

    @Test
    void load_orderTooSlow_ordersNullWithinBudget() {
        long start = System.nanoTime();
        UserProfileAggregateResponse resp = service.load(
                "u1", 50, false, 5_000, false, executor);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(resp.user()).isNotNull();
        assertThat(resp.orders()).isNull();
        assertThat(elapsedMs).isLessThan(3_000L);
    }
}
```

- [ ] **Step 2: 实现 DTO / Mock / Service**

`UserProfileDto.java`:

```java
package com.jason.demo.demo2.model;

public record UserProfileDto(String userId, String name) {
}
```

`OrderDto.java`:

```java
package com.jason.demo.demo2.model;

public record OrderDto(String orderId, double amount) {
}
```

`UserProfileAggregateResponse.java`:

```java
package com.jason.demo.demo2.model;

import java.util.List;

public record UserProfileAggregateResponse(UserProfileDto user, List<OrderDto> orders) {
}
```

`MockUserQuery.java`:

```java
package com.jason.demo.demo2.parallel;

import com.jason.demo.demo2.model.UserProfileDto;
import org.springframework.stereotype.Component;

@Component
public class MockUserQuery {

    public UserProfileDto find(String userId, long delayMs, boolean fail) {
        delay(delayMs);
        if (fail) {
            throw new IllegalStateException("mock user query failed");
        }
        String id = (userId == null || userId.isBlank()) ? "u1" : userId.strip();
        return new UserProfileDto(id, "Alice");
    }

    private static void delay(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
```

`MockOrderQuery.java`:

```java
package com.jason.demo.demo2.parallel;

import com.jason.demo.demo2.model.OrderDto;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MockOrderQuery {

    public List<OrderDto> findByUserId(String userId, long delayMs, boolean fail) {
        delay(delayMs);
        if (fail) {
            throw new IllegalStateException("mock order query failed");
        }
        String id = (userId == null || userId.isBlank()) ? "u1" : userId.strip();
        return List.of(new OrderDto("o-" + id + "-1", 99.0));
    }

    private static void delay(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
```

`ParallelProfileService.java`:

```java
package com.jason.demo.demo2.service;

import com.jason.demo.demo2.model.OrderDto;
import com.jason.demo.demo2.model.UserProfileAggregateResponse;
import com.jason.demo.demo2.model.UserProfileDto;
import com.jason.demo.demo2.parallel.MockOrderQuery;
import com.jason.demo.demo2.parallel.MockUserQuery;
import com.jason.demo.demo2.parallel.ParallelProperties;
import com.jason.demo.demo2.parallel.ParallelQuerySupport;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

@Service
public class ParallelProfileService {

    private final ParallelQuerySupport parallelQuerySupport;
    private final MockUserQuery mockUserQuery;
    private final MockOrderQuery mockOrderQuery;
    private final ParallelProperties properties;

    public ParallelProfileService(
            ParallelQuerySupport parallelQuerySupport,
            MockUserQuery mockUserQuery,
            MockOrderQuery mockOrderQuery,
            ParallelProperties properties) {
        this.parallelQuerySupport = parallelQuerySupport;
        this.mockUserQuery = mockUserQuery;
        this.mockOrderQuery = mockOrderQuery;
        this.properties = properties;
    }

    public UserProfileAggregateResponse load(
            String userId,
            long userDelayMs,
            boolean userFail,
            long orderDelayMs,
            boolean orderFail,
            Executor executor) {
        Map<String, Supplier<?>> tasks = new LinkedHashMap<>();
        tasks.put("user", () -> mockUserQuery.find(userId, userDelayMs, userFail));
        tasks.put("orders", () -> mockOrderQuery.findByUserId(userId, orderDelayMs, orderFail));

        Map<String, Object> raw = parallelQuerySupport.run(
                tasks, properties.getTimeout(), executor);

        UserProfileDto user = (UserProfileDto) raw.get("user");
        @SuppressWarnings("unchecked")
        List<OrderDto> orders = (List<OrderDto>) raw.get("orders");
        return new UserProfileAggregateResponse(user, orders);
    }
}
```

- [ ] **Step 3: 跑 Service 测试**

```bash
cd demo2
mvn -q -Dtest=ParallelProfileServiceTest test
```

Expected: `BUILD SUCCESS`。

- [ ] **Step 4: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/model/UserProfileDto.java \
        demo2/src/main/java/com/jason/demo/demo2/model/OrderDto.java \
        demo2/src/main/java/com/jason/demo/demo2/model/UserProfileAggregateResponse.java \
        demo2/src/main/java/com/jason/demo/demo2/parallel/MockUserQuery.java \
        demo2/src/main/java/com/jason/demo/demo2/parallel/MockOrderQuery.java \
        demo2/src/main/java/com/jason/demo/demo2/service/ParallelProfileService.java \
        demo2/src/test/java/com/jason/demo/demo2/service/ParallelProfileServiceTest.java
git commit -m "feat(demo2): add parallel profile mock queries and service"
```

---

### Task 4: Controller 双路径

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/controller/ParallelProfileController.java`

**Interfaces:**
- Consumes: `ParallelProfileService.load(...)`；`@Qualifier("parallelVirtualExecutor")` / `@Qualifier("parallelJdk8Executor")`
- Produces:
  - `GET /demo/parallel/virtual/user-profile`
  - `GET /demo/parallel/jdk8/user-profile`
  - Query：`userId`（默认 `u1`）、`userDelayMs`（默认 200）、`orderDelayMs`（默认 300）、`userFail` / `orderFail`（默认 false）

- [ ] **Step 1: 实现 Controller**

```java
package com.jason.demo.demo2.controller;

import com.jason.demo.demo2.model.UserProfileAggregateResponse;
import com.jason.demo.demo2.service.ParallelProfileService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.Executor;

@RestController
@RequestMapping("/demo/parallel")
public class ParallelProfileController {

    private final ParallelProfileService parallelProfileService;
    private final Executor parallelVirtualExecutor;
    private final Executor parallelJdk8Executor;

    public ParallelProfileController(
            ParallelProfileService parallelProfileService,
            @Qualifier("parallelVirtualExecutor") Executor parallelVirtualExecutor,
            @Qualifier("parallelJdk8Executor") Executor parallelJdk8Executor) {
        this.parallelProfileService = parallelProfileService;
        this.parallelVirtualExecutor = parallelVirtualExecutor;
        this.parallelJdk8Executor = parallelJdk8Executor;
    }

    @GetMapping("/virtual/user-profile")
    public UserProfileAggregateResponse virtualProfile(
            @RequestParam(defaultValue = "u1") String userId,
            @RequestParam(defaultValue = "200") long userDelayMs,
            @RequestParam(defaultValue = "300") long orderDelayMs,
            @RequestParam(defaultValue = "false") boolean userFail,
            @RequestParam(defaultValue = "false") boolean orderFail) {
        return parallelProfileService.load(
                userId, userDelayMs, userFail, orderDelayMs, orderFail,
                parallelVirtualExecutor);
    }

    @GetMapping("/jdk8/user-profile")
    public UserProfileAggregateResponse jdk8Profile(
            @RequestParam(defaultValue = "u1") String userId,
            @RequestParam(defaultValue = "200") long userDelayMs,
            @RequestParam(defaultValue = "300") long orderDelayMs,
            @RequestParam(defaultValue = "false") boolean userFail,
            @RequestParam(defaultValue = "false") boolean orderFail) {
        return parallelProfileService.load(
                userId, userDelayMs, userFail, orderDelayMs, orderFail,
                parallelJdk8Executor);
    }
}
```

- [ ] **Step 2: 编译确认**

```bash
cd demo2
mvn -q -DskipTests compile
```

Expected: `BUILD SUCCESS`。

- [ ] **Step 3: 跑本特性相关测试**

```bash
cd demo2
mvn -q -Dtest=ParallelQuerySupportTest,ParallelPropertiesBindingTest,ParallelProfileServiceTest test
```

Expected: 全部通过。

- [ ] **Step 4: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/controller/ParallelProfileController.java
git commit -m "feat(demo2): expose virtual and jdk8 parallel user-profile demos"
```

---

### Task 5: 手工验收（成功标准对照）

**Files:** 无代码变更（除非发现缺陷再修）

- [ ] **Step 1: 启动应用**

```bash
cd demo2
mvn -q spring-boot:run
```

- [ ] **Step 2: 正常路径**

```bash
curl -s "http://localhost:8081/demo/parallel/virtual/user-profile?userId=u1"
curl -s "http://localhost:8081/demo/parallel/jdk8/user-profile?userId=u1"
```

Expected: HTTP 200；`user` 与 `orders` 均非 null。

- [ ] **Step 3: 订单失败**

```bash
curl -s "http://localhost:8081/demo/parallel/virtual/user-profile?userId=u1&orderFail=true"
```

Expected: `user` 有值，`orders` 为 `null`；HTTP 200。

- [ ] **Step 4: 订单超时**

```bash
curl -s -w "\nhttp_code=%{http_code} time=%{time_total}\n" \
  "http://localhost:8081/demo/parallel/jdk8/user-profile?userId=u1&orderDelayMs=4000&userDelayMs=200"
```

Expected: 约 3s 内返回；`user` 有值，`orders` 为 `null`；HTTP 200；日志有超时 warn。

- [ ] **Step 5: 有缺陷则修复并补测后另开 commit；无缺陷则本 Task 无需 commit**

---

## Spec coverage checklist

| Spec 要求 | Task |
|-----------|------|
| `ParallelQuerySupport` 多路 + 墙钟超时 + null | Task 1 |
| 部分成功 / 日志不回传前端 | Task 1、3、4 |
| 虚拟线程 Demo 路径 | Task 2、4 |
| JDK8 `ThreadPoolExecutor`（非 fixed pool） | Task 2、4 |
| CPU sizing / 队列 / 拒绝策略可配 | Task 2 |
| Mock + query 演示参数 | Task 3、4 |
| 扁平 `{user,orders}` HTTP 200 | Task 3、4 |
| Support 单测三类场景 | Task 1 |
| 成功标准手工验收 | Task 5 |
| 不迁移 MultiAgentService | 全局约束（无 Task） |
