# 通用延时任务 + 订单定时取消 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 demo2 落地可配置（Redisson / RocketMQ）主投递 + MySQL 台账扫描兜底的通用延时任务组件，并以订单超时取消作为首个 Demo。

**Architecture:** 调度事实落 `delay_task`，业务事实落 `demo_order`。`DelayTaskService` 写台账后经 `DelayDispatcher` 单主投递；到期由 Backend 回调或 `FallbackScanner` 进入同一 `DelayTaskExecutor`（lock4j 按 taskId 防重 → Handler）。支付成功将台账置 `CANCELLED`（MQ 无法撤回，消费时校验台账）。

**Tech Stack:** Spring Boot 4.1、Java 21、MyBatis-Plus、Hutool 雪花、Redisson DelayedQueue、现有 RocketMQ `DelayTimeLevel` / `sendDelay`、lock4j、JUnit 5 + Mockito

**Spec:** [2026-08-06-delay-task-order-cancel-design.md](../specs/2026-08-06-delay-task-order-cancel-design.md)

## Global Constraints

- 模块仅限 `demo2`；不改 `demo` 工程
- 公共能力在 `com.jason.demo.demo2.framework.*`；订单 Demo 在 `com.jason.demo.demo2.order.*`
- `order_id` / `task_id` 均为 `Long`，统一 `SnowflakeIdGenerator`（Hutool）
- 主投递可配置单主：`app.delay.backend=redisson|rocketmq`；禁止双写
- 台账表必须保留；不以「仅订单表」替代
- RocketMQ 延时映射：不小于目标时长的最小 `DelayTimeLevel`
- 取消：台账 `CANCELLED`；Redisson 尽量撤队；MQ 逻辑取消
- 执行：仅 `PENDING_PAY` 才改订单为 `CANCELLED`；跳过仍标任务 `SUCCESS`
- 重试：`max_retry=3`，退避 +5s / +15s / +30s
- 扫描默认 5s，是兜底不是主时钟
- 复用现有 MySQL `spring.datasource`、Redis/lock4j、RocketMQ framework

---

## File Structure

| 文件 | 职责 |
|------|------|
| `demo2/pom.xml` | MyBatis-Plus、Hutool |
| `demo2/src/main/resources/db/delay-order-schema.sql` | `demo_order` / `delay_task` DDL |
| `demo2/src/main/resources/application.properties` | `app.delay.*`、MyBatis-Plus、延时 MQ producer/consumer |
| `demo2/.../framework/id/SnowflakeIdGenerator.java` | Hutool 雪花 |
| `demo2/.../framework/delay/*` | 状态枚举、属性、Service、Dispatcher、Executor、Scanner、Handler SPI、Backend |
| `demo2/.../framework/delay/repository/*` | 台账 Entity / Mapper / Repository |
| `demo2/.../framework/delay/support/DelayTimeLevelMapper.java` | Duration → DelayTimeLevel |
| `demo2/.../order/*` | 订单 Entity / Repository / Service / Controller / OrderCancelHandler |
| `demo2/.../mq/*`（按需） | 延时任务 MQ 消息模型、Publisher、Listener |
| 对应 `src/test/java/...` | 单测 |

---

### Task 1: 依赖、建表脚本、配置、MyBatis-Plus 扫描

**Files:**
- Modify: `demo2/pom.xml`
- Create: `demo2/src/main/resources/db/delay-order-schema.sql`
- Modify: `demo2/src/main/resources/application.properties`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/delay/config/DelayMybatisPlusConfig.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/delay/config/DelayProperties.java`

**Interfaces:**
- Produces: 可注入的 `DelayProperties`；Mapper 扫描 `framework.delay.repository` 与 `order.repository`

- [ ] **Step 1: 在 `pom.xml` `<properties>` 增加版本，在 `<dependencies>` 增加依赖**

```xml
<mybatis-plus.version>3.5.9</mybatis-plus.version>
<hutool.version>5.8.35</hutool.version>
```

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    <version>${mybatis-plus.version}</version>
</dependency>
<dependency>
    <groupId>cn.hutool</groupId>
    <artifactId>hutool-core</artifactId>
    <version>${hutool.version}</version>
</dependency>
```

