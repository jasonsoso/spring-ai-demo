# demo2 库存同步按商品串行消费设计规范

**日期**: 2026-09-30  
**项目**: spring-ai-demo / demo2  
**状态**: 待实现  
**前置**: [2026-08-27-redis-stock-consistency-design.md](./2026-08-27-redis-stock-consistency-design.md)

---

## 1. 背景与目标

热库存投影是：Lua 写 Redis 并 `XADD` 出箱，`RedisStockOutboxRelay` 调用 `StockSyncEventPublisher.sendNow`，`StockSyncMqListener` 并发消费后执行 `ProductStockDomainService.applyDelta`。

`sendNow` 今天走 `sendImmediate`，`producer.send(message)` 不带队列选择器。`productId` 只放在消息 keys 里。并发监听的多个线程可以同时对同一个商品执行 `applyDelta`。MySQL 条件 `stock_seq = seq - 1` 能挡住写乱，挡不住两个线程同时进入投影。

目标：同一个 `productId` 的投影一个一个执行；不同 `productId` 可以同时执行。seq=6 先于 seq=5 被执行时，仍抛 `StockSeqGapException`，监听返回 `RECONSUME_LATER`。

### 1.1 已确认决策

| 维度 | 选择 |
|------|------|
| 发送 | `sendNow` 用 `productId` 的十进制字符串哈希选队列，仍同步发送，失败抛异常 |
| 消费 | `StockSyncMqListener` 保持并发监听，按 `productId` 互斥后再调 `applyDelta` |
| 顺序 | 互斥，不保证等待中的同商品消息按 seq 先执行 |
| 缺口 | 保持 `StockSeqGapException` → `RECONSUME_LATER` |
| 兜底 | `applyDelta` 的 `stock_seq = seq - 1` 继续挡住重平衡、队列列表变化时的并发写入 |

### 1.2 非目标

不把监听改成 `MessageListenerOrderly`。不改 `applyDelta`、Lua、`RedisStockOutboxRelay`。不加 Redis 分布式锁。不按商品扩容 RocketMQ 队列。不保证同商品消息的消费顺序与 seq 一致。

---

## 2. 架构

```text
Relay
  StockSyncEventPublisher.sendNow
    sendImmediateByKey(productId)
        │
        ▼
DEMO_STOCK_TOPIC
  同一 productId → 同一条队列（队列列表不变时）
        │
        ▼
StockSyncMqListener（并发监听）
  productId 非空 → 该商品的锁
  applyDelta
    命中     → CONSUME_SUCCESS
    已入账   → CONSUME_SUCCESS
    缺口     → RECONSUME_LATER
  离开 applyDelta 后释放锁
```

同一条物理队列上，不同 `productId` 使用不同的锁，可以同时进入 `applyDelta`。同一个 `productId` 只有持锁线程在执行 `applyDelta`。

选队列只保证集群里这条队列平时只分给一个消费者实例。一个一个执行靠监听器里的锁。并发监听仍会多个线程同时拿同一条队列上的消息。

---

## 3. 发送

### 3.1 `sendImmediateByKey`

在 `BaseEventPublisher` 增加：

```text
protected void sendImmediateByKey(Object messageBodyObj, String shardingKey, String... keys)
```

行为与 `sendImmediate` 相同：当前线程同步发送，不走 `afterCommit`，`maxTryTimes` 次都失败后抛 `IllegalStateException`。选队列与 `sendOrderly` 使用同一个私有方法，公式保持：

```text
index = Math.floorMod(shardingKey.hashCode(), queues.size())
return queues.get(index)
```

`shardingKey` 就是传给 `MessageQueueSelector` 的 arg。`String.hashCode()` 在各 JVM 上结果相同，所以多个生产者实例对同一个 `productId` 算出同一个下标。

`sendOrderly` 仍在事务提交后发送，失败只打日志。库存出箱继续不用它。

### 3.2 `sendNow`

`StockSyncEventPublisher.sendNow` 改为：

```text
sendImmediateByKey(event, String.valueOf(event.getProductId()),
    String.valueOf(event.getProductId()), event.getIdempotentKey())
```

消息 keys 仍是 `productId` 和幂等键。队列由第一个 `productId` 字符串决定。

商品 `1001` 在 4 条队列里始终进同一下标。商品 `1002` 下标不同则进另一条队列。下标相同的两个商品共用一条物理队列，消费时仍按各自的 `productId` 加锁。

