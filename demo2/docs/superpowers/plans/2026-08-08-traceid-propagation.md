# TraceId 传播与日志关联 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 HTTP / RocketMQ / 延时任务执行路径的日志稳定带上 `traceId`，且「下单 → 发 MQ → 监听」共用同一 `traceId`。

**Architecture:** 新增 `TraceSupport`（统一开 span）与 `RocketMqTracePropagator`（W3C `traceparent` 注入/提取）；在 `BaseEventPublisher` 与抽象 Listener 接入传播；`DelayTaskExecutor#execute` 用 `TraceSupport` 包一层（有父则 child，无则新根）。HTTP 侧核对 Observation→MDC，禁止假 UUID。

**Tech Stack:** Spring Boot 4.1、Java 21、Micrometer Tracing + `spring-boot-starter-opentelemetry`、RocketMQ 原生 `Message` user properties、JUnit 5 + Mockito；测试依赖 `micrometer-tracing-test`（`SimpleTracer`）

**Spec:** [2026-08-08-traceid-propagation-design.md](../specs/2026-08-08-traceid-propagation-design.md)

## Global Constraints

- 模块仅限 `demo2`；不改 `demo` 工程
- **不**修改 `delay_task` 表 / 不落库 `create_trace_id`
- **不**在 Redisson 队列载荷中传播 trace（本次）
- Tracing 失败只 warn，不得改变下单 / 发消息 / 消费状态 / 延时执行业务语义
- 禁止用 `MDC.put` 随机 UUID 冒充 `traceId`
- MQ 传播格式：仅 W3C **`traceparent`**（由 Micrometer `Propagator` 写入；不另造私有键）
- 调用方入口日志（MqListener / Scanner / Redisson）保留；tracing 不放到调用方
- 测试环境无真实 OTel：用 `SimpleTracer`；`DelayTaskExecutor` 构造增加 `TraceSupport`，现有单测必须同步改构造

---

## File Structure

| 文件 | 职责 |
|------|------|
| `.../framework/trace/TraceSupport.java` | `runInSpan(name, runnable)`：有父 child、无父新根；保证 finally end |
| `.../framework/rocketmq/RocketMqTracePropagator.java` | Message inject / MessageExt extract+scope |
| `.../framework/rocketmq/producer/BaseEventPublisher.java` | `buildMessage` 后 inject；async 可用 ContextSnapshot |
| `.../framework/rocketmq/AbstractConcurrentlyRocketListener.java` | consume 外包 extract 或新根 |
| `.../framework/rocketmq/AbstractOrderlyRocketListener.java` | 同上 |
| `.../framework/delay/DelayTaskExecutor.java` | `execute` 外包 `TraceSupport.runInSpan` |
| `demo2/pom.xml` | test 依赖 `micrometer-tracing-test` |
| `.../framework/trace/TraceSupportTest.java` | TraceSupport 单测 |
| `.../framework/rocketmq/RocketMqTracePropagatorTest.java` | inject/extract 行为 |
| `.../framework/delay/DelayTaskExecutorTest.java` | 构造补 `TraceSupport`；回归原断言 |
| `demo2/src/main/resources/application.properties` | 仅当 HTTP MDC 确缺时补官方配置项 |

---

### Task 1: TraceSupport + 测试依赖

**Files:**

- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/trace/TraceSupport.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/framework/trace/TraceSupportTest.java`
- Modify: `demo2/pom.xml`（test scope 增加 `micrometer-tracing-test`）

**Interfaces:**

- Produces: `TraceSupport(Tracer tracer)`；`void runInSpan(String name, Runnable action)`
  - `tracer.nextSpan().name(name).start()` → `tracer.withSpan(span)` → `action` → `span.end()`
  - `action` 抛异常时仍 end span，异常原样抛出

- [ ] **Step 1: 在 pom.xml 增加测试依赖**

在 `demo2/pom.xml` 的 `<dependencies>` 中、靠近 `spring-boot-starter-test` 处加入：

```xml
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-tracing-test</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Write the failing test**