若 Boot 4 启动时报 MyBatis-Plus 自动配置不兼容，改用当时文档推荐的 Boot4 兼容坐标，但 API 保持 `BaseMapper` + `@TableName` 不变。

- [ ] **Step 2: 写 DDL**

```sql
-- demo2/src/main/resources/db/delay-order-schema.sql
-- 在 spring_ai_agent2 执行一次

CREATE TABLE IF NOT EXISTS demo_order (
    order_id    BIGINT       NOT NULL PRIMARY KEY,
    status      VARCHAR(32)  NOT NULL,
    amount      DECIMAL(12,2) NOT NULL,
    created_at  DATETIME(3)  NOT NULL,
    updated_at  DATETIME(3)  NOT NULL,
    INDEX idx_demo_order_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS delay_task (
    task_id      BIGINT       NOT NULL PRIMARY KEY,
    task_type    VARCHAR(64)  NOT NULL,
    biz_key      VARCHAR(128) NOT NULL,
    payload      TEXT         NULL,
    execute_at   DATETIME(3)  NOT NULL,
    status       VARCHAR(32)  NOT NULL,
    retry_count  INT          NOT NULL DEFAULT 0,
    max_retry    INT          NOT NULL DEFAULT 3,
    backend      VARCHAR(32)  NOT NULL,
    created_at   DATETIME(3)  NOT NULL,
    updated_at   DATETIME(3)  NOT NULL,
    INDEX idx_delay_task_due (status, execute_at),
    INDEX idx_delay_task_biz (task_type, biz_key, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 3: 追加 `application.properties`**

```properties
# ===== 延时任务（framework.delay）=====
app.delay.backend=redisson
app.delay.default-delay=30s
app.delay.scan-interval-ms=5000
app.delay.max-retry=3
app.delay.lock-timeout=10s
app.delay.scan-batch-size=50
app.delay.redisson-queue-name=demo2:delay:queue
app.delay.redisson-delay-queue-name=demo2:delay:delayed

mybatis-plus.configuration.map-underscore-to-camel-case=true
mybatis-plus.global-config.db-config.id-type=input

# 延时任务专用 MQ（与 DEMO_ORDER_TOPIC 分离）
rocketmq.producers.delayTaskProducer.enabled=true
rocketmq.producers.delayTaskProducer.namesrvAddr=127.0.0.1:9876
rocketmq.producers.delayTaskProducer.producerGroup=demo-delay-task-producer-group
rocketmq.producers.delayTaskProducer.topic=DEMO_DELAY_TASK_TOPIC

rocketmq.consumers.delayTask.enabled=true
rocketmq.consumers.delayTask.namesrvAddr=127.0.0.1:9876
rocketmq.consumers.delayTask.topic=DEMO_DELAY_TASK_TOPIC
rocketmq.consumers.delayTask.tags=*
rocketmq.consumers.delayTask.consumerGroup=demo-delay-task-group
rocketmq.consumers.delayTask.listenerBeanName=delayTaskMqListener
```

- [ ] **Step 4: 写 `DelayProperties` + MyBatis 配置**

```java
package com.jason.demo.demo2.framework.delay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "app.delay")
public class DelayProperties {
    private String backend = "redisson";
    private Duration defaultDelay = Duration.ofSeconds(30);
    private long scanIntervalMs = 5000;
    private int maxRetry = 3;
    private Duration lockTimeout = Duration.ofSeconds(10);
    private int scanBatchSize = 50;
    private String redissonQueueName = "demo2:delay:queue";
    private String redissonDelayQueueName = "demo2:delay:delayed";
    // getters/setters
}
```

```java
package com.jason.demo.demo2.framework.delay.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(DelayProperties.class)
@MapperScan({
    "com.jason.demo.demo2.framework.delay.repository",
    "com.jason.demo.demo2.order.repository"
})
public class DelayMybatisPlusConfig {
}
```

- [ ] **Step 5: 编译确认依赖可解析**

Run: `mvn -pl demo2 -am compile -q`  
（若仓库是单模块 `demo2` 目录内：`cd demo2 && mvn compile -q`）  
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add demo2/pom.xml demo2/src/main/resources/db/delay-order-schema.sql \
  demo2/src/main/resources/application.properties \
  demo2/src/main/java/com/jason/demo/demo2/framework/delay/config/
git commit -m "chore(demo2): add MyBatis-Plus, Hutool, delay schema and config"
```

