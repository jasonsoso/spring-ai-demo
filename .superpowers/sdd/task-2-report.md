# Task 2 Report: ProductStock 内存推演与 reverse

**Date:** 2026-08-27  
**Branch:** feat/redis-stock-consistency  
**Status:** DONE

## Summary

为 Redis 热库存方案实现 `ProductStock` 领域对象的内存推演（apply*）与逆向还原（reverse）能力：`from()` 现复制 `stockSeq`；新增 `copy` / `applyReserve` / `applyConfirm` / `applyRelease` / `applyAdjust` / `reverse` 方法；保留既有 `assertBalance()` 不变。通过 TDD 新增 6 个单测全部通过。

## Changed Files

| Action | Path |
|--------|------|
| Modify | `demo2/src/main/java/com/jason/demo/demo2/product/service/core/domain/ProductStock.java` |
| Create | `demo2/src/test/java/com/jason/demo/demo2/product/ProductStockTest.java` |

## TDD Evidence

### Step 1–2: RED（先写失败测试）

**命令（`demo2/`）：**

```powershell
.\mvnw.cmd test "-Dtest=ProductStockTest"
```

**结果：** BUILD FAILURE（exit code 1）

**失败原因（编译期 7 处找不到符号）：**

```
ProductStockTest.java:[25,36] 找不到符号: 方法 copy()
ProductStockTest.java:[29,45] 找不到符号: 方法 reverse(...)
ProductStockTest.java:[37,50] 找不到符号: 方法 applyConfirm(int)
ProductStockTest.java:[43,45] 找不到符号: 方法 reverse(...)
ProductStockTest.java:[51,50] 找不到符号: 方法 applyRelease(int)
ProductStockTest.java:[59,50] 找不到符号: 方法 applyAdjust(int)
ProductStockTest.java:[68,80] 找不到符号: 方法 applyAdjust(int)
```

符合 TDD RED 预期：`copy` / `apply*` / `reverse` 方法尚不存在；`from()` 亦未复制 `stockSeq`（该断言尚未到达运行阶段）。

### Step 3–4: GREEN（完整实现后重跑）

**命令：**

```powershell
.\mvnw.cmd test "-Dtest=ProductStockTest"
```

**结果：** BUILD SUCCESS（exit code 0）

```
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
Time elapsed: 0.407 s
```

**覆盖用例：**

1. `from_copiesStockSeq` — `from()` 复制 `stockSeq=7L` 且保留 `stock=100`
2. `applyReserve_thenReverse_restores` — 预占 3 后 reverse 还原 stock/withhold，且返回新实例
3. `applyConfirm_doesNotChangeAvail` — 确认扣减不改变可用库存，reverse 还原 actual/withhold/sell
4. `applyRelease_restoresAvail` — 释放预占恢复可用库存
5. `applyAdjust_setsActualAndAvail` — 调整目标 actual 并重算 stock
6. `applyAdjust_rejectsBelowWithhold` — targetActual < withhold 抛 `IllegalArgumentException`

## Implementation Details

### ProductStock.from()

在原有字段复制基础上新增 `stock.setStockSeq(source.getStockSeq())`，修复 Task 1 遗留的 `stockSeq` 未复制问题。

### apply* 内存推演

| 方法 | 行为 |
|------|------|
| `applyReserve(qty)` | `stock -= qty`, `withhold += qty` |
| `applyConfirm(qty)` | `actual -= qty`, `withhold -= qty`, `sell += qty`（stock 不变） |
| `applyRelease(qty)` | `stock += qty`, `withhold -= qty` |
| `applyAdjust(targetActual)` | 校验 `targetActual >= 0 && >= withhold`；设 `actual = targetActual`，`stock = targetActual - withhold` |

所有 apply 方法返回 `this` 以支持链式调用。

### reverse(after, op, n)

从 after 状态 copy 出新实例，按操作类型逆向：

- **RESERVE:** `stock += n`, `withhold -= n`
- **CONFIRM:** `actual += n`, `withhold += n`, `sell -= n`
- **RELEASE:** `stock -= n`, `withhold += n`
- **ADJUST / default:** 抛 `IllegalArgumentException("cannot reverse " + op)`

### assertBalance()

保持 Task 1 既有实现不变：校验 `stock == actual - withhold`，字段非 null。

## Regression Check

额外运行既有领域服务测试，确认无回归：

```powershell
.\mvnw.cmd test "-Dtest=ProductStockDomainServiceTest"
```

**结果：** BUILD SUCCESS — Tests run: 5, Failures: 0, Errors: 0, Skipped: 0

## Self-Review

| 检查项 | 结果 |
|--------|------|
| 实现与 brief 完整类 verbatim 一致 | ✓ |
| `from()` 复制 `stockSeq` | ✓ |
| `assertBalance()` 保留未改 | ✓ |
| `reverse` 不支持 ADJUST（按 brief default 分支） | ✓ |
| `applyAdjust` 拒绝 targetActual < withhold | ✓ |
| 6 个测试用例与 brief 完全一致 | ✓ |
| 未修改 DelayTaskMqListener 等无关文件 | ✓ |
| 未创建 git commit（用户策略） | ✓ |
| `ProductStockDoConvert` 经 `from()` 自动获益于 stockSeq 复制 | ✓ |

## Out of Scope（后续 Task 承接）

- `ProductStockDomainService` 尚未调用 apply* / reverse（Redis 热路径集成在后续 Task）
- `reverse` 未覆盖 ADJUST（brief 设计如此，ADJUST 走独立补偿逻辑）
- Mapper / Repository 层无变更（本 Task 仅 domain 内存推演）

## Concerns

- 无任务范围内 concerns。
- Maven 编译含仓库既有弃用警告，不影响本次 6 个测试通过。

## Commits

无（用户策略：不自动 commit）。
