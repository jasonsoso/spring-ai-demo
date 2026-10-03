# 出箱 Relay 改为 StreamListener Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `RedisStockOutboxRelay` 从手写虚拟线程循环改成 `StreamListener` + `StreamMessageListenerContainer`，发送成功才 `XACKDEL(ACKED)`，组内 idle PEL 由独立调度领到本机唯一消费者名，看门狗只拉起本机订阅。

**Architecture:** 容器只 `XREADGROUP >` 且 `autoAcknowledge(false)`、`cancelOnError` 恒 false。`onMessage` 调现有 `sendNow`，成功后再 `acknowledgeAndDelete(..., ACKED)`。Spring Data Redis 4.1 没有 `autoClaim`，补发用组级 `pending(..., count, minIdle)` + `claim(..., 本机消费者名, minIdle, ids)`，语义等同扫全组 PEL 再转所有权。看门狗用自备 `ExecutorService` 记下轮询 `Future`。消费者名 `{prefix}-{hostname}-{pid}`，组名共享。

**Tech Stack:** Java 21、Spring Boot 4.1、Spring Data Redis 4.1、JUnit 5、Mockito

**Spec:** [2026-09-29-redis-stock-outbox-stream-listener-design.md](../specs/2026-09-29-redis-stock-outbox-stream-listener-design.md)

## Global Constraints

- Relay 只发 RocketMQ，禁止调 `applyDelta`
- 继续 `StockSyncEventPublisher.sendNow`（`sendImmediateByKey`），不要退回 `sendImmediate`
- 不改 Lua、`StockSyncMqListener`、`applyDelta`
- `autoAcknowledge(false)`；容器不得代发 `XACK`
- `cancelOnError` 对任意异常返回 false
- 确认：`acknowledgeAndDelete(OUTBOX, outboxGroup, ACKED, recordId)`；返回不是 `DELETED` 只 warn
- 消费者名每 JVM 启动算一次，看门狗重注册不得换名；配置 `outbox-consumer` 只当前缀
- 不用分布式锁互斥 Relay；看门狗不得 `XGROUP DELCONSUMER`
- 建组只忽略消息含 `BUSYGROUP` 的异常，其它创建失败要抛出
- 测试在 `demo2` 目录跑 Maven；git 在仓库根目录；不新增真实 Redis/RocketMQ 集成测试

---

## File Structure

- `demo2/src/main/java/com/jason/demo/demo2/product/app/listener/OutboxConsumerNames.java` — `{prefix}-{hostname}-{pid}`
- `demo2/src/test/java/com/jason/demo/demo2/product/OutboxConsumerNamesTest.java` — 锁住格式与 unknown 主机
- `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/config/ProductStockProperties.java` — claim/watchdog 间隔与 min-idle
- `demo2/src/main/java/com/jason/demo/demo2/product/app/listener/RedisStockOutboxRelay.java` — 容器 + 补发 + 看门狗
- `demo2/src/test/java/com/jason/demo/demo2/product/RedisStockOutboxRelayTest.java` — 确认、补发隔离、注册参数、看门狗
- `demo2/src/main/resources/application.properties` — 新配置项（可选写出默认）
- `demo2/README.md`、`docs/superpowers/specs/2026-08-27-redis-stock-consistency-design.md` §6、本 spec 状态

---

### Task 1: 消费者名与出箱调度配置

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/product/app/listener/OutboxConsumerNames.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/product/OutboxConsumerNamesTest.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/config/ProductStockProperties.java`
- Modify: `demo2/src/main/resources/application.properties`（在 `outbox-consumer` 下追加三项）

**Interfaces:**
- Consumes: 无
- Produces: `OutboxConsumerNames.unique(String prefix, String hostname, long pid)` → `String`；`OutboxConsumerNames.forThisProcess(String prefix)` → `String`；`ProductStockProperties.outboxClaimIntervalMs` 默认 `10000`；`outboxClaimMinIdleMs` 默认 `30000`；`outboxWatchdogIntervalMs` 默认 `10000`

- [ ] **Step 1: Write the failing test**

```java
package com.jason.demo.demo2.product;