---

### Task 2: SnowflakeIdGenerator

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/id/SnowflakeIdGenerator.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/framework/id/SnowflakeIdGeneratorTest.java`

**Interfaces:**
- Produces: `long nextId()`

- [ ] **Step 1: 写失败测试**

```java
package com.jason.demo.demo2.framework.id;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SnowflakeIdGeneratorTest {
    @Test
    void nextId_isUniqueAndPositive() {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1, 1);
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            long id = gen.nextId();
            assertTrue(id > 0);
            assertTrue(ids.add(id));
        }
    }
}
```

- [ ] **Step 2: Run test — expect FAIL（类不存在）**

Run: `mvn -pl demo2 -Dtest=SnowflakeIdGeneratorTest test`

- [ ] **Step 3: 实现**

```java
package com.jason.demo.demo2.framework.id;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import org.springframework.stereotype.Component;

@Component
public class SnowflakeIdGenerator {
    private final Snowflake snowflake;

    public SnowflakeIdGenerator() {
        this(1, 1);
    }

    public SnowflakeIdGenerator(long workerId, long datacenterId) {
        this.snowflake = IdUtil.getSnowflake(workerId, datacenterId);
    }

    public long nextId() {
        return snowflake.nextId();
    }
}
```

- [ ] **Step 4: Run test — expect PASS**

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/framework/id/ \
  demo2/src/test/java/com/jason/demo/demo2/framework/id/
git commit -m "feat(demo2): add Hutool SnowflakeIdGenerator"
```

---

### Task 3: 台账 Entity / Mapper / Repository

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/delay/DelayTaskStatus.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/delay/DelayTaskType.java`（常量即可）
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/delay/repository/DelayTaskEntity.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/delay/repository/DelayTaskMapper.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/delay/repository/DelayTaskRepository.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/framework/delay/repository/DelayTaskRepositoryTest.java`（可用 `@MybatisPlusTest` 或 Mockito 测 Repository 委托；若集成测需 MySQL，本 Task 用 Mockito 测查询条件拼装方法也可——优先对 Repository 的「到期列表 / 取消」方法做 Mockito 单元测 Mapper 调用）

**Interfaces:**
- Produces:
  - `DelayTaskRepository.insert(DelayTaskEntity)`
  - `Optional<DelayTaskEntity> findById(long taskId)`
  - `List<DelayTaskEntity> findDuePending(Instant now, int limit)`
  - `boolean markCancelled(String taskType, String bizKey)` — 仅 `PENDING` → `CANCELLED`
  - `boolean markCancelledById(long taskId)`
  - `boolean casStatus(long taskId, String from, String to)`
  - `void markSuccess(long taskId)` / `void scheduleRetry(long taskId, int newRetry, Instant newExecuteAt)` / `void markFailed(long taskId)`

- [ ] **Step 1: 定义枚举与实体**

```java
public enum DelayTaskStatus {
    PENDING, RUNNING, SUCCESS, FAILED, CANCELLED
}

public final class DelayTaskType {
    public static final String ORDER_CANCEL = "ORDER_CANCEL";
    private DelayTaskType() {}
}
```

