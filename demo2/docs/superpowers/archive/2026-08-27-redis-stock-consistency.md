# Redis 热库存与 MySQL 最终一致 · 功能归档

**归档日期**: 2026-08-28  
**项目**: spring-ai-demo / demo2  
**状态**: 已实现  

**设计规范**: [2026-08-27-redis-stock-consistency-design.md](../specs/2026-08-27-redis-stock-consistency-design.md)  
**实施计划**: [2026-08-27-redis-stock-consistency.md](../plans/2026-08-27-redis-stock-consistency.md)  
**前置**: [2026-08-26-product-module.md](./2026-08-26-product-module.md)  
**参考代码**: `com.jason.demo.demo2.product`

---

## 1. 做了什么

秒杀闸门放到 Redis；MySQL 仍是 `actual` / `withhold` / `sell` 的账本。热卖先 Lua 改 `avail+seq` 与预占票，出箱 Stream → Relay 只发 RocketMQ → 消费者按 `seq` 乐观投影。运营 ADJUST 与开关关闭仍走方案 A 行锁。

- Redis Hash **只有** `avail` + `seq`；票 `demo2:stock:reserve:{orderId}:{productId}` = qty
- 出箱 `demo2:stock:outbox`；Relay **MQ 成功才 XACK**，禁止写 MySQL
- 投影 `UPDATE … WHERE stock_seq = seq-1`，**无** `SELECT FOR UPDATE`；缺口 `RECONSUME_LATER`
- 乱序 `RELEASE` 在 RESERVE 尚未入账时按 seq 缺口重试（不因「尚无 RESERVE 流水」直接成功）
- Demo HTTP：`offShelf` / `onShelf` / `adjustStock`；C 端 `availableStock` 热路径 overlay Redis，`sellStock` 仍 MySQL
- 对账先比 seq：在途不报 avail 不一致；齐了才比 `avail ≟ mysql.stock`

**本阶段未做**：订单 HTTP / 订单表改为调 `ProductStockHotService`（下单/支付/取消仍走旧入口）。

---

## 2. 热 / 冷路径

| 场景 | 入口 | Redis | MySQL |
|------|------|--------|--------|
| 热路径开启 | `ProductStockHotService` | Lua 改 Hash + 票 + XADD | MQ `applyDelta` 跟 `seq` |
| 开关关闭 | 同上，委托 DomainService | 不作为闸门 | 方案 A 行锁 + `stock_seq+=1` |
| 运营 ADJUST | `ProductAdjustStockCmdExe` | 成功后 `adjustHash` 对齐 | 须先下架；行锁 |

热路径 `UNLOADED`（Hash 不存在）→ `40010`，**禁止**用当时的 `mysql.stock` 灌 Hash。再次上架若 Hash 已在，**禁止**用 `mysql.stock` 覆盖 `avail`。

---

## 3. 数据与 Redis Key

已有库执行 `src/main/resources/db/product-stock-seq-schema.sql`（`stock_seq`、`idempotent_key` UNIQUE）。全新库可用已合并列的 `product-module-schema.sql`。

| Key | 内容 |
|-----|------|
| `demo2:stock:{productId}` | Hash `avail`, `seq` |
| `demo2:stock:reserve:{orderId}:{productId}` | 预占 qty |
| `demo2:stock:outbox` | Stream 出箱 |

Lua：`src/main/resources/lua/stock-*.lua`（`GET`+`DEL`，不用 `GETDEL`）。

---

## 4. 包位置（硬约束）

| 职责 | 包 |
|------|-----|
| 消息体 / 发 MQ | `product.service.infrastructure.publisher` |
| RocketMQ 消费者、Stream Relay | `product.app.listener` |
| 定时对账 | `product.app.job` |

**禁止**把库存同步放到全局 `com.jason.demo.demo2.mq`。自定义 SQL 仍只写 XML。

---

## 5. HTTP 与错误码

全部 **POST** + JSON，无 `@LoginRequired`。HTTP 始终 200。

| 路径 | 说明 |
|------|------|
| `POST /demo/products/listProducts` | 上架列表；热路径 overlay `availableStock` |
| `POST /demo/products/getProduct` | 详情同上 |
| `POST /demo/products/offShelf` | 只改 `OFF_SHELF`，不改 Redis |
| `POST /demo/products/onShelf` | 无 Hash 则 HSETNX，已有 Hash 不覆盖 |
| `POST /demo/products/adjustStock` | 必须下架；Hash 存在且 seq 不齐 → 40010 |

| 码 | 枚举 | 说明 |
|----|------|------|
| 40008 | ADJUST_REQUIRES_OFF_SHELF | 未下架不允许调库存 |
| 40009 | ADJUST_INVALID_TARGET | 目标现货非法 |
| 40010 | STOCK_SYNC_LAG | 同步未追上 / Hash 未加载 |

---

## 6. 配置

```properties
app.product.stock.redis-hot-enabled=true
app.product.stock.reconcile-interval-ms=60000
app.product.stock.reconcile-lag-alarm-ms=300000
rocketmq.producers.stockSyncProducer.topic=DEMO_STOCK_TOPIC
rocketmq.consumers.stockSync.listenerBeanName=stockSyncMqListener
```

依赖：MySQL + Redis + RocketMQ NameServer（`127.0.0.1:9876`）。

---

## 7. 测试与手工验证

单测覆盖 Lua 语义、Ops、HotService、`applyDelta`、Relay ACK、Listener 缺口/冲突、上下架/ADJUST、对账分流、C 端 overlay。

手工：会员 Tab 看详情库存；Scalar / curl 走下架 → `adjustStock` → 上架，再刷新详情应对齐 Redis `avail`。seed 商品若已上架但 Redis 无 Hash，热路径预占会 `40010`，需先下架再上架灌入。

---

## 8. 变更记录

| 日期 | 说明 |
|------|------|
| 2026-08-28 | 初版归档：Redis 热闸门 + MQ 投影 + Demo 上下架/调库存 + seq 对账 |
