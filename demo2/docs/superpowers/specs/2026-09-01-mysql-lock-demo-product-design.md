# demo2 MySQL 锁表 / 锁行演示（demo_product）操作手册

**日期**: 2026-09-01  
**项目**: spring-ai-demo / demo2  
**状态**: 已定稿（交付物即本文，无代码改动）  
**范围**: 仅文档。用现有 `demo_product` 在直连 MySQL 上复现「正确行锁 / 行锁放大 / 真·表锁」，并给出观察与解除、避免再犯的解法。

---

## 1. 背景与目标

### 1.1 背景

业务里常把「卡住」笼统叫成锁表。InnoDB 默认是**行锁**；真·表锁多来自显式 `LOCK TABLES`，或无合适索引导致锁范围放大后「看起来像锁表」。

商品主表 `demo_product` 已有种子数据，适合做双会话对照实验，无需改 Java / 表结构。

### 1.2 目标

1. 用双会话逐步脚本，亲手造出三类现象并观察等待关系。  
2. 每类都给出：**当场解除** + **业务上如何避免**。  
3. 零代码改动；直连物理库执行即可。

### 1.3 已确认决策

| 维度 | 选择 |
|------|------|
| 形态 | 中文 Markdown 操作手册（本文） |
| 表 | 仅 `demo_product` |
| 场景 | ① 按 `product_id` 正确行锁 ② 行锁放大（像锁表）③ `LOCK TABLES` 真·表锁 |
| 连接 | 直连 `spring_ai_agent2`，**不要**走应用 ShardingSphere |
| 实现 | 不改 Java / API / 前端 / DDL |

### 1.4 非目标

- 死锁专题、MDL/DDL、间隙锁深挖  
- 新 HTTP 演示接口或前端调试页  
- 改库存表 `demo_product_stock`（库存冷路径已有 `FOR UPDATE`，本手册不重复）

---

## 2. 前置条件

| 项 | 约定 |
|----|------|
| MySQL | 本机 `127.0.0.1:3306`，库 `spring_ai_agent2`（与 `shardingsphere.yaml` 中 `ds_default` 一致） |
| 账号 | 与本地一致即可（示例：`root` / `123456`） |
| 客户端 | **两个**独立连接，下文称 **会话 A**（持锁）、**会话 B**（冲突）；观察可用第三连接 **会话 C** |
| 引擎 / 隔离级别 | InnoDB，默认 `REPEATABLE READ` |
| 种子行 | `product_id = 2085550503315509001`（拿铁）、`…002`（生椰拿铁）、`…003`（芝士蛋糕） |

每个会话先执行：

```sql
USE spring_ai_agent2;
SELECT CONNECTION_ID() AS conn_id;
```

记下 `conn_id`，急救时用。

确认表与数据：

```sql
SHOW CREATE TABLE demo_product\G
SELECT id, product_id, product_name, status, subtitle
FROM demo_product
ORDER BY id;
```

---

## 3. 卡住时急救（先看这里）

在 **会话 C**（或任意空闲连接）：

```sql
SHOW FULL PROCESSLIST;
```

找到长时间 `Locked` / 未提交事务的线程，记下 `Id`：

```sql
KILL <thread_id>;
```

| 场景 | 正规解除（优先） | 粗暴解除 |
|------|------------------|----------|
| 行锁（场景一、二） | 持锁会话 `COMMIT;` 或 `ROLLBACK;` | `KILL` 持锁连接 |
| 表锁（场景三） | 持锁会话 `UNLOCK TABLES;`（**不能**用 `COMMIT` 代替） | `KILL` 持锁连接 |

每换下一场景前，确保 A/B 都无未结束事务、无表锁。

---

## 4. 通用观察脚本（会话 C）

MySQL 8.x（本项目默认按 8）：

```sql
-- 会话是否卡住
SHOW FULL PROCESSLIST;

-- 未提交事务
SELECT trx_id, trx_state, trx_started, trx_mysql_thread_id, trx_query
FROM information_schema.INNODB_TRX;

-- 谁持有哪些锁
SELECT ENGINE_TRANSACTION_ID, OBJECT_SCHEMA, OBJECT_NAME, INDEX_NAME,
       LOCK_TYPE, LOCK_MODE, LOCK_STATUS, LOCK_DATA
FROM performance_schema.data_locks
WHERE OBJECT_NAME = 'demo_product';

-- 谁在等谁
SELECT *
FROM performance_schema.data_lock_waits;
```

若是 5.7，用：

```sql
SELECT * FROM information_schema.INNODB_LOCKS;
SELECT * FROM information_schema.INNODB_LOCK_WAITS;
```

---

## 5. 场景一：正确行锁（只堵同行）

**目的**：证明按唯一键 `product_id` 加锁时，只阻塞同一行；其他 `product_id` 不受影响。

### 5.1 造锁 — 会话 A