import com.jason.demo.demo2.product.app.listener.OutboxConsumerNames;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxConsumerNamesTest {

    @Test
    void unique_joinsPrefixHostPid() {
        assertEquals("relay-box-42", OutboxConsumerNames.unique("relay", "box", 42L));
    }

    @Test
    void unique_blankHost_usesUnknown() {
        assertEquals("relay-unknown-1", OutboxConsumerNames.unique("relay", "  ", 1L));
        assertEquals("relay-unknown-1", OutboxConsumerNames.unique("relay", null, 1L));
    }

    @Test
    void unique_differentPid_differentName() {
        assertNotEquals(
                OutboxConsumerNames.unique("relay", "box", 1L),
                OutboxConsumerNames.unique("relay", "box", 2L));
    }

    @Test
    void forThisProcess_startsWithPrefixDash() {
        String name = OutboxConsumerNames.forThisProcess("relay");
        assertTrue(name.startsWith("relay-"));
        assertTrue(name.length() > "relay-".length());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd demo2 && mvn -q -Dtest=OutboxConsumerNamesTest test`

Expected: FAIL，类不存在

- [ ] **Step 3: Write minimal implementation**

`OutboxConsumerNames.java`：

```java
package com.jason.demo.demo2.product.app.listener;

import java.net.InetAddress;

public final class OutboxConsumerNames {

    private OutboxConsumerNames() {
    }

    public static String unique(String prefix, String hostname, long pid) {
        String host = hostname == null || hostname.isBlank() ? "unknown" : hostname.trim();
        return prefix + "-" + host + "-" + pid;
    }

    public static String forThisProcess(String prefix) {
        return unique(prefix, hostname(), ProcessHandle.current().pid());
    }

    static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            return "unknown";
        }
    }
}
```

`ProductStockProperties` 增加字段（Lombok `@Data` 已有）：

```java
private long outboxClaimIntervalMs = 10000;
private long outboxClaimMinIdleMs = 30000;
private long outboxWatchdogIntervalMs = 10000;
```

`application.properties` 追加：

```properties
app.product.stock.outbox-claim-interval-ms=10000
app.product.stock.outbox-claim-min-idle-ms=30000
app.product.stock.outbox-watchdog-interval-ms=10000
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd demo2 && mvn -q -Dtest=OutboxConsumerNamesTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/product/app/listener/OutboxConsumerNames.java demo2/src/test/java/com/jason/demo/demo2/product/OutboxConsumerNamesTest.java demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/config/ProductStockProperties.java demo2/src/main/resources/application.properties
git commit -m "feat(product): unique Redis stream consumer names for stock outbox"
```

---

### Task 2: 确认返回非 DELETED 不抛错

**Files:**
- Modify: `demo2/src/test/java/com/jason/demo/demo2/product/RedisStockOutboxRelayTest.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/app/listener/RedisStockOutboxRelay.java`（若当前对非 DELETED 已只 warn，本 task 只补测试）

**Interfaces:**
- Consumes: 现有 `onRecord(Map<String,String>, String)`
- Produces: 非 `DELETED` 时方法正常返回，仍调用了 `acknowledgeAndDelete`

- [ ] **Step 1: Write the failing test**

在 `RedisStockOutboxRelayTest` 增加：

```java
@Test
void onRecord_ackNotDeleted_doesNotThrow() {
    when(streamOps.acknowledgeAndDelete(eq(RedisStockKeys.OUTBOX), eq("demo2-stock-relay"),
            any(RedisStreamCommands.XDelOptions.class), eq("1-0")))
            .thenReturn(List.of(StreamEntryDeletionResult.NOT_FOUND));

    relay.onRecord(sampleFields(), "1-0");

    verify(publisher).sendNow(any());
    verify(streamOps).acknowledgeAndDelete(RedisStockKeys.OUTBOX, "demo2-stock-relay", acked(), "1-0");
}
```

若编译期没有 `NOT_FOUND`，改用该枚举里 **不是** `DELETED` 的那个常量（打开 `StreamEntryDeletionResult` 源码，只许用真实枚举名）。

- [ ] **Step 2: Run test**

Run: `cd demo2 && mvn -q -Dtest=RedisStockOutboxRelayTest#onRecord_ackNotDeleted_doesNotThrow test`

