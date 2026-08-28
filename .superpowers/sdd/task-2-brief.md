### Task 2: `ProductStock` 内存推演与 reverse

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/service/core/domain/ProductStock.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/product/ProductStockTest.java`

**Interfaces:**
- Consumes: `ProductStockDO.stockSeq`
- Produces: `copy` / `applyReserve` / `applyConfirm` / `applyRelease` / `applyAdjust` / `reverse`

- [ ] **Step 1: Write the failing test**

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

- [ ] **Step 2: Run test to verify it fails**

```powershell
.\mvnw.cmd test "-Dtest=ProductStockTest"
```

Expected: FAIL（`copy`/`applyReserve` 等方法不存在）

- [ ] **Step 3: Write minimal implementation**

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

- [ ] **Step 4: Run test to verify it passes**

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

