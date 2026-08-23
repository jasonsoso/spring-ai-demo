# Snowflake 服务隔离自动分配 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 Redis 永久绑定 `datacenterId`（服务）+ TTL 租约分配 `workerId`（机器），启动 fail-fast，替换写死的 `(1,1)`。

**Architecture:** `SnowflakeNodeAllocator` 在启动时通过 `StringRedisTemplate`（Lua + SET NX EX）拿到 `(datacenterId, workerId)` 并启动心跳；`SnowflakeIdConfiguration` 据此构造 `SnowflakeIdGenerator` Bean。去掉 `@Component` 无参写死路径，业务注入方式不变。

**Tech Stack:** Spring Boot 4.x、Java 21、Spring Data Redis（`StringRedisTemplate` / `DefaultRedisScript`）、Hutool Snowflake、JUnit 5 + Mockito

**Spec:** [2026-08-07-snowflake-service-isolation-design.md](../specs/2026-08-07-snowflake-service-isolation-design.md)

## Global Constraints

- 模块仅限 `demo2`；不改 `demo` 工程
- `datacenterId` = 服务（`spring.application.name`），`workerId` = 实例；范围均为 `0..31`
- 协调存储仅 Redis；不引入 Leaf / 号段 / MySQL 租约
- Redis 不可用或槽位耗尽 → **启动失败**；禁止降级固定节点号
- `datacenterId` 映射永久，本版不自动回收
- 不新增中间件依赖；测试用 Mockito 模拟 Redis（本版不强制 Testcontainers）
- 复用现有 `spring.data.redis.*`
- `SnowflakeIdGenerator` 双参构造顺序保持 `(workerId, datacenterId)`，与 Hutool `IdUtil.getSnowflake` 一致

---



## File Structure


| 文件                                                 | 职责                                      |
| -------------------------------------------------- | --------------------------------------- |
| `.../framework/id/SnowflakeProperties.java`        | `app.snowflake.*` 配置                    |
| `.../framework/id/AllocatedSnowflakeNode.java`     | 分配结果：app / dc / worker / instanceId     |
| `.../framework/id/SnowflakeNodeAllocator.java`     | dc 分配、worker 租约、心跳、释放                   |
| `.../framework/id/SnowflakeIdConfiguration.java`   | `@Bean` 装配 allocator + generator        |
| `.../framework/id/SnowflakeIdGenerator.java`       | 去掉 `@Component` 与写死无参；保留双参 + `nextId()` |
| `demo2/src/main/resources/application.properties`  | `app.snowflake.*`                       |
| `.../framework/id/SnowflakeNodeAllocatorTest.java` | Allocator 单测（Mock Redis）                |
| `.../framework/id/SnowflakeIdGeneratorTest.java`   | 已有，保持双参构造                               |
| `.../order/OrderServiceTest.java` 等                | 回归：仍 `@Mock SnowflakeIdGenerator`，无需改逻辑 |


---



### Task 1: SnowflakeProperties + AllocatedSnowflakeNode

**Files:**

- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/id/SnowflakeProperties.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/id/AllocatedSnowflakeNode.java`
- Modify: `demo2/src/main/resources/application.properties`
- Test: `demo2/src/test/java/com/jason/demo/demo2/framework/id/SnowflakePropertiesTest.java`

**Interfaces:**

- Produces: `SnowflakeProperties`（`getKeyPrefix()` / `getLeaseTtlSeconds()` / `getHeartbeatIntervalSeconds()`）；`AllocatedSnowflakeNode(String applicationName, long datacenterId, long workerId, String instanceId)`

- [ ] **Step 1: Write the failing test**

```java
package com.jason.demo.demo2.framework.id;

