# demo2 出箱 Relay 改为 StreamListener 设计规范

**日期**: 2026-09-29  
**修订**: 2026-10-03（对齐 `XACKDEL` / `sendNow`；多机用唯一消费者名）  
**项目**: spring-ai-demo / demo2  
**状态**: 待实现  
**前置**: [2026-08-27-redis-stock-consistency-design.md](./2026-08-27-redis-stock-consistency-design.md)  
**相关**: [2026-09-30-stock-sync-product-serial-consume-design.md](./2026-09-30-stock-sync-product-serial-consume-design.md)（已实现；本规范不改消费侧）

---

## 1. 背景与目标

热库存出箱仍是：Lua 在同一次脚本里扣可售并 `XADD` `demo2:stock:outbox`，Relay 把 Stream 记录发给 RocketMQ，`StockSyncMqListener.applyDelta` 再写 MySQL。Relay 不写 MySQL。

当前 `RedisStockOutboxRelay` 自己起虚拟线程做 `XREADGROUP`。发送成功后已改为 `acknowledgeAndDelete`（Redis 8.2+ `XACKDEL`，策略 `ACKED`），不是纯 `XACK`。`sendNow` 已按 `productId` 选队列同步发送。这条循环仍有三个缺口：

- `readOnce` 里一条 `sendNow` 抛错，同批后面的记录不再处理。`claimIdlePending` 同样一条失败就中断整批。
- PEL 回收写在同一条读循环里，每 10 轮才跑一次，且仍是 `pending` + `claim`。发送或读取堵住时，补发跟着停。
- 虚拟线程因 `Error` 退出后，`isRunning` 仍为 true，没有人再拉流。

目标：改成 `StreamListener` + `StreamMessageListenerContainer`，并且比现在更稳。容器只负责拉新消息。确认仍走现有的 `XACKDEL(ACKED)`。PEL 补发、订阅拉起由 Relay 自己保住。

### 1.1 已确认决策

| 维度 | 选择 |
|------|------|
| 拉取 | `StreamMessageListenerContainer` + `StreamListener` |
| 发送 | 继续调用现有 `StockSyncEventPublisher.sendNow`（`sendImmediateByKey`，按 `productId` 选队列） |
| 确认 | `autoAcknowledge(false)`；`sendNow` 成功之后才 `acknowledgeAndDelete(..., ACKED)` |
| Redis | 需要 8.2+（`XACKDEL`） |
| 出错 | `cancelOnError` 恒为 false；一条失败不影响同批后续，也不取消订阅 |
| 补发 | 独立调度 `XAUTOCLAIM`，不绑在读循环上；领组内任意消费者的 idle PEL |
| 空闲阈值 | 30 秒，大于一次 `sendNow` 最坏耗时 |
| 多机 | 组名共享；消费者名每进程唯一，启动后不变 |
| 看门狗 | 只盯本机 `Subscription` / `Future`；不选主、不删 Redis 消费者 |
| 开关 | `redis-hot-enabled=false` 时容器不自动启动，补发和看门狗不跑 |
| 职责 | Relay 仍然只发 RocketMQ |

### 1.2 非目标

不改 Lua `XADD`。不改 `StockSyncMqListener` / `applyDelta`。不把 `sendNow` 退回无选择器的 `sendImmediate`。Relay 不写 MySQL。不把坏消息丢进死信，也不在发送失败时确认或删除。不把读取和发送拆成线程池。容器不得代替 `XACKDEL` 去调 `XACK`。不用分布式锁做成「全集群只跑一个 Relay」。看门狗不得 `XGROUP DELCONSUMER`。

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
  sendNow（按 productId 选队列）
    成功 → acknowledgeAndDelete ACKED（XACKDEL）
    失败 → 不确认、不删除，留在 PEL

独立调度（每 10 秒，每台都跑）
  XAUTOCLAIM min-idle=30s，COUNT=outbox-batch-size，起点 0-0
  新所有者 = 本机唯一消费者名
  领组内 idle PEL（含已死实例），走同一个 onMessage

看门狗（每 10 秒，只管本机）
  订阅已取消，或轮询 Future 已结束 → 重新 register + start
  订阅仍在跑且 Future 未结束       → 不做任何事