```sql
USE spring_ai_agent2;
BEGIN;

SELECT id, product_id, product_name, subtitle
FROM demo_product
WHERE product_id = 2085550503315509001
FOR UPDATE;
```

**说明**：`uk_demo_product_product_id` 命中，InnoDB 对这一行加排他行锁；事务未提交前锁一直持有。  
**预期**：立刻返回 1 行（拿铁）。**不要**在本会话 `COMMIT`，先去做 B。

### 5.2 冲突 — 会话 B（会卡住）

```sql
USE spring_ai_agent2;

UPDATE demo_product
SET subtitle = '场景一行锁冲突-同商品',
    updated_at = NOW(3)
WHERE product_id = 2085550503315509001;
```

**预期**：语句挂起，直到 A 提交/回滚，或超过 `innodb_lock_wait_timeout`（默认常 50s）报错。

### 5.3 对照 — 会话 B 另开语句（或等 5.2 结束后再测）

若 5.2 仍在等待，请再开一个 **会话 B2**，或先跳过本步、完成 5.5 后再单独测：

```sql
UPDATE demo_product
SET subtitle = '场景一对照-其他商品仍可改',
    updated_at = NOW(3)
WHERE product_id = 2085550503315509002;
```

**预期**（在 A 仍持有 …001 行锁时）：**立刻成功**。说明不是整表锁。

### 5.4 观察 — 会话 C

执行第 4 节观察 SQL。  
**预期**：能看到 B（或 B 的线程）在等 A 持有的 `demo_product` 行锁。

### 5.5 解除 — 会话 A

```sql
COMMIT;
-- 或 ROLLBACK;
```

**预期**：会话 B 中卡住的 `UPDATE` 立刻完成（或失败后重试可成功）。

清理演示脏数据（任一会话）：

```sql
UPDATE demo_product
SET subtitle = CASE product_id
    WHEN 2085550503315509001 THEN '经典浓郁，口感顺滑'
    WHEN 2085550503315509002 THEN '椰香清甜，清爽不腻'
    ELSE subtitle END,
    updated_at = NOW(3)
WHERE product_id IN (2085550503315509001, 2085550503315509002);
```

### 5.6 如何解决 / 避免

| 手段 | 做法 |
|------|------|
| 当场解除 | 持锁方尽快 `COMMIT`/`ROLLBACK`；必要时 `KILL` |
| 正确加锁 | `WHERE product_id = ?` 或 `WHERE id = ?`，禁止无索引条件 `FOR UPDATE` |
| 缩短临界区 | 事务内只做必要读写；RPC/外部调用移出事务 |
| 超时 | 关注 `innodb_lock_wait_timeout`；应用层捕获锁等待超时后重试或提示冲突 |
| 业务更新 | 优先 `UPDATE … WHERE product_id = ?`；需要先读后写再用 `SELECT … FOR UPDATE` |

---

## 6. 场景二：行锁放大（像「锁表」）

**目的**：条件锁住**多行**（或全表扫描加锁）时，其他商品更新也会等，现象很像锁表，本质仍是行锁范围过大。

本种子数据里三件商品都是 `ON_SHELF`，且存在索引 `idx_demo_product_status_sort (status, sort)`。对 `status = 'ON_SHELF'` 加 `FOR UPDATE` 会锁住**全部上架行**，用三行就能稳定复现「改谁都等」。

### 6.1 造锁 — 会话 A

```sql
USE spring_ai_agent2;
BEGIN;

SELECT id, product_id, product_name, status
FROM demo_product
WHERE status = 'ON_SHELF'
FOR UPDATE;
```

**说明**：走 `status` 二级索引，对所有匹配行（种子下即全部商品）加锁。  
**预期**：返回多行；事务保持打开。

### 6.2 冲突 — 会话 B（「无关」商品也会卡）

```sql
USE spring_ai_agent2;

UPDATE demo_product
SET subtitle = '场景二放大-改芝士蛋糕也等',
    updated_at = NOW(3)
WHERE product_id = 2085550503315509003;
```

**预期**：**等待**（A 已锁住含 …003 在内的上架行）。对比场景一：那里改 …002 不需要等。

### 6.3 观察 — 会话 C

再跑第 4 节 SQL。  
**预期**：`data_locks` 中 `demo_product` 相关锁条目明显多于场景一（多行 / 索引记录）。

### 6.4 解除 — 会话 A

```sql
COMMIT;
-- 或 ROLLBACK;
```

**预期**：B 的 UPDATE 完成。

可选还原 subtitle：

```sql
UPDATE demo_product
SET subtitle = '绵密芝士，下午茶推荐',
    updated_at = NOW(3)
WHERE product_id = 2085550503315509003;
```

### 6.5 可选扩展：无索引条件（原理对照）

`product_name` **没有**索引。下列语句会走聚簇索引扫描并对考察行加锁，行少时锁形态依赖版本与优化器，不如 6.1 直观，仅作对照：