import com.jason.demo.demo2.framework.id.configuration.SnowflakeProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SnowflakePropertiesTest {

    @Test
    void defaults_matchSpec() {
        SnowflakeProperties props = new SnowflakeProperties();
        assertEquals("app:snowflake", props.getKeyPrefix());
        assertEquals(30, props.getLeaseTtlSeconds());
        assertEquals(10, props.getHeartbeatIntervalSeconds());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=SnowflakePropertiesTest test
```

Expected: FAIL（`SnowflakeProperties` 不存在或编译失败）

- [ ] **Step 3: Implement properties + record + application.properties**

`AllocatedSnowflakeNode.java`:

```java
package com.jason.demo.demo2.framework.id;

public record AllocatedSnowflakeNode(
        String applicationName,
        long datacenterId,
        long workerId,
        String instanceId
) {
}
```

`SnowflakeProperties.java`:

```java
package com.jason.demo.demo2.framework.id;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.snowflake")
public class SnowflakeProperties {

    private String keyPrefix = "app:snowflake";
    private int leaseTtlSeconds = 30;
    private int heartbeatIntervalSeconds = 10;

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public int getLeaseTtlSeconds() {
        return leaseTtlSeconds;
    }

    public void setLeaseTtlSeconds(int leaseTtlSeconds) {
        this.leaseTtlSeconds = leaseTtlSeconds;
    }

    public int getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    public void setHeartbeatIntervalSeconds(int heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
    }
}
```

在 `application.properties` 增加（可放在 Redis 配置段附近）：

```properties
# ===== Snowflake 节点自动分配（服务=dc，机器=worker）=====
app.snowflake.key-prefix=app:snowflake
app.snowflake.lease-ttl-seconds=30
app.snowflake.heartbeat-interval-seconds=10
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=SnowflakePropertiesTest test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/framework/id/SnowflakeProperties.java \
  demo2/src/main/java/com/jason/demo/demo2/framework/id/AllocatedSnowflakeNode.java \
  demo2/src/test/java/com/jason/demo/demo2/framework/id/SnowflakePropertiesTest.java \
  demo2/src/main/resources/application.properties
git commit -m "feat(demo2): add snowflake node allocation properties"
```

---



### Task 2: 改造 SnowflakeIdGenerator（去掉写死节点）

**Files:**

- Modify: `demo2/src/main/java/com/jason/demo/demo2/framework/id/SnowflakeIdGenerator.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/framework/id/SnowflakeIdGeneratorTest.java`（已有，应仍通过）

**Interfaces:**

- Consumes: 无
- Produces: `SnowflakeIdGenerator(long workerId, long datacenterId)` + `long nextId()`；**不再**是 `@Component`，无参写死 `(1,1)` 删除（Spring Bean 由 Task 5 提供）

- [ ] **Step 1: Write / adjust failing expectation**

确认 `SnowflakeIdGeneratorTest` 使用双参构造。若类上仍有 `@Component` 与无参构造，本 Task 删除它们后，**在尚未有** `SnowflakeIdConfiguration` **时**，完整 Spring 上下文可能缺 Bean——因此本 Task **不要**跑需要真实 `SnowflakeIdGenerator` Bean 的 `@SpringBootTest`；只跑纯单元测试。

可选增强测试（同一文件追加）：

```java
@Test
void constructor_rejectsOutOfRangeIds() {
    // 本步若暂不校验范围可跳过；范围由 allocator 保证。
    // 保持最小改动：仅验证 nextId 仍可用即可。
}
```

本 Task 以现有 `nextId_isUniqueAndPositive` 为准即可。

- [ ] **Step 2: Run existing generator test (baseline)**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=SnowflakeIdGeneratorTest test
```

Expected: PASS（改前也应 PASS）

- [ ] **Step 3: Rewrite SnowflakeIdGenerator**

替换为：

```java
package com.jason.demo.demo2.framework.id;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;

public class SnowflakeIdGenerator {

    private final Snowflake snowflake;

    public SnowflakeIdGenerator(long workerId, long datacenterId) {
        this.snowflake = IdUtil.getSnowflake(workerId, datacenterId);
    }

    public long nextId() {
        return snowflake.nextId();
    }
}
```

注意：删除 `@Component` 与无参构造。`OrderServiceTest` / `DelayTaskServiceTest` 使用 `@Mock`，不受影响。

- [ ] **Step 4: Re-run generator unit test**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=SnowflakeIdGeneratorTest,OrderServiceTest,DelayTaskServiceTest test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/framework/id/SnowflakeIdGenerator.java
git commit -m "refactor(demo2): require explicit snowflake worker/datacenter ids"
```

---



### Task 3: SnowflakeNodeAllocator — datacenterId 永久分配

**Files:**

- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/id/SnowflakeNodeAllocator.java`（本 Task 先实现 dc + 骨架；worker 可抛 `UnsupportedOperationException` 或先写完整类但测试只覆盖 dc）
- Create: `demo2/src/test/java/com/jason/demo/demo2/framework/id/SnowflakeNodeAllocatorTest.java`

**Interfaces:**

- Consumes: `StringRedisTemplate`、`SnowflakeProperties`、`String applicationName`
- Produces（本 Task）: `long ensureDatacenterId(String applicationName)`；Redis keys：`{prefix}:dc:{app}`、`{prefix}:dc:used`

- [ ] **Step 1: Write the failing tests**

```java
package com.jason.demo.demo2.framework.id;

import com.jason.demo.demo2.framework.id.configuration.SnowflakeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SnowflakeNodeAllocatorTest {

    @Mock
    private StringRedisTemplate redis;

    private SnowflakeProperties properties;
    private SnowflakeNodeAllocator allocator;

    @BeforeEach
    void setUp() {
        properties = new SnowflakeProperties();
        properties.setKeyPrefix("test:snowflake");
        properties.setLeaseTtlSeconds(30);
        properties.setHeartbeatIntervalSeconds(10);
        allocator = new SnowflakeNodeAllocator(redis, properties, "order-service");
    }

    @Test
    void ensureDatacenterId_returnsExistingMapping() {
        when(redis.execute(
                ArgumentMatchers.<DefaultRedisScript<String>>any(),
                eq(List.of("test:snowflake:dc:order-service", "test:snowflake:dc:used"))
        )).thenReturn("7");

        assertEquals(7L, allocator.ensureDatacenterId("order-service"));
    }

    @Test
    void ensureDatacenterId_failsWhenSlotsExhausted() {
        when(redis.execute(
                ArgumentMatchers.<DefaultRedisScript<String>>any(),
                anyList()
        )).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> allocator.ensureDatacenterId("order-service"));
    }
}
```

若 Mockito 对 `execute` 泛型匹配困难，可改为：

```java
when(redis.execute(any(DefaultRedisScript.class), anyList())).thenReturn("7");
```

并 `@SuppressWarnings("unchecked")`。

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=SnowflakeNodeAllocatorTest test
```

Expected: FAIL（类不存在）

- [ ] **Step 3: Implement ensureDatacenterId + Lua**

在 `SnowflakeNodeAllocator` 中：

```java
package com.jason.demo.demo2.framework.id;

import com.jason.demo.demo2.framework.id.configuration.SnowflakeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class SnowflakeNodeAllocator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SnowflakeNodeAllocator.class);
    private static final int MAX_NODE_ID = 31;

    private static final String LUA_ENSURE_DC = """
            local existing = redis.call('GET', KEYS[1])
            if existing then
              return existing
            end
            for i = 0, 31 do
              local id = tostring(i)
              if redis.call('SADD', KEYS[2], id) == 1 then
                redis.call('SET', KEYS[1], id)
                return id
              end
            end
            return false
            """;

    private final StringRedisTemplate redis;
    private final SnowflakeProperties properties;
    private final String applicationName;

    private AllocatedSnowflakeNode node;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> heartbeatFuture;
    private final AtomicBoolean heartbeatStopped = new AtomicBoolean(false);

    public SnowflakeNodeAllocator(
            StringRedisTemplate redis,
            SnowflakeProperties properties,
            String applicationName) {
        this.redis = redis;
        this.properties = properties;
        this.applicationName = Objects.requireNonNull(applicationName);
    }

    public long ensureDatacenterId(String appName) {
        String dcKey = properties.getKeyPrefix() + ":dc:" + appName;
        String usedKey = properties.getKeyPrefix() + ":dc:used";
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptText(LUA_ENSURE_DC);
        script.setResultType(String.class);
        String result = redis.execute(script, List.of(dcKey, usedKey));
        if (result == null || result.isBlank() || "false".equalsIgnoreCase(result)) {
            throw new IllegalStateException(
                    "Snowflake datacenterId slots exhausted (0-31) for prefix="
                            + properties.getKeyPrefix());
        }
        return Long.parseLong(result);
    }

    // allocate / worker / heartbeat 在 Task 4 补全
}
```

说明：Redis Lua `return false` 经 Spring 可能变成 `null`；两种都当耗尽处理。

- [ ] **Step 4: Run tests**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=SnowflakeNodeAllocatorTest test
```

