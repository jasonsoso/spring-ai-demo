### Task 5: Lua 脚本 + `RedisStockOps`

**Files:**
- Create: 四个 `demo2/src/main/resources/lua/stock-*.lua`
- Create: `RedisStockKeys.java`、`RedisStockResult.java`、`RedisStockOps.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/product/RedisStockLuaScriptTest.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/product/RedisStockOpsTest.java`

**Interfaces:**
- Consumes: Spec §5 Lua 语义
- Produces: `RedisStockOps.reserve/confirm/release/adjustHash/hsetnxHash/getAvail/getSeq`

- [ ] **Step 1: Write the failing tests**

`RedisStockLuaScriptTest`：从 classpath 读四个 lua；`stock-confirm.lua` / `stock-release.lua` **不得**包含 `GETDEL`；必须包含 `redis.call('GET'` 与 `redis.call('DEL'`；`stock-reserve.lua` 必须包含 `SETNX`、`HINCRBY`、`XADD`；`stock-adjust.lua` 必须包含 `HSET` 且 **不得**包含 `XADD`。

`RedisStockOpsTest`：mock `StringRedisTemplate.execute` 返回 `List.of(-1L, "UNLOADED")` / `List.of(1L, "OK")` / `List.of(0L, "INSUFFICIENT")`，断言 `RedisStockOps.reserve` 映射 `code`/`reason`。`hsetnxHash` 必须用 `putIfAbsent`（或 Lua `HSETNX`），禁止 `HSET` 覆盖已有 Hash：

```java
Boolean created = redis.opsForHash().putIfAbsent(RedisStockKeys.hash(productId), "avail", String.valueOf(avail));
if (Boolean.TRUE.equals(created)) {
    redis.opsForHash().put(RedisStockKeys.hash(productId), "seq", String.valueOf(seq));
    return true;
}
return false;
```

测：`putIfAbsent` true → 方法 true 且写入 seq；false → 方法 false 且 **verify 不再 put seq**。

`adjustHash` mock `execute` 返回 `List.of(1L, "OK")`，断言调用的 script resource 为 `lua/stock-adjust.lua`（可用 `ArgumentCaptor` 抓 `DefaultRedisScript` 或把 script 字段包可见后 assert location）。更简单：测 `adjustHash` 在 mock execute 返回 OK 时 `code==1`。

- [ ] **Step 2: Run tests to verify they fail**

```powershell
.\mvnw.cmd test "-Dtest=RedisStockLuaScriptTest,RedisStockOpsTest"
```

Expected: FAIL（脚本或类不存在）

- [ ] **Step 3: Write Lua + Java**

`RedisStockKeys`：

```java
package com.jason.demo.demo2.product.service.infrastructure.redis;

public final class RedisStockKeys {
    public static final String OUTBOX = "demo2:stock:outbox";

    private RedisStockKeys() {
    }

    public static String hash(long productId) {
        return "demo2:stock:" + productId;
    }

    public static String ticket(long orderId, long productId) {
        return "demo2:stock:reserve:" + orderId + ":" + productId;
    }
}
```

`RedisStockResult`：`public record RedisStockResult(int code, String reason) {}` 放 `product.service.common`。

四个 Lua **逐字采用 spec §5.1–5.4**（含注释）。确认脚本使用 `GET`+`DEL` 而非 `GETDEL`。

`RedisStockOps`：四个 `DefaultRedisScript<List>`，`setLocation(new ClassPathResource("lua/stock-xxx.lua"))`，`setResultType(List.class)`。

```java
@SuppressWarnings("unchecked")
private RedisStockResult eval(DefaultRedisScript<List> script, List<String> keys, String... argv) {
    List<Object> raw = stringRedisTemplate.execute(script, keys, (Object[]) argv);
    if (raw == null || raw.size() < 2) {
        throw new IllegalStateException("unexpected lua result: " + raw);
    }
    int code = Integer.parseInt(String.valueOf(raw.get(0)));
    return new RedisStockResult(code, String.valueOf(raw.get(1)));
}
```

KEYS：RESERVE/CONFIRM/RELEASE 为 `hash(productId)`, `ticket(orderId, productId)`, `OUTBOX`；ADJUST 只有 `hash(productId)`，ARGV 为新可售与新 seq。

`getAvail` / `getSeq`：`opsForHash().get(hash, "avail"|"seq")`，blank → empty。

`adjustHash` 执行 `lua/stock-adjust.lua`，不要在 Java 里对已有 Hash 做裸 `HSET`（上架回灌走 `hsetnxHash`）。

- [ ] **Step 4: Run tests to verify they pass**

```powershell
.\mvnw.cmd test "-Dtest=RedisStockLuaScriptTest,RedisStockOpsTest"
```

Expected: PASS

- [ ] **Step 5: Commit**（仅当用户要求）

```bash
git add demo2/src/main/resources/lua demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/redis demo2/src/main/java/com/jason/demo/demo2/product/service/common/RedisStockResult.java demo2/src/test/java/com/jason/demo/demo2/product/RedisStockLuaScriptTest.java demo2/src/test/java/com/jason/demo/demo2/product/RedisStockOpsTest.java
git commit -m "feat(product): add Redis stock Lua scripts and RedisStockOps"
```

---

