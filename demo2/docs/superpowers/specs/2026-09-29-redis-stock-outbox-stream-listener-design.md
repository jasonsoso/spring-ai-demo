# demo2 出箱 Relay 改为 StreamListener 设计规范

**日期**: 2026-09-29  
**项目**: spring-ai-demo / demo2  
**状态**: 待实现  
**前置**: [2026-08-27-redis-stock-consistency-design.md](./2026-08-27-redis-stock-consistency-design.md)

---

## 1. 背景与目标

热库存出箱仍是：Lua 在同一次脚本里扣可售并 `XADD` `demo2:stock:outbox`，Relay 把 Stream 记录发给 RocketMQ，`StockSyncMqListener.applyDelta` 再写 MySQL。Relay 不写 MySQL。

当前 `RedisStockOutboxRelay` 自己起虚拟线程做 `XREADGROUP`。这条循环有三个缺口：

- `readOnce` 里一条 `sendNow` 抛错，同批后面的记录不再处理。
- PEL 回收写在同一条读循环里，每 10 轮才跑一次。发送或读取堵住时，补发跟着停。com.jason.demo.demo2.product.service.core.ProductStockDomainService#applyDelta
- 虚拟线程因 `Error` 退出后，`isRunning` 仍为 true，没有人再拉流。

目标：改成 `StreamListener` + `StreamMessageListenerContainer`，并且比现在更稳。容器只负责拉新消息。确认、PEL 补发、订阅拉起由 Relay 自己保住。

### 1.1 已确认决策


| 维度   | 选择                                                  |
| ---- | --------------------------------------------------- |
| 拉取   | `StreamMessageListenerContainer` + `StreamListener` |
| 确认   | `autoAcknowledge(false)`；`sendNow` 成功之后才 `XACK`     |
| 出错   | `cancelOnError` 恒为 false；一条失败不影响同批后续，也不取消订阅         |
| 补发   | 独立调度 `XAUTOCLAIM`，不绑在读循环上                           |
| 空闲阈值 | 30 秒，大于 `sendImmediate` 最坏耗时                        |
| 看门狗  | 订阅不在跑时重新注册；仍在跑时不重复注册                                |
| 开关   | `redis-hot-enabled=false` 时容器不自动启动，补发和看门狗不跑         |
| 职责   | Relay 仍然只发 RocketMQ                                 |




### 1.2 非目标

不改 Lua `XADD`。不改 `StockSyncMqListener` / `applyDelta`。Relay 不写 MySQL。不把坏消息丢进死信或直接 `XACK` 丢掉。不支持多个进程共用同一个消费者名 `relay`。不把读取和发送拆成线程池。

---



## 2. 架构

```text
Lua XADD demo2:stock:outbox
        │
        ▼
StreamMessageListenerContainer
  XREADGROUP >                         只拉新消息，不自动 ACK
        │
        ▼
RedisStockOutboxRelay.onMessage
  sendNow 成功 → XACK
  失败         → 不 XACK，留在 PEL

独立调度（每 10 秒）
  XAUTOCLAIM min-idle=30s，每批 16 条，起点 0-0
  领到的记录走同一个 onMessage

看门狗（每 10 秒）
  订阅已取消，或轮询 Future 已结束 → 重新 register + start
  订阅仍在跑且 Future 未结束       → 不做任何事
```

`StreamMessageListenerContainer` 本身是 `SmartLifecycle`。`RedisStockOutboxRelay` 仍实现 `SmartLifecycle`，在 `start` / `stop` 里启动和关闭容器、补发调度、看门狗。`isAutoStartup` 继续跟随 `app.product.stock.redis-hot-enabled`。

类留在 `product.app.listener.RedisStockOutboxRelay`。不新增全局 `mq` 包里的监听器。

### 2.1 为何容器默认不够

只换成容器、沿用默认值，会比现在差：

- 出错默认取消订阅。Redis 短暂超时后订阅停在 `CANCELLED`，不会自己再拉。
- 消费者组的下一次读取是 `>`，看不到 PEL。容器不负责 `XAUTOCLAIM`。
- `autoAcknowledge(true)` 对应 `XREADGROUP NOACK`。消息不进 PEL，`sendNow` 失败后记录已经没了。

因此订阅必须显式关掉这两项，补发和看门狗必须在容器外面。

### 2.2 比当前循环好在哪里

- 容器对单条 `onMessage` 异常会记日志并继续同批下一条。现在的 `readOnce` 会中断整批。
- `XAUTOCLAIM` 在独立调度上跑。读线程堵住时，已经空闲的 PEL 仍会被领走。
- 看门狗在订阅死后重新注册。现在的虚拟线程退出后没有人拉起。

---



## 3. 组件与数据流



### 3.1 订阅

启动前在 `demo2:stock:outbox` 上创建消费组 `app.product.stock.outbox-group`（默认 `demo2-stock-relay`），起点 `0-0`。组已存在（`BUSYGROUP`）则忽略。其他创建失败：启动失败并打错误日志，不进入“进程在跑但没有消费组”的状态。

注册参数：


| 项               | 值                                                                      |
| --------------- | ---------------------------------------------------------------------- |
| Stream          | `RedisStockKeys.OUTBOX`                                                |
| 偏移              | `ReadOffset.lastConsumed()`（`>`）                                       |
| 消费者             | `Consumer.from(outboxGroup, outboxConsumer)`，默认消费者名 `relay`            |
| 批量              | `outbox-batch-size`，默认 16                                              |
| 阻塞              | `outbox-block-ms`，默认 2000                                              |
| 序列化             | key、hash key、hash value 均为字符串，回调类型 `MapRecord<String, String, String>` |
| 自动确认            | false                                                                  |
| `cancelOnError` | `throwable -> false`                                                   |