Expected: PASS（现有实现已 warn 不抛）或 FAIL（若实现抛了错则改 `onRecord`：仅 `if (result != DELETED) log.warn`）

- [ ] **Step 3: Implement only if red**

保持：

```java
if (result != StreamEntryDeletionResult.DELETED) {
    log.warn("outbox entry not deleted after ack, recordId={}, result={}", recordId, result);
}
```

不要 throw。

- [ ] **Step 4: Re-run `RedisStockOutboxRelayTest`**

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/test/java/com/jason/demo/demo2/product/RedisStockOutboxRelayTest.java demo2/src/main/java/com/jason/demo/demo2/product/app/listener/RedisStockOutboxRelay.java
git commit -m "test(product): lock outbox ack when XACKDEL does not delete"
```

若工作区无变更则跳过 commit。

---

### Task 3: 换成容器订阅（删虚拟线程循环）

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/app/listener/RedisStockOutboxRelay.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/product/RedisStockOutboxRelayTest.java`

**Interfaces:**
- Consumes: `OutboxConsumerNames`；`StringRedisTemplate.getConnectionFactory()`；`ProductStockProperties` 阻塞/批量/组名
- Produces: `RedisStockOutboxRelay` 实现 `StreamListener<String, MapRecord<String, String, String>>` 与 `SmartLifecycle`；生产构造器 `RedisStockOutboxRelay(StringRedisTemplate, StockSyncEventPublisher, ProductStockProperties)` 内调用 `OutboxConsumerNames.forThisProcess(properties.getOutboxConsumer())`；测试构造器第四参 `String consumerName`；`onMessage` 调 `onRecord`；`newReadRequest()`；`pollExecutor()` 为 `ExecutorService`；`ensureGroup()` 只吞 `BUSYGROUP`

- [ ] **Step 1: Write the failing tests**

构造改为可注入消费者名。`setUp`：

```java
relay = new RedisStockOutboxRelay(redis, publisher, properties, "relay-testhost-1");
```

增加：

```java
@Test
void consumerName_isInjectedNotBarePrefix() {
    assertEquals("relay-testhost-1", relay.consumerName());
    assertNotEquals("relay", relay.consumerName());
}

@Test
void readRequest_manualAck_andNeverCancelsOnError() {
    var request = relay.newReadRequest();
    assertTrue(request instanceof StreamMessageListenerContainer.ConsumerStreamReadRequest<?>);
    var consumerRequest = (StreamMessageListenerContainer.ConsumerStreamReadRequest<String>) request;
    assertFalse(consumerRequest.isAutoAcknowledge());
    assertFalse(consumerRequest.getCancelSubscriptionOnError().test(new RuntimeException("x")));
    assertEquals("demo2-stock-relay", consumerRequest.getConsumer().getGroup());
    assertEquals("relay-testhost-1", consumerRequest.getConsumer().getName());
}

@Test
void pollExecutor_isExecutorService() {
    assertTrue(relay.pollExecutor() instanceof ExecutorService);
}

@Test
void ensureGroup_busyGroup_isIgnored() {
    doThrow(new RuntimeException("BUSYGROUP Consumer Group name already exists"))
            .when(streamOps).createGroup(eq(RedisStockKeys.OUTBOX), any(), eq("demo2-stock-relay"));
    relay.ensureGroup();
}

@Test
void ensureGroup_otherError_propagates() {
    doThrow(new IllegalStateException("NOGROUP")).when(streamOps)
            .createGroup(eq(RedisStockKeys.OUTBOX), any(), eq("demo2-stock-relay"));
    assertThrows(IllegalStateException.class, () -> relay.ensureGroup());
}
```

`onMessage` 测试：