```sql
BEGIN;
SELECT id, product_id, product_name
FROM demo_product
WHERE product_name LIKE '%拿铁%'
FOR UPDATE;
-- 观察后：COMMIT; 或 ROLLBACK;
```

**解法同一套**：加锁条件必须能精确命中索引；模糊查询不要 `FOR UPDATE`。

### 6.6 如何解决 / 避免

| 手段 | 做法 |
|------|------|
| 当场解除 | 持锁方 `COMMIT`/`ROLLBACK`；或 `KILL` |
| 索引命中 | 加锁/更新条件用 `product_id` / `PRIMARY KEY`；避免 `LIKE '%…%'`、无索引列 |
| 缩小锁范围 | 不要 `WHERE status = ? FOR UPDATE` 后再改一行；改为 `WHERE product_id = ? FOR UPDATE` |
| 批量 | 大范围变更按主键分段、短事务多次提交，避免一次锁住整表等价集合 |
| 能更新则更新 | 无需先读时直接 `UPDATE … WHERE product_id = ?` |

---

## 7. 场景三：真·表锁（`LOCK TABLES`）

**目的**：显式表写锁会挡住**对该表的任意**读写（其他会话），与行锁「只堵冲突行」不同。

### 7.1 造锁 — 会话 A

先确保无未提交事务（`LOCK TABLES` 会隐式提交当前事务）：

```sql
USE spring_ai_agent2;

LOCK TABLES demo_product WRITE;
```

**说明**：会话 A 独占表；在 `UNLOCK TABLES` 前，A 也只能访问被锁住的这些表。  
**预期**：立刻成功。

### 7.2 冲突 — 会话 B

```sql
USE spring_ai_agent2;

SELECT product_id, product_name FROM demo_product LIMIT 1;
-- 或
UPDATE demo_product
SET subtitle = '场景三表锁',
    updated_at = NOW(3)
WHERE product_id = 2085550503315509002;
```

**预期**：任意访问 `demo_product` 都**等待**（含「无关」product_id）。这才是真·表级互斥。

### 7.3 观察 — 会话 C

```sql
SHOW FULL PROCESSLIST;
```

**预期**：B 的状态多为等待表元数据/表锁相关（不同版本文案略有差异）；`performance_schema.data_locks` 未必能像行锁那样列出 InnoDB 行锁条目——表锁是另一套机制。

### 7.4 解除 — 会话 A

```sql
UNLOCK TABLES;
```

**预期**：B 立刻继续。  
注意：**`COMMIT` 不能代替 `UNLOCK TABLES`。**

### 7.5 如何解决 / 避免

| 手段 | 做法 |
|------|------|
| 当场解除 | 持锁连接执行 `UNLOCK TABLES;`；失联则 `KILL` |
| 业务禁令 | InnoDB 业务路径**不要**使用 `LOCK TABLES`；用行锁或乐观条件更新 |
| 运维 | 若必须锁表（极少见），缩短窗口、避开高峰、先公告 |
| DDL | 注意 Metadata Lock（本手册不展开）；长查询会堵住 DDL，DDL 也会堵住 DML |

---

## 8. 三场景对照总结

| | 场景一 正确行锁 | 场景二 行锁放大 | 场景三 真·表锁 |
|--|-----------------|-----------------|----------------|
| 造锁语句 | `WHERE product_id=? FOR UPDATE` | `WHERE status='ON_SHELF' FOR UPDATE` | `LOCK TABLES … WRITE` |
| 同商品 UPDATE | 等待 | 等待 | 等待 |
| 其他商品 UPDATE | **不**等待 | **等待** | **等待** |
| 解除 | `COMMIT`/`ROLLBACK` | 同左 | **`UNLOCK TABLES`** |
| 本质 | 单行排他锁 | 多行/索引范围锁 | 表级锁 |

**处理口诀**

1. 先 `SHOW FULL PROCESSLIST` + `INNODB_TRX` 找到持锁方。  
2. 能沟通就让对方提交/解锁；不能就 `KILL`。  
3. 根治：唯一键短事务；禁止无索引 / 过宽条件加锁；禁止业务 `LOCK TABLES`。

---

## 9. 交付说明

| 项 | 内容 |
|----|------|
| 交付物 | 本文（可执行操作手册） |
| 代码 | **无** |
| 实施计划 | **不需要**（无实现任务） |
| 使用方式 | 按第 5→6→7 节顺序，双会话逐步复制 SQL |

---

## 10. 自检清单（写作者）

- [x] 无 TBD/TODO 占位  
- [x] 三场景均含：造锁 → 冲突 → 观察 → 解除 → 解法  
- [x] 明确直连 `spring_ai_agent2`、双会话、急救与 `UNLOCK TABLES`  
- [x] 范围仅文档 + `demo_product`，与「不改代码」一致  
