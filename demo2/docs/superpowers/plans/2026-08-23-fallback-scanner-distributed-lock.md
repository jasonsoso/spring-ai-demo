# FallbackScanner 扫描级分布式锁 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `FallbackScanner#scan` 增加扫描级分布式锁，多节点部署时同一周期仅一个节点执行 `findDuePending`，同时保留 Executor 层 per-taskId 锁 + CAS 不变。

**Architecture:** 在 `scan()` 入口通过 lock4j `LockTemplate` 尝试获取全局锁 `delay:scanner:fallback`；持锁节点执行现有扫描逻辑，未持锁节点 debug 跳过。`scan-lock-enabled=false` 时行为与改动前一致。Executor 层 `delay:task:{taskId}` 锁不变，形成「扫描互斥 + 执行互斥」双层防护。

**Tech Stack:** Spring Boot 4.1、Java 21、lock4j + Redisson（已有）、JUnit 5 + Mockito

**Spec:** [2026-08-23-fallback-scanner-distributed-lock-design.md](../specs/2026-08-23-fallback-scanner-distributed-lock-design.md)

## Global Constraints

- 模块仅限 `demo2`；不改 `DelayTaskExecutor` 锁逻辑
- 复用现有 `LockTemplate`（lock4j + Redisson）；不引入 ShedLock 等新依赖
- 锁 Key：`delay:scanner:fallback`，常量放 `LockKeys.delayScannerFallbackKey()`
- 默认 `app.delay.scan-lock-enabled=true`；`app.delay.scan-lock-timeout=10s`
- 抢扫描锁失败：`debug` 日志 + return；释放锁失败：`warn` 日志，不抛异常
- 公共能力在 `com.jason.demo.demo2.framework.delay.*`；锁 Key 在 `com.jason.demo.demo2.lock.LockKeys`

---

## File Structure

| 文件 | 职责 |
|------|------|
| `demo2/.../lock/LockKeys.java` | 新增 `delayScannerFallbackKey()` |
| `demo2/.../framework/delay/config/DelayProperties.java` | 新增 `scanLockEnabled`、`scanLockTimeout` |
| `demo2/.../framework/delay/FallbackScanner.java` | 注入 `LockTemplate`；扫描入口加锁；提取 `doScan()` |
| `demo2/src/main/resources/application.properties` | 新增 `app.delay.scan-lock-*` 配置 |
| `demo2/src/test/.../lock/LockKeysTest.java` | 断言 scanner 锁 Key |
| `demo2/src/test/.../framework/delay/FallbackScannerTest.java` | 持锁/不持锁/关闭锁/异常释放 |

---

### Task 1: LockKeys + DelayProperties + 配置

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/lock/LockKeys.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/framework/delay/config/DelayProperties.java`
- Modify: `demo2/src/main/resources/application.properties`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/lock/LockKeysTest.java`

**Interfaces:**
- Produces: `LockKeys.delayScannerFallbackKey()` → `"delay:scanner:fallback"`
- Produces: `DelayProperties.isScanLockEnabled()` → `boolean`（默认 `true`）
- Produces: `DelayProperties.getScanLockTimeout()` → `Duration`（默认 `10s`）

- [ ] **Step 1: 写 LockKeys 失败测试**

在 `LockKeysTest.java` 末尾追加：

```java
@Test
void delayScannerFallbackKey_isStable() {
    assertThat(LockKeys.delayScannerFallbackKey()).isEqualTo("delay:scanner:fallback");
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -f demo2/pom.xml -Dtest=LockKeysTest#delayScannerFallbackKey_isStable test`

Expected: FAIL — `delayScannerFallbackKey()` 方法不存在

- [ ] **Step 3: 实现 LockKeys + DelayProperties + application.properties**

`LockKeys.java` 末尾（`devAgentAskKey` 之后）追加：

```java
public static String delayScannerFallbackKey() {
    return "delay:scanner:fallback";
}
```

`DelayProperties.java` 在 `scanBatchSize` 字段之后追加：

```java
/** 是否启用 FallbackScanner 扫描级分布式锁（多节点互斥） */
private boolean scanLockEnabled = true;

/** 扫描锁 TTL，应 ≥ 单次扫描最大耗时 */
private Duration scanLockTimeout = Duration.ofSeconds(10);
```

`application.properties` 在 `app.delay.scan-batch-size=50` 之后追加：

```properties
# FallbackScanner 多节点扫描互斥（lock4j）；单节点/debug 可设 false
app.delay.scan-lock-enabled=true
app.delay.scan-lock-timeout=10s
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -f demo2/pom.xml -Dtest=LockKeysTest test`

