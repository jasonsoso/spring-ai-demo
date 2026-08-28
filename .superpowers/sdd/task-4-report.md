# Task 4 Report: applyDelta 乐观投影（无行锁）

**Date:** 2026-08-27  
**Branch:** feat/redis-stock-consistency  
**Status:** DONE_WITH_CONCERNS  
**Commits:** none（按指令未提交）

## Summary

热路径投影 `ProductStockDomainService.applyDelta(StockSyncEvent)`：幂等键命中直接 return；CONFIRM/RELEASE 互斥走 `existsOpt` → `40005`；从未预占的 RELEASE 直接 return。按 `optType` 调用 `apply*Delta`（`UPDATE … SET stock_seq = #{seq} WHERE stock_seq = #{seq} - 1`），**不用** `SELECT FOR UPDATE`。命中后普通 `requireByProductId()` 读 after，`ProductStock.reverse` 推 before，`assertBalance` + `writeLog`。0 行则 `stockSeq >= seq` 跳过，否则 `StockSeqGapException`。成功后不写 Redis。

`StockSyncEvent` 放在 `com.jason.demo.demo2.product.service.infrastructure.publisher`，未放 `com.jason.demo.demo2.mq`。未改 `DelayTaskMqListener`。自定义 SQL 仅在 `ProductStockMapper.xml`。

## Changed Files

| Action | Path |
|--------|------|
| Create | `demo2/src/main/java/com/jason/demo/demo2/product/service/common/StockSeqGapException.java` |
| Create | `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/publisher/StockSyncEvent.java` |
| Create | `demo2/src/test/java/com/jason/demo/demo2/product/ProductStockApplyDeltaTest.java` |
| Modify | `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/dao/mapper/ProductStockMapper.java` |
| Modify | `demo2/src/main/resources/mapper/product/ProductStockMapper.xml` |
| Modify | `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/repository/ProductStockRepository.java` |
| Modify | `demo2/src/main/java/com/jason/demo/demo2/product/service/core/ProductStockDomainService.java` |
| Modify | `demo2/src/test/java/com/jason/demo/demo2/product/ProductStockMapperXmlTest.java` |

未改 `DelayTaskMqListener`。Mapper 接口无 `@Update`。

## TDD Evidence

### RED（先写测试，确认失败）

**命令（`demo2/`）：**

```powershell
.\mvnw.cmd test "-Dtest=ProductStockApplyDeltaTest,ProductStockMapperXmlTest"
```

**结果：** BUILD FAILURE（exit code 1）  
失败原因符合预期（类型/方法尚不存在，而非断言写错）：

- 包 `com.jason.demo.demo2.product.service.infrastructure.publisher` 不存在
- `StockSeqGapException` 不存在
- `StockSyncEvent` 不存在（因此 `applyDelta` 也无法编译调用）

### GREEN（实现后重跑 brief 指定套件）

**命令（`demo2/`）：**

```powershell
.\mvnw.cmd test "-Dtest=ProductStockApplyDeltaTest,ProductStockDomainServiceTest,ProductStockMapperXmlTest"
```

**结果：** BUILD SUCCESS（exit code 0）

```
ProductStockApplyDeltaTest        Tests run: 5, Failures: 0, Errors: 0
ProductStockDomainServiceTest     Tests run: 7, Failures: 0, Errors: 0
ProductStockMapperXmlTest         Tests run: 1, Failures: 0, Errors: 0
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
```

（Mockito inline-mock-maker / dynamic agent 警告为既有 JDK 提示，非本 Task 引入。）

## Implementation Details

### StockSyncEvent / StockSeqGapException

- `StockSyncEvent`：`@Data @NoArgsConstructor @AllArgsConstructor`，字段 `productId, orderId, optType, qty, idempotentKey, seq`（boxed，便于后续 MQ JSON）
- `StockSeqGapException(productId, messageSeq, currentSeq)` 消息格式与 brief 一致

### Mapper / XML

- `applyReserveDelta` / `applyConfirmDelta` / `applyReleaseDelta` 仅方法声明 + `@Param`
- SET 公式与直写路径相同（RESERVE `stock-=n, withhold+=n`；CONFIRM `actual-=n, withhold-=n, sell+=n`；RELEASE `stock+=n, withhold-=n`）
- `stock_seq = #{seq}`，`WHERE product_id = ? AND stock_seq = #{seq} - 1`
- **无** `stock >= qty` / `withhold >= qty` 守卫（乐观序号锁，数量约束在 Redis 热路径）

### Repository

- `applyXxxDelta(...)` 包装 mapper 影响行 `> 0`

### DomainService.applyDelta

1. `existsByIdempotentKey` → return
2. CONFIRM 且已有 RELEASE → `STOCK_CONFLICT(40005)`；RELEASE 且已有 CONFIRM → `40005`
3. RELEASE 且无 RESERVE 且无 RELEASE 流水 → return
4. 按 `optType` 调对应 `applyXxxDelta`（ADJUST 抛 `IllegalArgumentException`）
5. 命中：`after = requireByProductId()`（**不是** `requireByProductIdForUpdate`），`before = ProductStock.reverse(after, op, qty)`，`after.assertBalance()`，`writeLog`
6. 0 行：`current = requireByProductId()`；`nullToZero(stockSeq) >= seq` → return；否则 `throw new StockSeqGapException(...)`
7. 无 Redis 写入

方案 A 的 `reserve`/`confirm`/`release`/`adjust` 仍走 FOR UPDATE，本 Task 未改。

### Tests

- `ProductStockApplyDeltaTest`：内容与 brief 一致（命中写 reverse 流水、seq 已应用跳过、seq 缺口抛异常、CONFIRM 遇 RELEASE 冲突、幂等键跳过）
- `ProductStockMapperXmlTest`：额外断言三个 `apply*Delta` statement 已注册

## Self-Review

**Completeness**

- Brief 列出的异常、事件、Mapper/XML、Repository、`applyDelta`、测试均已落地
- 无 FOR UPDATE；无 Redis 回写；事件包名正确；未 commit

**Quality**

- XML 与 brief 代码块一致
- 命中路径 before-image 来自 `reverse`（测试断言 `beforeStock=100`, `afterStock=95`）
- 缺口比较使用 `nullToZero`，与方案 A seq 处理一致

**Discipline**

- 未给 RELEASE 从未预占 / RELEASE 遇 CONFIRM 补单测（brief 测试清单未包含）
- 未在 `apply*Delta` SQL 加库存守卫（brief WHERE 只有 `stock_seq`）

**Findings（非阻塞）**

- 🟡 `applyDelta_confirmAfterRelease_conflicts` 覆盖 CONFIRM→RELEASE；反向 RELEASE→CONFIRM 与「从未预占 RELEASE」已实现但无测试
- 🟡 乐观 `UPDATE` 命中后普通 SELECT 与 `reverse` 之间存在 TOCTOU（brief 明确禁止 FOR UPDATE，属设计取舍）
- 🟢 XML 测试只断言 statement 注册，不校验 `stock_seq = #{seq} - 1` 文本

## Concerns

- RELEASE 从未预占跳过、RELEASE 遇 CONFIRM 的 `40005` 无单测（行为已实现）
- 投影成功后的流水 before-image 依赖无锁二次 SELECT，高并发下可能与本次 delta 不完全对齐

未 git commit。