Expected: PASS（本 Task 两个用例）

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/framework/id/SnowflakeNodeAllocator.java \
  demo2/src/test/java/com/jason/demo/demo2/framework/id/SnowflakeNodeAllocatorTest.java
git commit -m "feat(demo2): allocate permanent snowflake datacenterId via Redis"
```

---



### Task 4: worker 租约、心跳、释放、allocate()

**Files:**

- Modify: `demo2/src/main/java/com/jason/demo/demo2/framework/id/SnowflakeNodeAllocator.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/framework/id/SnowflakeNodeAllocatorTest.java`

**Interfaces:**

- Consumes: Task 3 的 `ensureDatacenterId`
- Produces:
  - `AllocatedSnowflakeNode allocate()`
  - `AllocatedSnowflakeNode current()`
  - `boolean renewLease()`（测试用 package/public）
  - `boolean releaseLease()`
  - `void startHeartbeat()`
  - `void close()`（停心跳 + 释放）

- [ ] **Step 1: Write the failing tests（追加到 SnowflakeNodeAllocatorTest）**

```java
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@Test
void allocate_acquiresFirstFreeWorker() {
    when(redis.execute(any(DefaultRedisScript.class), anyList())).thenReturn("1"); // dc=1

    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.setIfAbsent(eq("test:snowflake:worker:1:0"), anyString(), eq(Duration.ofSeconds(30))))
            .thenReturn(true);

    AllocatedSnowflakeNode node = allocator.allocate();
    assertEquals(1L, node.datacenterId());
    assertEquals(0L, node.workerId());
    assertEquals("order-service", node.applicationName());
    assertNotNull(node.instanceId());
}

