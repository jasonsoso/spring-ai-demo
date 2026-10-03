# 库存同步按商品串行消费 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 同一个 `productId` 的库存投影一个一个执行 `applyDelta`，不同 `productId` 可以同时执行。

**Architecture:** `sendNow` 用 `productId` 哈希选择 RocketMQ 队列，使同一商品在集群稳态下只落在一台消费者上。`StockSyncMqListener` 仍是并发监听，进入 `applyDelta` 前按 `productId` 加进程内锁。seq 缺口继续 `RECONSUME_LATER`。`stock_seq = seq - 1` 继续挡住重平衡窗口里的并发写入。

**Tech Stack:** Java 21、Spring Boot、RocketMQ client 5.5.1、JUnit 5、Mockito、AssertJ

**Spec:** [2026-09-30-stock-sync-product-serial-consume-design.md](../specs/2026-09-30-stock-sync-product-serial-consume-design.md)

## Global Constraints

- 发送方法签名是 `protected void sendImmediateByKey(Object messageBodyObj, String shardingKey, String... keys)`
- 选队列公式是 `Math.floorMod(shardingKey.hashCode(), queues.size())`，与 `sendOrderly` 共用同一个私有方法
- `sendImmediateByKey` 在当前线程同步发送，不走 `afterCommit`；`maxTryTimes` 次失败后抛 `IllegalStateException`，消息与 `sendImmediate` 相同：`rocketmq immediate send failed after retries`
- `sendNow` 调用 `sendImmediateByKey(event, String.valueOf(event.getProductId()), String.valueOf(event.getProductId()), event.getIdempotentKey())`
- 消费锁在 `StockSyncMqListener` 内：`ConcurrentHashMap<Long, Object>`，`computeIfAbsent(productId, id -> new Object())`，`synchronized` 只包住 `applyDelta`
- 锁对象留到进程结束，不移除
- `productId` 为空时不加锁
- `StockSeqGapException` 与 `STOCK_CONFLICT` 的捕获在锁外面
- 监听保持 `MessageListenerConcurrently`，不改为顺序消费
- 不改 `applyDelta`、Lua、`RedisStockOutboxRelay`，不加 Redis 分布式锁，不改 topic 队列数
- 不保证同商品按 seq 先执行
- 测试命令在 `demo2` 目录执行；git 在仓库根目录执行
- 不新增真实 RocketMQ 集成测试

---

## File Structure

- `demo2/src/main/java/com/jason/demo/demo2/framework/rocketmq/producer/BaseEventPublisher.java` — 增加 `sendImmediateByKey`，并把队列选择抽成 `sendOrderly` 与它共用的私有方法
- `demo2/src/test/java/com/jason/demo/demo2/framework/rocketmq/producer/BaseEventPublisherTest.java` — 锁住三参数 `send`、同一 key 的队列下标、重试后抛异常
- `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/publisher/StockSyncEventPublisher.java` — `sendNow` 改为按 `productId` 选队列
- `demo2/src/test/java/com/jason/demo/demo2/product/StockSyncEventPublisherTest.java` — 锁住 `sendNow` 的 sharding key 与消息 keys
- `demo2/src/main/java/com/jason/demo/demo2/product/app/listener/StockSyncMqListener.java` — 按 `productId` 互斥调用 `applyDelta`
- `demo2/src/test/java/com/jason/demo/demo2/product/StockSyncMqListenerTest.java` — 同商品不重叠、不同商品可重叠、缺口释放锁
- `demo2/README.md` — 热库存读写分工改成选队列与加锁
- `demo2/docs/superpowers/specs/2026-09-30-stock-sync-product-serial-consume-design.md` — 实现完成后把状态改为已实现

---

