# Task 3 Report: 方案 A DomainService（FOR UPDATE、内存 after、confirm 幂等、ADJUST）

**Date:** 2026-08-27  
**Branch:** feat/redis-stock-consistency  
**Status:** DONE  
**Commits:** none（按指令未提交）

## Summary

直写路径改为：`SELECT … FOR UPDATE` 一次锁定库存行，用 `copy().apply*` 在内存推导 after-image（不再二次 SELECT），`writeLog` 写入 `idempotentKey`。`confirm` 在已有 CONFIRM 流水时直接 return；新增 `adjust`（本 Task 不校验上下架与 Redis seq）。`reserve`/`confirm`/`release`/`adjustActual` SQL 均 `stock_seq = stock_seq + 1`。

## Changed Files

| Action | Path |
|--------|------|
| Modify | `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/dao/mapper/ProductStockMapper.java` |
| Modify | `demo2/src/main/resources/mapper/product/ProductStockMapper.xml` |
| Modify | `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/repository/ProductStockRepository.java` |
| Modify | `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/repository/ProductStockLogRepository.java` |
| Modify | `demo2/src/main/java/com/jason/demo/demo2/product/service/core/ProductStockDomainService.java` |
| Modify | `demo2/src/test/java/com/jason/demo/demo2/product/ProductStockDomainServiceTest.java` |
| Modify | `demo2/src/test/java/com/jason/demo/demo2/product/ProductStockMapperXmlTest.java` |

未改 `DelayTaskMqListener`。自定义 SQL 仅在 `ProductStockMapper.xml`，Mapper 接口无 `@Update`。

## TDD Evidence

### RED（先改测试，确认失败）

**命令（`demo2/`）：**

```powershell
.\mvnw.cmd test "-Dtest=ProductStockDomainServiceTest,ProductStockMapperXmlTest"
```

**结果：** BUILD FAILURE（exit code 1）  
**Tests run:** 8, **Failures:** 1, **Errors:** 6

失败原因符合预期（方法/语句尚不存在，而非断言写错）：

- `requireByProductIdForUpdate(long)` undefined on `ProductStockRepository`
- `adjustActual(long, int)` undefined on `ProductStockRepository`
- `adjust(long, int, long)` undefined on `ProductStockDomainService`
- `existsOpt(...)` undefined on `ProductStockLogRepository`
- `ProductStockMapperXmlTest`: `hasStatement(...adjustActual)` expected true, was false

### GREEN（实现后重跑 brief 指定套件）

**命令（`demo2/`）：**

```powershell
.\mvnw.cmd test "-Dtest=ProductStockDomainServiceTest,ProductStockMapperXmlTest,ProductStockTest,ProductStockLogRepositoryTest"
```

**结果：** BUILD SUCCESS（exit code 0）

```
ProductStockDomainServiceTest     Tests run: 7, Failures: 0, Errors: 0
ProductStockLogRepositoryTest     Tests run: 2, Failures: 0, Errors: 0
ProductStockMapperXmlTest         Tests run: 1, Failures: 0, Errors: 0
ProductStockTest                  Tests run: 6, Failures: 0, Errors: 0
Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
```

（Mockito inline-mock-maker / dynamic agent 警告为既有 JDK 提示，非本 Task 引入。）

## Implementation Details

### Mapper / XML

- `ProductStockMapper.adjustActual(productId, targetActual)` 仅方法声明
- `reserve` / `confirm` / `release` 的 SET 各增 `stock_seq = stock_seq + 1`
- `adjustActual`：`actual_stock = targetActual`，`stock = targetActual - withhold_stock`，`stock_seq += 1`，`WHERE product_id = ? AND targetActual >= withhold_stock`

### Repository

- `ProductStockRepository.requireByProductIdForUpdate`：`LambdaQueryWrapper` + `.last("FOR UPDATE")`，null → `STOCK_NOT_FOUND`
- `ProductStockRepository.adjustActual`：mapper 影响行 `> 0`
- `ProductStockLogRepository.existsByIdempotentKey` / `existsOpt`

### DomainService

- `writeLog` 增加 `idempotentKey`，`log.setIdempotentKey(...)`
- `reserve`：`existsByIdempotentKey` 命中则 return；否则 FOR UPDATE → SQL → `before.copy().applyReserve(qty)` → `stockSeq+1` → `assertBalance()` → 流水 key `{orderId}:{productId}:RESERVE`
- `confirm`：`existsOpt(..., CONFIRM)` 命中则 return；否则 pending RESERVE → FOR UPDATE → SQL → `applyConfirm(effectiveQty)`（不再二次 `requireByProductId`）
- `release`：无 pending RESERVE 则 return；有则 FOR UPDATE + `applyRelease`
- `adjust`：幂等命中则 `requireByProductId` 返回当前行；否则校验 `targetActual >= 0 && >= withhold`，`adjustActual` 失败抛 `ADJUST_INVALID_TARGET`；**不**校验上下架 / Redis seq
- `nullToZero`：`v == null ? 0L : v`

### Tests

- `reserve_success_writesLog`：只 stub `requireByProductIdForUpdate` 一次；断言 `beforeStock=100`、`afterStock=95`、`idempotentKey=100:9001:RESERVE`
- `confirm_incrementsSellStock` / `release_restoresStock_fromReserveQty`：改为 `thenReturn(before)`，禁止 `thenReturn(before, after)`
- 新增 `confirm_idempotent_whenConfirmLogExists`、`adjust_updatesActual`
- `stock(...)` helper 补 `setStockSeq(0L)`
- `reserve_insufficientStock` 同步改为 stub `requireByProductIdForUpdate`（否则实现切换后 stub 失效）
- XML 测试增加 `hasStatement(...adjustActual)`

## Self-Review

**Completeness**

- Brief 列出的 Mapper / XML / Repository / DomainService / 测试均已落地
- 内存 after-image + `assertBalance()` 在 apply 之后由 DomainService 调用（mutator 本身不 assert）
- Custom SQL 只在 XML；未提交；未改无关 listener

**Quality**

- 实现与 brief 代码块一致，confirm/release 按同一模式补全（brief 只给了 prose + reserve/adjust 全文）
- `ProductStock.from` / `copy` 已复制 `stockSeq`，FOR UPDATE 路径 seq 推导正确

**Discipline**

- 未给 `existsByIdempotentKey` / `existsOpt` 额外加仓储单测（brief 未要求）
- 未在 DomainService 做上下架 / Redis seq 校验（Task 8）

**Findings（非阻塞）**

- `confirm(long productId, long orderId, int qty)` 的 `qty` 仍未使用（沿用既有行为：以 pending RESERVE 流水 `changeQty` 为准）
- XML 测试只断言 statement 注册，不解析 SQL 是否含 `stock_seq += 1`（brief 只要求 `hasStatement`）

## Concerns

无正确性疑虑。未 git commit。