@Test
void allocate_failsWhenAllWorkersTaken() {
    when(redis.execute(any(DefaultRedisScript.class), anyList())).thenReturn("1");
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

    assertThrows(IllegalStateException.class, () -> allocator.allocate());
}

@Test
void renewLease_succeedsOnlyForOwner() {
    // 先 allocate 成功拿到 instanceId，再 stub renew Lua 返回 1 / 0
    when(redis.execute(any(DefaultRedisScript.class), anyList()))
            .thenReturn("1")   // ensure dc
            .thenReturn("1");  // renew ok — 按实现调用次序调整
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

    allocator.allocate();
    // 第二次 execute 为 renew：可再用 Answer 按 script 文本分支；或把 renew 提取为可见方法后单独 stub
    when(redis.execute(any(DefaultRedisScript.class), anyList(), any()))
            .thenReturn(1L);
    assertTrue(allocator.renewLease());
}

@Test
void releaseLease_deletesOnlyOwnedKey() {
    when(redis.execute(any(DefaultRedisScript.class), anyList())).thenReturn("1");
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    allocator.allocate();

    when(redis.execute(any(DefaultRedisScript.class), anyList(), any())).thenReturn(1L);
    assertTrue(allocator.releaseLease());
}
```

实现时若 `execute` 重载（带 `Object... args`）与无 args 冲突，测试用 `lenient()` 或自定义 `Answer` 区分脚本。原则：覆盖「抢到 0 号 worker」「32 全满失败」「续约/释放走持有者校验」。

- [ ] **Step 2: Run tests — expect new ones fail**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=SnowflakeNodeAllocatorTest test
```

Expected: 新用例 FAIL

- [ ] **Step 3: Implement worker + heartbeat + allocate**

补全 `SnowflakeNodeAllocator`（关键片段）：