```java
@Test
void onMessage_sendSuccess_acksGroupNotConsumer() {
    MapRecord<String, String, String> record = MapRecord.create(RedisStockKeys.OUTBOX, sampleFields()).withId(RecordId.of("1-0"));
    relay.onMessage(record);
    verify(streamOps).acknowledgeAndDelete(RedisStockKeys.OUTBOX, "demo2-stock-relay", acked(), "1-0");
}
```

需要的 import：`StreamMessageListenerContainer`、`ExecutorService`、`assertEquals`、`assertNotEquals`。

- [ ] **Step 2: Run tests, expect FAIL**（缺方法 / 仍吞掉全部建组异常）

Run: `cd demo2 && mvn -q -Dtest=RedisStockOutboxRelayTest test`

- [ ] **Step 3: Rewrite Relay 拉取部分**

删掉 `Thread worker`、`loopCount`、`loop()`、`readOnce()`。保留 `ACKED`、`onRecord`、`stringify`、`required`。

类声明：

```java
public class RedisStockOutboxRelay implements SmartLifecycle,
        StreamListener<String, MapRecord<String, String, String>> {
```

字段增加：`consumerName`、`ExecutorService pollExecutor`、`StreamMessageListenerContainer<String, MapRecord<String, String, String>> container`、`Subscription subscription`、`volatile Future<?> pollFuture`、`ScheduledExecutorService scheduler`（本 task 可以先不 start scheduler，Task 4/5 再挂任务）。

生产构造：

```java
public RedisStockOutboxRelay(StringRedisTemplate redis, StockSyncEventPublisher publisher,
        ProductStockProperties properties) {
    this(redis, publisher, properties, OutboxConsumerNames.forThisProcess(properties.getOutboxConsumer()));
}

RedisStockOutboxRelay(StringRedisTemplate redis, StockSyncEventPublisher publisher,
        ProductStockProperties properties, String consumerName) {
    this.redis = redis;
    this.publisher = publisher;
    this.properties = properties;
    this.consumerName = consumerName;
    this.pollExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stock-outbox-poll");
        t.setDaemon(true);
        return t;
    });
}
```

`pollExecutor()` 返回该 `ExecutorService`。容器 `executor` 必须是包装器：

```java
Executor trackingExecutor() {
    return command -> pollFuture = pollExecutor.submit(command);
}
```

`newReadRequest()`：

```java
StreamMessageListenerContainer.StreamReadRequest<String> newReadRequest() {
    return StreamMessageListenerContainer.StreamReadRequest.builder(
                    StreamOffset.create(RedisStockKeys.OUTBOX, ReadOffset.lastConsumed()))
            .consumer(Consumer.from(properties.getOutboxGroup(), consumerName))
            .autoAcknowledge(false)
            .cancelOnError(throwable -> false)
            .build();
}
```

`onMessage`：

```java
@Override
public void onMessage(MapRecord<String, String, String> message) {
    Map<String, String> fields = new LinkedHashMap<>();
    message.getValue().forEach((k, v) -> fields.put(String.valueOf(k), String.valueOf(v)));
    onRecord(fields, message.getId().getValue());
}
```

`ensureGroup`：只当 `isBusyGroup(ex)` 为 true 时 return，否则 `throw ex`。`isBusyGroup` 沿 cause 链找 `"BUSYGROUP"`。方法从 `private` 改为包可见以便测试。

`start()`：`ensureGroup()`；若 `container == null` 则 `StreamMessageListenerContainer.create(redis.getConnectionFactory(), options)`，options：`pollTimeout(Duration.ofMillis(outboxBlockMs))`、`batchSize(outboxBatchSize)`、`executor(trackingExecutor())`、key/hashKey/hashValue 均为 `RedisSerializer.string()`；`subscription = container.register(newReadRequest(), this)`；`container.start()`。`isAutoStartup` 仍跟 `redisHotEnabled`。

`stop()`：`running = false`；若 `subscription != null` 则 `container.remove(subscription)`；`container.stop()`；不要在本 task shutdown `pollExecutor` 直到 Task 5 的 stop 完整化（本 task `stop` 必须 `container.stop()` 且 `pollExecutor.shutdownNow()`，看门狗 scheduler 在 Task 5 创建）。