Expected: BUILD SUCCESS，含 `delayScannerFallbackKey_isStable`

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/lock/LockKeys.java \
        demo2/src/main/java/com/jason/demo/demo2/framework/delay/config/DelayProperties.java \
        demo2/src/main/resources/application.properties \
        demo2/src/test/java/com/jason/demo/demo2/lock/LockKeysTest.java
git commit -m "feat(demo2): add FallbackScanner scan-lock config and LockKeys"
```

---

### Task 2: FallbackScanner 扫描锁 + 单测

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/framework/delay/FallbackScanner.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/framework/delay/FallbackScannerTest.java`

**Interfaces:**
- Consumes: `LockKeys.delayScannerFallbackKey()`、`DelayProperties.isScanLockEnabled()`、`DelayProperties.getScanLockTimeout().toMillis()`
- Consumes: `LockTemplate.lock(String key, long expireMs, long acquireTimeoutMs)` → `LockInfo | null`
- Consumes: `LockTemplate.releaseLock(LockInfo lockInfo)`
- Produces: `FallbackScanner(DelayTaskRepository, DelayTaskExecutor, DelayProperties, LockTemplate)` 构造器

- [ ] **Step 1: 重写 FallbackScannerTest（TDD — 先写全部失败测试）**

完整替换 `FallbackScannerTest.java`：

```java
package com.jason.demo.demo2.framework.delay;

import com.baomidou.lock.LockInfo;
import com.baomidou.lock.LockTemplate;
import com.jason.demo.demo2.framework.delay.config.DelayProperties;
import com.jason.demo.demo2.framework.delay.repository.DelayTaskEntity;
import com.jason.demo.demo2.framework.delay.repository.DelayTaskRepository;
import com.jason.demo.demo2.lock.LockKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FallbackScannerTest {

    @Mock
    private DelayTaskRepository repository;
    @Mock
    private DelayTaskExecutor executor;
    @Mock
    private LockTemplate lockTemplate;
    @Mock
    private LockInfo lockInfo;

    private DelayProperties properties;

    @BeforeEach
    void setUp() {
        properties = new DelayProperties();
        properties.setScanBatchSize(50);
        properties.setScanLockEnabled(true);
        properties.setScanLockTimeout(Duration.ofSeconds(10));
    }

    private FallbackScanner newScanner() {
        return new FallbackScanner(repository, executor, properties, lockTemplate);
    }

    @Test
    void scan_lockDisabled_executesWithoutLockTemplate() {
        properties.setScanLockEnabled(false);
        DelayTaskEntity task = task(1L);
        when(repository.findDuePending(any(Instant.class), eq(50))).thenReturn(List.of(task));

        newScanner().scan();

        verifyNoInteractions(lockTemplate);
        verify(executor).execute(1L);
    }

    @Test
    void scan_lockAcquired_executesAllDueTasksAndReleasesLock() {
        when(lockTemplate.lock(eq(LockKeys.delayScannerFallbackKey()), eq(10_000L), eq(0L)))
                .thenReturn(lockInfo);
        DelayTaskEntity a = task(1L);
        DelayTaskEntity b = task(2L);
        when(repository.findDuePending(any(Instant.class), eq(50))).thenReturn(List.of(a, b));

        newScanner().scan();

        verify(executor).execute(1L);
        verify(executor).execute(2L);
        verify(lockTemplate).releaseLock(lockInfo);
    }

    @Test
    void scan_lockNotAcquired_skipsScan() {
        when(lockTemplate.lock(anyString(), anyLong(), anyLong())).thenReturn(null);

        newScanner().scan();

        verify(repository, never()).findDuePending(any(), anyInt());
        verify(executor, never()).execute(anyLong());
        verify(lockTemplate, never()).releaseLock(any());
    }

    @Test
    void scan_repositoryThrows_stillReleasesLock() {
        when(lockTemplate.lock(anyString(), anyLong(), anyLong())).thenReturn(lockInfo);
        when(repository.findDuePending(any(Instant.class), anyInt()))
                .thenThrow(new RuntimeException("db down"));

        newScanner().scan();

        verify(lockTemplate).releaseLock(lockInfo);
    }

    private static DelayTaskEntity task(long taskId) {
        DelayTaskEntity entity = new DelayTaskEntity();
        entity.setTaskId(taskId);
        return entity;
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -f demo2/pom.xml -Dtest=FallbackScannerTest test`

Expected: FAIL — 构造器签名不匹配（缺少 `LockTemplate` 参数）

- [ ] **Step 3: 实现 FallbackScanner**

完整替换 `FallbackScanner.java`：