```java
package com.jason.demo.demo2.framework.trace;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceSupportTest {

    @Test
    void runInSpan_setsCurrentTraceAndClearsAfter() {
        SimpleTracer tracer = new SimpleTracer();
        TraceSupport support = new TraceSupport(tracer);
        AtomicReference<String> inside = new AtomicReference<>();

        support.runInSpan("test.span", () -> {
            assertThat(tracer.currentSpan()).isNotNull();
            inside.set(tracer.currentSpan().context().traceId());
        });

        assertThat(inside.get()).isNotBlank();
        assertThat(tracer.currentSpan()).isNull();
    }

    @Test
    void runInSpan_rethrowsAndStillEndsSpan() {
        SimpleTracer tracer = new SimpleTracer();
        TraceSupport support = new TraceSupport(tracer);

        assertThatThrownBy(() -> support.runInSpan("boom", () -> {
            throw new IllegalStateException("x");
        })).isInstanceOf(IllegalStateException.class).hasMessage("x");

        assertThat(tracer.currentSpan()).isNull();
    }

    @Test
    void runInSpan_withParent_createsChildSameTraceId() {
        SimpleTracer tracer = new SimpleTracer();
        TraceSupport support = new TraceSupport(tracer);
        AtomicReference<String> parentTrace = new AtomicReference<>();
        AtomicReference<String> childTrace = new AtomicReference<>();

        support.runInSpan("parent", () -> {
            parentTrace.set(tracer.currentSpan().context().traceId());
            support.runInSpan("child", () ->
                    childTrace.set(tracer.currentSpan().context().traceId()));
        });

        assertThat(childTrace.get()).isEqualTo(parentTrace.get());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=TraceSupportTest test
```

Expected: FAIL（`TraceSupport` 不存在或编译失败）

- [ ] **Step 4: Implement TraceSupport**

```java
package com.jason.demo.demo2.framework.trace;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;

/**
 * 统一开 span：已有父上下文时为 child，否则为新根；保证 finally end，避免线程 MDC 泄漏。
 */
@Component
public class TraceSupport {

    private final Tracer tracer;

    public TraceSupport(Tracer tracer) {
        this.tracer = tracer;
    }

    public void runInSpan(String name, Runnable action) {
        Span span = tracer.nextSpan().name(name).start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            action.run();
        } finally {
            span.end();
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=TraceSupportTest test
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add demo2/pom.xml \
  demo2/src/main/java/com/jason/demo/demo2/framework/trace/TraceSupport.java \
  demo2/src/test/java/com/jason/demo/demo2/framework/trace/TraceSupportTest.java
git commit -m "feat(demo2): add TraceSupport for span-scoped execution"
```

---

### Task 2: RocketMqTracePropagator

**Files:**

- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/rocketmq/RocketMqTracePropagator.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/framework/rocketmq/RocketMqTracePropagatorTest.java`

**Interfaces:**

- Consumes: `Tracer`、`Propagator`（Micrometer）
- Produces:
  - `void inject(Message message)` — 无当前 context 则 no-op；异常只 warn
  - `void runWithExtractedOrNew(MessageExt message, String spanName, Runnable action)` — extract 后开 span；异常则新根；finally end

- [ ] **Step 1: Write the failing test**

```java
package com.jason.demo.demo2.framework.rocketmq;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RocketMqTracePropagatorTest {

    @Test
    void inject_writesViaPropagator_whenContextPresent() {
        SimpleTracer tracer = new SimpleTracer();
        Propagator propagator = mock(Propagator.class);
        RocketMqTracePropagator mq = new RocketMqTracePropagator(tracer, propagator);

        Span parent = tracer.nextSpan().name("parent").start();
        try (Tracer.SpanInScope scope = tracer.withSpan(parent)) {
            Message message = new Message("t", "body".getBytes());
            mq.inject(message);
            verify(propagator).inject(eq(parent.context()), eq(message), any());
        } finally {
            parent.end();
        }
    }

    @Test
    void inject_noop_whenNoContext() {
        SimpleTracer tracer = new SimpleTracer();
        Propagator propagator = mock(Propagator.class);
        RocketMqTracePropagator mq = new RocketMqTracePropagator(tracer, propagator);

        mq.inject(new Message("t", "body".getBytes()));
        verifyNoInteractions(propagator);
    }

    @Test
    void runWithExtractedOrNew_withoutHeaders_stillRunsUnderSpan() {
        SimpleTracer tracer = new SimpleTracer();
        Propagator propagator = mock(Propagator.class);
        when(propagator.extract(any(), any())).thenReturn(tracer.spanBuilder());

        RocketMqTracePropagator mq = new RocketMqTracePropagator(tracer, propagator);
        MessageExt ext = new MessageExt();
        AtomicBoolean ran = new AtomicBoolean();
        AtomicReference<Span> inside = new AtomicReference<>();

        mq.runWithExtractedOrNew(ext, "rocketmq.consume", () -> {
            ran.set(true);
            inside.set(tracer.currentSpan());
        });

        assertThat(ran.get()).isTrue();
        assertThat(inside.get()).isNotNull();
        assertThat(tracer.currentSpan()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn -f demo2/pom.xml -Dtest=RocketMqTracePropagatorTest test
```

Expected: FAIL（类不存在）

- [ ] **Step 3: Implement RocketMqTracePropagator**

```java
package com.jason.demo.demo2.framework.rocketmq;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RocketMqTracePropagator {

    private final Tracer tracer;
    private final Propagator propagator;

    public RocketMqTracePropagator(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public void inject(Message message) {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return;
        }
        try {
            propagator.inject(context, message, (carrier, key, value) -> {
                if (key != null && value != null) {
                    carrier.putUserProperty(key, value);
                }
            });
        } catch (Exception e) {
            log.warn("rocketmq trace inject failed", e);
        }
    }

    public void runWithExtractedOrNew(MessageExt message, String spanName, Runnable action) {
        Span span;
        try {
            Span.Builder builder = propagator.extract(message, (carrier, key) -> {
                if (carrier == null || key == null) {
                    return null;
                }
                return carrier.getUserProperty(key);
            });
            span = builder.name(spanName).start();
        } catch (Exception e) {
            log.warn("rocketmq trace extract failed, starting new root span", e);
            span = tracer.nextSpan().name(spanName).start();
        }
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            action.run();
        } finally {
            span.end();
        }
    }
}
```

注意：若运行期缺少 `Propagator` Bean 导致启动失败，用 `mvn -f demo2/pom.xml -DskipTests compile` 与启动日志确认；Boot + `spring-boot-starter-opentelemetry` 通常会自动配置。若缺失，在 `framework/trace` 增加 `@Configuration` 暴露官方 `Propagator`，禁止手写假 ID。

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn -f demo2/pom.xml -Dtest=RocketMqTracePropagatorTest test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/framework/rocketmq/RocketMqTracePropagator.java \
  demo2/src/test/java/com/jason/demo/demo2/framework/rocketmq/RocketMqTracePropagatorTest.java
git commit -m "feat(demo2): add RocketMqTracePropagator for W3C trace headers"
```

---

### Task 3: BaseEventPublisher 注入 trace

**Files:**

- Modify: `demo2/src/main/java/com/jason/demo/demo2/framework/rocketmq/producer/BaseEventPublisher.java`

**Interfaces:**

- Consumes: `RocketMqTracePropagator`（经 `ApplicationContext.getBeanProvider(...).getIfAvailable()`）
- Produces: 所有经 `buildMessage` 发出的 `Message` 在有当前上下文时带传播头

- [ ] **Step 1: initialize 解析 propagator；buildMessage 末尾 inject**

字段：

```java
    private RocketMqTracePropagator tracePropagator;
```

`initialize()` 末尾：

```java
        this.tracePropagator = applicationContext.getBeanProvider(RocketMqTracePropagator.class).getIfAvailable();
```

`buildMessage` 在 return 前：

```java
            if (tracePropagator != null) {
                tracePropagator.inject(message);
            }
            return message;
```

- [ ] **Step 2:（推荐）async 回调保上下文**

在 `sendAsync` 提交 `producer.send` 前捕获 snapshot，回调里恢复，使 success/error 日志有 `traceId`。

```java
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
```

```java
            ContextSnapshot snapshot = ContextSnapshotFactory.builder().build().captureAll();
            try {
                producer.send(message, new SendCallback() {
                    @Override
                    public void onSuccess(SendResult sendResult) {
                        try (ContextSnapshot.Scope scope = snapshot.setThreadLocals()) {
                            log.info("async send success, result:{}", sendResult);
                        }
                    }

                    @Override
                    public void onException(Throwable e) {
                        try (ContextSnapshot.Scope scope = snapshot.setThreadLocals()) {
                            log.error("async send error, message:{}", messageBodyObj, e);
                        }
                    }
                });
            } catch (Exception e) {
                log.error("async send submit error, message:{}", messageBodyObj, e);
            }
```

若 `ContextSnapshotFactory` 编译不过，可改用当前 Boot 传递依赖中的等价 API；仍失败则跳过本步，只保留 inject。同步 `send` 不受影响。

- [ ] **Step 3: Compile**

```bash
mvn -f demo2/pom.xml -DskipTests compile
```

Expected: SUCCESS

- [ ] **Step 4: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/framework/rocketmq/producer/BaseEventPublisher.java
git commit -m "feat(demo2): inject trace context into RocketMQ messages"
```

---

### Task 4: 抽象 Listener 提取 / 新开 span

**Files:**

- Modify: `demo2/src/main/java/com/jason/demo/demo2/framework/rocketmq/AbstractConcurrentlyRocketListener.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/framework/rocketmq/AbstractOrderlyRocketListener.java`

**Interfaces:**

- Consumes: `RocketMqTracePropagator`（父类 `@Autowired(required = false)` setter，具体 `@Component` Listener 会被注入）
- Produces: `preReceiveMessage` / `doReceiveMessage` / 业务日志均在 span 内

- [ ] **Step 1: 改 AbstractConcurrentlyRocketListener**

增加：

```java
    private RocketMqTracePropagator tracePropagator;

    @Autowired(required = false)
    public void setTracePropagator(RocketMqTracePropagator tracePropagator) {
        this.tracePropagator = tracePropagator;
    }
```

将消费逻辑拆出 `consumeWithoutTrace`，`consumeMessage` 在 propagator 非空时外包：

```java
    @Override
    public ConsumeConcurrentlyStatus consumeMessage(
            List<MessageExt> msgs, ConsumeConcurrentlyContext context) {
        if (msgs == null || msgs.isEmpty()) {
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }
        MessageExt messageExt = msgs.getFirst();
        if (tracePropagator == null) {
            return consumeWithoutTrace(messageExt);
        }
        AtomicReference<ConsumeConcurrentlyStatus> status =
                new AtomicReference<>(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        tracePropagator.runWithExtractedOrNew(messageExt, "rocketmq.consume", () ->
                status.set(consumeWithoutTrace(messageExt)));
        return status.get();
    }

    private ConsumeConcurrentlyStatus consumeWithoutTrace(MessageExt messageExt) {
        try {
            preReceiveMessage(messageExt);
            return doReceiveMessage(messageExt);
        } catch (RuntimeException e) {
            log.error("接收消息异常", e);
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        } catch (Exception e) {
            log.error("处理消息异常", e);
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        } finally {
            postReceiveMessage(messageExt);
        }
    }
```

需要 `import java.util.concurrent.atomic.AtomicReference;` 与 `org.springframework.beans.factory.annotation.Autowired`。

- [ ] **Step 2: 同样改 AbstractOrderlyRocketListener**

镜像结构：失败返回 `SUSPEND_CURRENT_QUEUE_A_MOMENT`，成功 `SUCCESS`；span 名 `"rocketmq.consume"`。

- [ ] **Step 3: Compile**

```bash
mvn -f demo2/pom.xml -DskipTests compile
```

Expected: SUCCESS

- [ ] **Step 4: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/framework/rocketmq/AbstractConcurrentlyRocketListener.java \
  demo2/src/main/java/com/jason/demo/demo2/framework/rocketmq/AbstractOrderlyRocketListener.java
git commit -m "feat(demo2): restore or start trace span in RocketMQ listeners"
```

---

### Task 5: DelayTaskExecutor 包 span

**Files:**

- Modify: `demo2/src/main/java/com/jason/demo/demo2/framework/delay/DelayTaskExecutor.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/framework/delay/DelayTaskExecutorTest.java`

**Interfaces:**

- Consumes: `TraceSupport`
- Produces: `execute(long)` 整段在 `delay.task.execute` span 内

- [ ] **Step 1: 改构造与 execute**

构造增加 `TraceSupport traceSupport` 并保存字段。

```java
    public void execute(long taskId) {
        traceSupport.runInSpan("delay.task.execute", () -> doExecuteUnderLock(taskId));
    }

    private void doExecuteUnderLock(long taskId) {
        String lockKey = "delay:task:" + taskId;
        long expireMs = properties.getLockTimeout().toMillis();
        LockInfo lockInfo = lockTemplate.lock(lockKey, expireMs, 0L);
        if (lockInfo == null) {
            log.debug("skip delay task, lock not acquired, taskId={}", taskId);
            return;
        }
        try {
            doExecute(taskId);
        } finally {
            try {
                lockTemplate.releaseLock(lockInfo);
            } catch (Exception e) {
                log.warn("release delay task lock failed, taskId={}", taskId, e);
            }
        }
    }
```

- [ ] **Step 2: 更新 DelayTaskExecutorTest**

```java
        TraceSupport traceSupport = new TraceSupport(new SimpleTracer());
        executor = new DelayTaskExecutor(repository, lockTemplate, properties, List.of(handler), traceSupport);
```

```java
import com.jason.demo.demo2.framework.trace.TraceSupport;
import io.micrometer.tracing.test.simple.SimpleTracer;
```

- [ ] **Step 3: Run tests**

```bash
mvn -f demo2/pom.xml -Dtest=DelayTaskExecutorTest,FallbackScannerTest,TraceSupportTest test
```

Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/framework/delay/DelayTaskExecutor.java \
  demo2/src/test/java/com/jason/demo/demo2/framework/delay/DelayTaskExecutorTest.java
git commit -m "feat(demo2): wrap DelayTaskExecutor.execute in tracing span"
```

---

### Task 6: HTTP Observation → MDC 核对与修复

**Files:**

- Possibly modify: `demo2/src/main/resources/application.properties`
- Possibly create: `demo2/src/test/java/com/jason/demo/demo2/config/HttpTraceIdSmokeTest.java`（仅当需要自动化时）

**Interfaces:**

- Produces: HTTP 请求处理线程有非空 `traceId`；`TraceIdFilter` 仍写 `X-Trace-Id`

- [ ] **Step 1: 核对现有配置与依赖**

确认已存在：`spring-boot-starter-opentelemetry`、`logging.pattern.level` 含 `%X{traceId:-}`、`management.tracing.sampling.probability=1.0`。

启动后调任意业务接口，看日志是否为 `[app,<traceId>,<spanId>]` 而非空 trace。

- [ ] **Step 2: 若日志为空 — 按官方方式修**

1. 确认 `management.tracing.enabled` 未关
2. 确认未把全局 `ObservationRegistry` 盖成 NOOP（向量库懒加载除外）
3. 仅当默认被关时再开 HTTP server observation 相关配置
4. 确认运行时有 `Tracer` / `Propagator` Bean

禁止新增「生成 UUID 写入 MDC」的 Filter。

- [ ] **Step 3:（可选）Smoke 测试**

`@SpringBootTest` + MockMvc 断言请求内 `Tracer` 有 context；若过重可跳过，以 Task 7 手工验收为准。

- [ ] **Step 4: Commit（仅当有变更）**

```bash
git add demo2/src/main/resources/application.properties
git commit -m "fix(demo2): ensure HTTP tracing populates log MDC"
```

无变更则跳过。

---

### Task 7: 手工验收清单

- [ ] **Step 1: HTTP** — 业务接口日志非空 `traceId`；开关开时有 `X-Trace-Id`
- [ ] **Step 2: 下单 → MQ** — 生产与消费日志同一 `traceId`
- [ ] **Step 3: 延时任务 MQ** — Listener / Executor 与上游同 `traceId`（或 child）
- [ ] **Step 4: Scanner / Redisson** — 仍有 `traceId`（可为新根）
- [ ] **Step 5: 无头消息** — 消费成功且日志有新 `traceId`
- [ ] **Step 6: 回归**

```bash
mvn -f demo2/pom.xml -Dtest=TraceSupportTest,RocketMqTracePropagatorTest,DelayTaskExecutorTest,FallbackScannerTest test
```

Expected: PASS

- [ ] **Step 7: 验收中有小修则再 commit；无则结束**

---

## Spec Coverage Checklist

| Spec 项 | Task |
|---------|------|
| HTTP 日志有 `traceId` | Task 6、7 |
| MQ 同 `traceId` | Task 2–4、7 |
| DelayTaskExecutor child / 新根 | Task 1、5、7 |
| 不落库 / 不改表 | 全任务 |
| Redisson 不传头、新根兜底 | Task 5、7 |
| 传播失败不影响业务 | Task 2–4 |
| 禁止假 UUID | Task 6 |
| 调用方入口日志保留 | 不改三处调用方 tracing |
| 单测 | Task 1、2、5 |
| async ContextSnapshot | Task 3 Step 2 |

## Self-Review Notes

- 无 TBD；`Propagator` Bean 缺失处理写在 Task 2。
- `DelayTaskExecutor` 构造与测试同步在 Task 5。
- API 名前后一致：`runInSpan` / `inject` / `runWithExtractedOrNew`。