`consumerName()` 包可见 getter。

若 `createGroup` 的 Mockito stub 签名与现有 `ReadOffset` 重载不一致，以编译器选出的重载为准，测试里 `any()` 对齐。

- [ ] **Step 4: Run `RedisStockOutboxRelayTest`**

Expected: PASS（含原 `onRecord_*`、`autoStartup_*`）

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/product/app/listener/RedisStockOutboxRelay.java demo2/src/test/java/com/jason/demo/demo2/product/RedisStockOutboxRelayTest.java
git commit -m "feat(product): consume stock outbox with StreamMessageListenerContainer"
```

---

### Task 4: 独立补发（组 PEL → 本机消费者）

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/app/listener/RedisStockOutboxRelay.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/product/RedisStockOutboxRelayTest.java`

**Interfaces:**
- Consumes: `outboxClaimMinIdleMs`、`outboxBatchSize`、`consumerName`
- Produces: `claimIdlePending()` 使用 `pending(OUTBOX, group, Range.unbounded(), batchSize, minIdle)` 再 `claim(OUTBOX, group, consumerName, minIdle, ids...)`；单条 `onRecord` 失败不中断同批

说明：不要调用不存在的 `StreamOperations.autoClaim`。组级 `pending` + `claim` 到本机名，等于 spec 的 `XAUTOCLAIM`。

- [ ] **Step 1: Replace `claimIdle_sendFails_doesNotAck` and add isolation test**

`pending` stub 改为带 `Duration` 与 `count` 的重载（与实现一致）。`claim` 的 newOwner 为 `"relay-testhost-1"`。

```java
@Test
void claimIdle_oneSendFails_stillSendsRest() {
    RecordId id1 = RecordId.of("2-0");
    RecordId id2 = RecordId.of("3-0");
    PendingMessage p1 = mock(PendingMessage.class);
    PendingMessage p2 = mock(PendingMessage.class);
    when(p1.getId()).thenReturn(id1);
    when(p2.getId()).thenReturn(id2);
    PendingMessages pending = mock(PendingMessages.class);
    when(pending.isEmpty()).thenReturn(false);
    when(pending.iterator()).thenReturn(List.of(p1, p2).iterator());
    when(streamOps.pending(eq(RedisStockKeys.OUTBOX), eq("demo2-stock-relay"), any(Range.class),
            eq(16L), eq(Duration.ofSeconds(30)))).thenReturn(pending);

    MapRecord<String, Object, Object> rec1 = MapRecord.create(RedisStockKeys.OUTBOX, body()).withId(id1);
    MapRecord<String, Object, Object> rec2 = MapRecord.create(RedisStockKeys.OUTBOX, body()).withId(id2);
    when(streamOps.claim(eq(RedisStockKeys.OUTBOX), eq("demo2-stock-relay"), eq("relay-testhost-1"),
            eq(Duration.ofSeconds(30)), eq(id1), eq(id2))).thenReturn(List.of(rec1, rec2));
    doThrow(new IllegalStateException("mq down")).doNothing().when(publisher).sendNow(any());

    relay.claimIdlePending();

    verify(publisher, times(2)).sendNow(any());
    verify(streamOps, never()).acknowledgeAndDelete(any(), any(), any(), eq("2-0"));
    verify(streamOps).acknowledgeAndDelete(eq(RedisStockKeys.OUTBOX), eq("demo2-stock-relay"),
            any(RedisStreamCommands.XDelOptions.class), eq("3-0"));
}

private static Map<Object, Object> body() {
    Map<Object, Object> body = new LinkedHashMap<>();
    body.put("productId", "9001");
    body.put("orderId", "100");
    body.put("optType", "RESERVE");
    body.put("qty", "2");
    body.put("idempotentKey", "100:9001:RESERVE");
    body.put("seq", "4");
    return body;
}
```