`onMessage` 解析字段 `productId`、`orderId`、`optType`、`qty`、`idempotentKey`、`seq`，组装 `StockSyncEvent`，调用 `StockSyncEventPublisher.sendNow`。`sendNow` 返回后，对这条记录的 id 执行 `XACK`。

### 3.2 补发调度

Relay 自己持有调度器，在 `start` 时启动，在 `stop` 时关闭。不用全局 `@Scheduled`，避免热路径关闭后仍然领 PEL。


| 参数   | 默认                       | 配置                                           |
| ---- | ------------------------ | -------------------------------------------- |
| 间隔   | 10 秒                     | `app.product.stock.outbox-claim-interval-ms` |
| 最小空闲 | 30 秒                     | `app.product.stock.outbox-claim-min-idle-ms` |
| 每批条数 | 与 `outbox-batch-size` 相同 | 已有 `outbox-batch-size`                       |


调用 Spring Data Redis `StreamOperations.autoClaim`，对应 Redis `XAUTOCLAIM`：组、新所有者都是当前消费者，起点 `0-0`，`min-idle` 取上面的 30 秒，`COUNT` 取 `outbox-batch-size`。不写 `COUNT` 时 Redis 默认最多领 100 条，那不是本规范的批量。领到的每条记录调用同一个 `onMessage`。

补发和看门狗用同一个调度器，至少两条线程。补发里的 `sendNow` 不得堵住看门狗的触发。

30 秒必须大于一次 `sendImmediate` 的最坏耗时。当前生产者 `maxTryTimes=2`，RocketMQ 默认同步发送超时约 3 秒，最坏约 6 秒。30 秒不会把正在发送的消息抢走。

### 3.3 看门狗

同一调度器上每 10 秒跑一次，配置项 `app.product.stock.outbox-watchdog-interval-ms`，默认 10000。

不能只看 `Subscription.isActive()`。轮询线程因 `Error` 退出时，任务状态可能仍是 `RUNNING`，`isActive()` 仍为 true。Relay 使用自有 `Executor`，记下容器提交的轮询 `Future`。下面任一成立就重新注册：订阅引用为空、`isActive()` 为 false、或 Relay 仍处于运行中且该 `Future` 已经结束。重新注册前先 `remove` 旧订阅（若有），再按第 3.1 节 `register` 并 `start` 容器。

Relay 已 `stop` 时看门狗必须直接返回，不得再把容器拉起来。同一消费者名 `relay` 只允许一个进程。旧订阅仍活跃且轮询 `Future` 未结束时，不得再注册一条。

---



## 4. 失败处理


| 情况                                           | 行为                                                            |
| -------------------------------------------- | ------------------------------------------------------------- |
| `sendNow` 抛错                                 | 不 `XACK`。容器继续同批后续记录，订阅保持。记录留在 PEL，空闲超过 30 秒后由补发再走 `onMessage` |
| 字段缺失或解析失败                                    | 不 `XACK`，不进死信，不丢弃。打错误日志。补发每 30 秒重试                            |
| `sendNow` 已成功，随后 `XACK` 失败                   | 不在同一次调用里再发。记录留在 PEL。补发会再发一次 RocketMQ。`applyDelta` 靠幂等键只落一次账   |
| 补发批里某一条失败                                    | 只跳过这一条，同批其余继续 `onMessage`                                     |
| Redis 读取抛错                                   | 订阅不取消，下一轮继续 `XREADGROUP`                                      |
| 订阅被取消，或轮询 `Future` 已结束而 `isActive()` 仍为 true | 看门狗先去掉旧订阅，再重新注册并启动                                            |
| 热路径关闭或 `stop`                                | 停止容器、补发和看门狗。已进入 PEL 的记录留在 Redis，下次开启再领                        |


坏消息会按 30 秒重复打错误日志。这是故意留下的，避免把库存增量确认掉之后 MySQL 永远收不到。

---



## 5. 测试

改 `RedisStockOutboxRelayTest`，锁住下面这些行为：

- `sendNow` 成功才 `XACK`；`sendNow` 抛错不 `XACK`；字段缺失不 `XACK`
- 注册出去的读取请求是手动确认，且 `cancelOnError` 对任意异常返回 false
- 补发时一条 `sendNow` 失败，同批后面的记录仍会发送；失败的那条不 `XACK`
- 订阅不活跃，或轮询 `Future` 已结束时，看门狗重新注册；订阅仍活跃且 `Future` 未结束时不再注册第二条
- `redis-hot-enabled=false` 时 `isAutoStartup()` 为 false，补发调度不跑

不新增对真实 Redis 或 RocketMQ 的集成测试。Lua 脚本测试保持不变，仍要求 `stock-reserve.lua` 含 `XADD`。

---



## 6. 文档

实现时把 [2026-08-27 规范](./2026-08-27-redis-stock-consistency-design.md) 第 6 节里 Relay 的手写 `XREADGROUP` 循环，改成指向本文。该规范里 Lua、`applyDelta`、对账章节不动。`README.md` 热库存一节的 Relay 描述改成：容器拉新消息，发送成功才 `XACK`，空闲 PEL 由独立 `XAUTOCLAIM` 补发。

---



## 7. 验收

- 热路径开启后，新的出箱记录经 `onMessage` 发到 `DEMO_STOCK_TOPIC`，成功后 Stream 里该 id 已 `XACK`。
- 发送失败时该 id 留在 PEL；空闲超过 30 秒后被 `XAUTOCLAIM` 再次发送。
- 同批中一条发送失败，其余记录仍会发送。
- 关掉热路径后，容器不自动启动，补发调度不跑。

