# Task 6 Report: ProductStockHotService + 开关

**Date:** 2026-08-27  
**Branch:** feat/redis-stock-consistency  
**Status:** DONE_WITH_CONCERNS  
**Commits:** none（按指令未提交）

## Summary

落地热/冷库存入口：`ProductStockHotService` 按 `app.product.stock.redis-hot-enabled` 在 Redis Lua 与方案 A `ProductStockDomainService` 之间切换。RESERVE 前校验 `qty>0` 并调用已有 `ProductDomainService.requireOnShelf`；热路径 Lua `UNLOADED` → `40010`，**不**调用 `hsetnxHash` / `adjustHash`。CONFIRM `NOT_FOUND`、RELEASE `NO_TICKET` 按 spec §5.2–§5.3 查 MySQL `existsOpt`。`overlayAvail` 供 Task 9 列表/详情覆盖可售。未改 `ProductDomainService`（`requireOnShelf` 已存在）。未改 `DelayTaskMqListener`。

## Changed Files

| Action | Path |
|--------|------|
| Create | `demo2/src/main/java/com/jason/demo/demo2/product/config/ProductStockProperties.java` |
| Create | `demo2/src/main/java/com/jason/demo/demo2/product/config/ProductStockConfiguration.java` |
| Create | `demo2/src/main/java/com/jason/demo/demo2/product/service/core/ProductStockHotService.java` |
| Create | `demo2/src/test/java/com/jason/demo/demo2/product/ProductStockHotServiceTest.java` |
| Modify | `demo2/src/main/resources/application.properties`（追加 7 条 `app.product.stock.*`） |

未改 `ProductDomainService.java`（brief 标注 Modify，因 `requireOnShelf` 已存在，仅热路径 RESERVE 前调用）。  
未改 `DelayTaskMqListener`。未 git commit。

## TDD Evidence

### RED（先写测试，确认失败）

**命令（`demo2/`）：**

```powershell
.\mvnw.cmd test "-Dtest=ProductStockHotServiceTest"
```

**结果：** BUILD FAILURE（exit code 1）  
失败原因符合预期（类型/包尚不存在，而非断言写错）：

- 包 `com.jason.demo.demo2.product.config` 不存在（`ProductStockProperties`）
- `ProductStockHotService` 不存在，测试无法编译

### GREEN（实现后重跑 brief 指定套件）

**命令（`demo2/`）：**

```powershell
.\mvnw.cmd test "-Dtest=ProductStockHotServiceTest,ProductStockDomainServiceTest"
```

**结果：** BUILD SUCCESS（exit code 0）

```
ProductStockDomainServiceTest   Tests run: 7, Failures: 0, Errors: 0
ProductStockHotServiceTest      Tests run: 14, Failures: 0, Errors: 0
Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
```

中间一次 GREEN 前有 4 个 Mockito `PotentialStubbingProblem`（STRICT_STUBS：先查 CONFIRM 再查 RELEASE 时，只 stub 后者会触发）。已在测试中按检查顺序补全 `existsOpt` stub 后全绿。

（Mockito inline-mock-maker / dynamic agent 警告为既有 JDK 提示，非本 Task 引入。）

## Implementation Details

### 配置

- `ProductStockProperties`：`@ConfigurationProperties(prefix = "app.product.stock")`，`redisHotEnabled` 默认 `true`；其余字段与 brief 表对应（`reconcileIntervalMs` / `reconcileLagAlarmMs` / `outboxBlockMs` / `outboxBatchSize` / `outboxGroup` / `outboxConsumer`）
- `ProductStockConfiguration`：`@Configuration` + `@EnableConfigurationProperties(ProductStockProperties.class)`
- `application.properties` 追加 brief 列出的 7 条属性（值原文）

### ProductStockHotService

构造器注入 `ProductStockProperties` + `RedisStockOps` + `ProductStockDomainService` + `ProductStockLogRepository` + `ProductDomainService`。测试手动 `new ProductStockHotService(...)`，不用 `@InjectMocks`。

**reserve：** `qty<=0` → `BAD_REQUEST`；`requireOnShelf`；`!redisHotEnabled` → `domainService.reserve`；否则 Lua：