```java
@Data
@TableName("delay_task")
public class DelayTaskEntity {
    @TableId(value = "task_id", type = IdType.INPUT)
    private Long taskId;
    private String taskType;
    private String bizKey;
    private String payload;
    private LocalDateTime executeAt;
    private String status;
    private Integer retryCount;
    private Integer maxRetry;
    private String backend;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: Mapper + Repository**

```java
@Mapper
public interface DelayTaskMapper extends BaseMapper<DelayTaskEntity> {
}
```

`DelayTaskRepository` 用 `LambdaQueryWrapper` / `LambdaUpdateWrapper` 实现上述接口；`findDuePending`：`status=PENDING AND execute_at<=now ORDER BY execute_at ASC LIMIT n`。

- [ ] **Step 3: 单测（Mockito）验证 `findDuePending` / `markCancelled` 调用了正确 wrapper 条件**

或集成测：本地执行 schema 后 `@SpringBootTest` 插入一条过去 `execute_at` 能查到。

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(demo2): add delay_task MyBatis-Plus repository"
```

---

### Task 4: DelayTimeLevel 映射

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/delay/support/DelayTimeLevelMapper.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/framework/delay/support/DelayTimeLevelMapperTest.java`

**Interfaces:**
- Produces: `DelayTimeLevel mapAtLeast(Duration delay)` — 不小于目标的最小档；超过 24h 用 `H_24`

- [ ] **Step 1: 失败测试**

```java
@Test
void twentySeconds_mapsToS30() {
    assertEquals(DelayTimeLevel.S_30, DelayTimeLevelMapper.mapAtLeast(Duration.ofSeconds(20)));
}

@Test
void exactlyFiveSeconds_mapsToS5() {
    assertEquals(DelayTimeLevel.S_5, DelayTimeLevelMapper.mapAtLeast(Duration.ofSeconds(5)));
}

@Test
void over24h_mapsToH24() {
    assertEquals(DelayTimeLevel.H_24, DelayTimeLevelMapper.mapAtLeast(Duration.ofHours(25)));
}
```

- [ ] **Step 2: 实现 — 按 `DelayTimeLevel` 声明顺序对应秒数数组 `[1,5,10,30,60,...]`，找第一个 `seconds >= delay.toSeconds()`**

注意：`DelayTimeLevel` 当前只有 `level`/`desc`，Mapper 内维护平行 duration 表，或给枚举补 `Duration` 字段（优先补枚举字段，避免两处漂移）。

若改枚举：

```java
S_1(1, Duration.ofSeconds(1)),
S_5(2, Duration.ofSeconds(5)),
// ...
H_24(18, Duration.ofHours(24));
```

- [ ] **Step 3: 测试 PASS 后 Commit**

```bash
git commit -m "feat(demo2): map Duration to RocketMQ DelayTimeLevel"
```

---

### Task 5: Handler SPI + DelayTaskExecutor

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/delay/DelayTaskHandler.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/delay/DelayTaskExecutor.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/framework/delay/DelayTaskExecutorTest.java`

**Interfaces:**
- Consumes: `DelayTaskRepository`、`LockTemplate`、`DelayProperties`、`List<DelayTaskHandler>`
- Produces: `void execute(long taskId)`
- Handler: `String taskType(); void handle(DelayTaskEntity task);`

锁 key：`"delay:task:" + taskId`；`acquireTimeout=0`；`expire` 用 `lockTimeout` 毫秒。

执行逻辑：
1. `lockTemplate.lock(key, expireMs, 0)`，失败 return
2. try：读台账；非 `PENDING` 或 `executeAt>now` 则 return
3. CAS `PENDING→RUNNING`，失败 return
4. 找 Handler；无 Handler → markFailed + log
5. `handle` 成功 → markSuccess
6. catch：retryCount+1；若 `< maxRetry` → scheduleRetry（退避 5/15/30s）；否则 markFailed
7. finally unlock

- [ ] **Step 1: 写 Executor 单测（Mockito）**

覆盖：
- 非 PENDING → 不调 Handler
- Handler 成功 → SUCCESS
- Handler 抛错第一次 → 回 PENDING 且 executeAt 推迟
- 锁失败 → 不读库或不执行业务（按实现断言 lock 后无后续）

- [ ] **Step 2: 实现 SPI + Executor**

- [ ] **Step 3: 测试 PASS + Commit**

```bash
git commit -m "feat(demo2): add DelayTaskExecutor with lock and retry"
```

---

