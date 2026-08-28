# Redis 热库存与 MySQL 最终一致 Implementation Plan

> **Status:** 已实现（2026-08-28）。归档见 `docs/superpowers/archive/2026-08-27-redis-stock-consistency.md`。Task 1–9 的 Step 1–4 已完成；各 Task Step 5 commit 按用户策略未执行。
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把秒杀闸门放到 Redis（Lua 改 `avail`/`seq`/预占票 + Stream 出箱），Relay **只发** RocketMQ，消费者用 `stock_seq` 乐观投影写 MySQL；直写方案 A（行锁）与运营 ADJUST 仍走 MySQL，并提供 Demo 上下架/调库存 HTTP 与按 seq 分流的对账。

**Architecture:** 热路径：`ProductStockHotService` → `RedisStockOps`（classpath Lua）→ Stream `demo2:stock:outbox` → `RedisStockOutboxRelay` **仅** `sendImmediate` RocketMQ → `StockSyncMqListener.applyDelta`（`UPDATE … WHERE stock_seq = seq-1`，无 `SELECT FOR UPDATE`）。冷路径/开关关闭/ADJUST：`ProductStockDomainService` 行锁 + 内存推 after + `stock_seq+=1`。C 端可售在热路径开启时读 Redis `avail`，`sell_stock` 仍读 MySQL。

**Tech Stack:** Java 21, Spring Boot 4.x, MyBatis-Plus XML Mapper, `StringRedisTemplate` + `DefaultRedisScript`, RocketMQ（沿用 `BaseEventPublisher` / `RocketMessageConcurrentlyListener`）, JUnit 5, Mockito.

**Spec:** `demo2/docs/superpowers/specs/2026-08-27-redis-stock-consistency-design.md`

## Global Constraints

- 依赖方向不变：`app → service.core → service.infrastructure`；framework 不得依赖 product。
- 自定义 SQL **只写 XML**（`src/main/resources/mapper/product/ProductStockMapper.xml`），Mapper 接口禁止 `@Select/@Update`。
- Redis Hash **只有** `avail` + `seq`；禁止把 `actual`/`withhold`/`sell`/ticket 状态机放进 Redis。
- 热路径投影 **禁止** `SELECT FOR UPDATE`；行锁只用于 ADJUST 与 `redis-hot-enabled=false`。
- Relay **禁止**写 MySQL；**只有 MQ 发送成功才 XACK**。
- Lua 用 `GET` + `DEL`，禁止 `GETDEL`（兼容 Redis < 6.2）。
- 热卖中 Lua `UNLOADED`：**禁止**用当时的 `mysql.stock` 去 `SET`/`HSET` Hash。
- 再次上架若 Hash 已存在：**禁止**用 `mysql.stock` 覆盖 `avail`。
- MQ 投影成功后：**禁止**回写 Redis。
- 错误码：`40008` `ADJUST_REQUIRES_OFF_SHELF`、`40009` `ADJUST_INVALID_TARGET`、`40010` `STOCK_SYNC_LAG`；**不要**复用 `40006`。
- HTTP 始终 200；业务失败 `throw new BusinessException(ProductErrorCodeEnum.xxx)`。
- 枚举名以 `Enum` 结尾；日志 `@Slf4j`。
- 本阶段 **不改订单 HTTP / 订单表**；`ProductStockHotService` 作为后续下单入口。
- 商品模块 **禁止**把库存同步消息放到 `com.jason.demo.demo2.mq`：消息体与 Publisher 在 `product.service.infrastructure.publisher`；RocketMQ 消费者与 Redis Stream 出箱消费者都在 `product.app.listener`。定时对账才放 `product.app.job`。
- 工作目录：`demo2/`。Maven：`.\mvnw.cmd test "-Dtest=ClassName"`（PowerShell 必须给 `-Dtest=` 加引号）。
- **执行时仅当用户当轮明确要求 commit 才执行各 Task 的 Commit 步骤**；否则跳过 `git commit`，只完成代码与测试。

---

## File Structure

### Create

**SQL**

- `demo2/src/main/resources/db/product-stock-seq-schema.sql`（已有库 ALTER + 回填）

**Lua**

- `demo2/src/main/resources/lua/stock-reserve.lua`
- `demo2/src/main/resources/lua/stock-confirm.lua`
- `demo2/src/main/resources/lua/stock-release.lua`
- `demo2/src/main/resources/lua/stock-adjust.lua`

**service.common / config**

- `demo2/src/main/java/com/jason/demo/demo2/product/config/ProductStockProperties.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/config/ProductStockConfiguration.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/common/ProductStockIdempotentKeys.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/common/StockSeqGapException.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/common/RedisStockResult.java`

**service.core**

- `demo2/src/main/java/com/jason/demo/demo2/product/service/core/ProductStockHotService.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/core/StockReconcileService.java`

**service.infrastructure**

- `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/redis/RedisStockKeys.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/redis/RedisStockOps.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/publisher/StockSyncEvent.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/publisher/StockSyncEventPublisher.java`

**app listener**

- `demo2/src/main/java/com/jason/demo/demo2/product/app/listener/StockSyncMqListener.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/app/listener/RedisStockOutboxRelay.java`

**app job**

- `demo2/src/main/java/com/jason/demo/demo2/product/app/job/StockReconcileJob.java`

**app HTTP**

- `demo2/src/main/java/com/jason/demo/demo2/product/app/executor/ProductOffShelfCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/app/executor/ProductOnShelfCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/app/executor/ProductAdjustStockCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/app/vo/req/AdjustStockReqVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/app/vo/res/ProductShelfResVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/app/vo/res/AdjustStockResVO.java`

**tests**

- `demo2/src/test/java/com/jason/demo/demo2/product/ProductStockIdempotentKeysTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/product/ProductStockTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/product/ProductStockApplyDeltaTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/product/RedisStockLuaScriptTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/product/RedisStockOpsTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/product/ProductStockHotServiceTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/product/RedisStockOutboxRelayTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/product/StockSyncMqListenerTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/product/StockReconcileServiceTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/product/ProductShelfCmdExeTest.java`

### Modify

- `demo2/src/main/resources/db/product-module-schema.sql`（新库建表即含 `stock_seq` / `idempotent_key`）
- `demo2/src/main/java/com/jason/demo/demo2/product/service/common/ProductErrorCodeEnum.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/dao/entity/ProductStockDO.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/dao/entity/ProductStockLogDO.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/core/domain/ProductStock.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/core/ProductStockDomainService.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/core/ProductDomainService.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/dao/mapper/ProductStockMapper.java`
- `demo2/src/main/resources/mapper/product/ProductStockMapper.xml`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/repository/ProductStockRepository.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/repository/ProductStockLogRepository.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/repository/ProductRepository.java`
- `demo2/src/main/java/com/jason/demo/demo2/framework/rocketmq/producer/BaseEventPublisher.java`（新增 `sendImmediate`，失败抛异常）
- `demo2/src/main/java/com/jason/demo/demo2/product/app/controller/ProductController.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/app/executor/ProductListCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/app/executor/ProductGetCmdExe.java`
- `demo2/src/main/resources/application.properties`
- `demo2/src/test/java/com/jason/demo/demo2/product/ProductStockDomainServiceTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/product/ProductStockMapperXmlTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/product/ProductCmdExeTest.java`
- `demo2/CLAUDE.md`
- Spec 状态：实现全部完成后改为「已实现」

---

## Interfaces Produced Across Tasks

```java
public final class ProductStockIdempotentKeys {
    public static String of(long orderId, long productId, ProductStockOptTypeEnum optType);
    public static String ofAdjust(long adjustId);
}

public class ProductStock extends ProductStockDO {
    public static ProductStock from(ProductStockDO source); // 必须拷贝 stockSeq
    public ProductStock copy();
    public ProductStock applyReserve(int qty);
    public ProductStock applyConfirm(int qty);
    public ProductStock applyRelease(int qty);
    public ProductStock applyAdjust(int targetActual);
    public static ProductStock reverse(ProductStock after, ProductStockOptTypeEnum op, int n);
}

// ProductStockDomainService（方案 A + 投影）
@Transactional public void reserve(long productId, long orderId, int qty);
@Transactional public void confirm(long productId, long orderId, int qty);
@Transactional public void release(long productId, long orderId);
@Transactional public ProductStock adjust(long productId, int targetActual, long adjustId);
@Transactional public void applyDelta(StockSyncEvent event); // 缺口抛 StockSeqGapException

public record RedisStockResult(int code, String reason) {}
// code: -1 UNLOADED; 1 OK; 2 IDEMPOTENT 或 NO_TICKET; 0 失败（INSUFFICIENT/CONFLICT/NOT_FOUND）

