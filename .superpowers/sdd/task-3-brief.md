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

- [ ] **Step 1: Write the failing tests（改 `ProductStockDomainServiceTest`）**

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

- [ ] **Step 2: Run tests to verify they fail**

```powershell
.\mvnw.cmd test "-Dtest=ProductStockDomainServiceTest,ProductStockMapperXmlTest"
```

Expected: FAIL（`requireByProductIdForUpdate` / `adjust` 不存在，或旧测试仍按二次 SELECT stub）

- [ ] **Step 3: Implement DomainService**

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

- [ ] **Step 4: Run tests to verify they pass**

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