```java
private static final String LUA_RENEW = """
        if redis.call('GET', KEYS[1]) == ARGV[1] then
          return redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
        end
        return 0
        """;

private static final String LUA_RELEASE = """
        if redis.call('GET', KEYS[1]) == ARGV[1] then
          return redis.call('DEL', KEYS[1])
        end
        return 0
        """;

public AllocatedSnowflakeNode allocate() {
    long dc = ensureDatacenterId(applicationName);
    String instanceId = UUID.randomUUID().toString();
    Long workerId = null;
    for (int i = 0; i <= MAX_NODE_ID; i++) {
        String key = workerKey(dc, i);
        Boolean ok = redis.opsForValue().setIfAbsent(
                key, instanceId, Duration.ofSeconds(properties.getLeaseTtlSeconds()));
        if (Boolean.TRUE.equals(ok)) {
            workerId = (long) i;
            break;
        }
    }
    if (workerId == null) {
        throw new IllegalStateException(
                "Snowflake workerId slots exhausted for datacenterId=" + dc);
    }
    this.node = new AllocatedSnowflakeNode(applicationName, dc, workerId, instanceId);
    log.info("snowflake ready, app={}, datacenterId={}, workerId={}, instanceId={}",
            applicationName, dc, workerId, instanceId);
    return this.node;
}

public AllocatedSnowflakeNode current() {
    if (node == null) {
        throw new IllegalStateException("Snowflake node not allocated");
    }
    return node;
}

public boolean renewLease() {
    AllocatedSnowflakeNode n = current();
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setScriptText(LUA_RENEW);
    script.setResultType(Long.class);
    Long r = redis.execute(
            script,
            List.of(workerKey(n.datacenterId(), n.workerId())),
            n.instanceId(),
            String.valueOf(properties.getLeaseTtlSeconds()));
    return r != null && r > 0;
}

public boolean releaseLease() {
    if (node == null) {
        return false;
    }
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setScriptText(LUA_RELEASE);
    script.setResultType(Long.class);
    Long r = redis.execute(
            script,
            List.of(workerKey(node.datacenterId(), node.workerId())),
            node.instanceId());
    return r != null && r > 0;
}

public void startHeartbeat() {
    if (scheduler != null) {
        return;
    }
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "snowflake-heartbeat");
        t.setDaemon(true);
        return t;
    });
    long interval = properties.getHeartbeatIntervalSeconds();
    heartbeatFuture = scheduler.scheduleAtFixedRate(() -> {
        if (heartbeatStopped.get()) {
            return;
        }
        try {
            if (!renewLease()) {
                log.error(
                        "snowflake lease renew failed (lost ownership?), app={}, datacenterId={}, workerId={}, instanceId={}",
                        node.applicationName(), node.datacenterId(), node.workerId(), node.instanceId());
                heartbeatStopped.set(true);
                if (heartbeatFuture != null) {
                    heartbeatFuture.cancel(false);
                }
            }
        } catch (Exception e) {
            log.error("snowflake lease renew error, app={}", applicationName, e);
            heartbeatStopped.set(true);
            if (heartbeatFuture != null) {
                heartbeatFuture.cancel(false);
            }
        }
    }, interval, interval, TimeUnit.SECONDS);
}

@Override
public void close() {
    heartbeatStopped.set(true);
    if (heartbeatFuture != null) {
        heartbeatFuture.cancel(false);
    }
    if (scheduler != null) {
        scheduler.shutdownNow();
    }
    try {
        releaseLease();
    } catch (Exception e) {
        log.warn("snowflake lease release failed on shutdown", e);
    }
}

private String workerKey(long dc, long worker) {
    return properties.getKeyPrefix() + ":worker:" + dc + ":" + worker;
}
```

- [ ] **Step 4: Run allocator tests**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=SnowflakeNodeAllocatorTest test
```



Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/framework/id/SnowflakeNodeAllocator.java \
  demo2/src/test/java/com/jason/demo/demo2/framework/id/SnowflakeNodeAllocatorTest.java
git commit -m "feat(demo2): lease snowflake workerId with Redis TTL heartbeat"
```

---



### Task 5: Spring 装配 SnowflakeIdConfiguration

**Files:**

- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/id/SnowflakeIdConfiguration.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/framework/id/SnowflakeIdConfigurationTest.java`（纯单测：用 mock allocator 验证 generator 构造参数；或跳过 Spring 上下文，改为轻量验证配置类可编译 + 手动 new）

**Interfaces:**

- Consumes: `SnowflakeNodeAllocator.allocate()` / `startHeartbeat()` / `current()` / `close()`
- Produces: Spring Bean `SnowflakeIdGenerator`、`SnowflakeNodeAllocator`（`destroyMethod = "close"`）

- [ ] **Step 1: Write a focused wiring test**

不启动完整 `Demo2Application`（避免拉起 MQ/DB）。用手动装配验证：

```java
package com.jason.demo.demo2.framework.id;

import com.jason.demo.demo2.framework.id.configuration.SnowflakeIdConfiguration;
import com.jason.demo.demo2.framework.id.configuration.SnowflakeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SnowflakeIdConfigurationTest {

    @Test
    void generator_usesAllocatedNodeIds() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList())).thenReturn("3");
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        SnowflakeProperties props = new SnowflakeProperties();
        SnowflakeNodeAllocator allocator =
                new SnowflakeNodeAllocator(redis, props, "demo2");
        allocator.allocate();

        SnowflakeIdGenerator generator = new SnowflakeIdConfiguration()
                .snowflakeIdGenerator(allocator);

        assertTrue(generator.nextId() > 0);
        allocator.close();
    }
}
```

（若 `snowflakeIdGenerator` 为 package/实例方法，按下面实现暴露为 `@Bean` 方法，测试可直接 `new SnowflakeIdConfiguration().snowflakeIdGenerator(allocator)`。）

- [ ] **Step 2: Run test — expect fail（配置类不存在）**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=SnowflakeIdConfigurationTest test
```

Expected: FAIL

- [ ] **Step 3: Implement configuration**