public class RedisStockOps {
    public RedisStockResult reserve(long productId, long orderId, int qty, String idempotentKey);
    public RedisStockResult confirm(long productId, long orderId, String idempotentKey);
    public RedisStockResult release(long productId, long orderId, String idempotentKey);
    public RedisStockResult adjustHash(long productId, int avail, long seq); // 跑 stock-adjust.lua
    public boolean hsetnxHash(long productId, int avail, long seq); // true=本次写入；已存在不得覆盖
    public Optional<Long> getAvail(long productId);
    public Optional<Long> getSeq(long productId);
}

public class ProductStockHotService {
    public void reserve(long productId, long orderId, int qty);
    public void confirm(long productId, long orderId, int qty);
    public void release(long productId, long orderId);
    public Optional<Integer> overlayAvail(long productId); // 冷路径 empty；热路径读 Redis avail
}

package com.jason.demo.demo2.product.service.infrastructure.publisher;
public class StockSyncEvent {
    long productId; long orderId; String optType; int qty; String idempotentKey; long seq;
}

protected void BaseEventPublisher.sendImmediate(Object messageBodyObj, String... keys);
// 无 afterCommit；重试耗尽后 throw IllegalStateException（Relay 据此不 XACK）
```

---

### Task 1: Schema、DO、错误码、幂等键

**Files:**
- Create: `demo2/src/main/resources/db/product-stock-seq-schema.sql`
- Create: `demo2/src/main/java/com/jason/demo/demo2/product/service/common/ProductStockIdempotentKeys.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/product/ProductStockIdempotentKeysTest.java`
- Modify: `demo2/src/main/resources/db/product-module-schema.sql`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/service/common/ProductErrorCodeEnum.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/dao/entity/ProductStockDO.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/dao/entity/ProductStockLogDO.java`

**Interfaces:**
- Consumes: 无
- Produces: `ProductStockDO.stockSeq`、`ProductStockLogDO.idempotentKey`、`ProductErrorCodeEnum.ADJUST_REQUIRES_OFF_SHELF/ADJUST_INVALID_TARGET/STOCK_SYNC_LAG`、`ProductStockIdempotentKeys`

- [x] **Step 1: Write the failing test**

```java
package com.jason.demo.demo2.product;

import com.jason.demo.demo2.product.service.common.ProductErrorCodeEnum;
import com.jason.demo.demo2.product.service.common.ProductStockIdempotentKeys;
import com.jason.demo.demo2.product.service.common.ProductStockOptTypeEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductStockIdempotentKeysTest {

    @Test
    void of_joinsOrderProductOpt() {
        assertEquals("100:9001:RESERVE",
                ProductStockIdempotentKeys.of(100L, 9001L, ProductStockOptTypeEnum.RESERVE));
        assertEquals("ADJUST:55", ProductStockIdempotentKeys.ofAdjust(55L));
    }

    @Test
    void newErrorCodes_areStable() {
        assertEquals(40008, ProductErrorCodeEnum.ADJUST_REQUIRES_OFF_SHELF.getCode());
        assertEquals(40009, ProductErrorCodeEnum.ADJUST_INVALID_TARGET.getCode());
        assertEquals(40010, ProductErrorCodeEnum.STOCK_SYNC_LAG.getCode());
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run（在 `demo2/`）:

```powershell
.\mvnw.cmd test "-Dtest=ProductStockIdempotentKeysTest"
```

Expected: FAIL（`ProductStockIdempotentKeys` 不存在 和/或 枚举常量不存在）

- [x] **Step 3: Write minimal implementation**

`ProductErrorCodeEnum` 在 `STOCK_NOT_FOUND` 后追加（保留现有 40001–40005、40007）：

```java
    ADJUST_REQUIRES_OFF_SHELF(40008, "调整库存前必须先下架"),
    ADJUST_INVALID_TARGET(40009, "目标现货非法"),
    STOCK_SYNC_LAG(40010, "库存同步未追上");
```

`ProductStockIdempotentKeys.java`：

```java
package com.jason.demo.demo2.product.service.common;

public final class ProductStockIdempotentKeys {

    private ProductStockIdempotentKeys() {
    }

    public static String of(long orderId, long productId, ProductStockOptTypeEnum optType) {
        return orderId + ":" + productId + ":" + optType.name();
    }

    public static String ofAdjust(long adjustId) {
        return "ADJUST:" + adjustId;
    }
}
```

`ProductStockDO` 增加：

```java
    private Long stockSeq;
```

`ProductStockLogDO` 增加（放在 `optType` 后）：

```java
    private String idempotentKey;
```

`product-module-schema.sql`：

- `demo_product_stock` 在 `sell_stock` 后加 `stock_seq BIGINT NOT NULL DEFAULT 0 COMMENT '已投影的 Redis seq'`
- seed `INSERT INTO demo_product_stock` 增加列 `stock_seq`，三行均写 `0`
- `demo_product_stock_log` 在 `opt_type` 后加 `idempotent_key VARCHAR(64) NOT NULL COMMENT '幂等键'`，并 `UNIQUE KEY uk_stock_log_idempotent (idempotent_key)`

`product-stock-seq-schema.sql`（已有库执行一次；列/索引已存在时该文件会失败，属预期）：

```sql
ALTER TABLE demo_product_stock
    ADD COLUMN stock_seq BIGINT NOT NULL DEFAULT 0 COMMENT '已投影的 Redis seq' AFTER sell_stock;

ALTER TABLE demo_product_stock_log
    ADD COLUMN idempotent_key VARCHAR(64) NULL COMMENT '幂等键' AFTER opt_type;

UPDATE demo_product_stock_log
SET idempotent_key = CONCAT(IFNULL(order_id, '0'), ':', product_id, ':', opt_type)
WHERE idempotent_key IS NULL;

ALTER TABLE demo_product_stock_log
    MODIFY idempotent_key VARCHAR(64) NOT NULL,
    ADD UNIQUE KEY uk_stock_log_idempotent (idempotent_key);
```

- [x] **Step 4: Run test to verify it passes**

```powershell
.\mvnw.cmd test "-Dtest=ProductStockIdempotentKeysTest"
```

Expected: PASS

- [ ] **Step 5: Commit**（仅当用户要求）

```bash
git add demo2/src/main/resources/db/product-module-schema.sql demo2/src/main/resources/db/product-stock-seq-schema.sql demo2/src/main/java/com/jason/demo/demo2/product/service/common/ProductErrorCodeEnum.java demo2/src/main/java/com/jason/demo/demo2/product/service/common/ProductStockIdempotentKeys.java demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/dao/entity/ProductStockDO.java demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/dao/entity/ProductStockLogDO.java demo2/src/test/java/com/jason/demo/demo2/product/ProductStockIdempotentKeysTest.java
git commit -m "feat(product): add stock_seq, log idempotent key, and 40008-40010"
```

---

### Task 2: `ProductStock` 内存推演与 reverse

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/service/core/domain/ProductStock.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/product/ProductStockTest.java`

**Interfaces:**
- Consumes: `ProductStockDO.stockSeq`
- Produces: `copy` / `applyReserve` / `applyConfirm` / `applyRelease` / `applyAdjust` / `reverse`

- [x] **Step 1: Write the failing test**

```java
package com.jason.demo.demo2.product;

import com.jason.demo.demo2.product.service.common.ProductStockOptTypeEnum;
import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductStockTest {

    @Test
    void from_copiesStockSeq() {
        ProductStock source = base(100, 0, 100, 10);
        source.setStockSeq(7L);
        ProductStock copy = ProductStock.from(source);
        assertEquals(7L, copy.getStockSeq());
        assertEquals(100, copy.getStock());
    }

    @Test
    void applyReserve_thenReverse_restores() {
        ProductStock before = base(100, 0, 100, 10);
        ProductStock after = before.copy().applyReserve(3);
        after.assertBalance();
        assertEquals(97, after.getStock());
        assertEquals(3, after.getWithholdStock());
        ProductStock restored = ProductStock.reverse(after, ProductStockOptTypeEnum.RESERVE, 3);
        assertEquals(100, restored.getStock());
        assertEquals(0, restored.getWithholdStock());
        assertNotSame(after, restored);
    }

    @Test
    void applyConfirm_doesNotChangeAvail() {
        ProductStock after = base(97, 3, 100, 10).applyConfirm(3);
        after.assertBalance();
        assertEquals(97, after.getStock());
        assertEquals(0, after.getWithholdStock());
        assertEquals(97, after.getActualStock());
        assertEquals(13, after.getSellStock());
        ProductStock restored = ProductStock.reverse(after, ProductStockOptTypeEnum.CONFIRM, 3);
        assertEquals(100, restored.getActualStock());
        assertEquals(3, restored.getWithholdStock());
        assertEquals(10, restored.getSellStock());
    }

    @Test
    void applyRelease_restoresAvail() {
        ProductStock after = base(97, 3, 100, 10).applyRelease(3);
        after.assertBalance();
        assertEquals(100, after.getStock());
        assertEquals(0, after.getWithholdStock());
    }

    @Test
    void applyAdjust_setsActualAndAvail() {
        ProductStock after = base(90, 10, 100, 5).applyAdjust(80);
        after.assertBalance();
        assertEquals(80, after.getActualStock());
        assertEquals(70, after.getStock());
        assertEquals(10, after.getWithholdStock());
    }

    @Test
    void applyAdjust_rejectsBelowWithhold() {
        assertThrows(IllegalArgumentException.class, () -> base(90, 10, 100, 5).applyAdjust(9));
    }

    private static ProductStock base(int stock, int withhold, int actual, int sell) {
        ProductStock s = new ProductStock();
        s.setStockId(1L);
        s.setProductId(9L);
        s.setStock(stock);
        s.setWithholdStock(withhold);
        s.setActualStock(actual);
        s.setSellStock(sell);
        s.setStockSeq(0L);
        return s;
    }
}
```