```java
package com.jason.demo.demo2.framework.delay;

import com.baomidou.lock.LockInfo;
import com.baomidou.lock.LockTemplate;
import com.jason.demo.demo2.framework.delay.config.DelayProperties;
import com.jason.demo.demo2.framework.delay.repository.DelayTaskEntity;
import com.jason.demo.demo2.framework.delay.repository.DelayTaskRepository;
import com.jason.demo.demo2.lock.LockKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * MySQL 台账扫描兜底：主路径（Redisson/RocketMQ）丢消息或投递失败时，仍能捞起到期 PENDING 任务执行。
 * <p>多节点下通过扫描级分布式锁避免重复扫 DB；单任务执行仍由 {@link DelayTaskExecutor} 按 taskId 防重。
 */
@Slf4j
@Component
public class FallbackScanner {

    private final DelayTaskRepository repository;
    private final DelayTaskExecutor executor;
    private final DelayProperties properties;
    private final LockTemplate lockTemplate;

    public FallbackScanner(
            DelayTaskRepository repository,
            DelayTaskExecutor executor,
            DelayProperties properties,
            LockTemplate lockTemplate) {
        this.repository = repository;
        this.executor = executor;
        this.properties = properties;
        this.lockTemplate = lockTemplate;
    }

    /** 固定间隔扫描到期 PENDING 任务，批量交给 {@link DelayTaskExecutor}。 */
    @Scheduled(fixedDelayString = "${app.delay.scan-interval-ms:5000}")
    public void scan() {
        if (!properties.isScanLockEnabled()) {
            doScan();
            return;
        }
        LockInfo lockInfo = lockTemplate.lock(
                LockKeys.delayScannerFallbackKey(),
                properties.getScanLockTimeout().toMillis(),
                0L);
        if (lockInfo == null) {
            log.debug("skip fallback scan, scanner lock not acquired");
            return;
        }
        try {
            doScan();
        } finally {
            releaseQuietly(lockInfo);
        }
    }

    private void doScan() {
        List<DelayTaskEntity> due = repository.findDuePending(Instant.now(), properties.getScanBatchSize());
        for (DelayTaskEntity task : due) {
            try {
                log.info("calling DelayTaskExecutor#execute from FallbackScanner, taskId={}",
                        task.getTaskId());
                executor.execute(task.getTaskId());
            } catch (Exception e) {
                log.error("fallback scan execute failed, taskId={}", task.getTaskId(), e);
            }
        }
    }

    private void releaseQuietly(LockInfo lockInfo) {
        try {
            lockTemplate.releaseLock(lockInfo);
        } catch (Exception e) {
            log.warn("release fallback scanner lock failed", e);
        }
    }
}
```

- [ ] **Step 4: 运行 FallbackScannerTest 确认通过**

Run: `mvn -f demo2/pom.xml -Dtest=FallbackScannerTest test`

Expected: BUILD SUCCESS，4 tests passed

- [ ] **Step 5: 运行关联 delay 模块测试回归**

Run: `mvn -f demo2/pom.xml -Dtest=FallbackScannerTest,DelayTaskExecutorTest,LockKeysTest test`

Expected: BUILD SUCCESS，全部通过；`DelayTaskExecutorTest` 无需修改

- [ ] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/framework/delay/FallbackScanner.java \
        demo2/src/test/java/com/jason/demo/demo2/framework/delay/FallbackScannerTest.java
git commit -m "feat(demo2): add scanner-level distributed lock to FallbackScanner"
```

---

## Spec Coverage Checklist

| Spec 要求 | 对应 Task |
|-----------|-----------|
| 锁 Key `delay:scanner:fallback` | Task 1 — `LockKeys.delayScannerFallbackKey()` |
| `scan-lock-enabled` 默认 true | Task 1 — `DelayProperties` + `application.properties` |
| `scan-lock-timeout` 默认 10s | Task 1 — `DelayProperties` + `application.properties` |
| 持锁 → doScan；未持锁 → debug skip | Task 2 — `FallbackScanner.scan()` |
| `scanLockEnabled=false` 向后兼容 | Task 2 — 测试 `scan_lockDisabled_*` |
| finally 释放锁 | Task 2 — `releaseQuietly` + 异常测试 |
| Executor 层不变 | 无改动；Task 2 Step 5 回归 `DelayTaskExecutorTest` |
| FallbackScannerTest 四条路径 | Task 2 — 4 个测试方法 |

---

## 验收命令（全部 Task 完成后）

```bash
mvn -f demo2/pom.xml -Dtest=FallbackScannerTest,DelayTaskExecutorTest,LockKeysTest test
```

Expected: BUILD SUCCESS