```

`StreamMessageListenerContainer` 本身是 `SmartLifecycle`。`RedisStockOutboxRelay` 仍实现 `SmartLifecycle`，在 `start` / `stop` 里启动和关闭容器、补发调度、看门狗。`isAutoStartup` 继续跟随 `app.product.stock.redis-hot-enabled`。

类留在 `product.app.listener.RedisStockOutboxRelay`。不新增全局 `mq` 包里的监听器。

容器创建时必须带上 Relay 自备的 `ExecutorService`（见 §3.3）。默认 `Executor.execute` 不返回 `Future`，看门狗没法判断轮询线程是否已死。

### 2.1 为何容器默认不够

只换成容器、沿用默认值，会比现在差：

- 出错默认取消订阅。Redis 短暂超时后订阅停在 `CANCELLED`，不会自己再拉。
- 消费者组的下一次读取是 `>`，看不到 PEL。容器不负责 `XAUTOCLAIM`。
- `autoAcknowledge(true)` 对应 `XREADGROUP NOACK`。消息不进 PEL，`sendNow` 失败后记录已经没了。
- 容器手动确认也只发 `XACK`，不会删正文，更不会走 `ACKED` 策略。本项目已用 `XACKDEL` 回收出箱正文，确认必须留在 `onMessage` 里。

因此订阅必须显式关掉自动确认和出错退订，补发和看门狗必须在容器外面，确认必须继续调 `acknowledgeAndDelete`。

### 2.2 比当前循环好在哪里

- 容器对单条 `onMessage` 异常会记日志并继续同批下一条。现在的 `readOnce` 和 `claimIdlePending` 都会中断整批。
- `XAUTOCLAIM` 在独立调度上跑。读线程堵住时，已经空闲的 PEL 仍会被领走。
- 看门狗在轮询 `Future` 结束后重新注册。现在的虚拟线程退出后没有人拉起。
- 消费者名每进程唯一后，多机可以同时拉新消息；死实例的 PEL 由活着的实例 `XAUTOCLAIM` 领走。

---

## 3. 组件与数据流

### 3.1 订阅

启动前在 `demo2:stock:outbox` 上创建消费组 `app.product.stock.outbox-group`（默认 `demo2-stock-relay`），起点 `0-0`。组已存在（`BUSYGROUP`）则忽略。其他创建失败：启动失败并打错误日志，不进入“进程在跑但没有消费组”的状态。现有实现吞掉全部 `RuntimeException`，本规范收紧为只忽略 `BUSYGROUP`。

注册参数：

| 项 | 值 |
|----|----|
| Stream | `RedisStockKeys.OUTBOX` |
| 偏移 | `ReadOffset.lastConsumed()`（`>`） |
| 消费者 | `Consumer.from(outboxGroup, 本进程唯一名)`，见 §3.4 |
| 批量 | `outbox-batch-size`，默认 16 |
| 阻塞 | `outbox-block-ms`，默认 2000 |
| 序列化 | key、hash key、hash value 均为字符串，回调类型 `MapRecord<String, String, String>` |
| 自动确认 | false |
| `cancelOnError` | `throwable -> false` |
| 执行器 | Relay 自备的 `ExecutorService`（§3.3） |

`onMessage` 解析字段 `productId`、`orderId`、`optType`、`qty`、`idempotentKey`、`seq`，组装 `StockSyncEvent`，调用现有 `StockSyncEventPublisher.sendNow`。不要改成 `sendImmediate`。`sendNow` 返回后，对这条记录的 id 调用：

```text
ops.acknowledgeAndDelete(OUTBOX, outboxGroup, ACKED, recordId)
```

`ACKED` 与现有 Relay 相同：`XDelOptions.deletionPolicy(StreamDeletionPolicy.ACKNOWLEDGED)`。本组确认后，仅当所有消费组都已确认才删正文。

返回值不是 `DELETED`：只打 warn，不抛错，不补发。本组 PEL 多半已清掉，正文可能还在；再 `XAUTOCLAIM` 领不到，也不该当成发送失败。命令抛错：不在同一次调用里再发 MQ；记录可能仍在 PEL，由补发再走 `onMessage`。

### 3.2 补发调度

Relay 自己持有调度器，在 `start` 时启动，在 `stop` 时关闭。不用全局 `@Scheduled`，避免热路径关闭后仍然领 PEL。现有 `pending` + `claim` 整段删掉，改成 `XAUTOCLAIM`。

| 参数 | 默认 | 配置 |
|------|------|------|
| 间隔 | 10 秒 | `app.product.stock.outbox-claim-interval-ms` |
| 最小空闲 | 30 秒 | `app.product.stock.outbox-claim-min-idle-ms` |
| 每批条数 | 与 `outbox-batch-size` 相同 | 已有 `outbox-batch-size` |

调用 Spring Data Redis `StreamOperations.autoClaim`，对应 Redis `XAUTOCLAIM`：消费组仍是 `outbox-group`，**新所有者是本进程唯一消费者名**，起点 `0-0`，`min-idle` 30 秒，`COUNT` 取 `outbox-batch-size`。不写 `COUNT` 时 Redis 默认最多领 100 条，那不是本规范的批量。

`XAUTOCLAIM` 扫的是 **整个组** 的 PEL，不是只扫自己。活着的实例会领走已死实例名下、idle 超过 30 秒的记录。两台同时 `XAUTOCLAIM` 由 Redis 串行，同一条不会发给两个新所有者。领到的每条记录调用同一个 `onMessage`。某一条失败只跳过这一条，同批其余继续。

每台都跑这个调度。不要做成「集群里只有一台 claim」。不要用分布式锁互斥 Relay。

补发和看门狗用同一个调度器，至少两条线程。补发里的 `sendNow` 不得堵住看门狗的触发。

30 秒必须大于一次 `sendNow` 的最坏耗时。当前生产者 `maxTryTimes=2`，RocketMQ 默认同步发送超时约 3 秒，最坏约 6 秒。对端不会在发送进行中把消息抢走。发送卡住超过 30 秒时对端可以领走并再发一次 MQ，`applyDelta` 靠幂等键只落一次账。

### 3.3 看门狗

同一调度器上每 10 秒跑一次，配置项 `app.product.stock.outbox-watchdog-interval-ms`，默认 10000。

不能只看 `Subscription.isActive()`。`StreamPollTask` 因 `Error` 退出时，任务状态可能仍是 `RUNNING`，`isActive()` 仍为 true。`DefaultStreamMessageListenerContainer` 对执行器只调 `execute`，不返回 `Future`。

Relay 必须自备 `ExecutorService`，经 `StreamMessageListenerContainerOptions.executor(...)` 交给容器。包装 `execute`：把任务 `submit` 进该 `ExecutorService`，把返回的 `Future` 存成当前轮询任务。容器不得使用未包装的默认执行器。

下面任一成立就重新注册：订阅引用为空、`isActive()` 为 false、或 Relay 仍处于运行中且该 `Future` 已经结束。重新注册前先 `remove` 旧订阅（若有），再按第 3.1 节 `register` 并 `start` 容器。

Relay 已 `stop` 时看门狗必须直接返回，不得再把容器拉起来。停止时关闭自备 `ExecutorService`。重新 `register` 必须仍用 **本进程启动时定下的那个消费者名**，不得每次看门狗换新名字（否则本机 PEL 会挂到废弃名下，要等 30 秒才被别人领）。旧订阅仍活跃且轮询 `Future` 未结束时，不得再注册一条。

看门狗不得调用 `XGROUP DELCONSUMER`。不得根据别的机器是否存活来 start/stop 本机容器。

### 3.4 多机：组共享、消费者名唯一

两台机器可以同时 `redis-hot-enabled=true`。

| 项 | 规则 |
|----|------|
| 消费组 | 所有实例相同，配置 `outbox-group`，默认 `demo2-stock-relay` |
| 消费者名 | 每个 JVM 启动时算一次，进程内不变。`{outbox-consumer}-{hostname}-{pid}`，默认前缀 `relay`。hostname 取不到则用 `unknown` |
| `XREADGROUP >` | 新消息在组内分给不同消费者，各进各的 PEL |
| `XACKDEL` | 仍对 **组** 确认，与机器数无关。一组里一条消息只属于一个消费者 |
| 看门狗 / 执行器 | 只恢复本机订阅 |
| 下线留下的空消费者名 | 允许留在 Redis。PEL 被领光后无害。本阶段不做定期清理 |

禁止把配置里的 `outbox-consumer=relay` 直接当成 Redis 消费者名。现有实现所有进程都叫 `relay`，多机时 PEL 共用、`XAUTOCLAIM` 会抢同一条。本规范改掉这一点。

同一台机器两个进程：hostname 相同，靠 `pid` 区分。Kubernetes 里 hostname 通常是 Pod 名，加上 pid 仍然唯一。

重启后进程号变了，旧消费者名作废。旧名下未确认的记录 idle 超过 30 秒后，被任意活着的实例 `XAUTOCLAIM` 领走。这是预期的故障转移，不是丢消息。

---

## 4. 失败处理

| 情况 | 行为 |
|------|------|
| `sendNow` 抛错 | 不调用 `acknowledgeAndDelete`。容器继续同批后续记录，订阅保持。记录留在 PEL，空闲超过 30 秒后由补发再走 `onMessage` |
| 字段缺失或解析失败 | 不确认、不删除，不进死信。打错误日志。补发每 30 秒重试 |
| `sendNow` 已成功，随后 `acknowledgeAndDelete` 抛错 | 不在同一次调用里再发。记录可能仍在 PEL。补发会再发一次 RocketMQ。`applyDelta` 靠幂等键只落一次账 |
| `acknowledgeAndDelete` 返回不是 `DELETED` | 只打 warn。不抛错，不补发 |
| 补发批里某一条失败 | 只跳过这一条，同批其余继续 `onMessage` |
| Redis 读取抛错 | 订阅不取消，下一轮继续 `XREADGROUP` |
| 订阅被取消，或轮询 `Future` 已结束而 `isActive()` 仍为 true | 看门狗先去掉旧订阅，再 **用同一个消费者名** 重新注册并启动 |
| 本机进程退出，PEL 仍挂在旧消费者名下 | 其他实例 `XAUTOCLAIM` 在 idle ≥ 30 秒后领走并 `sendNow` |
| 热路径关闭或 `stop` | 停止容器、补发和看门狗，关闭自备执行器。已进入 PEL 的记录留在 Redis，由本机再开或他机 claim 再领 |

坏消息会按 30 秒重复打错误日志。这是故意留下的，避免把库存增量确认掉之后 MySQL 永远收不到。

---

## 5. 测试

改 `RedisStockOutboxRelayTest`，锁住下面这些行为：

- `sendNow` 成功才 `acknowledgeAndDelete(..., ACKED)`；`sendNow` 抛错不调用；字段缺失不调用
- `acknowledgeAndDelete` 返回不是 `DELETED` 时不抛错
- 注册出去的读取请求是手动确认，且 `cancelOnError` 对任意异常返回 false
- 容器使用的执行器是 Relay 自备的 `ExecutorService`，不是未包装的默认执行器
- 补发时一条 `sendNow` 失败，同批后面的记录仍会发送；失败的那条不调用 `acknowledgeAndDelete`
- 订阅不活跃，或轮询 `Future` 已结束时，看门狗重新注册且消费者名与启动时相同；订阅仍活跃且 `Future` 未结束时不再注册第二条
- 消费者名格式 `{outbox-consumer}-{hostname}-{pid}`，配置值 `relay` 只当前缀；hostname+pid 不同则名字不同
- `redis-hot-enabled=false` 时 `isAutoStartup()` 为 false，补发调度不跑
- `acknowledgeAndDelete` 的组名仍是 `outbox-group`，不拿消费者名当组名

不新增对真实 Redis 或 RocketMQ 的集成测试。Lua 脚本测试保持不变，仍要求 `stock-reserve.lua` 含 `XADD`。不改 `StockSyncMqListenerTest`。

---

## 6. 文档

实现时把 [2026-08-27 规范](./2026-08-27-redis-stock-consistency-design.md) 第 6 节里 Relay 的手写 `XREADGROUP` 循环，改成指向本文；原文「发送成功才 XACK」改为「发送成功才 `XACKDEL(ACKED)`」。该规范里 Lua、`applyDelta`、对账章节不动。

`README.md` 热库存一节：出箱改为容器拉新消息，发送成功才 `XACKDEL(ACKED)`，空闲 PEL 由独立 `XAUTOCLAIM` 补发。多机共用组名、消费者名带 hostname 与 pid。按 `productId` 选队列与监听加锁的描述仍以 [2026-09-30 规范](./2026-09-30-stock-sync-product-serial-consume-design.md) 为准。

---

## 7. 验收

- 热路径开启后，新的出箱记录经 `onMessage` 发到 `DEMO_STOCK_TOPIC`（仍按 `productId` 选队列），成功后调用 `acknowledgeAndDelete(..., ACKED)`。
- 发送失败时该 id 留在 PEL；空闲超过 30 秒后被 `XAUTOCLAIM` 再次发送。
- 同批中一条发送失败，其余记录仍会发送。
- `acknowledgeAndDelete` 返回不是 `DELETED` 时进程继续跑，不把该 id 当失败补发。
- 关掉热路径后，容器不自动启动，补发调度不跑。
- 两台进程消费者名不同；`acknowledgeAndDelete` 仍使用同一个 `outbox-group`。
- 看门狗重启订阅时不更换消费者名。