- [x] **Step 2: Run test to verify it fails**

```powershell
.\mvnw.cmd test "-Dtest=ProductStockTest"
```

Expected: FAIL（`copy`/`applyReserve` 等方法不存在）

- [x] **Step 3: Write minimal implementation**

完整替换 `ProductStock.java`：

```java
package com.jason.demo.demo2.product.service.core.domain;

import com.jason.demo.demo2.product.service.common.ProductStockOptTypeEnum;
import com.jason.demo.demo2.product.service.infrastructure.dao.entity.ProductStockDO;

public class ProductStock extends ProductStockDO {

    public static ProductStock from(ProductStockDO source) {
        if (source == null) {
            return null;
        }
        ProductStock stock = new ProductStock();
        stock.setId(source.getId());
        stock.setStockId(source.getStockId());
        stock.setProductId(source.getProductId());
        stock.setActualStock(source.getActualStock());
        stock.setStock(source.getStock());
        stock.setWithholdStock(source.getWithholdStock());
        stock.setSellStock(source.getSellStock());
        stock.setStockSeq(source.getStockSeq());
        stock.setUpdatedAt(source.getUpdatedAt());
        return stock;
    }

    public ProductStock copy() {
        return ProductStock.from(this);
    }

    public ProductStock applyReserve(int qty) {
        setStock(getStock() - qty);
        setWithholdStock(getWithholdStock() + qty);
        return this;
    }

    public ProductStock applyConfirm(int qty) {
        setActualStock(getActualStock() - qty);
        setWithholdStock(getWithholdStock() - qty);
        setSellStock(getSellStock() + qty);
        return this;
    }

    public ProductStock applyRelease(int qty) {
        setStock(getStock() + qty);
        setWithholdStock(getWithholdStock() - qty);
        return this;
    }

    public ProductStock applyAdjust(int targetActual) {
        if (targetActual < 0 || targetActual < getWithholdStock()) {
            throw new IllegalArgumentException("targetActual must be >= withhold");
        }
        setActualStock(targetActual);
        setStock(targetActual - getWithholdStock());
        return this;
    }

    public static ProductStock reverse(ProductStock after, ProductStockOptTypeEnum op, int n) {
        ProductStock before = after.copy();
        switch (op) {
            case RESERVE -> {
                before.setStock(after.getStock() + n);
                before.setWithholdStock(after.getWithholdStock() - n);
            }
            case CONFIRM -> {
                before.setActualStock(after.getActualStock() + n);
                before.setWithholdStock(after.getWithholdStock() + n);
                before.setSellStock(after.getSellStock() - n);
            }
            case RELEASE -> {
                before.setStock(after.getStock() - n);
                before.setWithholdStock(after.getWithholdStock() + n);
            }
            default -> throw new IllegalArgumentException("cannot reverse " + op);
        }
        return before;
    }

    public void assertBalance() {
        if (getStock() == null || getActualStock() == null || getWithholdStock() == null) {
            throw new IllegalStateException("stock fields must not be null");
        }
        if (!getStock().equals(getActualStock() - getWithholdStock())) {
            throw new IllegalStateException("stock balance violated: stock="
                    + getStock() + ", actual=" + getActualStock() + ", withhold=" + getWithholdStock());
        }
    }
}
```

- [x] **Step 4: Run test to verify it passes**

```powershell
.\mvnw.cmd test "-Dtest=ProductStockTest"
```

Expected: PASS

- [ ] **Step 5: Commit**（仅当用户要求）

```bash
git add demo2/src/main/java/com/jason/demo/demo2/product/service/core/domain/ProductStock.java demo2/src/test/java/com/jason/demo/demo2/product/ProductStockTest.java
git commit -m "feat(product): compute stock after-image in memory and reverse from it"
```

---

### Task 3: 方案 A DomainService（FOR UPDATE、内存 after、confirm 幂等、ADJUST）

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/dao/mapper/ProductStockMapper.java`
- Modify: `demo2/src/main/resources/mapper/product/ProductStockMapper.xml`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/repository/ProductStockRepository.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/repository/ProductStockLogRepository.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/service/core/ProductStockDomainService.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/product/ProductStockDomainServiceTest.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/product/ProductStockMapperXmlTest.java`

**Interfaces:**
- Consumes: Task 2 `ProductStock.copy/apply*`、Task 1 `idempotentKey` / `stockSeq`
- Produces: `requireByProductIdForUpdate`、`existsOpt`、`existsByIdempotentKey`、`adjust`、直写 SQL 带 `stock_seq+=1`

直写 XML（在现有 `reserve`/`confirm`/`release` 的 SET 列表增加一行）：

```xml
            stock_seq = stock_seq + 1,
```

Mapper 追加：

```java
    int adjustActual(@Param("productId") long productId, @Param("targetActual") int targetActual);
```

XML 追加：

```xml
    <update id="adjustActual">
        UPDATE demo_product_stock
        SET actual_stock = #{targetActual},
            stock = #{targetActual} - withhold_stock,
            stock_seq = stock_seq + 1,
            updated_at = NOW(3)
        WHERE product_id = #{productId}
          AND #{targetActual} >= withhold_stock
    </update>
```

`ProductStockRepository` 增加：

```java
    public ProductStock requireByProductIdForUpdate(long productId) {
        ProductStockDO row = productStockMapper.selectOne(new LambdaQueryWrapper<ProductStockDO>()
                .eq(ProductStockDO::getProductId, productId)
                .last("FOR UPDATE"));
        if (row == null) {
            throw new BusinessException(ProductErrorCodeEnum.STOCK_NOT_FOUND);
        }
        return productStockDoConvert.toDomain(row);
    }

    public boolean adjustActual(long productId, int targetActual) {
        return productStockMapper.adjustActual(productId, targetActual) > 0;
    }
```

`ProductStockLogRepository` 增加：

```java
    public boolean existsByIdempotentKey(String idempotentKey) {
        return productStockLogMapper.selectCount(new LambdaQueryWrapper<ProductStockLogDO>()
                .eq(ProductStockLogDO::getIdempotentKey, idempotentKey)) > 0;
    }

    public boolean existsOpt(long orderId, long productId, ProductStockOptTypeEnum optType) {
        return productStockLogMapper.selectCount(new LambdaQueryWrapper<ProductStockLogDO>()
                .eq(ProductStockLogDO::getOrderId, orderId)
                .eq(ProductStockLogDO::getProductId, productId)
                .eq(ProductStockLogDO::getOptType, optType.name())) > 0;
    }
```

- [x] **Step 1: Write the failing tests（改 `ProductStockDomainServiceTest`）**

把 `reserve_success_writesLog` 改为：**只 stub `requireByProductIdForUpdate` 一次**，禁止第二次查 after；断言流水 `beforeStock=100`、`afterStock=95`、`idempotentKey=100:9001:RESERVE`。

新增：

```java
    @Test
    void confirm_idempotent_whenConfirmLogExists() {
        when(productStockLogRepository.existsOpt(ORDER_ID, PRODUCT_ID, ProductStockOptTypeEnum.CONFIRM))
                .thenReturn(true);

        service.confirm(PRODUCT_ID, ORDER_ID, 2);

        verify(productStockRepository, never()).confirm(anyLong(), anyInt());
    }

    @Test
    void adjust_updatesActual() {
        ProductStock locked = stock(90, 10, 100);
        locked.setStockSeq(3L);
        when(productStockRepository.requireByProductIdForUpdate(PRODUCT_ID)).thenReturn(locked);
        when(productStockRepository.adjustActual(PRODUCT_ID, 80)).thenReturn(true);
        when(idGenerator.nextId()).thenReturn(2000L);

        ProductStock result = service.adjust(PRODUCT_ID, 80, 55L);

        assertEquals(80, result.getActualStock());
        assertEquals(70, result.getStock());
        ArgumentCaptor<ProductStockLogDO> captor = ArgumentCaptor.forClass(ProductStockLogDO.class);
        verify(productStockLogRepository).insertLog(captor.capture());
        assertEquals("ADJUST:55", captor.getValue().getIdempotentKey());
    }
```