### Task 1: `sendImmediateByKey`

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/framework/rocketmq/producer/BaseEventPublisher.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/framework/rocketmq/producer/BaseEventPublisherTest.java`

**Interfaces:**
- Consumes: 已有 `buildMessage`、`maxTryTimes`、`producer`
- Produces: `protected void sendImmediateByKey(Object messageBodyObj, String shardingKey, String... keys)`；私有 `selectQueue(List<MessageQueue> queues, Message message, Object shardingKey)`

- [ ] **Step 1: Write the failing test**

在 `BaseEventPublisherTest` 的 import 中加入：

```java
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.common.message.MessageQueue;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
```

`DemoPublisher` 增加：

```java
void publishByKey(String shardingKey) {
    sendImmediateByKey(Map.of("productId", shardingKey), shardingKey, shardingKey, "idem");
}
```

在类中增加两个测试：

```java
@Test
void sendImmediateByKey_selectsStableQueueAndSkipsPlainSend() throws Exception {
    DemoPublisher pub = ready("stockSyncProducer", "DEMO_STOCK_TOPIC");
    when(producer.send(any(Message.class), any(MessageQueueSelector.class), any()))
            .thenReturn(new SendResult());

    pub.publishByKey("1001");

    ArgumentCaptor<MessageQueueSelector> selectorCaptor = ArgumentCaptor.forClass(MessageQueueSelector.class);
    ArgumentCaptor<Object> argCaptor = ArgumentCaptor.forClass(Object.class);
    verify(producer).send(any(Message.class), selectorCaptor.capture(), argCaptor.capture());
    verify(producer, never()).send(any(Message.class));
    assertThat(argCaptor.getValue()).isEqualTo("1001");

    List<MessageQueue> queues = List.of(
            new MessageQueue("DEMO_STOCK_TOPIC", "broker-a", 0),
            new MessageQueue("DEMO_STOCK_TOPIC", "broker-a", 1),
            new MessageQueue("DEMO_STOCK_TOPIC", "broker-a", 2),
            new MessageQueue("DEMO_STOCK_TOPIC", "broker-a", 3));
    Message probe = new Message("DEMO_STOCK_TOPIC", new byte[0]);
    MessageQueue first = selectorCaptor.getValue().select(queues, probe, "1001");
    MessageQueue second = selectorCaptor.getValue().select(queues, probe, "1001");
    assertThat(first.getQueueId()).isEqualTo(Math.floorMod("1001".hashCode(), 4));
    assertThat(second.getQueueId()).isEqualTo(first.getQueueId());
}