`pending` 的参数顺序若与本机 Spring Data 不一致（有的版本是 `range, minIdle, count`），以 `StreamOperations` 编译签名为准改测试和方法，**必须带 min-idle 与 batchSize**，禁止无 idle 过滤的全量 pending。

- [ ] **Step 2: Run, expect FAIL**（旧 `claim` 仍用 `"relay"` 或一条失败抛出）

- [ ] **Step 3: Implement `claimIdlePending`**

```java
public void claimIdlePending() {
    Duration minIdle = Duration.ofMillis(properties.getOutboxClaimMinIdleMs());
    StreamOperations<String, Object, Object> ops = redis.opsForStream();
    PendingMessages pending = ops.pending(
            RedisStockKeys.OUTBOX,
            properties.getOutboxGroup(),
            Range.unbounded(),
            properties.getOutboxBatchSize(),
            minIdle);
    if (pending == null || pending.isEmpty()) {
        return;
    }
    List<RecordId> ids = new ArrayList<>();
    pending.forEach(message -> ids.add(message.getId()));
    if (ids.isEmpty()) {
        return;
    }
    List<MapRecord<String, Object, Object>> claimed = ops.claim(
            RedisStockKeys.OUTBOX,
            properties.getOutboxGroup(),
            consumerName,
            minIdle,
            ids.toArray(RecordId[]::new));
    if (claimed == null) {
        return;
    }
    for (MapRecord<String, Object, Object> record : claimed) {
        try {
            onRecord(stringify(record.getValue()), record.getId().getValue());
        } catch (RuntimeException ex) {
            log.warn("stock outbox claim send failed, recordId={}", record.getId(), ex);
        }
    }
}
```

`start()` 里 `scheduler = Executors.newScheduledThreadPool(2, ...)`，`scheduleWithFixedDelay(this::claimIdlePending, interval, interval, MILLISECONDS)`，interval = `outboxClaimIntervalMs`。仅当 `running` 为 true 时 claim（方法开头 `if (!running) return`）。

删掉旧的「先 pending 100 再按 idle 过滤再 claim `"relay"`」路径。

- [ ] **Step 4: Run `RedisStockOutboxRelayTest`**

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/product/app/listener/RedisStockOutboxRelay.java demo2/src/test/java/com/jason/demo/demo2/product/RedisStockOutboxRelayTest.java
git commit -m "feat(product): reclaim idle stock outbox PEL onto this consumer"
```

---

### Task 5: 看门狗（本机 Future + 同名重注册）

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/app/listener/RedisStockOutboxRelay.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/product/RedisStockOutboxRelayTest.java`

**Interfaces:**
- Consumes: `outboxWatchdogIntervalMs`；`pollFuture`；`subscription`
- Produces: `boolean shouldReregister(Subscription, Future<?>)`；`void watchOnce()`；`stop()` 时看门狗直接 return 且 `scheduler.shutdownNow()`；重注册使用同一个 `consumerName`

- [ ] **Step 1: Write tests**

```java
@Test
void shouldReregister_whenSubscriptionInactiveOrFutureDone() {
    Subscription active = mock(Subscription.class);
    when(active.isActive()).thenReturn(true);
    Future<?> live = mock(Future.class);
    when(live.isDone()).thenReturn(false);
    assertFalse(relay.shouldReregister(active, live));
    assertTrue(relay.shouldReregister(null, live));
    when(active.isActive()).thenReturn(false);
    assertTrue(relay.shouldReregister(active, live));
    when(active.isActive()).thenReturn(true);
    when(live.isDone()).thenReturn(true);
    assertTrue(relay.shouldReregister(active, live));
}

@Test
void watchOnce_whenStopped_doesNotChangeConsumerName() {
    relay.stop();
    String before = relay.consumerName();
    relay.watchOnce();
    assertEquals(before, relay.consumerName());
}
```

- [ ] **Step 2: Run, expect FAIL**（方法不存在）

- [ ] **Step 3: Implement**