`stock(...)` helper 补 `setStockSeq(0L)`。`reserve_success_writesLog` / `confirm_incrementsSellStock` / `release_restoresStock_fromReserveQty` 全部改为 `when(requireByProductIdForUpdate).thenReturn(before)`，**不要** `thenReturn(before, after)`。

`ProductStockMapperXmlTest` 增加：

```java
        assertTrue(configuration.hasStatement(NAMESPACE + ".adjustActual"));
```

- [x] **Step 2: Run tests to verify they fail**

```powershell
.\mvnw.cmd test "-Dtest=ProductStockDomainServiceTest,ProductStockMapperXmlTest"
```

Expected: FAIL（`requireByProductIdForUpdate` / `adjust` 不存在，或旧测试仍按二次 SELECT stub）

- [x] **Step 3: Implement DomainService**

`writeLog` 增加参数 `String idempotentKey`，写入 `log.setIdempotentKey(idempotentKey)`。

`reserve`：

```java
    @Transactional
    public void reserve(long productId, long orderId, int qty) {
        if (qty <= 0) {
            throw new BusinessException(CommonErrorCodeEnum.BAD_REQUEST, "qty must be positive");
        }
        String key = ProductStockIdempotentKeys.of(orderId, productId, ProductStockOptTypeEnum.RESERVE);
        if (productStockLogRepository.existsByIdempotentKey(key)) {
            return;
        }
        ProductStock before = productStockRepository.requireByProductIdForUpdate(productId);
        if (!productStockRepository.reserve(productId, qty)) {
            throw new BusinessException(ProductErrorCodeEnum.STOCK_INSUFFICIENT);
        }
        ProductStock after = before.copy().applyReserve(qty);
        after.setStockSeq(nullToZero(before.getStockSeq()) + 1);
        after.assertBalance();
        writeLog(before, after, ProductStockOptTypeEnum.RESERVE, orderId, qty, null, key);
    }
```

`confirm`：若 `existsOpt(..., CONFIRM)` 直接 return；否则 FOR UPDATE → `confirm` SQL → `before.copy().applyConfirm(effectiveQty)`，**不要**再 `requireByProductId`。

`release`：保持「无 pending RESERVE 则 return」；有则 FOR UPDATE + `applyRelease`。

`adjust`（本 Task **不**校验上下架与 Redis seq，那是 Task 8 CmdExe 的职责）：

```java
    @Transactional
    public ProductStock adjust(long productId, int targetActual, long adjustId) {
        String key = ProductStockIdempotentKeys.ofAdjust(adjustId);
        if (productStockLogRepository.existsByIdempotentKey(key)) {
            return productStockRepository.requireByProductId(productId);
        }
        ProductStock before = productStockRepository.requireByProductIdForUpdate(productId);
        if (targetActual < 0 || targetActual < before.getWithholdStock()) {
            throw new BusinessException(ProductErrorCodeEnum.ADJUST_INVALID_TARGET);
        }
        if (!productStockRepository.adjustActual(productId, targetActual)) {
            throw new BusinessException(ProductErrorCodeEnum.ADJUST_INVALID_TARGET);
        }
        ProductStock after = before.copy().applyAdjust(targetActual);
        after.setStockSeq(nullToZero(before.getStockSeq()) + 1);
        after.assertBalance();
        writeLog(before, after, ProductStockOptTypeEnum.ADJUST, 0L, Math.abs(targetActual - before.getActualStock()),
                "adjust", key);
        return after;
    }
```

`nullToZero`：`v == null ? 0L : v`。

- [x] **Step 4: Run tests to verify they pass**

```powershell
.\mvnw.cmd test "-Dtest=ProductStockDomainServiceTest,ProductStockMapperXmlTest,ProductStockTest,ProductStockLogRepositoryTest"
```

Expected: PASS

- [ ] **Step 5: Commit**（仅当用户要求）

```bash
git add demo2/src/main/java/com/jason/demo/demo2/product/service/core/ProductStockDomainService.java demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure demo2/src/main/resources/mapper/product/ProductStockMapper.xml demo2/src/test/java/com/jason/demo/demo2/product/ProductStockDomainServiceTest.java demo2/src/test/java/com/jason/demo/demo2/product/ProductStockMapperXmlTest.java
git commit -m "fix(product): lock stock row, derive after-image, make confirm idempotent"
```

---

### Task 4: `applyDelta` 乐观投影（无行锁）

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/product/service/common/StockSeqGapException.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/publisher/StockSyncEvent.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/product/ProductStockApplyDeltaTest.java`
- Modify: `ProductStockMapper.java` / `ProductStockMapper.xml` / `ProductStockRepository.java` / `ProductStockDomainService.java` / `ProductStockMapperXmlTest.java`

**Interfaces:**
- Consumes: `ProductStock.reverse`、`StockSyncEvent`
- Produces: `applyReserveDelta` / `applyConfirmDelta` / `applyReleaseDelta`、`applyDelta`、`StockSeqGapException`

Mapper 方法：

```java
    int applyReserveDelta(@Param("productId") long productId, @Param("qty") int qty, @Param("seq") long seq);
    int applyConfirmDelta(@Param("productId") long productId, @Param("qty") int qty, @Param("seq") long seq);
    int applyReleaseDelta(@Param("productId") long productId, @Param("qty") int qty, @Param("seq") long seq);
```

XML（三份结构相同，SET 不同）：

```xml
    <update id="applyReserveDelta">
        UPDATE demo_product_stock
        SET stock = stock - #{qty},
            withhold_stock = withhold_stock + #{qty},
            stock_seq = #{seq},
            updated_at = NOW(3)
        WHERE product_id = #{productId}
          AND stock_seq = #{seq} - 1
    </update>
    <update id="applyConfirmDelta">
        UPDATE demo_product_stock
        SET actual_stock = actual_stock - #{qty},
            withhold_stock = withhold_stock - #{qty},
            sell_stock = sell_stock + #{qty},
            stock_seq = #{seq},
            updated_at = NOW(3)
        WHERE product_id = #{productId}
          AND stock_seq = #{seq} - 1
    </update>
    <update id="applyReleaseDelta">
        UPDATE demo_product_stock
        SET stock = stock + #{qty},
            withhold_stock = withhold_stock - #{qty},
            stock_seq = #{seq},
            updated_at = NOW(3)
        WHERE product_id = #{productId}
          AND stock_seq = #{seq} - 1
    </update>
```

Repository 包装为 `boolean applyXxxDelta(...)`（`> 0`）。

`StockSeqGapException`：

```java
package com.jason.demo.demo2.product.service.common;

public class StockSeqGapException extends RuntimeException {
    public StockSeqGapException(long productId, long messageSeq, Long currentSeq) {
        super("stock seq gap, productId=" + productId + ", messageSeq=" + messageSeq + ", currentSeq=" + currentSeq);
    }
}
```

`StockSyncEvent`：包名 `com.jason.demo.demo2.product.service.infrastructure.publisher`（与 Publisher 同包，**不要**放 `com.jason.demo.demo2.mq`）。Lombok `@Data @NoArgsConstructor @AllArgsConstructor`，字段 `productId, orderId, optType, qty, idempotentKey, seq`。

- [x] **Step 1: Write the failing test**

```java
package com.jason.demo.demo2.product;

