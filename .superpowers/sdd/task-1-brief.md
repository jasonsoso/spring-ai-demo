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

- [ ] **Step 1: Write the failing test**

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

- [ ] **Step 2: Run test to verify it fails**

Run（在 `demo2/`）:

```powershell
.\mvnw.cmd test "-Dtest=ProductStockIdempotentKeysTest"
```

Expected: FAIL（`ProductStockIdempotentKeys` 不存在 和/或 枚举常量不存在）

- [ ] **Step 3: Write minimal implementation**

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

- [ ] **Step 4: Run test to verify it passes**

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