```java
package com.jason.demo.demo2.framework.id;

import com.jason.demo.demo2.framework.id.configuration.SnowflakeProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableConfigurationProperties(SnowflakeProperties.class)
public class SnowflakeIdConfiguration {

    @Bean(destroyMethod = "close")
    public SnowflakeNodeAllocator snowflakeNodeAllocator(
            StringRedisTemplate stringRedisTemplate,
            SnowflakeProperties properties,
            @Value("${spring.application.name}") String applicationName) {
        SnowflakeNodeAllocator allocator =
                new SnowflakeNodeAllocator(stringRedisTemplate, properties, applicationName);
        allocator.allocate();
        allocator.startHeartbeat();
        return allocator;
    }

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(SnowflakeNodeAllocator allocator) {
        AllocatedSnowflakeNode node = allocator.current();
        return new SnowflakeIdGenerator(node.workerId(), node.datacenterId());
    }
}
```

Redis 连接失败会在 `allocate()` 抛出 → Spring 上下文刷新失败（符合 fail-fast）。

- [ ] **Step 4: Run tests**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=SnowflakeIdConfigurationTest,SnowflakeNodeAllocatorTest,SnowflakeIdGeneratorTest,SnowflakePropertiesTest,OrderServiceTest,DelayTaskServiceTest test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/framework/id/SnowflakeIdConfiguration.java \
  demo2/src/test/java/com/jason/demo/demo2/framework/id/SnowflakeIdConfigurationTest.java
git commit -m "feat(demo2): wire snowflake generator from Redis-allocated node"
```

---



### Task 6: 回归与手工验收清单

**Files:**

- 无必须改动的生产代码（若发现 `@SpringBootTest` 缺 Redis 再补 test properties / 排除）
- 检查：`grep -R "new SnowflakeIdGenerator()" demo2` 应为空；无参路径已删除

**Interfaces:**

- Consumes: 完整装配
- Produces: 可运行的 demo2（需本地 Redis）

- [ ] **Step 1: 静态检查无参构造残留**

Run:

```bash
rg "new SnowflakeIdGenerator\\(\\)" demo2/src
rg "@Component" demo2/src/main/java/com/jason/demo/demo2/framework/id
```

Expected: 无匹配（或仅注释）

- [ ] **Step 2: 跑相关单测全集**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=Snowflake*Test,OrderServiceTest,DelayTaskServiceTest test
```

Expected: PASS

- [ ] **Step 3: 手工验收（本地 Redis 已启动）**

1. `docker compose -f demo2/docker/redis/docker-compose.yml up -d`（若尚未启动）
2. 启动 `Demo2Application`
3. 日志出现：`snowflake ready, app=demo2, datacenterId=..., workerId=..., instanceId=...`
4. Redis CLI：

```bash
redis-cli GET app:snowflake:dc:demo2
redis-cli SMEMBERS app:snowflake:dc:used
redis-cli KEYS "app:snowflake:worker:*"
```

1. 停应用后 worker key 应被 DEL；或 kill -9 后等待 ≤30s TTL 消失
2. 再启一次：`GET app:snowflake:dc:demo2` 与首次相同

- [ ] **Step 4: Commit（若有测试/文档微调）**

仅当本 Task 有文件变更时提交，例如：

```bash
git add -u demo2
git commit -m "test(demo2): cover snowflake allocation regression checks"
```

无变更则跳过 commit。

---



## Spec coverage（自检）


| Spec 要求                    | Task       |
| -------------------------- | ---------- |
| dc=服务、worker=机器，0..31      | Task 3–4   |
| Redis 永久 dc + TTL worker   | Task 3–4   |
| fail-fast，不降级              | Task 4–5   |
| `spring.application.name`  | Task 5     |
| 心跳 / 释放 / 丢租约打 error       | Task 4     |
| 配置项 `app.snowflake.*`      | Task 1     |
| 改造 Generator，业务注入不变        | Task 2、5、6 |
| 单测：复用 dc、抢 worker、占满、续约/释放 | Task 3–4   |
| 可观测 ready 日志               | Task 4     |
| 非目标（Leaf/哈希/自动回收 dc 等）     | 未纳入        |




## Placeholder / 一致性自检

- 无 TBD；构造参数顺序全程 `(workerId, datacenterId)`
- Key 前缀与 spec：`{prefix}:dc:{app}` / `{prefix}:dc:used` / `{prefix}:worker:{dc}:{worker}`
- Lua `false`/`null` 均视为耗尽