import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.product.service.infrastructure.publisher.StockSyncEvent;
import com.jason.demo.demo2.product.service.common.ProductErrorCodeEnum;
import com.jason.demo.demo2.product.service.common.ProductStockIdempotentKeys;
import com.jason.demo.demo2.product.service.common.ProductStockOptTypeEnum;
import com.jason.demo.demo2.product.service.common.StockSeqGapException;
import com.jason.demo.demo2.product.service.core.ProductStockDomainService;
import com.jason.demo.demo2.product.service.core.domain.ProductStock;
import com.jason.demo.demo2.product.service.infrastructure.dao.entity.ProductStockLogDO;
import com.jason.demo.demo2.product.service.infrastructure.repository.ProductStockLogRepository;
import com.jason.demo.demo2.product.service.infrastructure.repository.ProductStockRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductStockApplyDeltaTest {

    @Mock private ProductStockRepository productStockRepository;
    @Mock private ProductStockLogRepository productStockLogRepository;
    @Mock private SnowflakeIdGenerator idGenerator;
    @InjectMocks private ProductStockDomainService service;

    @Test
    void applyDelta_writesLogFromReverseWhenUpdateHits() {
        StockSyncEvent event = event(ProductStockOptTypeEnum.RESERVE, 5, 4L);
        when(productStockLogRepository.existsByIdempotentKey(event.getIdempotentKey())).thenReturn(false);
        when(productStockRepository.applyReserveDelta(9001L, 5, 4L)).thenReturn(true);
        ProductStock after = stock(95, 5, 100);
        after.setStockSeq(4L);
        when(productStockRepository.requireByProductId(9001L)).thenReturn(after);
        when(idGenerator.nextId()).thenReturn(1L);

        service.applyDelta(event);

        ArgumentCaptor<ProductStockLogDO> captor = ArgumentCaptor.forClass(ProductStockLogDO.class);
        verify(productStockLogRepository).insertLog(captor.capture());
        assertEquals(100, captor.getValue().getBeforeStock());
        assertEquals(95, captor.getValue().getAfterStock());
        assertEquals(event.getIdempotentKey(), captor.getValue().getIdempotentKey());
    }

    @Test
    void applyDelta_skipsWhenSeqAlreadyApplied() {
        StockSyncEvent event = event(ProductStockOptTypeEnum.RESERVE, 5, 4L);
        when(productStockLogRepository.existsByIdempotentKey(event.getIdempotentKey())).thenReturn(false);
        when(productStockRepository.applyReserveDelta(9001L, 5, 4L)).thenReturn(false);
        ProductStock current = stock(95, 5, 100);
        current.setStockSeq(4L);
        when(productStockRepository.requireByProductId(9001L)).thenReturn(current);

        service.applyDelta(event);

        verify(productStockLogRepository, never()).insertLog(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void applyDelta_throwsGapWhenSeqBehind() {
        StockSyncEvent event = event(ProductStockOptTypeEnum.CONFIRM, 5, 4L);
        when(productStockLogRepository.existsByIdempotentKey(event.getIdempotentKey())).thenReturn(false);
        when(productStockLogRepository.existsOpt(100L, 9001L, ProductStockOptTypeEnum.RELEASE)).thenReturn(false);
        when(productStockRepository.applyConfirmDelta(9001L, 5, 4L)).thenReturn(false);
        ProductStock current = stock(100, 0, 100);
        current.setStockSeq(2L);
        when(productStockRepository.requireByProductId(9001L)).thenReturn(current);

        assertThrows(StockSeqGapException.class, () -> service.applyDelta(event));
    }

    @Test
    void applyDelta_confirmAfterRelease_conflicts() {
        StockSyncEvent event = event(ProductStockOptTypeEnum.CONFIRM, 2, 3L);
        when(productStockLogRepository.existsByIdempotentKey(event.getIdempotentKey())).thenReturn(false);
        when(productStockLogRepository.existsOpt(100L, 9001L, ProductStockOptTypeEnum.RELEASE)).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.applyDelta(event));
        assertEquals(ProductErrorCodeEnum.STOCK_CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void applyDelta_idempotentKeyExists_skips() {
        StockSyncEvent event = event(ProductStockOptTypeEnum.RESERVE, 5, 4L);
        when(productStockLogRepository.existsByIdempotentKey(event.getIdempotentKey())).thenReturn(true);

        service.applyDelta(event);

        verify(productStockRepository, never()).applyReserveDelta(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong());
    }

    private static StockSyncEvent event(ProductStockOptTypeEnum op, int qty, long seq) {
        return new StockSyncEvent(9001L, 100L, op.name(), qty,
                ProductStockIdempotentKeys.of(100L, 9001L, op), seq);
    }

    private static ProductStock stock(int available, int withhold, int actual) {
        ProductStock stock = new ProductStock();
        stock.setStockId(9101L);
        stock.setProductId(9001L);
        stock.setStock(available);
        stock.setWithholdStock(withhold);
        stock.setActualStock(actual);
        stock.setSellStock(10);
        return stock;
    }
}
```

- [x] **Step 2: Run test to verify it fails**

```powershell
.\mvnw.cmd test "-Dtest=ProductStockApplyDeltaTest"
```

Expected: FAIL（`applyDelta` 不存在）

- [x] **Step 3: Implement `applyDelta`**

逻辑（**不要** `FOR UPDATE`）：

1. `existsByIdempotentKey` → return  
2. `CONFIRM` 且已有 `RELEASE` → `40005`；`RELEASE` 且已有 `CONFIRM` → `40005`  
3. `RELEASE` 且无 `RESERVE` 且无 `RELEASE` 流水 → return（从未预占）  
4. 按 `optType` 调对应 `applyXxxDelta`  
5. 命中 1 行：`after = requireByProductId()`（普通 SELECT），`before = reverse(after, op, qty)`，`assertBalance`，`writeLog`  
6. 0 行：`current = requireByProductId()`；若 `stockSeq >= seq` → return；否则 `throw new StockSeqGapException(...)`  
7. **禁止**在成功后写 Redis

`ProductStockMapperXmlTest` 断言三个 `apply*Delta` statement 已注册。

- [x] **Step 4: Run tests to verify they pass**

```powershell
.\mvnw.cmd test "-Dtest=ProductStockApplyDeltaTest,ProductStockDomainServiceTest,ProductStockMapperXmlTest"
```

Expected: PASS

- [ ] **Step 5: Commit**（仅当用户要求）

```bash
git add demo2/src/main/java/com/jason/demo/demo2/product demo2/src/main/resources/mapper/product/ProductStockMapper.xml demo2/src/test/java/com/jason/demo/demo2/product/ProductStockApplyDeltaTest.java demo2/src/test/java/com/jason/demo/demo2/product/ProductStockMapperXmlTest.java
git commit -m "feat(product): project Redis stock deltas with optimistic stock_seq"
```

---

### Task 5: Lua 脚本 + `RedisStockOps`

**Files:**
- Create: 四个 `demo2/src/main/resources/lua/stock-*.lua`
- Create: `RedisStockKeys.java`、`RedisStockResult.java`、`RedisStockOps.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/product/RedisStockLuaScriptTest.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/product/RedisStockOpsTest.java`

**Interfaces:**
- Consumes: Spec §5 Lua 语义
- Produces: `RedisStockOps.reserve/confirm/release/adjustHash/hsetnxHash/getAvail/getSeq`

- [x] **Step 1: Write the failing tests**

`RedisStockLuaScriptTest`：从 classpath 读四个 lua；`stock-confirm.lua` / `stock-release.lua` **不得**包含 `GETDEL`；必须包含 `redis.call('GET'` 与 `redis.call('DEL'`；`stock-reserve.lua` 必须包含 `SETNX`、`HINCRBY`、`XADD`；`stock-adjust.lua` 必须包含 `HSET` 且 **不得**包含 `XADD`。

`RedisStockOpsTest`：mock `StringRedisTemplate.execute` 返回 `List.of(-1L, "UNLOADED")` / `List.of(1L, "OK")` / `List.of(0L, "INSUFFICIENT")`，断言 `RedisStockOps.reserve` 映射 `code`/`reason`。`hsetnxHash` 必须用 `putIfAbsent`（或 Lua `HSETNX`），禁止 `HSET` 覆盖已有 Hash：

```java
Boolean created = redis.opsForHash().putIfAbsent(RedisStockKeys.hash(productId), "avail", String.valueOf(avail));
if (Boolean.TRUE.equals(created)) {
    redis.opsForHash().put(RedisStockKeys.hash(productId), "seq", String.valueOf(seq));
    return true;
}
return false;
```

测：`putIfAbsent` true → 方法 true 且写入 seq；false → 方法 false 且 **verify 不再 put seq**。

`adjustHash` mock `execute` 返回 `List.of(1L, "OK")`，断言调用的 script resource 为 `lua/stock-adjust.lua`（可用 `ArgumentCaptor` 抓 `DefaultRedisScript` 或把 script 字段包可见后 assert location）。更简单：测 `adjustHash` 在 mock execute 返回 OK 时 `code==1`。

- [x] **Step 2: Run tests to verify they fail**

```powershell
.\mvnw.cmd test "-Dtest=RedisStockLuaScriptTest,RedisStockOpsTest"
```

Expected: FAIL（脚本或类不存在）

- [x] **Step 3: Write Lua + Java**

`RedisStockKeys`：

```java
package com.jason.demo.demo2.product.service.infrastructure.redis;

public final class RedisStockKeys {
    public static final String OUTBOX = "demo2:stock:outbox";

    private RedisStockKeys() {
    }

    public static String hash(long productId) {
        return "demo2:stock:" + productId;
    }

    public static String ticket(long orderId, long productId) {
        return "demo2:stock:reserve:" + orderId + ":" + productId;
    }
}
```

`RedisStockResult`：`public record RedisStockResult(int code, String reason) {}` 放 `product.service.common`。

四个 Lua **逐字采用 spec §5.1–5.4**（含注释）。确认脚本使用 `GET`+`DEL` 而非 `GETDEL`。

`RedisStockOps`：四个 `DefaultRedisScript<List>`，`setLocation(new ClassPathResource("lua/stock-xxx.lua"))`，`setResultType(List.class)`。

```java
@SuppressWarnings("unchecked")
private RedisStockResult eval(DefaultRedisScript<List> script, List<String> keys, String... argv) {
    List<Object> raw = stringRedisTemplate.execute(script, keys, (Object[]) argv);
    if (raw == null || raw.size() < 2) {
        throw new IllegalStateException("unexpected lua result: " + raw);
    }
    int code = Integer.parseInt(String.valueOf(raw.get(0)));
    return new RedisStockResult(code, String.valueOf(raw.get(1)));
}
```

KEYS：RESERVE/CONFIRM/RELEASE 为 `hash(productId)`, `ticket(orderId, productId)`, `OUTBOX`；ADJUST 只有 `hash(productId)`，ARGV 为新可售与新 seq。

`getAvail` / `getSeq`：`opsForHash().get(hash, "avail"|"seq")`，blank → empty。

`adjustHash` 执行 `lua/stock-adjust.lua`，不要在 Java 里对已有 Hash 做裸 `HSET`（上架回灌走 `hsetnxHash`）。

- [x] **Step 4: Run tests to verify they pass**

```powershell
.\mvnw.cmd test "-Dtest=RedisStockLuaScriptTest,RedisStockOpsTest"
```

Expected: PASS

- [ ] **Step 5: Commit**（仅当用户要求）

```bash
git add demo2/src/main/resources/lua demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/redis demo2/src/main/java/com/jason/demo/demo2/product/service/common/RedisStockResult.java demo2/src/test/java/com/jason/demo/demo2/product/RedisStockLuaScriptTest.java demo2/src/test/java/com/jason/demo/demo2/product/RedisStockOpsTest.java
git commit -m "feat(product): add Redis stock Lua scripts and RedisStockOps"
```

---

### Task 6: `ProductStockHotService` + 开关

**Files:**
- Create: `ProductStockProperties.java`、`ProductStockConfiguration.java`
- Create: `ProductStockHotService.java`、`ProductStockHotServiceTest.java`
- Modify: `demo2/src/main/resources/application.properties`
- Modify: `ProductDomainService.java`（`requireOnShelf` 已存在，热路径 RESERVE 前调用它）

**Interfaces:**
- Consumes: `RedisStockOps`、`ProductStockDomainService`、`ProductStockLogRepository`、`ProductDomainService.requireOnShelf`
- Produces: 热/冷切换的 `reserve/confirm/release`；`overlayAvail`；`UNLOADED` → `40010`；CONFIRM `NOT_FOUND` / RELEASE `NO_TICKET` 按 spec 查 MySQL

配置：

```properties
app.product.stock.redis-hot-enabled=true
app.product.stock.reconcile-interval-ms=60000
app.product.stock.reconcile-lag-alarm-ms=300000
app.product.stock.outbox-block-ms=2000
app.product.stock.outbox-batch-size=16
app.product.stock.outbox-group=demo2-stock-relay
app.product.stock.outbox-consumer=relay
```

`ProductStockProperties`：`@ConfigurationProperties(prefix = "app.product.stock")`，字段与上表对应（`redisHotEnabled` 默认 `true`）。

`ProductStockConfiguration`：`@Configuration` + `@EnableConfigurationProperties(ProductStockProperties.class)`。

- [x] **Step 1: Write the failing test**

`ProductStockHotServiceTest`（Mockito）：

1. `redisHotEnabled=false` → `reserve` 只调 `domainService.reserve`，never `redisStockOps`  
2. hot + `requireOnShelf` ok + Lua `INSUFFICIENT` → `40003`  
3. hot + Lua `UNLOADED` → `40010`，**never** `hsetnxHash` / `adjustHash`  
4. CONFIRM `NOT_FOUND` + MySQL 已有 CONFIRM → 成功（never throw）  
5. CONFIRM `NOT_FOUND` + 已有 RELEASE → `40005`  
6. CONFIRM `NOT_FOUND` + 仅 RESERVE → `40010`  
7. CONFIRM `NOT_FOUND` + 都没有 → `40004`  
8. RELEASE `NO_TICKET` + 已有 RELEASE → 成功  
9. RELEASE `NO_TICKET` + 已有 CONFIRM → `40005`  
10. RELEASE `NO_TICKET` + 仅 RESERVE → `40010`  
11. RELEASE `NO_TICKET` + 都没有 → 成功  

构造器注入 `ProductStockProperties`（不要 `@InjectMocks` 字段注入 properties：手动 `new ProductStockHotService(...)`）。

- [x] **Step 2: Run test to verify it fails**

```powershell
.\mvnw.cmd test "-Dtest=ProductStockHotServiceTest"
```

Expected: FAIL

- [x] **Step 3: Implement `ProductStockHotService`**

`reserve`：`qty<=0` → `BAD_REQUEST`；`productDomainService.requireOnShelf(productId)`；若 `!redisHotEnabled` → `domainService.reserve`；否则 `redisStockOps.reserve(...)`：

| code/reason | 行为 |
|-------------|------|
| 1 OK / 2 IDEMPOTENT | return |
| -1 UNLOADED | `40010`（**不要**灌 MySQL stock） |
| 0 INSUFFICIENT | `40003` |
| 0 CONFLICT | `40005` |

`confirm` / `release` 冷路径直接委托 DomainService。热路径 Lua 后按 spec 表查 `existsOpt`。

幂等键：`ProductStockIdempotentKeys.of(orderId, productId, RESERVE/CONFIRM/RELEASE)`。

`overlayAvail(productId)`：`!redisHotEnabled` → `Optional.empty()`；否则 `getAvail` → `Optional.of(Math.toIntExact(v))`，Hash 不存在 → empty。Task 9 的列表/详情会调用它。

- [x] **Step 4: Run tests to verify they pass**

```powershell
.\mvnw.cmd test "-Dtest=ProductStockHotServiceTest,ProductStockDomainServiceTest"
```

Expected: PASS

- [ ] **Step 5: Commit**（仅当用户要求）

```bash
git add demo2/src/main/java/com/jason/demo/demo2/product/config demo2/src/main/java/com/jason/demo/demo2/product/service/core/ProductStockHotService.java demo2/src/main/resources/application.properties demo2/src/test/java/com/jason/demo/demo2/product/ProductStockHotServiceTest.java
git commit -m "feat(product): route hot-path stock ops through Redis with MySQL fallback"
```

---

### Task 7: Relay + Publisher + Listener

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/framework/rocketmq/producer/BaseEventPublisher.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/publisher/StockSyncEventPublisher.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/product/app/listener/StockSyncMqListener.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/product/app/listener/RedisStockOutboxRelay.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/product/RedisStockOutboxRelayTest.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/product/StockSyncMqListenerTest.java`
- Modify: `application.properties`（producer/consumer）

**Interfaces:**
- Consumes: `StockSyncEvent`、`applyDelta`、`sendImmediate`
- Produces: Stream → MQ → MySQL；缺口 `RECONSUME_LATER`；发 MQ 失败不 XACK

`application.properties` 追加（namesrv 与现有一致 `127.0.0.1:9876`）：

```properties
rocketmq.producers.stockSyncProducer.enabled=true
rocketmq.producers.stockSyncProducer.namesrvAddr=127.0.0.1:9876
rocketmq.producers.stockSyncProducer.producerGroup=demo-stock-sync-producer-group
rocketmq.producers.stockSyncProducer.topic=DEMO_STOCK_TOPIC

rocketmq.consumers.stockSync.enabled=true
rocketmq.consumers.stockSync.namesrvAddr=127.0.0.1:9876
rocketmq.consumers.stockSync.topic=DEMO_STOCK_TOPIC
rocketmq.consumers.stockSync.tags=*
rocketmq.consumers.stockSync.consumerGroup=demo-stock-sync-group
rocketmq.consumers.stockSync.listenerBeanName=stockSyncMqListener
```

`BaseEventPublisher` 新增（`doSend` 旁，**不要**走 `afterCommit`）：

```java
    protected void sendImmediate(Object messageBodyObj, String... keys) {
        Message message = buildMessage(messageBodyObj, keys);
        Exception last = null;
        for (int i = 0; i < maxTryTimes; i++) {
            try {
                SendResult sendResult = producer.send(message);
                log.info("immediate send success, attempt:{}, result:{}", i + 1, sendResult);
                return;
            } catch (Exception e) {
                last = e;
                log.error("immediate send error, attempt:{}, message:{}", i + 1, messageBodyObj, e);
                if (i < maxTryTimes - 1) {
                    sleepQuietly(100L * (i + 1));
                }
            }
        }
        throw new IllegalStateException("rocketmq immediate send failed after retries", last);
    }
```

`StockSyncEventPublisher`（`com.jason.demo.demo2.product.service.infrastructure.publisher`）：

```java
package com.jason.demo.demo2.product.service.infrastructure.publisher;

@Component
public class StockSyncEventPublisher extends BaseEventPublisher {
    public static final String PRODUCER_ID = "stockSyncProducer";
    public StockSyncEventPublisher() { super(PRODUCER_ID); }
    public void sendNow(StockSyncEvent event) {
        sendImmediate(event, String.valueOf(event.getProductId()), event.getIdempotentKey());
    }
}
```

`StockSyncMqListener`：包名 `com.jason.demo.demo2.product.app.listener`，`@Component("stockSyncMqListener")`，extends `RocketMessageConcurrentlyListener<StockSyncEvent>`。

```java
package com.jason.demo.demo2.product.app.listener;

@Slf4j
@Component("stockSyncMqListener")
public class StockSyncMqListener extends RocketMessageConcurrentlyListener<StockSyncEvent> {

    private final ProductStockDomainService productStockDomainService;

    public StockSyncMqListener(JsonMapper jsonMapper, ProductStockDomainService productStockDomainService) {
        super(jsonMapper);
        this.productStockDomainService = productStockDomainService;
    }

    @Override
    protected ConsumeConcurrentlyStatus handleMessage(StockSyncEvent payload, String message, MessageExt messageExt) {
        try {
            productStockDomainService.applyDelta(payload);
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
}
```

`RedisStockOutboxRelay`：包名 `com.jason.demo.demo2.product.app.listener`（Redis Stream `XREADGROUP` 消费者，**不要**放 `app.job`）。`SmartLifecycle` 后台循环（虚拟线程或守护线程）。启动时：

```java
try {
    stringRedisTemplate.opsForStream().createGroup(RedisStockKeys.OUTBOX, ReadOffset.from("0-0"), properties.getOutboxGroup());
} catch (Exception ignored) {
    // BUSYGROUP：组已存在
}
```

循环：`XREADGROUP`（`Consumer.from(group, consumer)`，`StreamReadOptions.empty().count(batch).block(Duration.ofMillis(blockMs))`，`StreamOffset.create(OUTBOX, ReadOffset.lastConsumed())`）。

每条 Record：把 field map 拼成 `StockSyncEvent`（`productId/orderId/qty/seq` 用 `Long.parseLong` / `Integer.parseInt`），`publisher.sendNow(event)` **成功后** `opsForStream().acknowledge(OUTBOX, group, record.getId())`。`sendNow` 抛错 → **不 ACK**。

每 10 次循环调用一次 `claimIdlePending`：`opsForStream().claim(OUTBOX, Consumer.from(group, consumer), Duration.ofSeconds(30), ...idle ids via pending())`；对 claim 到的每条同样 `sendNow` + 成功才 ACK。实现可先 `pending(PendingMessagesSummary)` 再 `claim`；单测覆盖「claim 后 send 失败不 ACK」。

Relay **禁止**调用 `applyDelta` 或任何 Mapper。

`redis-hot-enabled=false` 时 Relay 不启动（`isAutoStartup()` 返回 `properties.isRedisHotEnabled()`）。

- [x] **Step 1: Write the failing tests**

`StockSyncMqListenerTest`：mock DomainService；`applyDelta` 正常 → `CONSUME_SUCCESS`；抛 `StockSeqGapException` → `RECONSUME_LATER`；抛 `BusinessException(STOCK_CONFLICT)` → `CONSUME_SUCCESS`。

`RedisStockOutboxRelayTest`：抽一个包可见方法 `onRecord(Map<String,String> fields, String recordId)`（或 package-private `dispatch`）：mock publisher `sendNow` 成功 → 调 `acknowledge`；`sendNow` throw → never acknowledge。

- [x] **Step 2: Run tests to verify they fail**

```powershell
.\mvnw.cmd test "-Dtest=StockSyncMqListenerTest,RedisStockOutboxRelayTest"
```

Expected: FAIL

- [x] **Step 3: Implement publisher / listener / relay**

按上面完整类实现。Relay 解析 Stream 字段名必须与 Lua XADD 一致：`productId, orderId, optType, qty, idempotentKey, seq`。

- [x] **Step 4: Run tests to verify they pass**

```powershell
.\mvnw.cmd test "-Dtest=StockSyncMqListenerTest,RedisStockOutboxRelayTest,ProductStockApplyDeltaTest"
```

Expected: PASS

- [ ] **Step 5: Commit**（仅当用户要求）

```bash
git add demo2/src/main/java/com/jason/demo/demo2/framework/rocketmq/producer/BaseEventPublisher.java demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/publisher demo2/src/main/java/com/jason/demo/demo2/product/app/listener demo2/src/main/resources/application.properties demo2/src/test/java/com/jason/demo/demo2/product/StockSyncMqListenerTest.java demo2/src/test/java/com/jason/demo/demo2/product/RedisStockOutboxRelayTest.java
git commit -m "feat(product): relay Redis stock outbox to RocketMQ then project MySQL"
```

---

### Task 8: Demo HTTP 下架 / 上架 / 调库存

**Files:**
- Create: `AdjustStockReqVO`、`ProductShelfResVO`、`AdjustStockResVO`、三个 `*CmdExe`
- Create: `ProductShelfCmdExeTest.java`
- Modify: `ProductController.java`、`ProductDomainService.java`、`ProductRepository.java`

**Interfaces:**
- Consumes: `ProductStockHotService` / `RedisStockOps` / `ProductStockDomainService.adjust`
- Produces: `POST /demo/products/offShelf|onShelf|adjustStock`

`ProductRepository` 增加：

```java
    public Product requireByProductId(long productId) {
        return findByProductId(productId)
                .orElseThrow(() -> new BusinessException(ProductErrorCodeEnum.PRODUCT_NOT_FOUND));
    }

    public void updateStatus(long productId, ProductStatusEnum status) {
        ProductDO patch = new ProductDO();
        patch.setStatus(status.name());
        patch.setUpdatedAt(LocalDateTime.now());
        productMapper.update(patch, new LambdaQueryWrapper<ProductDO>()
                .eq(ProductDO::getProductId, productId));
    }
```

`ProductDomainService` 增加 `offShelf` / `onShelf`（只改 status；上架前由 CmdExe 负责 HSETNX）：

```java
    public Product offShelf(long productId) {
        Product product = productRepository.requireByProductId(productId);
        productRepository.updateStatus(productId, ProductStatusEnum.OFF_SHELF);
        product.setStatus(ProductStatusEnum.OFF_SHELF.name());
        return product;
    }

    public Product onShelf(long productId) {
        Product product = productRepository.requireByProductId(productId);
        productRepository.updateStatus(productId, ProductStatusEnum.ON_SHELF);
        product.setStatus(ProductStatusEnum.ON_SHELF.name());
        return product;
    }
```

`offShelf` **禁止**改 Redis Hash。

`ProductOnShelfCmdExe`：

1. `requireByProductId` + `requireByProductId` 库存  
2. `hsetnxHash(productId, mysql.stock, mysql.stockSeq==null?0:stockSeq)` —— 已存在则 **不要**再 `adjustHash`  
3. `productDomainService.onShelf(productId)`

`ProductAdjustStockCmdExe`：

1. 商品必须 `OFF_SHELF`，否则 `40008`  
2. 若 Redis Hash 存在且 `getSeq != mysql.stockSeq` → `40010`（Hash 不存在则跳过 seq 校验）  
3. `domainService.adjust(productId, targetActual, adjustId)`，`adjustId` 用 `SnowflakeIdGenerator.nextId()`  
4. 成功后 `redisStockOps.adjustHash(productId, after.getStock(), after.getStockSeq())`

`AdjustStockReqVO`：`productId` `@NotNull @Min(1)`；`targetActual` `@NotNull @Min(0)`；均 `@Schema`。下架/上架复用 `GetProductReqVO`（同字段），Controller `@Valid @RequestBody GetProductReqVO`。

`ProductController` 三个方法均 `@Operation`，无 `@LoginRequired`。

- [x] **Step 1: Write the failing test**

`ProductShelfCmdExeTest`：

1. `onShelf`：mock `hsetnxHash` true → 再 `productDomainService.onShelf`；`hsetnxHash` false → **never** `adjustHash`，仍 `onShelf`  
2. `offShelf`：never 调 `hsetnxHash` / `adjustHash`  
3. `adjust` 商品 `ON_SHELF` → `40008`  
4. `adjust` 下架但 `redis.seq=5`、`mysql.stockSeq=3` → `40010`，never `domain.adjust`  
5. `adjust` 成功 → verify `adjustHash(id, after.getStock(), after.getStockSeq())`

- [x] **Step 2: Run test to verify it fails**

```powershell
.\mvnw.cmd test "-Dtest=ProductShelfCmdExeTest"
```

Expected: FAIL

- [x] **Step 3: Implement CmdExe + Controller**

ResVO：`ProductShelfResVO { Long productId; String status; }`；`AdjustStockResVO { Long productId; Integer actualStock; Integer stock; Integer withholdStock; Long stockSeq; }`。CmdExe 内手写 set，不必新增 VoConvert。

- [x] **Step 4: Run tests to verify they pass**

```powershell
.\mvnw.cmd test "-Dtest=ProductShelfCmdExeTest,ProductCmdExeTest,ProductStockHotServiceTest"
```

Expected: PASS

- [ ] **Step 5: Commit**（仅当用户要求）

```bash
git add demo2/src/main/java/com/jason/demo/demo2/product demo2/src/test/java/com/jason/demo/demo2/product/ProductShelfCmdExeTest.java
git commit -m "feat(product): add demo offShelf, onShelf, and adjustStock APIs"
```

---

### Task 9: 对账 + C 端可售读 Redis + 收尾

**Files:**
- Create: `StockReconcileService.java`、`StockReconcileJob.java`、`StockReconcileServiceTest.java`
- Modify: `ProductListCmdExe.java`、`ProductGetCmdExe.java`、`ProductCmdExeTest.java`
- Modify: `demo2/CLAUDE.md`
- Modify: spec 状态 → 已实现

**Interfaces:**
- Consumes: `RedisStockOps.getAvail/getSeq`、MySQL `ProductStock`
- Produces: seq 分流对账；列表/详情 `availableStock` 热路径读 Redis；`sellStock` 仍 MySQL

`StockReconcileService.reconcileOne(productId)` 返回 `enum ReconcileKindEnum { OK, IN_FLIGHT, IN_FLIGHT_SLOW, AVAIL_MISMATCH, MYSQL_AHEAD, REDIS_MISSING }`：

```text
redis.seq 缺失且 mysql 有行 → REDIS_MISSING（info，不与 avail 对打）
redis.seq  > mysql.stock_seq → IN_FLIGHT；若该 productId 连续落后超过 reconcileLagAlarmMs → IN_FLIGHT_SLOW
redis.seq == mysql.stock_seq → avail 必须 == mysql.stock，否则 AVAIL_MISMATCH
redis.seq  < mysql.stock_seq → MYSQL_AHEAD
```

用 `ConcurrentHashMap<Long, Instant> lagStartedAt` 记「seq 落后开始时间」；seq 对齐则 remove。

`IN_FLIGHT`：`log.debug`；`IN_FLIGHT_SLOW` / `AVAIL_MISMATCH` / `MYSQL_AHEAD`：`log.warn`。

`StockReconcileJob`：`@Scheduled(fixedDelayString = "${app.product.stock.reconcile-interval-ms:60000}")`，扫描全部 `productStockRepository` 行（演示数据量小）。`redis-hot-enabled=false` 时直接 return。

C 端：`ProductListCmdExe` / `ProductGetCmdExe` 在 VoConvert 之后，若 `overlayAvail(productId)` 有值则 `setAvailableStock`。**不要**改 `sellStock`。

`ProductCmdExeTest`：给两个 CmdExe 增加 `ProductStockHotService` mock，`overlayAvail` → `Optional.empty()`，原断言仍过；另增 `listProducts_overlaysRedisAvail`：convert 后 item.availableStock=100，overlay 返回 77，execute 后为 77。

`CLAUDE.md` 在商品相关处补：热路径 Redis `avail+seq`；投影走 MQ `applyDelta`；自定义 SQL 仍在 XML；发 MQ 放 `product.service.infrastructure.publisher`；RocketMQ / Redis Stream 消费者放 `product.app.listener`；定时对账放 `product.app.job`。

- [x] **Step 1: Write the failing tests**

`StockReconcileServiceTest`：

1. seq redis=5 mysql=3 → `IN_FLIGHT`（即使 avail 与 mysql.stock 不同也 **不是** `AVAIL_MISMATCH`）  
2. seq 相等但 avail≠stock → `AVAIL_MISMATCH`  
3. seq redis=2 mysql=4 → `MYSQL_AHEAD`  
4. seq 相等且 avail==stock → `OK`  
5. 同一 product 第一次 IN_FLIGHT 后把 `lagStartedAt` 设为 6 分钟前（测试用 package 可见 setter 或构造注入 `Clock`），再 reconcile → `IN_FLIGHT_SLOW`

注入 `Clock`：`Clock.systemDefaultZone()` 生产；测试 `Clock.fixed`。

- [x] **Step 2: Run tests to verify they fail**

```powershell
.\mvnw.cmd test "-Dtest=StockReconcileServiceTest,ProductCmdExeTest"
```

Expected: `StockReconcileServiceTest` FAIL；`ProductCmdExeTest` 可能因构造器改签名编译失败

- [x] **Step 3: Implement reconcile + overlay + docs**

`ProductListCmdExe` 构造器增加 `ProductStockHotService`。列表循环：

```java
ProductListItemResVO item = productVoConvert.toListItem(row);
hotService.overlayAvail(row.getProduct().getProductId())
        .ifPresent(item::setAvailableStock);
```

列表/详情只调用 Task 6 已实现的 `overlayAvail`，本 Task 不要再改 `ProductStockHotService` 语义。

实现完成后把 spec 文首 **状态** 改为 `已实现`。

- [x] **Step 4: Run the full product + new tests**

```powershell
.\mvnw.cmd test "-Dtest=ProductStockIdempotentKeysTest,ProductStockTest,ProductStockDomainServiceTest,ProductStockApplyDeltaTest,ProductStockMapperXmlTest,ProductStockLogRepositoryTest,RedisStockLuaScriptTest,RedisStockOpsTest,ProductStockHotServiceTest,StockSyncMqListenerTest,RedisStockOutboxRelayTest,ProductShelfCmdExeTest,StockReconcileServiceTest,ProductCmdExeTest"
```

Expected: 全部 PASS

- [ ] **Step 5: Commit**（仅当用户要求）

```bash
git add demo2/src/main/java/com/jason/demo/demo2/product demo2/src/test/java/com/jason/demo/demo2/product demo2/CLAUDE.md demo2/docs/superpowers/specs/2026-08-27-redis-stock-consistency-design.md
git commit -m "feat(product): reconcile stock by seq and overlay Redis avail on C-end reads"
```

---

## 手工验证（实现全部 Task 后）

在 `demo2/` 启动应用，先对已有库执行 `product-stock-seq-schema.sql`。Redis / RocketMQ 需可用。

```text
# 下架 → 调库存 → 上架（第一次应 HSETNX）
POST /demo/products/offShelf     {"productId":"2085550503315509001"}
POST /demo/products/adjustStock  {"productId":"2085550503315509001","targetActual":80}
POST /demo/products/onShelf      {"productId":"2085550503315509001"}

# 上架中 ADJUST → 40008
POST /demo/products/adjustStock  {"productId":"2085550503315509001","targetActual":80}

# 列表可售应接近 Redis avail（热路径开启时）
POST /demo/products/listProducts
```

热路径 `reserve/confirm/release` 本阶段无 HTTP，用单测覆盖；订单接入留到后续。

---

## Spec 覆盖对照

| Spec | Task |
|------|------|
| DDL `stock_seq` / `idempotent_key` | 1 |
| 内存 after + reverse，不再二次无锁 SELECT after | 2–3 |
| confirm 幂等；直写 `stock_seq+=1`；ADJUST 行锁 | 3 |
| `applyDelta` 无 FOR UPDATE，缺口重试 | 4 |
| Redis 仅 avail+seq；Lua §5；GET+DEL | 5 |
| 开关；UNLOADED 不灌库；NOT_FOUND/NO_TICKET 见表 | 6 |
| Relay 只发 MQ；成功才 XACK；消费者 RECONSUME_LATER | 7 |
| Demo 上下架/调库存；40008/09/10；上架不覆盖 Hash | 8 |
| 对账先比 seq；C 端 avail 读 Redis | 9 |
| 不改订单表 / 不把四字段镜像进 Redis | 全局约束 |

---

## 执行说明

实现完 Task 9 且测试全绿后，本计划即完成。订单 `orderPlace`/`pay`/`cancel` 改为调 `ProductStockHotService` **不在本计划内**（spec §8）。
