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

- [ ] **Step 1: Write the failing test**

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

- [ ] **Step 2: Run test to verify it fails**

```powershell
.\mvnw.cmd test "-Dtest=ProductStockApplyDeltaTest"
```

Expected: FAIL（`applyDelta` 不存在）

- [ ] **Step 3: Implement `applyDelta`**

逻辑（**不要** `FOR UPDATE`）：

1. `existsByIdempotentKey` → return  
2. `CONFIRM` 且已有 `RELEASE` → `40005`；`RELEASE` 且已有 `CONFIRM` → `40005`  
3. `RELEASE` 且无 `RESERVE` 且无 `RELEASE` 流水 → return（从未预占）  
4. 按 `optType` 调对应 `applyXxxDelta`  
5. 命中 1 行：`after = requireByProductId()`（普通 SELECT），`before = reverse(after, op, qty)`，`assertBalance`，`writeLog`  
6. 0 行：`current = requireByProductId()`；若 `stockSeq >= seq` → return；否则 `throw new StockSeqGapException(...)`  
7. **禁止**在成功后写 Redis

`ProductStockMapperXmlTest` 断言三个 `apply*Delta` statement 已注册。

- [ ] **Step 4: Run tests to verify they pass**

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