@Test
void sendImmediateByKey_retriesThenThrows() throws Exception {
    DemoPublisher pub = ready("stockSyncProducer", "DEMO_STOCK_TOPIC");
    when(producer.send(any(Message.class), any(MessageQueueSelector.class), any()))
            .thenThrow(new RuntimeException("temp"));

    assertThatThrownBy(() -> pub.publishByKey("1001"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("rocketmq immediate send failed after retries");
    verify(producer, times(2)).send(any(Message.class), any(MessageQueueSelector.class), eq("1001"));
    verify(producer, never()).send(any(Message.class));
}

private DemoPublisher ready(String producerId, String topic) {
    RocketMQProperties properties = new RocketMQProperties();
    RocketMQProperties.ProducerConfig config = new RocketMQProperties.ProducerConfig();
    config.setTopic(topic);
    properties.getProducers().put(producerId, config);
    when(applicationContext.getBean(producerId, DefaultMQProducer.class)).thenReturn(producer);
    when(applicationContext.getBean(JsonMapper.class)).thenReturn(JsonMapper.builder().build());
    when(applicationContext.getBean(RocketMQProperties.class)).thenReturn(properties);
    when(applicationContext.getBeanProvider(RocketMqTracePropagator.class))
            .thenReturn(tracePropagatorProvider);
    DemoPublisher pub = new DemoPublisher(producerId);
    pub.setApplicationContext(applicationContext);
    pub.initialize();
    return pub;
}
```

现有 `send_retriesThenSucceeds_withoutTag` 保持原样，继续验证无选择器的 `producer.send(Message)`。

- [ ] **Step 2: Run test to verify it fails**

在 `demo2` 目录执行：

```powershell
.\mvnw.cmd test "-Dtest=BaseEventPublisherTest"
```

Expected: 编译失败，`sendImmediateByKey` 找不到。

- [ ] **Step 3: Write minimal implementation**

把 `sendOrderly` 里的选择器换成共用方法。`sendOrderly` 其余行为不变：事务提交后发送，失败只打日志。

```java
SendResult sendResult = producer.send(message, BaseEventPublisher::selectQueue, shardingKey);
```

在 `sendImmediate` 后面增加：

```java
/**
 * 立即同步发送，并按 shardingKey 哈希选择队列。
 * 不走 afterCommit。重试耗尽后抛异常，供出箱 Relay 据此不 XACK。
 */
protected void sendImmediateByKey(Object messageBodyObj, String shardingKey, String... keys) {
    Message message = buildMessage(messageBodyObj, keys);
    Exception last = null;
    for (int i = 0; i < maxTryTimes; i++) {
        try {
            SendResult sendResult = producer.send(message, BaseEventPublisher::selectQueue, shardingKey);
            log.info("immediate send by key success, attempt:{}, result:{}", i + 1, sendResult);
            return;
        } catch (Exception e) {
            last = e;
            log.error("immediate send by key error, attempt:{}, message:{}", i + 1, messageBodyObj, e);
            if (i < maxTryTimes - 1) {
                sleepQuietly(100L * (i + 1));
            }
        }
    }
    throw new IllegalStateException("rocketmq immediate send failed after retries", last);
}

/** message 只为匹配 MessageQueueSelector，下标只由 shardingKey 决定。 */
private static MessageQueue selectQueue(List<MessageQueue> queues, Message message, Object shardingKey) {
    int index = Math.floorMod(shardingKey.hashCode(), queues.size());
    return queues.get(index);
}
```

补上 `MessageQueue` 的 import：`org.apache.rocketmq.common.message.MessageQueue`。`Message` 与 `List` 已经导入。

- [ ] **Step 4: Run test to verify it passes**

在 `demo2` 目录执行：

```powershell
.\mvnw.cmd test "-Dtest=BaseEventPublisherTest"
```

Expected: PASS。`sendImmediateByKey_retriesThenThrows` 会睡约 100 毫秒。

- [ ] **Step 5: Commit**

在仓库根目录执行：

```powershell
git add demo2/src/main/java/com/jason/demo/demo2/framework/rocketmq/producer/BaseEventPublisher.java demo2/src/test/java/com/jason/demo/demo2/framework/rocketmq/producer/BaseEventPublisherTest.java
git commit -m "feat(rocketmq): send immediately onto the queue selected by key"
```

---

### Task 2: `sendNow` 按商品选队列

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/publisher/StockSyncEventPublisher.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/product/StockSyncEventPublisherTest.java`

**Interfaces:**
- Consumes: Task 1 的 `sendImmediateByKey(Object, String, String...)`
- Produces: `StockSyncEventPublisher.sendNow(StockSyncEvent)` 使用 `productId` 的十进制字符串作为 sharding key，消息 keys 为 `productId` 与幂等键

- [ ] **Step 1: Write the failing test**

创建 `StockSyncEventPublisherTest`：

```java
package com.jason.demo.demo2.product;

import com.jason.demo.demo2.framework.rocketmq.RocketMqTracePropagator;
import com.jason.demo.demo2.framework.rocketmq.configuration.RocketMQProperties;
import com.jason.demo.demo2.product.service.infrastructure.publisher.StockSyncEvent;
import com.jason.demo.demo2.product.service.infrastructure.publisher.StockSyncEventPublisher;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockSyncEventPublisherTest {

    @Mock
    ApplicationContext applicationContext;
    @Mock
    DefaultMQProducer producer;
    @Mock
    ObjectProvider<RocketMqTracePropagator> tracePropagatorProvider;

    @Test
    void sendNow_selectsQueueByProductId() throws Exception {
        RocketMQProperties properties = new RocketMQProperties();
        RocketMQProperties.ProducerConfig config = new RocketMQProperties.ProducerConfig();
        config.setTopic("DEMO_STOCK_TOPIC");
        properties.getProducers().put(StockSyncEventPublisher.PRODUCER_ID, config);
        when(applicationContext.getBean(StockSyncEventPublisher.PRODUCER_ID, DefaultMQProducer.class))
                .thenReturn(producer);
        when(applicationContext.getBean(JsonMapper.class)).thenReturn(JsonMapper.builder().build());
        when(applicationContext.getBean(RocketMQProperties.class)).thenReturn(properties);
        when(applicationContext.getBeanProvider(RocketMqTracePropagator.class))
                .thenReturn(tracePropagatorProvider);

        StockSyncEventPublisher publisher = new StockSyncEventPublisher();
        publisher.setApplicationContext(applicationContext);
        publisher.initialize();
        publisher.sendNow(new StockSyncEvent(9001L, 100L, "RESERVE", 2, "100:9001:RESERVE", 4L));

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<Object> argCaptor = ArgumentCaptor.forClass(Object.class);
        verify(producer).send(messageCaptor.capture(), any(MessageQueueSelector.class), argCaptor.capture());
        verify(producer, never()).send(any(Message.class));
        assertThat(argCaptor.getValue()).isEqualTo("9001");
        assertThat(messageCaptor.getValue().getKeys()).isEqualTo("9001 100:9001:RESERVE");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

在 `demo2` 目录执行：

```powershell
.\mvnw.cmd test "-Dtest=StockSyncEventPublisherTest"
```

Expected: FAIL。`sendNow` 仍调用单参数 `send(Message)`，三参数 `verify` 失败。

- [ ] **Step 3: Write minimal implementation**

把 `StockSyncEventPublisher.sendNow` 换成：

```java
/** 出箱专用：按 productId 选队列同步发送。失败抛给 Relay，以便不 XACK。 */
public void sendNow(StockSyncEvent event) {
    String productId = String.valueOf(event.getProductId());
    sendImmediateByKey(event, productId, productId, event.getIdempotentKey());
}
```

- [ ] **Step 4: Run test to verify it passes**

在 `demo2` 目录执行：

```powershell
.\mvnw.cmd test "-Dtest=StockSyncEventPublisherTest,BaseEventPublisherTest"
```

Expected: PASS。

- [ ] **Step 5: Commit**

在仓库根目录执行：

```powershell
git add demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/publisher/StockSyncEventPublisher.java demo2/src/test/java/com/jason/demo/demo2/product/StockSyncEventPublisherTest.java
git commit -m "feat(product): route stock sync messages by productId"
```

---

### Task 3: 按商品互斥执行 `applyDelta`

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/app/listener/StockSyncMqListener.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/product/StockSyncMqListenerTest.java`

**Interfaces:**
- Consumes: 现有 `ProductStockDomainService.applyDelta(StockSyncEvent)`、`StockSeqGapException`、`ProductErrorCodeEnum.STOCK_CONFLICT`
- Produces: `handleMessage` 对非空 `productId` 串行调用 `applyDelta`；不同 `productId` 可以重叠；`productId == null` 不加锁

- [ ] **Step 1: Write the failing same-product test**

在 `StockSyncMqListenerTest` 增加 import：

```java
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
```

增加测试：

```java
@Test
void sameProductId_applyDeltaDoesNotOverlap() throws Exception {
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger max = new AtomicInteger();
    doAnswer(invocation -> {
        int now = inFlight.incrementAndGet();
        max.accumulateAndGet(now, Math::max);
        try {
            Thread.sleep(80);
        } finally {
            inFlight.decrementAndGet();
        }
        return null;
    }).when(productStockDomainService).applyDelta(any(StockSyncEvent.class));

    StockSyncEvent event = sampleEvent();
    Thread first = new Thread(() -> listener.expose(event, messageExt));
    Thread second = new Thread(() -> listener.expose(event, messageExt));
    first.start();
    second.start();
    first.join(2000);
    second.join(2000);

    assertEquals(false, first.isAlive());
    assertEquals(false, second.isAlive());
    assertEquals(1, max.get());
}
```

- [ ] **Step 2: Run test to verify it fails**

在 `demo2` 目录执行：

```powershell
.\mvnw.cmd test "-Dtest=StockSyncMqListenerTest#sameProductId_applyDeltaDoesNotOverlap"
```

Expected: FAIL。`max` 为 2，因为两条线程同时进入 `applyDelta`。

- [ ] **Step 3: Write minimal implementation**

`StockSyncMqListener` 增加字段与 import：

```java
import java.util.concurrent.ConcurrentHashMap;
```

```java
private final ConcurrentHashMap<Long, Object> productLocks = new ConcurrentHashMap<>();
```

把 `handleMessage` 换成下面的结构。捕获留在锁外面。锁对象不从 Map 删除。

```java
@Override
protected ConsumeConcurrentlyStatus handleMessage(StockSyncEvent payload, String message, MessageExt messageExt) {
    try {
        runApplyDelta(payload);
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    } catch (StockSeqGapException ex) {
        log.warn("stock seq gap, will retry, keys={}", messageExt.getKeys(), ex);
        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
    } catch (BusinessException ex) {
        if (ex.getCode() == ProductErrorCodeEnum.STOCK_CONFLICT.getCode()) {
            log.error("stock conflict on sync, skip retry, keys={}", messageExt.getKeys(), ex);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }
        throw ex;
    }
}

private void runApplyDelta(StockSyncEvent payload) {
    Long productId = payload.getProductId();
    if (productId == null) {
        productStockDomainService.applyDelta(payload);
        return;
    }
    Object lock = productLocks.computeIfAbsent(productId, id -> new Object());
    synchronized (lock) {
        productStockDomainService.applyDelta(payload);
    }
}
```

- [ ] **Step 4: Run the same-product test to verify it passes**

在 `demo2` 目录执行：

```powershell
.\mvnw.cmd test "-Dtest=StockSyncMqListenerTest"
```

Expected: PASS。已有的成功、缺口、冲突三条用例仍然通过。

- [ ] **Step 5: Add overlap, null, and lock-release tests**

再增加 import：

```java
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
```

增加三个测试：

```java
@Test
void differentProductIds_applyDeltaMayOverlap() throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(2);
    AtomicReference<Throwable> error = new AtomicReference<>();
    doAnswer(invocation -> {
        barrier.await(2, TimeUnit.SECONDS);
        return null;
    }).when(productStockDomainService).applyDelta(any(StockSyncEvent.class));

    StockSyncEvent firstEvent = new StockSyncEvent(9001L, 100L, "RESERVE", 2, "k1", 4L);
    StockSyncEvent secondEvent = new StockSyncEvent(9002L, 101L, "RESERVE", 2, "k2", 4L);
    Thread first = new Thread(() -> exposeQuietly(firstEvent, error));
    Thread second = new Thread(() -> exposeQuietly(secondEvent, error));
    first.start();
    second.start();
    first.join(3000);
    second.join(3000);

    assertEquals(false, first.isAlive());
    assertEquals(false, second.isAlive());
    assertEquals(null, error.get());
}

@Test
void nullProductId_applyDeltaMayOverlap() throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(2);
    AtomicReference<Throwable> error = new AtomicReference<>();
    doAnswer(invocation -> {
        barrier.await(2, TimeUnit.SECONDS);
        return null;
    }).when(productStockDomainService).applyDelta(any(StockSyncEvent.class));

    StockSyncEvent firstEvent = new StockSyncEvent(null, 100L, "RESERVE", 2, "k1", 4L);
    StockSyncEvent secondEvent = new StockSyncEvent(null, 101L, "RESERVE", 2, "k2", 4L);
    Thread first = new Thread(() -> exposeQuietly(firstEvent, error));
    Thread second = new Thread(() -> exposeQuietly(secondEvent, error));
    first.start();
    second.start();
    first.join(3000);
    second.join(3000);

    assertEquals(false, first.isAlive());
    assertEquals(false, second.isAlive());
    assertEquals(null, error.get());
}

@Test
void seqGap_releasesLockForNextMessage() throws Exception {
    StockSyncEvent event = sampleEvent();
    lenient().when(messageExt.getKeys()).thenReturn("9001 key");
    doThrow(new StockSeqGapException(9001L, 4L, 2L))
            .doNothing()
            .when(productStockDomainService).applyDelta(event);

    assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER, listener.expose(event, messageExt));

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
        Future<ConsumeConcurrentlyStatus> next = pool.submit(() -> listener.expose(event, messageExt));
        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, next.get(2, TimeUnit.SECONDS));
    } finally {
        pool.shutdownNow();
    }
}

private void exposeQuietly(StockSyncEvent event, AtomicReference<Throwable> error) {
    try {
        listener.expose(event, messageExt);
    } catch (Throwable ex) {
        error.set(ex);
    }
}
```

- [ ] **Step 6: Run the listener tests**

在 `demo2` 目录执行：

```powershell
.\mvnw.cmd test "-Dtest=StockSyncMqListenerTest"
```

Expected: PASS。不同商品与空 `productId` 在 2 秒内同时进入 `applyDelta`。缺口之后的下一次调用在 2 秒内返回 `CONSUME_SUCCESS`。

- [ ] **Step 7: Commit**

在仓库根目录执行：

```powershell
git add demo2/src/main/java/com/jason/demo/demo2/product/app/listener/StockSyncMqListener.java demo2/src/test/java/com/jason/demo/demo2/product/StockSyncMqListenerTest.java
git commit -m "feat(product): serialize stock projection per product"
```

---

### Task 4: 文档

**Files:**
- Modify: `demo2/README.md`（约第 814–816 行）
- Modify: `demo2/docs/superpowers/specs/2026-09-30-stock-sync-product-serial-consume-design.md`（状态行）

**Interfaces:**
- Consumes: Task 2 的 `sendNow` 选队列，Task 3 的按商品加锁
- Produces: README 与规范状态和实现一致

- [ ] **Step 1: Update the hot-stock diagram**

把 `demo2/README.md` 读写分工里的这两行：

```text
         RedisStockOutboxRelay ──sendImmediate──► RocketMQ
                      ▼
              StockSyncMqListener.applyDelta
```

换成：

```text
         RedisStockOutboxRelay ──sendNow 按 productId 选队列──► RocketMQ
                      ▼
              StockSyncMqListener 按 productId 加锁后 applyDelta
```

- [ ] **Step 2: Mark the spec implemented**

把 `2026-09-30-stock-sync-product-serial-consume-design.md` 的 `**状态**: 待实现` 改为 `**状态**: 已实现`。不改 2026-08-27 规范里的 `applyDelta` 描述。

- [ ] **Step 3: Run the affected tests**

在 `demo2` 目录执行：

```powershell
.\mvnw.cmd test "-Dtest=BaseEventPublisherTest,StockSyncEventPublisherTest,StockSyncMqListenerTest"
```

Expected: PASS。

- [ ] **Step 4: Commit**

在仓库根目录执行：

```powershell
git add demo2/README.md demo2/docs/superpowers/specs/2026-09-30-stock-sync-product-serial-consume-design.md
git commit -m "docs(demo2): describe per-product stock consume"
```