### Task 6: DelayBackend + Dispatcher（Redisson / RocketMQ）

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/delay/backend/DelayBackend.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/delay/backend/RedissonDelayBackend.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/delay/backend/RocketMqDelayBackend.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/delay/DelayDispatcher.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/mq/model/DelayTaskMessage.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/mq/publisher/DelayTaskEventPublisher.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/mq/listener/DelayTaskMqListener.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/framework/delay/DelayDispatcherTest.java`

**Interfaces:**
- `DelayBackend`: `String name(); void schedule(long taskId, Duration delay); void cancel(long taskId);`
- `DelayDispatcher`: `void schedule(long taskId, Duration delay); void cancel(long taskId);` — 按 `DelayProperties.backend` 选实现；`cancel` 仅转发给当前（或注册时）backend；简化：cancel 时两个 backend 都 try（Redisson 撤队，RocketMQ no-op），避免 backend 切换后取消失效

**Redisson:**
- `RBlockingQueue<Long> dest = redisson.getBlockingQueue(queueName);`
- `RDelayedQueue<Long> delayed = redisson.getDelayedQueue(dest);`
- `delayed.offer(taskId, delay.toMillis(), MILLISECONDS);`
- 启动时 `@PostConstruct` 用虚拟线程或单线程循环 `dest.take()` → `executor.execute(taskId)`（注意应用关闭 interrupt）
- `cancel`: `delayed.remove(taskId)`（尽量）

**RocketMQ:**
- `DelayTaskMessage { Long taskId; }`
- `DelayTaskEventPublisher extends BaseEventPublisher`，`publisherId=delayTaskProducer`
- `schedule`: `sendDelay(msg, DelayTimeLevelMapper.mapAtLeast(delay), String.valueOf(taskId))`
- `cancel`: no-op
- `DelayTaskMqListener` bean name `delayTaskMqListener`：反序列化后 `executor.execute(taskId)`

- [ ] **Step 1: Dispatcher 单测 — backend=redisson 只调 Redisson backend**

- [ ] **Step 2: 实现 Backend + Listener + Dispatcher**

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(demo2): add Redisson and RocketMQ delay backends"
```

---

### Task 7: DelayTaskService（注册 / 取消）

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/delay/DelayTaskService.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/controller/DelayTaskController.java`（通用查询/取消）
- Test: `demo2/src/test/java/com/jason/demo/demo2/framework/delay/DelayTaskServiceTest.java`

**Interfaces:**
- `long schedule(String taskType, String bizKey, String payload, Duration delay)`
  1. `taskId = snowflake.nextId()`
  2. insert PENDING 台账（backend 快照、maxRetry 自配置、executeAt=now+delay）
  3. try `dispatcher.schedule`；catch log warn（不抛，靠扫描）
  4. return taskId
- `boolean cancelByBizKey(String taskType, String bizKey)` — 台账 CANCELLED + `dispatcher.cancel(taskId)`（若能查到 id）
- `boolean cancelById(long taskId)`
- `Optional<DelayTaskEntity> get(long taskId)` / `List` by bizKey

Controller：
- `POST /demo/delay-tasks/{taskId}/cancel`
- `GET /demo/delay-tasks?taskId=&bizKey=`

- [ ] **Step 1: Service 单测 — 投递抛错仍返回 taskId；取消更新状态**

- [ ] **Step 2: 实现 Service + Controller**

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(demo2): add DelayTaskService schedule and cancel APIs"
```

---

### Task 8: FallbackScanner

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/delay/FallbackScanner.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/framework/delay/FallbackScannerTest.java`

**Interfaces:**
- `@Scheduled(fixedDelayString = "${app.delay.scan-interval-ms:5000}")`
- `findDuePending(now, batchSize)` → 每条 `executor.execute(taskId)`
- 单条异常 catch log，不中断 batch

- [ ] **Step 1: 单测 — 两条 due 任务都调用 executor**

- [ ] **Step 2: 实现 + Commit**

```bash
git commit -m "feat(demo2): add delay task fallback scanner"
```

---

### Task 9: 订单 Demo（表、Handler、API）

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/order/OrderStatus.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/order/repository/OrderEntity.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/order/repository/OrderMapper.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/order/repository/OrderRepository.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/order/OrderCancelHandler.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/order/OrderService.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/order/OrderController.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/order/CreateOrderRequest.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/order/OrderCancelHandlerTest.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/order/OrderServiceTest.java`