| reason | 行为 |
|--------|------|
| OK / IDEMPOTENT | return |
| UNLOADED | `40010`，不灌 Redis |
| INSUFFICIENT | `40003` |
| CONFLICT | `40005` |

**confirm / release：** 冷路径直接委托 DomainService。热路径 Lua 后：

CONFIRM `NOT_FOUND`（spec §5.2）：

| MySQL | 处理 |
|-------|------|
| 已有 CONFIRM | 成功 |
| 已有 RELEASE | `40005` |
| 仅有 RESERVE | `40010` |
| 都没有 | `40004` |

RELEASE `NO_TICKET`（code=2，spec §5.3）：

| MySQL | 处理 |
|-------|------|
| 已有 RELEASE | 成功 |
| 已有 CONFIRM | `40005` |
| 仅有 RESERVE | `40010` |
| 都没有 | 成功 |

幂等键：`ProductStockIdempotentKeys.of(orderId, productId, RESERVE/CONFIRM/RELEASE)`。

**overlayAvail：** `!redisHotEnabled` → empty；否则 `getAvail` → `Optional.of(Math.toIntExact(v))`；Hash 不存在 → empty。

`HotService` 实现中**从不**调用 `hsetnxHash` / `adjustHash`。

## Tests

Brief 要求的 11 条 + `overlayAvail` 3 条：

1. `redisHotEnabled=false` → reserve 只调 domainService，never redisStockOps
2. hot + requireOnShelf + INSUFFICIENT → 40003
3. hot UNLOADED → 40010，never hsetnxHash / adjustHash
4. CONFIRM NOT_FOUND + 已有 CONFIRM → 成功
5. CONFIRM NOT_FOUND + 已有 RELEASE → 40005
6. CONFIRM NOT_FOUND + 仅 RESERVE → 40010
7. CONFIRM NOT_FOUND + 都没有 → 40004
8. RELEASE NO_TICKET + 已有 RELEASE → 成功
9. RELEASE NO_TICKET + 已有 CONFIRM → 40005
10. RELEASE NO_TICKET + 仅 RESERVE → 40010
11. RELEASE NO_TICKET + 都没有 → 成功
12. overlayAvail 冷路径 empty
13. overlayAvail 热路径 getAvail → int
14. overlayAvail Hash 不存在 → empty

## Self-Review

**Completeness**

- Properties / Configuration / HotService / Test / application.properties 均已落地
- spec §5.2–§5.3 查表与 brief 11 条测试一一对应；UNLOADED 不灌 Hash
- `requireOnShelf` 在 RESERVE（含冷路径）前调用；`ProductDomainService` 无需改动
- 未 commit；未改 `DelayTaskMqListener`

**Quality**

- 构造器注入，测试手动 new，避免 `@InjectMocks` 注入 properties
- RELEASE 以 reason=`NO_TICKET`（code=2）分支，未把 code=2 当成 IDEMPOTENT 成功
- 查 MySQL 用已有 `existsOpt`，不引入新仓储方法

**Discipline**

- `qty<=0`、reserve `CONFLICT`、confirm/release `UNLOADED`、confirm/release 冷路径：行为已实现，无独立单测
- 空 `switch` case（OK/IDEMPOTENT）略冗，但分支表清晰

**Findings（非阻塞）**

- 🟡 `qty<=0` / `CONFLICT` / confirm·release `UNLOADED` / 冷路径 confirm·release 无独立单测
- 🟡 spec 原文「仅 RESERVE → 重试」由 brief 映射为 `40010 STOCK_SYNC_LAG`（已按 brief 实现）
- 🟢 `overlayAvail` 对超 `Integer.MAX_VALUE` 的 avail 会抛 `ArithmeticException`（库存场景不现实）

## Concerns

- `qty<=0`、Lua `CONFLICT`、confirm/release `UNLOADED`、冷路径 confirm/release 无独立单测（行为已实现）
- brief 将 spec「重试」落成 `40010`，调用方需按业务错误码重试/提示，本 Task 未做重试循环

未 git commit。
