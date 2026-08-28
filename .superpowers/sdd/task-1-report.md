# Task 1 Report: Schema、DO、错误码、幂等键

**Date:** 2026-08-27  
**Branch:** feat/redis-stock-consistency  
**Status:** DONE

## Summary

为 Redis 热库存与 MySQL 最终一致方案奠定数据层与公共常量基础：新增 `stock_seq` 列与 `idempotent_key` 列（含 DDL 与迁移脚本）、扩展 DO 字段、追加错误码 40008–40010、实现幂等键工具类，并通过 TDD 单测验证。

## Changed Files

| Action | Path |
|--------|------|
| Create | `demo2/src/main/resources/db/product-stock-seq-schema.sql` |
| Create | `demo2/src/main/java/com/jason/demo/demo2/product/service/common/ProductStockIdempotentKeys.java` |
| Create | `demo2/src/test/java/com/jason/demo/demo2/product/ProductStockIdempotentKeysTest.java` |
| Modify | `demo2/src/main/resources/db/product-module-schema.sql` |
| Modify | `demo2/src/main/java/com/jason/demo/demo2/product/service/common/ProductErrorCodeEnum.java` |
| Modify | `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/dao/entity/ProductStockDO.java` |
| Modify | `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/dao/entity/ProductStockLogDO.java` |

## TDD Evidence

### Step 1–2: RED（先写失败测试）

**命令（`demo2/`）：**

```powershell
.\mvnw.cmd test "-Dtest=ProductStockIdempotentKeysTest"
```

**结果：** BUILD FAILURE（exit code 1）

**失败原因：**

```
[ERROR] ProductStockIdempotentKeysTest.java:[4,51] 找不到符号
  符号:   类 ProductStockIdempotentKeys
```

编译阶段失败：`ProductStockIdempotentKeys` 类尚不存在；`ProductErrorCodeEnum.ADJUST_REQUIRES_OFF_SHELF` 等枚举常量亦未定义。符合 TDD RED 预期。

### Step 3–4: GREEN（最小实现后重跑）

**命令：**

```powershell
.\mvnw.cmd test "-Dtest=ProductStockIdempotentKeysTest"
```

**结果：** BUILD SUCCESS（exit code 0）

```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
```

**覆盖用例：**

1. `of_joinsOrderProductOpt` — 验证 `of(100, 9001, RESERVE)` → `"100:9001:RESERVE"`，`ofAdjust(55)` → `"ADJUST:55"`
2. `newErrorCodes_areStable` — 验证 40008/40009/40010 错误码稳定

## Implementation Details

### ProductErrorCodeEnum

在 `STOCK_NOT_FOUND(40007)` 后追加（保留既有 40001–40005、40007）：

- `ADJUST_REQUIRES_OFF_SHELF(40008, "调整库存前必须先下架")`
- `ADJUST_INVALID_TARGET(40009, "目标现货非法")`
- `STOCK_SYNC_LAG(40010, "库存同步未追上")`

### ProductStockIdempotentKeys

- `of(orderId, productId, optType)` → `"{orderId}:{productId}:{optType.name()}"`
- `ofAdjust(adjustId)` → `"ADJUST:{adjustId}"`
- 私有构造，纯静态工具类

### DO 字段

- `ProductStockDO`：新增 `Long stockSeq`（位于 `sellStock` 之后）
- `ProductStockLogDO`：新增 `String idempotentKey`（位于 `optType` 之后）

### Schema

**product-module-schema.sql（新库 bootstrap）：**

- `demo_product_stock`：`sell_stock` 后增加 `stock_seq BIGINT NOT NULL DEFAULT 0`
- seed INSERT 三行均写 `stock_seq = 0`
- `demo_product_stock_log`：`opt_type` 后增加 `idempotent_key VARCHAR(64) NOT NULL`，并 `UNIQUE KEY uk_stock_log_idempotent (idempotent_key)`

**product-stock-seq-schema.sql（已有库一次性迁移）：**

- ALTER 添加 `stock_seq`、`idempotent_key`（nullable 过渡）
- UPDATE 回填历史流水 `idempotent_key = CONCAT(IFNULL(order_id,'0'), ':', product_id, ':', opt_type)`
- MODIFY NOT NULL + ADD UNIQUE KEY

> 未对 live 数据库执行 ALTER（按任务要求仅写文件）。

## Self-Review

| 检查项 | 结果 |
|--------|------|
| 错误码 40001–40005、40007 未改动 | ✓ |
| 40008–40010 追加顺序与文案符合 brief | ✓ |
| DO 字段位置符合 brief（optType 后 / sellStock 后） | ✓ |
| 幂等键格式与 brief 完全一致 | ✓ |
| DDL seed 数据含 stock_seq=0 | ✓ |
| 迁移脚本含历史数据回填逻辑 | ✓ |
| 未修改 Mapper XML / Repository（本任务范围外） | ✓ |
| 未触碰 DelayTaskMqListener 无关改动 | ✓ |
| 未创建 git commit（用户策略） | ✓ |

## Out of Scope（后续 Task 承接）

- `ProductStock.from()` 尚未复制 `stockSeq`（domain 继承 DO，后续 applyDelta/Relay 任务可补）
- `ProductStockDomainService` 写流水时尚未设置 `idempotentKey`（Task 后续集成）
- Mapper XML 未更新 INSERT/SELECT 列（本 Task brief 明确不含）

## Concerns

- 无任务范围内 concerns。
- Maven 编译含仓库既有弃用警告，不影响本次 2 个测试通过。

## Commits

无（用户策略：不自动 commit）。
