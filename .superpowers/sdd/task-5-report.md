# Task 5 Report: Lua 脚本 + RedisStockOps

**Date:** 2026-08-27  
**Branch:** feat/redis-stock-consistency  
**Status:** DONE_WITH_CONCERNS  
**Commits:** none（按指令未提交）

## Summary

落地 Redis 热库存 Lua 与 Java 封装：四个 classpath 脚本（RESERVE/CONFIRM/RELEASE/ADJUST）逐字采用 spec §5.1–5.4；`RedisStockOps` 通过 `DefaultRedisScript` 执行脚本并映射 `{code, reason}`；上架回灌走 `hsetnxHash`（`putIfAbsent` avail，成功才写 seq，禁止覆盖已有 Hash）；运营灌 Redis 走 `adjustHash`（`lua/stock-adjust.lua`，Java 不对已有 Hash 裸 HSET）。CONFIRM/RELEASE 使用 `GET` 再 `DEL`，无 `GETDEL`。未改 `DelayTaskMqListener`。

## Changed Files

| Action | Path |
|--------|------|
| Create | `demo2/src/main/resources/lua/stock-reserve.lua` |
| Create | `demo2/src/main/resources/lua/stock-confirm.lua` |
| Create | `demo2/src/main/resources/lua/stock-release.lua` |
| Create | `demo2/src/main/resources/lua/stock-adjust.lua` |
| Create | `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/redis/RedisStockKeys.java` |
| Create | `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/redis/RedisStockOps.java` |
| Create | `demo2/src/main/java/com/jason/demo/demo2/product/service/common/RedisStockResult.java` |
| Create | `demo2/src/test/java/com/jason/demo/demo2/product/RedisStockLuaScriptTest.java` |
| Create | `demo2/src/test/java/com/jason/demo/demo2/product/RedisStockOpsTest.java` |

未改 `DelayTaskMqListener`。未 git commit。

## TDD Evidence

### RED（先写测试，确认失败）

**命令（`demo2/`）：**

```powershell
.\mvnw.cmd test "-Dtest=RedisStockLuaScriptTest,RedisStockOpsTest"
```

**结果：** BUILD FAILURE（exit code 1）  
失败原因符合预期（类型/包尚不存在，而非断言写错）：

- `RedisStockResult` 不存在
- 包 `com.jason.demo.demo2.product.service.infrastructure.redis` 不存在（`RedisStockKeys` / `RedisStockOps`）
- `RedisStockOps` 不存在，测试无法编译

### GREEN（实现后重跑 brief 指定套件）

**命令（`demo2/`）：**

```powershell
.\mvnw.cmd test "-Dtest=RedisStockLuaScriptTest,RedisStockOpsTest"
```

**结果：** BUILD SUCCESS（exit code 0）

```
RedisStockLuaScriptTest   Tests run: 3, Failures: 0, Errors: 0
RedisStockOpsTest         Tests run: 5, Failures: 0, Errors: 0
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
```

（Mockito inline-mock-maker / dynamic agent 警告为既有 JDK 提示，非本 Task 引入。）

## Implementation Details

### Lua（spec §5.1–5.4 原文，含注释）

- `stock-reserve.lua`：`EXISTS` → `SETNX` 票 → `HINCRBY avail -qty`（不足回滚票）→ `seq+1` → `XADD`
- `stock-confirm.lua`：`GET` 票 → `DEL` 票 → 不改 avail → `seq+1` → `XADD`（**无 GETDEL**）
- `stock-release.lua`：`GET` 票 → `DEL` 票 → `HINCRBY avail +qty` → `seq+1` → `XADD`（**无 GETDEL**）
- `stock-adjust.lua`：仅 `HSET avail/seq`，**无 ticket、无 XADD**

### RedisStockKeys / RedisStockResult

- Key：`demo2:stock:{productId}`、`demo2:stock:reserve:{orderId}:{productId}`、`demo2:stock:outbox`
- `RedisStockResult`：`public record RedisStockResult(int code, String reason)`，包 `product.service.common`

### RedisStockOps

- 四个 `DefaultRedisScript<List>`：`setLocation(ClassPathResource("lua/stock-xxx.lua"))`，`setResultType(List.class)`
- KEYS：RESERVE/CONFIRM/RELEASE = `hash, ticket, OUTBOX`；ADJUST 仅 `hash`，ARGV = 新可售与新 seq
- `eval`：空/短结果抛 `IllegalStateException`；`code` 用 `String.valueOf` 再 `parseInt`
- `hsetnxHash`：`putIfAbsent(hash, "avail", …)`；仅 `TRUE` 时再 `put seq`；已存在 **不再 put seq**
- `getAvail` / `getSeq`：`opsForHash().get`，blank → `Optional.empty()`
- `adjustHash` 只跑 `lua/stock-adjust.lua`，不对已有 Hash 做 Java 裸 `HSET`

## Tests

- `RedisStockLuaScriptTest`：classpath 读四个 lua；confirm/release 禁止 `GETDEL` 且必须 `GET`+`DEL`；reserve 含 `SETNX`/`HINCRBY`/`XADD`；adjust 含 `HSET` 且无 `XADD`
- `RedisStockOpsTest`：`reserve` 映射 `UNLOADED`/`OK`/`INSUFFICIENT`；`hsetnxHash` true 写 seq、false 不再 put seq；`adjustHash` `code==1` 且脚本内容为 adjust（`HSET`、无 `XADD`、含「新可售」注释）；`getAvail`/`getSeq` blank → empty

## Self-Review

**Completeness**

- Brief 列出的四个 lua、`RedisStockKeys`/`RedisStockResult`/`RedisStockOps`、两份测试均已落地
- CONFIRM/RELEASE 为 GET+DEL；ADJUST 无 XADD；上架走 `hsetnxHash`；`adjustHash` 走 Lua
- 未 commit；未改 `DelayTaskMqListener`

**Quality**

- Lua 与 spec §5.1–5.4 注释/返回码一致
- `hsetnxHash` 与 brief 代码块一致（`putIfAbsent` + 条件写 seq）
- `eval` 与 brief 一致

**Discipline**

- `confirm`/`release` Java 映射无单独单测（Lua 与 `eval` 共用路径已覆盖 reserve/adjust）
- `adjustHash` 用 `getScriptAsString()` 断言脚本身份（`DefaultRedisScript` 无公开 `getLocation()`）

**Findings（非阻塞）**

- 🟡 `hsetnxHash` 分两步 `HSETNX avail` + `HSET seq`，进程在中间崩溃会留下只有 `avail` 的 Hash（brief 明确要求该写法，非 Lua 原子 `HSETNX` 双字段）
- 🟡 `confirm`/`release` 的 Java ARGV/KEYS 无独立 mock 断言
- 🟢 Lua 单测是源码字符串检查，未连真实 Redis 执行语义

## Concerns

- `hsetnxHash` 非原子：`avail` 写入成功后、`seq` 写入前崩溃，Hash 可能缺 `seq`
- `confirm`/`release` Java 调用路径无单独单测（行为已实现，与 `reserve` 共用 `eval`）

未 git commit。