队列扩容或 Broker 上下线后，队列列表变化，新消息的下标可能变。已经在旧队列里的消息由原消费者处理。这段窗口里的并发写入由 `stock_seq = seq - 1` 挡住。

---

## 4. 消费锁

锁放在 `StockSyncMqListener` 内。`ConcurrentHashMap<Long, Object>`，`computeIfAbsent(productId, id -> new Object())` 得到该商品的锁对象，再 `synchronized` 包住 `applyDelta`。

锁对象一旦放入 Map 就留到进程结束。中途移除会让两个线程拿到不同的锁对象，同一商品又会并行。

`productId` 为空时不加锁，直接执行现有的 `applyDelta` 路径。

`synchronized` 包住的只有 `applyDelta`。`StockSeqGapException` 和 `STOCK_CONFLICT` 的捕获放在锁外面。异常离开同步块时锁已释放，监听器再返回 `RECONSUME_LATER` 或 `CONSUME_SUCCESS`。同商品的下一条消息可以马上进入。

这把锁只保证互斥。它不公平，也不按 seq 排队。seq=6 可以先于 seq=5 拿到锁；缺口按现有方式稍后重试，seq=5 在锁释放后执行。

等待发生在 RocketMQ 的消费线程上。监听器必须在返回消费状态之前跑完 `applyDelta`，所以同商品的下一条会占着一条消费线程直到轮到它。默认消费线程约 20。某个商品大量堆积时，这些线程会花在等这把锁上，其他商品的并行度随之下降。本规范不为它另建排队线程池。

冷路径 `reserve` / `confirm` / `release` / `adjust` 不经过这把锁。

---

## 5. 失败处理

| 情况 | 行为 |
|------|------|
| 同商品两条消息同时到达 | 后进入同步块的那条等待；先进入的那条执行完 `applyDelta` 并离开同步块后，下一条再执行 |
| 不同商品同时到达 | 各拿各的锁，`applyDelta` 可以重叠 |
| seq 缺口 | 抛 `StockSeqGapException`，锁释放，返回 `RECONSUME_LATER` |
| `STOCK_CONFLICT` | 锁释放，返回 `CONSUME_SUCCESS`，与现在相同 |
| 发送失败 | `sendImmediateByKey` 重试耗尽后抛异常，Relay 不 `XACK` |
| 消费者重平衡，同一队列短暂出现在两个实例 | 两把进程内锁互不影响；写入仍要求 `stock_seq = seq - 1` |
| 队列列表变化，同商品新消息换队列 | 与重平衡相同，由 `stock_seq` 挡住并发写入 |

---

## 6. 测试

`BaseEventPublisherTest` 增加 `sendImmediateByKey`：

- 调用的是 `producer.send(Message, MessageQueueSelector, Object)`，arg 为 sharding key
- 用固定的 4 条 `MessageQueue` 调选择器：同一 sharding key 两次得到同一条队列；公式与 `Math.floorMod(key.hashCode(), 4)` 一致
- 发送抛错时按 `maxTryTimes` 重试，耗尽后抛 `IllegalStateException`
- 现有无选择器的 `send` 测试仍然走 `producer.send(Message)`

`StockSyncMqListenerTest` 在现有三条成功 / 缺口 / 冲突用例之外增加：

- 两个线程、同一个 `productId`：`applyDelta` 内的在途计数最大为 1
- 两个线程、两个 `productId`：两边的 `applyDelta` 可以同时处于在途（用栅栏对齐，超时则失败）
- 缺口用例仍返回 `RECONSUME_LATER`。抛出 `StockSeqGapException` 之后，同商品的下一次 `handleMessage` 在超时时间内能够进入 `applyDelta`，说明锁已释放

不新增真实 RocketMQ 集成测试。

---

## 7. 文档

`README.md` 热库存读写分工里，发送那一行写成：`sendNow` 按 `productId` 选队列；`StockSyncMqListener` 按 `productId` 加锁后 `applyDelta`。

[2026-08-27 规范](./2026-08-27-redis-stock-consistency-design.md) 里 `applyDelta` 的乐观条件、缺口重试、无行锁保持不变。

---

## 8. 验收

- 同一 `productId` 的两次 `sendNow`，在队列列表不变时进入同一条 `MessageQueue`。
- 同一 `productId` 的两次消费不会重叠执行 `applyDelta`。
- 不同 `productId` 的两次消费可以重叠执行 `applyDelta`。
- seq 缺口仍返回 `RECONSUME_LATER`，并且不会占着该商品的锁不放。
- 发送失败仍向 Relay 抛异常。