**Interfaces:**
- `OrderCancelHandler.taskType()` → `ORDER_CANCEL`
- `handle`: 解析 `bizKey` 为 `orderId`；若 `PENDING_PAY` → `CANCELLED`；否则 no-op
- `OrderService.create(amount, Duration delay)`：雪花 orderId → insert → `delayTaskService.schedule(ORDER_CANCEL, String.valueOf(orderId), null, delayOrDefault)` → 返回 order + taskId
- `pay(orderId)`：仅 `PENDING_PAY`→`PAID`，然后 `cancelByBizKey(ORDER_CANCEL, ...)`
- `get(orderId)`

Controller：
- `POST /demo/orders` body `{ "amount": 19.9, "delay": "30s" }`（delay 可选）
- `POST /demo/orders/{id}/pay`
- `GET /demo/orders/{id}`

- [ ] **Step 1: Handler 单测 — PENDING_PAY 取消；PAID 不改状态**

- [ ] **Step 2: OrderService 单测 — create 调用 schedule；pay 调用 cancel**

- [ ] **Step 3: 实现全部订单文件**

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(demo2): add order timeout cancel demo on delay tasks"
```

---

### Task 10: 手工冒烟说明 + README 短节

**Files:**
- Modify: `demo2/README.md`（增加「延时任务 / 订单超时取消」小节：依赖 MySQL 建表、Redis、可选 RocketMQ、配置切换、curl 示例）

- [ ] **Step 1: 在 MySQL 执行 `delay-order-schema.sql`**

- [ ] **Step 2: 确保 Redis 已起；`app.delay.backend=redisson` 时冒烟**

```bash
curl -s -X POST http://localhost:8081/demo/orders -H "Content-Type: application/json" -d "{\"amount\":9.9,\"delay\":\"30s\"}"
# 记录 orderId；等待 >30s（或把 delay 调到 5s + Redisson）
curl -s http://localhost:8081/demo/orders/{orderId}
# 期望 status=CANCELLED
```

```bash
# 支付路径
curl -s -X POST http://localhost:8081/demo/orders -H "Content-Type: application/json" -d "{\"amount\":9.9,\"delay\":\"30s\"}"
curl -s -X POST http://localhost:8081/demo/orders/{orderId}/pay
curl -s http://localhost:8081/demo/orders/{orderId}
# 期望 PAID；到期后仍为 PAID
```

- [ ] **Step 3: 切换 `app.delay.backend=rocketmq`，启动 RocketMQ Docker，重复上述（注意 level 映射，20s 实际约 30s 档）**

- [ ] **Step 4: Commit README**

```bash
git commit -m "docs(demo2): document delay task order cancel demo"
```

---

## Spec Coverage Checklist

| Spec 项 | Task |
|---------|------|
| MyBatis-Plus + Repository + 建表 | 1, 3, 9 |
| Hutool 雪花 Long ids | 2, 7, 9 |
| 可配置 redisson/rocketmq 单主 | 1, 6 |
| 扫描兜底 | 8 |
| 注册/取消 | 7 |
| 锁 + 重试 + Handler 校验待支付 | 5, 9 |
| DelayTimeLevel 映射 | 4 |
| framework vs order 分包 | 全部 |
| Demo API | 7, 9 |
| 非目标（双写/死信控制台/MCP） | 不实现 |

---

## Self-Review Notes

- 无 TBD；类型统一 `Long taskId/orderId`
- RocketMQ 使用独立 Topic，避免与现有 `OrderEvent` Demo 监听器互相污染
- Redisson 消费循环需在 shutdown 时停止，避免泄漏
- `BaseEventPublisher` 的 `afterCommit`：若无事务，确认仍会发送（现有 `TransactionUtils.afterCommitSyncExecute` 行为需保持与订单 MQ Demo 一致；无事务时应立即发送）
