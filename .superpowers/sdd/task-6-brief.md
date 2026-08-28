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

- [ ] **Step 1: Write the failing test**

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

- [ ] **Step 2: Run test to verify it fails**

```powershell
.\mvnw.cmd test "-Dtest=ProductStockHotServiceTest"
```

Expected: FAIL

- [ ] **Step 3: Implement `ProductStockHotService`**

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

- [ ] **Step 4: Run tests to verify they pass**

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