```java
boolean shouldReregister(Subscription sub, Future<?> poll) {
    if (sub == null) {
        return true;
    }
    if (!sub.isActive()) {
        return true;
    }
    return poll != null && poll.isDone();
}

void watchOnce() {
    if (!running) {
        return;
    }
    if (!shouldReregister(subscription, pollFuture)) {
        return;
    }
    if (subscription != null && container != null) {
        container.remove(subscription);
    }
    subscription = container.register(newReadRequest(), this);
    if (!container.isRunning()) {
        container.start();
    }
}
```

`start()` 在 claim 调度之外再 `scheduleWithFixedDelay(this::watchOnce, wd, wd, MILLISECONDS)`。`stop()`：先 `running = false`，再 shutdown scheduler、remove 订阅、stop 容器、shutdown pollExecutor。`watchOnce` 不得 `XGROUP DELCONSUMER`。

`watchOnce` 在 `container == null` 时 return（未 start）。测试 `watchOnce_whenStopped` 不需要真实容器。

- [ ] **Step 4: Run `RedisStockOutboxRelayTest` 与 `OutboxConsumerNamesTest`**

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/product/app/listener/RedisStockOutboxRelay.java demo2/src/test/java/com/jason/demo/demo2/product/RedisStockOutboxRelayTest.java
git commit -m "feat(product): restart stock outbox subscription from poll Future"
```

---

### Task 6: 文档与 spec 状态

**Files:**
- Modify: `demo2/README.md`（热库存 Redis Key 表与配置块）
- Modify: `demo2/docs/superpowers/specs/2026-08-27-redis-stock-consistency-design.md` §6
- Modify: `demo2/docs/superpowers/specs/2026-09-29-redis-stock-outbox-stream-listener-design.md` 状态改为已实现

**Interfaces:**
- Consumes: 已实现行为
- Produces: 文档与代码一致

- [ ] **Step 1: README**

`demo2:stock:outbox` 那一行改为：出箱 Stream；容器拉新消息；**发 MQ 成功才 `XACKDEL(ACKED)`**；空闲 PEL 由独立调度 `pending+claim` 到 `{outbox-consumer}-{hostname}-{pid}`。

配置块补上 claim/watchdog 三项，并注明 `outbox-consumer` 只是前缀。

读写分工程图 Relay 一行可写成：`StreamMessageListenerContainer` → `sendNow` 按 productId 选队列。

- [ ] **Step 2: 2026-08-27 spec §6**

把

```text
→ RedisStockOutboxRelay XREADGROUP
→ ...
→ 发送成功才 XACK Stream
```

改成指向 [2026-09-29 spec](./2026-09-29-redis-stock-outbox-stream-listener-design.md)：容器拉流，发送成功才 `XACKDEL(ACKED)`。Lua / `applyDelta` / 对账各节不动。

- [ ] **Step 3: 将 2026-09-29 spec `状态` 改为 `已实现`**

- [ ] **Step 4: Commit**

```bash
git add demo2/README.md demo2/docs/superpowers/specs/2026-08-27-redis-stock-consistency-design.md demo2/docs/superpowers/specs/2026-09-29-redis-stock-outbox-stream-listener-design.md
git commit -m "docs(product): record stream listener stock outbox relay"
```

---

## Self-review

| Spec | Task |
|------|------|
| 容器 + StreamListener，手动确认，cancelOnError false | 3 |
| sendNow 后 XACKDEL ACKED；非 DELETED 只 warn | 2、3 |
| 独立补发、min-idle 30s、batchSize、不中断同批 | 4 |
| SDR 无 autoClaim → pending+claim 等价 | 4（计划约束） |
| 看门狗 Future + 同名重注册 + 已 stop 不拉起 | 5 |
| 消费者名 prefix-host-pid，组名共享 | 1、3 |
| 只忽略 BUSYGROUP | 3 |
| 不改 Lua / MQ 监听 / applyDelta | 全局 |
| README + 08-27 §6 | 6 |
| 热路径开关 isAutoStartup | 已有测试，start 不在开关 false 时自动跑 |

无 TBD。Task 3 测试构造器第四参 `consumerName` 供后续 claim/watchdog 使用，生产构造走 `forThisProcess`。
