# 订单分库分表与订单号基因法 Implementation Plan

> **Status:** 已实现（2026-08-30）。归档见 [archive/2026-08-30-order-sharding-gene.md](../archive/2026-08-30-order-sharding-gene.md)。
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 `memberId` 对 `demo_order` / `demo_order_item` 做 2 库 × 32 表分片，订单号低 9 位嵌入虚拟分片，只拿 `orderId` 也能直达库表；会员页右侧可算路由。

**Architecture:** `OrderShardGene` 写死 9 bit / 512 / 2 / 32 公式。`OrderIdGenerator` 在雪花上 `embed`。ShardingSphere `CLASS_BASED` 复合算法有 `member_id` 用会员、只有 `order_id` 则拆基因，禁止无键广播。主表与明细 binding。官方 `ShardingSphereDriver` + `shardingsphere.yaml`。调试接口纯计算、不登录。

**Tech Stack:** Spring Boot 4.1、Java 21、MyBatis-Plus、ShardingSphere-JDBC 5.5.2、Hutool Snowflake、JUnit 5 + Mockito

**Spec:** [2026-08-30-order-sharding-gene-design.md](../specs/2026-08-30-order-sharding-gene-design.md)

## Global Constraints

- 仅改 `demo2`；不改 `demo` 工程
- 基因 **9 bit**，`VIRTUAL_COUNT=512`，`DB_COUNT=2`，`TABLE_COUNT=32`；公式写死在 `OrderShardGene`，禁止做成配置项
- `ds = virtual % 2`，`table = (virtual / 2) % 32`；**禁止** `table = virtual % 32`
- 订单号：`(raw & ~0x1FF) | (memberId % 512)`；`itemId` 仍普通雪花
- 接入：`shardingsphere-jdbc` **5.5.2** + `ShardingSphereDriver` + `classpath:shardingsphere.yaml`；禁止已删除的 Spring Starter
- binding：`demo_order, demo_order_item`；未分片表走 `ds_default` → `spring_ai_agent2`
- 事务 **LOCAL**，不上 XA；热库存保持 `app.product.stock.redis-hot-enabled=true`
- 绿场：建 `order_ds_0` / `order_ds_1` 空表；不迁、不 DROP 旧 `spring_ai_agent2.demo_order*`
- `POST /demo/orders/shardExplain` 无 `@LoginRequired`、不查库；两个 ID 都空 → `PARAM_MISSING(10002)`
- 不新增订单错误码；不改 C 端下单/支付/列表/详情报文
- 算法无 Spring 注入，只调 `OrderShardGene`；枚举类名必须以 `Enum` 结尾；日志用 `@Slf4j`
- 单测不启 128 张真实表；在 `demo2` 目录跑 `mvn`

---

## File Structure

| 文件 | 职责 |
|------|------|
| `demo2/pom.xml` | `shardingsphere-jdbc` 5.5.2 |
| `.../order/service/infrastructure/shard/OrderShardGene.java` | 基因纯函数 |
| `.../order/service/infrastructure/shard/OrderIdGenerator.java` | 下单发号 |
| `.../order/service/infrastructure/shard/OrderComplexShardingAlgorithm.java` | SS 复合算法 |
| `.../order/service/common/OrderShardSourceEnum.java` | `MEMBER_ID` / `ORDER_ID` |
| `.../order/app/vo/req/OrderShardExplainReqVO.java` | 调试请求 |
| `.../order/app/vo/res/OrderShardExplainResVO.java` | 调试响应 |
| `.../order/app/executor/OrderShardExplainCmdExe.java` | 纯计算用例 |
| `.../order/app/controller/OrderShardController.java` | `/shardExplain` |
| `.../order/app/executor/OrderPlaceCmdExe.java` | 改用 `OrderIdGenerator` |
| `demo2/src/main/resources/shardingsphere.yaml` | 三数据源 + binding |
| `demo2/src/main/resources/application.properties` | 改 Driver / URL |
| `demo2/src/main/resources/db/order-shard-schema.sql` | 2 schema × 64 表 |
| `demo2/src/main/resources/static/index.html` | 分片调试卡片 |
| `demo2/src/main/resources/static/js/tabs/member.js` | 计算路由 + 下单回填 |
| `demo2/src/test/java/.../order/OrderShardGeneTest.java` | 公式 |
| `demo2/src/test/java/.../order/OrderIdGeneratorTest.java` | 发号基因 |
| `demo2/src/test/java/.../order/OrderComplexShardingAlgorithmTest.java` | 路由 / 禁广播 |
| `demo2/src/test/java/.../order/OrderShardExplainCmdExeTest.java` | 三种输入 |
| `demo2/src/test/java/.../order/OrderPlaceCmdExeTest.java` | mock `OrderIdGenerator` |

---

### Task 1: OrderShardGene 纯函数

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/shard/OrderShardGene.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/order/OrderShardGeneTest.java`

**Interfaces:**
- Produces: `OrderShardGene.GENE_BITS=9`、`VIRTUAL_COUNT=512`、`DB_COUNT=2`、`TABLE_COUNT=32`、`GENE_MASK=0x1FFL`；`virtualOfMember(long)`、`virtualOfOrderId(long)`、`dsIndex(long virtual)`、`tableIndex(long virtual)`、`embed(long raw, long memberId)`、`geneBits(long virtual)`、`dsName(long virtual)`、`orderTableName(long virtual)`、`itemTableName(long virtual)`

- [ ] **Step 1: 写失败测试**

```java
package com.jason.demo.demo2.order;

import com.jason.demo.demo2.order.service.infrastructure.shard.OrderShardGene;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderShardGeneTest {

    @Test
    void virtual612_routesToDs0Table18() {
        long virtual = OrderShardGene.virtualOfMember(612L);
        assertEquals(100L, virtual);
        assertEquals(100L, OrderShardGene.virtualOfOrderId((55L << 9) | 100L));
        assertEquals(0, OrderShardGene.dsIndex(virtual));
        assertEquals(18, OrderShardGene.tableIndex(virtual));
        assertEquals("001100100", OrderShardGene.geneBits(virtual));
        assertEquals("order_ds_0", OrderShardGene.dsName(virtual));
        assertEquals("demo_order_18", OrderShardGene.orderTableName(virtual));
        assertEquals("demo_order_item_18", OrderShardGene.itemTableName(virtual));
    }

    @Test
    void boundaries_zeroAnd511() {
        assertEquals(0L, OrderShardGene.virtualOfMember(0L));
        assertEquals(0, OrderShardGene.dsIndex(0L));
        assertEquals(0, OrderShardGene.tableIndex(0L));
        assertEquals(511L, OrderShardGene.virtualOfMember(511L));
        assertEquals(1, OrderShardGene.dsIndex(511L));
        assertEquals(255 % 32, OrderShardGene.tableIndex(511L));
        assertEquals("111111111", OrderShardGene.geneBits(511L));
    }

    @Test
    void embed_replacesLow9BitsOnly() {
        long raw = 0x1234_5678_9ABC_DE00L;
        long orderId = OrderShardGene.embed(raw, 612L);
        assertEquals(100L, orderId & 0x1FFL);
        assertEquals(raw >> 9, orderId >> 9);
    }

    @Test
    void bothDatabasesUseAll32Tables() {
        Set<String> ds0 = new HashSet<>();
        Set<String> ds1 = new HashSet<>();
        for (long memberId = 0; memberId < 512; memberId++) {
            long v = OrderShardGene.virtualOfMember(memberId);
            if (OrderShardGene.dsIndex(v) == 0) {
                ds0.add(OrderShardGene.orderTableName(v));
            } else {
                ds1.add(OrderShardGene.orderTableName(v));
            }
        }
        assertEquals(32, ds0.size());
        assertEquals(32, ds1.size());
        for (int i = 0; i < 32; i++) {
            assertTrue(ds0.contains("demo_order_" + i));
            assertTrue(ds1.contains("demo_order_" + i));
        }
    }

    @Test
    void wrongModulo32_wouldLeaveOddTablesEmptyOnOneDs() {
        Set<Integer> ds0Wrong = new HashSet<>();
        for (long memberId = 0; memberId < 512; memberId++) {
            long v = memberId % 512;
            if ((v % 2) == 0) {
                ds0Wrong.add((int) (v % 32));
            }
        }
        assertNotEquals(32, ds0Wrong.size());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run（在 `demo2` 目录）: `mvn -Dtest=OrderShardGeneTest test`

Expected: FAIL，找不到 `OrderShardGene`

- [ ] **Step 3: 实现 `OrderShardGene`**

```java
package com.jason.demo.demo2.order.service.infrastructure.shard;

public final class OrderShardGene {

    public static final int GENE_BITS = 9;
    public static final int VIRTUAL_COUNT = 512;
    public static final int DB_COUNT = 2;
    public static final int TABLE_COUNT = 32;
    public static final long GENE_MASK = 0x1FFL;

    private OrderShardGene() {
    }

    public static long virtualOfMember(long memberId) {
        return memberId % VIRTUAL_COUNT;
    }

    public static long virtualOfOrderId(long orderId) {
        return orderId & GENE_MASK;
    }

    public static int dsIndex(long virtual) {
        return (int) (virtual % DB_COUNT);
    }

    public static int tableIndex(long virtual) {
        return (int) ((virtual / DB_COUNT) % TABLE_COUNT);
    }

    public static long embed(long raw, long memberId) {
        return (raw & ~GENE_MASK) | virtualOfMember(memberId);
    }

    public static String geneBits(long virtual) {
        String bits = Long.toBinaryString(virtual & GENE_MASK);
        return "0".repeat(GENE_BITS - bits.length()) + bits;
    }

    public static String dsName(long virtual) {
        return "order_ds_" + dsIndex(virtual);
    }

    public static String orderTableName(long virtual) {
        return "demo_order_" + tableIndex(virtual);
    }

    public static String itemTableName(long virtual) {
        return "demo_order_item_" + tableIndex(virtual);
    }
}
```

- [ ] **Step 4: 再跑测试**

Run: `mvn -Dtest=OrderShardGeneTest test`

Expected: PASS（`tableIndex(511) = 255 % 32 = 31`）

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/shard/OrderShardGene.java demo2/src/test/java/com/jason/demo/demo2/order/OrderShardGeneTest.java
git commit -m "feat(order): add 9-bit shard gene calculator"
```

---

### Task 2: OrderIdGenerator

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/shard/OrderIdGenerator.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/order/OrderIdGeneratorTest.java`

**Interfaces:**
- Consumes: `OrderShardGene.embed`；`SnowflakeIdGenerator.nextId()`
- Produces: `@Component OrderIdGenerator.nextOrderId(long memberId): long`

- [ ] **Step 1: 写失败测试**

```java
package com.jason.demo.demo2.order;

import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import com.jason.demo.demo2.order.service.infrastructure.shard.OrderIdGenerator;
import com.jason.demo.demo2.order.service.infrastructure.shard.OrderShardGene;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class OrderIdGeneratorTest {

    @Test
    void nextOrderId_low9BitsMatchMemberVirtual() {
        OrderIdGenerator gen = new OrderIdGenerator(new SnowflakeIdGenerator(1, 1));
        long first = gen.nextOrderId(612L);
        long second = gen.nextOrderId(612L);
        assertEquals(100L, OrderShardGene.virtualOfOrderId(first));
        assertEquals(100L, OrderShardGene.virtualOfOrderId(second));
        assertNotEquals(first, second);
        assertNotEquals(first >> 9, second >> 9);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -Dtest=OrderIdGeneratorTest test`

Expected: FAIL，找不到 `OrderIdGenerator`

- [ ] **Step 3: 实现**

```java
package com.jason.demo.demo2.order.service.infrastructure.shard;

import com.jason.demo.demo2.framework.id.SnowflakeIdGenerator;
import org.springframework.stereotype.Component;

@Component
public class OrderIdGenerator {

    private final SnowflakeIdGenerator snowflakeIdGenerator;

    public OrderIdGenerator(SnowflakeIdGenerator snowflakeIdGenerator) {
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }

    public long nextOrderId(long memberId) {
        return OrderShardGene.embed(snowflakeIdGenerator.nextId(), memberId);
    }
}
```

- [ ] **Step 4: 再跑测试**

Run: `mvn -Dtest=OrderIdGeneratorTest,OrderShardGeneTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/shard/OrderIdGenerator.java demo2/src/test/java/com/jason/demo/demo2/order/OrderIdGeneratorTest.java
git commit -m "feat(order): embed shard gene into snowflake order ids"
```

---

### Task 3: Maven + 复合分片算法

**Files:**
- Modify: `demo2/pom.xml`
- Create: `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/shard/OrderComplexShardingAlgorithm.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/order/OrderComplexShardingAlgorithmTest.java`

**Interfaces:**
- Consumes: `OrderShardGene`；ShardingSphere `ComplexKeysShardingAlgorithm`
- Produces: `doSharding(availableTargetNames, shardingValue)` 返回 1～N 个物理节点；无 `member_id` 且无 `order_id` 抛 `IllegalArgumentException`；有 `member_id` 时忽略 order 基因是否匹配

- [ ] **Step 1: 在 `pom.xml` `<properties>` 增加**

在 `<hutool.version>5.8.35</hutool.version>` 后插入：

```xml
        <shardingsphere.version>5.5.2</shardingsphere.version>
```

在 `mybatis-plus-spring-boot4-starter` 依赖**之前**插入：

```xml
        <dependency>
            <groupId>org.apache.shardingsphere</groupId>
            <artifactId>shardingsphere-jdbc</artifactId>
            <version>${shardingsphere.version}</version>
        </dependency>
```

不要加 `shardingsphere-jdbc-core-spring-boot-starter`。

- [ ] **Step 2: 写失败测试**

若 5.5.2 的 `ComplexKeysShardingValue` 构造签名与下面不一致，按 IDE/`javap` 改为官方三参构造（logicTable、columnValues、rangeValues）。

```java
package com.jason.demo.demo2.order;

import com.jason.demo.demo2.order.service.infrastructure.shard.OrderComplexShardingAlgorithm;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingValue;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderComplexShardingAlgorithmTest {

    private final OrderComplexShardingAlgorithm algorithm = new OrderComplexShardingAlgorithm();

    @Test
    void memberIdOnly_routesDbAndTable() {
        assertEquals(List.of("order_ds_0"), algorithm.doSharding(List.of("order_ds_0", "order_ds_1"),
                value("demo_order", "member_id", 612L)));
        assertEquals(List.of("demo_order_18"), algorithm.doSharding(orderTables(),
                value("demo_order", "member_id", 612L)));
        assertEquals(List.of("demo_order_item_18"), algorithm.doSharding(itemTables(),
                value("demo_order_item", "member_id", 612L)));
    }

    @Test
    void orderIdOnly_extractsGene() {
        long orderId = (99L << 9) | 100L;
        assertEquals(List.of("order_ds_0"), algorithm.doSharding(List.of("order_ds_0", "order_ds_1"),
                value("demo_order", "order_id", orderId)));
        assertEquals(List.of("demo_order_18"), algorithm.doSharding(orderTables(),
                value("demo_order", "order_id", orderId)));
    }

    @Test
    void bothPresent_usesMemberId() {
        long mismatchedOrderId = (99L << 9) | 7L;
        assertEquals(List.of("demo_order_18"), algorithm.doSharding(orderTables(),
                both("demo_order", 612L, mismatchedOrderId)));
    }

    @Test
    void neitherColumn_forbidsBroadcast() {
        ComplexKeysShardingValue<Comparable<?>> empty = new ComplexKeysShardingValue<>(
                "demo_order", Map.of(), Map.of());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> algorithm.doSharding(orderTables(), empty));
        assertTrue(ex.getMessage().contains("broadcast"));
    }

    private static ComplexKeysShardingValue<Comparable<?>> value(String logic, String column, long id) {
        Map<String, Collection<Comparable<?>>> cols = new HashMap<>();
        cols.put(column, List.of(id));
        return new ComplexKeysShardingValue<>(logic, cols, Map.of());
    }

    private static ComplexKeysShardingValue<Comparable<?>> both(String logic, long memberId, long orderId) {
        Map<String, Collection<Comparable<?>>> cols = new HashMap<>();
        cols.put("member_id", List.of(memberId));
        cols.put("order_id", List.of(orderId));
        return new ComplexKeysShardingValue<>(logic, cols, Map.of());
    }

    private static List<String> orderTables() {
        return IntStream.range(0, 32).mapToObj(i -> "demo_order_" + i).toList();
    }

    private static List<String> itemTables() {
        return IntStream.range(0, 32).mapToObj(i -> "demo_order_item_" + i).toList();
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `mvn -Dtest=OrderComplexShardingAlgorithmTest test`

Expected: FAIL，找不到算法类（依赖应已能解析 `ComplexKeysShardingValue`）

- [ ] **Step 4: 实现算法**

列名大小写不敏感。多个 IN 值：每个值算节点后去重返回（同基因则单节点）。range 非空也视为无精确键，若同时没有精确列则禁广播。

```java
package com.jason.demo.demo2.order.service.infrastructure.shard;

import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingAlgorithm;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingValue;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

@Slf4j
public class OrderComplexShardingAlgorithm implements ComplexKeysShardingAlgorithm<Comparable<?>> {

    @Override
    public void init(Properties props) {
        // CLASS_BASED 会调 TypedSPI.init；无需配置项
    }

    @Override
    public Collection<String> doSharding(
            Collection<String> availableTargetNames,
            ComplexKeysShardingValue<Comparable<?>> shardingValue) {
        Collection<Long> memberIds = longs(shardingValue, "member_id", "memberId");
        Collection<Long> orderIds = longs(shardingValue, "order_id", "orderId");
        if (memberIds.isEmpty() && orderIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "order shard requires member_id or order_id, broadcast forbidden");
        }
        Set<String> result = new LinkedHashSet<>();
        String source = memberIds.isEmpty() ? "order_id" : "member_id";
        Collection<Long> values = memberIds.isEmpty() ? orderIds : memberIds;
        boolean fromOrderId = memberIds.isEmpty();
        for (Long id : values) {
            long virtual = fromOrderId
                    ? OrderShardGene.virtualOfOrderId(id)
                    : OrderShardGene.virtualOfMember(id);
            String target = pickTarget(availableTargetNames, shardingValue.getLogicTableName(), virtual);
            result.add(target);
            log.info("order shard route, logic={}, virtual={}, ds={}, table={}, source={}",
                    shardingValue.getLogicTableName(),
                    virtual,
                    OrderShardGene.dsName(virtual),
                    logicTableName(shardingValue.getLogicTableName(), virtual),
                    source);
        }
        return result;
    }

    private static String logicTableName(String logic, long virtual) {
        if (logic != null && logic.contains("item")) {
            return OrderShardGene.itemTableName(virtual);
        }
        return OrderShardGene.orderTableName(virtual);
    }

    private static String pickTarget(Collection<String> available, String logic, long virtual) {
        String expected;
        if (isDatabaseTargets(available)) {
            expected = OrderShardGene.dsName(virtual);
        } else if (logic != null && logic.contains("item")) {
            expected = OrderShardGene.itemTableName(virtual);
        } else {
            expected = OrderShardGene.orderTableName(virtual);
        }
        if (!available.contains(expected)) {
            throw new IllegalArgumentException("shard target not in available: " + expected);
        }
        return expected;
    }

    private static boolean isDatabaseTargets(Collection<String> available) {
        return available.stream().anyMatch(n -> n.startsWith("order_ds_"));
    }

    private static Collection<Long> longs(
            ComplexKeysShardingValue<Comparable<?>> value, String... columnNames) {
        Map<String, Collection<Comparable<?>>> map = value.getColumnNameAndShardingValuesMap();
        Set<Long> out = new LinkedHashSet<>();
        if (map == null) {
            return out;
        }
        for (Map.Entry<String, Collection<Comparable<?>>> e : map.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT).replace("_", "");
            for (String want : columnNames) {
                String normalized = want.toLowerCase(Locale.ROOT).replace("_", "");
                if (key.equals(normalized) && e.getValue() != null) {
                    for (Comparable<?> c : e.getValue()) {
                        if (c != null) {
                            out.add(((Number) c).longValue());
                        }
                    }
                }
            }
        }
        return out;
    }
}
```

若 `ComplexKeysShardingAlgorithm` 在 5.5.2 **没有** `init(Properties)`，删掉该方法只保留 `doSharding`。若接口还要求 `getType()`，返回 `"CLASS_BASED"` 以外的自定义名即可，CLASS_BASED 包装类会处理 type。

- [ ] **Step 5: 再跑测试**

Run: `mvn -Dtest=OrderComplexShardingAlgorithmTest,OrderShardGeneTest test`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add demo2/pom.xml demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/shard/OrderComplexShardingAlgorithm.java demo2/src/test/java/com/jason/demo/demo2/order/OrderComplexShardingAlgorithmTest.java
git commit -m "feat(order): add ShardingSphere complex algorithm for gene routing"
```

---

### Task 4: shardExplain 接口

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/order/service/common/OrderShardSourceEnum.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/order/app/vo/req/OrderShardExplainReqVO.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/order/app/vo/res/OrderShardExplainResVO.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderShardExplainCmdExe.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/order/app/controller/OrderShardController.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/order/OrderShardExplainCmdExeTest.java`

**Interfaces:**
- Consumes: `OrderShardGene`；`CommonErrorCodeEnum.PARAM_MISSING`
- Produces: `OrderShardExplainCmdExe.execute(OrderShardExplainReqVO): OrderShardExplainResVO`；`POST /demo/orders/shardExplain` 返回 `JsonResult`；**无** `@LoginRequired`

两个 ID 都空（含请求体 null 字段）时 **CmdExe** 抛 `BusinessException(PARAM_MISSING)`，不要用 `@AssertTrue`（会变成 10001）。

- [ ] **Step 1: 写失败测试**

```java
package com.jason.demo.demo2.order;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.framework.web.exception.CommonErrorCodeEnum;
import com.jason.demo.demo2.order.app.executor.OrderShardExplainCmdExe;
import com.jason.demo.demo2.order.app.vo.req.OrderShardExplainReqVO;
import com.jason.demo.demo2.order.app.vo.res.OrderShardExplainResVO;
import com.jason.demo.demo2.order.service.common.OrderShardSourceEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderShardExplainCmdExeTest {

    private final OrderShardExplainCmdExe exe = new OrderShardExplainCmdExe();

    @Test
    void empty_throwsParamMissing() {
        BusinessException ex = assertThrows(BusinessException.class, () -> exe.execute(new OrderShardExplainReqVO()));
        assertEquals(CommonErrorCodeEnum.PARAM_MISSING.getCode(), ex.getCode());
    }

    @Test
    void memberOnly() {
        OrderShardExplainReqVO req = new OrderShardExplainReqVO();
        req.setMemberId(612L);
        OrderShardExplainResVO res = exe.execute(req);
        assertEquals(100L, res.getVirtual());
        assertEquals("001100100", res.getGeneBits());
        assertEquals("order_ds_0", res.getDs());
        assertEquals("demo_order_18", res.getTable());
        assertEquals("demo_order_item_18", res.getItemTable());
        assertEquals(OrderShardSourceEnum.MEMBER_ID.name(), res.getSource());
        assertEquals(100L, res.getMemberVirtual());
        assertNull(res.getOrderVirtual());
        assertNull(res.getGeneMatch());
    }

    @Test
    void orderOnly() {
        OrderShardExplainReqVO req = new OrderShardExplainReqVO();
        req.setOrderId((99L << 9) | 100L);
        OrderShardExplainResVO res = exe.execute(req);
        assertEquals(OrderShardSourceEnum.ORDER_ID.name(), res.getSource());
        assertEquals(100L, res.getOrderVirtual());
        assertNull(res.getMemberVirtual());
        assertEquals("demo_order_18", res.getTable());
    }

    @Test
    void both_matchAndMismatch() {
        OrderShardExplainReqVO match = new OrderShardExplainReqVO();
        match.setMemberId(612L);
        match.setOrderId((1L << 9) | 100L);
        OrderShardExplainResVO ok = exe.execute(match);
        assertEquals(OrderShardSourceEnum.MEMBER_ID.name(), ok.getSource());
        assertTrue(ok.getGeneMatch());
        assertEquals("demo_order_18", ok.getTable());

        OrderShardExplainReqVO bad = new OrderShardExplainReqVO();
        bad.setMemberId(612L);
        bad.setOrderId((1L << 9) | 7L);
        OrderShardExplainResVO res = exe.execute(bad);
        assertEquals(Boolean.FALSE, res.getGeneMatch());
        assertEquals("demo_order_18", res.getTable());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -Dtest=OrderShardExplainCmdExeTest test`

Expected: FAIL，找不到类

- [ ] **Step 3: 实现枚举、VO、CmdExe、Controller**

```java
package com.jason.demo.demo2.order.service.common;

public enum OrderShardSourceEnum {
    MEMBER_ID,
    ORDER_ID
}
```

```java
package com.jason.demo.demo2.order.app.vo.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "分片路由试算，orderId 与 memberId 至少填一个")
public class OrderShardExplainReqVO {

    @Schema(description = "订单 ID（拆低 9 位基因）")
    private Long orderId;

    @Schema(description = "会员 ID")
    private Long memberId;
}
```

```java
package com.jason.demo.demo2.order.app.vo.res;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "分片路由试算结果")
public class OrderShardExplainResVO {

    @Schema(description = "用于展示的 virtual（两边都有时取 member）")
    private Long virtual;

    @Schema(description = "9 位二进制")
    private String geneBits;

    @Schema(description = "目标库", example = "order_ds_0")
    private String ds;

    @Schema(description = "主表", example = "demo_order_18")
    private String table;

    @Schema(description = "明细表", example = "demo_order_item_18")
    private String itemTable;

    @Schema(description = "MEMBER_ID 或 ORDER_ID")
    private String source;

    @Schema(description = "由 memberId 算出的 virtual")
    private Long memberVirtual;

    @Schema(description = "由 orderId 拆出的 virtual")
    private Long orderVirtual;

    @Schema(description = "两边都有时是否同一 virtual")
    private Boolean geneMatch;
}
```

```java
package com.jason.demo.demo2.order.app.executor;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.framework.web.exception.CommonErrorCodeEnum;
import com.jason.demo.demo2.order.app.vo.req.OrderShardExplainReqVO;
import com.jason.demo.demo2.order.app.vo.res.OrderShardExplainResVO;
import com.jason.demo.demo2.order.service.common.OrderShardSourceEnum;
import com.jason.demo.demo2.order.service.infrastructure.shard.OrderShardGene;
import org.springframework.stereotype.Service;

@Service
public class OrderShardExplainCmdExe {

    public OrderShardExplainResVO execute(OrderShardExplainReqVO req) {
        Long memberId = req == null ? null : req.getMemberId();
        Long orderId = req == null ? null : req.getOrderId();
        boolean hasMember = memberId != null;
        boolean hasOrder = orderId != null;
        if (!hasMember && !hasOrder) {
            throw new BusinessException(CommonErrorCodeEnum.PARAM_MISSING);
        }
        Long memberVirtual = hasMember ? OrderShardGene.virtualOfMember(memberId) : null;
        Long orderVirtual = hasOrder ? OrderShardGene.virtualOfOrderId(orderId) : null;
        long virtual = hasMember ? memberVirtual : orderVirtual;
        OrderShardSourceEnum source = hasMember
                ? OrderShardSourceEnum.MEMBER_ID
                : OrderShardSourceEnum.ORDER_ID;
        OrderShardExplainResVO res = new OrderShardExplainResVO();
        res.setVirtual(virtual);
        res.setGeneBits(OrderShardGene.geneBits(virtual));
        res.setDs(OrderShardGene.dsName(virtual));
        res.setTable(OrderShardGene.orderTableName(virtual));
        res.setItemTable(OrderShardGene.itemTableName(virtual));
        res.setSource(source.name());
        res.setMemberVirtual(memberVirtual);
        res.setOrderVirtual(orderVirtual);
        if (hasMember && hasOrder) {
            res.setGeneMatch(memberVirtual.equals(orderVirtual));
        }
        return res;
    }
}
```

```java
package com.jason.demo.demo2.order.app.controller;

import com.jason.demo.demo2.framework.web.result.JsonResult;
import com.jason.demo.demo2.framework.web.result.JsonResults;
import com.jason.demo.demo2.order.app.executor.OrderShardExplainCmdExe;
import com.jason.demo.demo2.order.app.vo.req.OrderShardExplainReqVO;
import com.jason.demo.demo2.order.app.vo.res.OrderShardExplainResVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "订单分片")
@RestController
@RequestMapping("/demo/orders")
public class OrderShardController {

    private final OrderShardExplainCmdExe orderShardExplainCmdExe;

    public OrderShardController(OrderShardExplainCmdExe orderShardExplainCmdExe) {
        this.orderShardExplainCmdExe = orderShardExplainCmdExe;
    }

    @Operation(summary = "分片路由试算", description = "不登录、不查库。orderId 与 memberId 至少填一个")
    @PostMapping("/shardExplain")
    public JsonResult<OrderShardExplainResVO> shardExplain(@RequestBody(required = false) OrderShardExplainReqVO request) {
        return JsonResults.ok(orderShardExplainCmdExe.execute(request));
    }
}
```

不要把该方法塞进带 `@LoginRequired` 的 `OrderController`。

- [ ] **Step 4: 再跑测试**

Run: `mvn -Dtest=OrderShardExplainCmdExeTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/order/service/common/OrderShardSourceEnum.java demo2/src/main/java/com/jason/demo/demo2/order/app/vo/req/OrderShardExplainReqVO.java demo2/src/main/java/com/jason/demo/demo2/order/app/vo/res/OrderShardExplainResVO.java demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderShardExplainCmdExe.java demo2/src/main/java/com/jason/demo/demo2/order/app/controller/OrderShardController.java demo2/src/test/java/com/jason/demo/demo2/order/OrderShardExplainCmdExeTest.java
git commit -m "feat(order): add shardExplain debug API"
```

---

### Task 5: 下单改用 OrderIdGenerator

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderPlaceCmdExe.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/order/OrderPlaceCmdExeTest.java`

**Interfaces:**
- Consumes: `OrderIdGenerator.nextOrderId(memberId)`；`SnowflakeIdGenerator.nextId()` 仅用于 `itemId`
- Produces: 下单 `orderId` 已嵌基因；其它 CmdExe 不改

- [ ] **Step 1: 改测试构造与 success stub**

`OrderPlaceCmdExeTest`：

- 增加 `@Mock OrderIdGenerator orderIdGenerator;`
- `newExe()` 在 `delayTaskService` 之后传入 `orderIdGenerator`，再传 `idGenerator`
- `success_schedulesCancelAndSavesResult` / `success_usesConfiguredDefaultDelay`：`when(orderIdGenerator.nextOrderId(9001L)).thenReturn(55L);`；`when(idGenerator.nextId()).thenReturn(66L);`（只发 itemId）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -Dtest=OrderPlaceCmdExeTest test`

Expected: FAIL（构造参数个数 / `nextId` 被多调）

- [ ] **Step 3: 改 `OrderPlaceCmdExe`**

字段与构造增加 `OrderIdGenerator orderIdGenerator`，保留 `SnowflakeIdGenerator idGenerator`。

把 `long orderId = idGenerator.nextId();` 换成：

```java
            long orderId = orderIdGenerator.nextOrderId(memberId);
```

`OrderItem.create` 的 `itemId` 仍是 `idGenerator.nextId()`。

- [ ] **Step 4: 再跑测试**

Run: `mvn -Dtest=OrderPlaceCmdExeTest,OrderShardExplainCmdExeTest,OrderIdGeneratorTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderPlaceCmdExe.java demo2/src/test/java/com/jason/demo/demo2/order/OrderPlaceCmdExeTest.java
git commit -m "feat(order): generate order ids with member gene"
```

---

### Task 6: YAML、数据源、建表脚本

**Files:**
- Create: `demo2/src/main/resources/shardingsphere.yaml`
- Create: `demo2/src/main/resources/db/order-shard-schema.sql`
- Modify: `demo2/src/main/resources/application.properties`（约 35–38 行数据源段）

**Interfaces:**
- Consumes: `OrderComplexShardingAlgorithm` 全限定名
- Produces: 启动后逻辑库经 SS 访问；`demo_order*` 进分片；其它表进 `ds_default`

YAML 密码与现网默认一致 `123456`。SS yaml **不解析** `application.properties` 的 `${DB_PASSWORD}`；本机密码不是 123456 时改 yaml 三处 `password`。

- [ ] **Step 1: 写 `shardingsphere.yaml`**

jdbcUrl 查询串与现网一致：`useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true`

```yaml
databaseName: demo2_logic

dataSources:
  ds_default:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.cj.jdbc.Driver
    jdbcUrl: jdbc:mysql://127.0.0.1:3306/spring_ai_agent2?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: 123456
  order_ds_0:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.cj.jdbc.Driver
    jdbcUrl: jdbc:mysql://127.0.0.1:3306/order_ds_0?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: 123456
  order_ds_1:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.cj.jdbc.Driver
    jdbcUrl: jdbc:mysql://127.0.0.1:3306/order_ds_1?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: 123456

rules:
  - !SHARDING
    tables:
      demo_order:
        actualDataNodes: order_ds_$->{0..1}.demo_order_$->{0..31}
        databaseStrategy:
          complex:
            shardingColumns: member_id,order_id
            shardingAlgorithmName: order-complex
        tableStrategy:
          complex:
            shardingColumns: member_id,order_id
            shardingAlgorithmName: order-complex
      demo_order_item:
        actualDataNodes: order_ds_$->{0..1}.demo_order_item_$->{0..31}
        databaseStrategy:
          complex:
            shardingColumns: member_id,order_id
            shardingAlgorithmName: order-complex
        tableStrategy:
          complex:
            shardingColumns: member_id,order_id
            shardingAlgorithmName: order-complex
    bindingTables:
      - demo_order,demo_order_item
    defaultDataSourceName: ds_default
    shardingAlgorithms:
      order-complex:
        type: CLASS_BASED
        props:
          strategy: COMPLEX
          algorithmClassName: com.jason.demo.demo2.order.service.infrastructure.shard.OrderComplexShardingAlgorithm

props:
  sql-show: true
```

库策略和表策略共用同一个 CLASS_BASED 算法：`availableTargetNames` 分别为 `order_ds_*` 与 `demo_order_*` / `demo_order_item_*`。

- [ ] **Step 2: 改 `application.properties` 数据源**

把：

```properties
spring.datasource.url=jdbc:mysql://127.0.0.1:3306/spring_ai_agent2?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
spring.datasource.username=root
spring.datasource.password=${DB_PASSWORD:123456}
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
```

换成：

```properties
spring.datasource.driver-class-name=org.apache.shardingsphere.driver.ShardingSphereDriver
spring.datasource.url=jdbc:shardingsphere:classpath:shardingsphere.yaml
```

删掉这两行 `username` / `password`（物理库账密只在 yaml）。Hikari 池参数（`maximum-pool-size` 等）可保留，它们包的是 SS Driver。

不要改 `app.product.stock.redis-hot-enabled=true`。

- [ ] **Step 3: 写 `order-shard-schema.sql`**

用存储过程循环建 128 张表，列为现网主表/明细的完整拷贝。文件头注释：在 MySQL 用 root 执行一次；不 DROP `spring_ai_agent2.demo_order*`。

```sql
CREATE DATABASE IF NOT EXISTS order_ds_0 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS order_ds_1 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

DROP PROCEDURE IF EXISTS demo2_create_order_shards;
DELIMITER $$
CREATE PROCEDURE demo2_create_order_shards()
BEGIN
  DECLARE i INT DEFAULT 0;
  DECLARE d INT DEFAULT 0;
  DECLARE dbn VARCHAR(32);
  DECLARE ddl TEXT;
  WHILE d < 2 DO
    SET dbn = CONCAT('order_ds_', d);
    SET i = 0;
    WHILE i < 32 DO
      SET ddl = CONCAT(
        'CREATE TABLE IF NOT EXISTS `', dbn, '`.`demo_order_', i, '` (',
        'order_id BIGINT NOT NULL COMMENT ''订单ID（雪花+9bit基因）'',',
        'member_id BIGINT NOT NULL COMMENT ''下单会员ID（分片键）'',',
        'order_status VARCHAR(32) NOT NULL COMMENT ''SUBMIT/COMPLETED/CANCEL'',',
        'pay_status VARCHAR(32) NOT NULL COMMENT ''WAIT_PAY/PAY_SUCCESS/CLOSE'',',
        'amount DECIMAL(12,2) NOT NULL,',
        'pay_time DATETIME(3) NULL,',
        'cancel_time DATETIME(3) NULL,',
        'created_at DATETIME(3) NOT NULL,',
        'updated_at DATETIME(3) NOT NULL,',
        'PRIMARY KEY (order_id),',
        'INDEX idx_demo_order_member_status_time (member_id, order_status, created_at)',
        ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=''演示订单主表分片'''
      );
      SET @ddl = ddl;
      PREPARE stmt FROM @ddl;
      EXECUTE stmt;
      DEALLOCATE PREPARE stmt;

      SET ddl = CONCAT(
        'CREATE TABLE IF NOT EXISTS `', dbn, '`.`demo_order_item_', i, '` (',
        'id BIGINT NOT NULL AUTO_INCREMENT,',
        'item_id BIGINT NOT NULL COMMENT ''明细业务ID（普通雪花）'',',
        'order_id BIGINT NOT NULL,',
        'member_id BIGINT NOT NULL COMMENT ''会员ID（与主表同分片）'',',
        'product_id BIGINT NOT NULL,',
        'product_name VARCHAR(128) NOT NULL,',
        'subtitle VARCHAR(255) NOT NULL DEFAULT '''',',
        'cover_url VARCHAR(512) NULL,',
        'sell_price DECIMAL(10,2) NOT NULL,',
        'market_price DECIMAL(10,2) NULL,',
        'qty INT UNSIGNED NOT NULL DEFAULT 1,',
        'created_at DATETIME(3) NOT NULL,',
        'PRIMARY KEY (id),',
        'UNIQUE KEY uk_demo_order_item_item_id (item_id),',
        'INDEX idx_demo_order_item_order (order_id),',
        'UNIQUE KEY uk_demo_order_item_order_product (order_id, product_id),',
        'INDEX idx_demo_order_item_member_order (member_id, order_id)',
        ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=''演示订单明细分片'''
      );
      SET @ddl = ddl;
      PREPARE stmt FROM @ddl;
      EXECUTE stmt;
      DEALLOCATE PREPARE stmt;
      SET i = i + 1;
    END WHILE;
    SET d = d + 1;
  END WHILE;
END$$
DELIMITER ;

CALL demo2_create_order_shards();
DROP PROCEDURE IF EXISTS demo2_create_order_shards;
```

- [ ] **Step 4: 本机执行脚本（无 MySQL 则记下，不阻塞单测）**

```bash
mysql -uroot -p123456 < demo2/src/main/resources/db/order-shard-schema.sql
```

Expected: 无报错；`SHOW TABLES FROM order_ds_0;` 含 `demo_order_0`～`31` 与 item 表。

- [ ] **Step 5: 单测仍全绿（不依赖新库）**

Run: `mvn -Dtest=OrderShardGeneTest,OrderIdGeneratorTest,OrderComplexShardingAlgorithmTest,OrderShardExplainCmdExeTest,OrderPlaceCmdExeTest test`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add demo2/src/main/resources/shardingsphere.yaml demo2/src/main/resources/db/order-shard-schema.sql demo2/src/main/resources/application.properties
git commit -m "feat(order): wire ShardingSphereDriver and shard schema script"
```

---

### Task 7: 会员页分片调试台

**Files:**
- Modify: `demo2/src/main/resources/static/index.html`（`member-side-panel` 里「查询 / 台账」卡片**上方**）
- Modify: `demo2/src/main/resources/static/js/tabs/member.js`

**Interfaces:**
- Consumes: `POST /demo/orders/shardExplain`；`memberOrderLastOrderId`；`memberRequest`
- Produces: 输入 orderId 或 memberId → 展示 virtual / 9 位二进制 / 库 / 主表 / 明细表 / source / geneMatch；下单成功回填 orderId

- [ ] **Step 1: 在「查询 / 台账」卡片前插入**

```html
                <div class="card">
                    <div class="card-title">分片调试</div>
                    <div class="card-body">
                        <label for="memberShardOrderId">orderId</label>
                        <input id="memberShardOrderId" type="text" placeholder="可只填订单号">
                        <label for="memberShardMemberId">memberId</label>
                        <input id="memberShardMemberId" type="text" placeholder="可只填会员 ID">
                        <div class="member-side-actions">
                            <button type="button" class="btn" onclick="memberShardExplain()">计算路由</button>
                        </div>
                        <div id="memberShardResult" class="result-box member-order-result">输入 orderId 或 memberId 后计算</div>
                    </div>
                </div>
```

- [ ] **Step 2: `member.js` 增加函数，并在下单成功处回填**

在 `memberFillOrderId` 旁增加：

```javascript
function memberShardExplain() {
    const orderIdRaw = (document.getElementById('memberShardOrderId').value || '').trim();
    const memberIdRaw = (document.getElementById('memberShardMemberId').value || '').trim();
    const body = {};
    if (orderIdRaw) {
        body.orderId = orderIdRaw;
    }
    if (memberIdRaw) {
        body.memberId = memberIdRaw;
    }
    const resultBox = document.getElementById('memberShardResult');
    memberRequest('/demo/orders/shardExplain', body).then(function (data) {
        resultBox.textContent = JSON.stringify(data, null, 2);
        memberAppendLog('分片 ' + data.ds + '.' + data.table + ' virtual=' + data.virtual);
    }).catch(function (e) {
        resultBox.textContent = e.message || String(e);
    });
}

function memberFillShardOrderId(orderId) {
    const input = document.getElementById('memberShardOrderId');
    if (input) {
        input.value = memberSnowflakeId(orderId);
    }
}
```

`memberPlaceSubmit` 里 `memberOrderLastOrderId = memberSnowflakeId(data.orderId);` 之后立刻调用 `memberFillShardOrderId(memberOrderLastOrderId);`

`memberFillOrderId` 末尾同样调用 `memberFillShardOrderId(memberOrderLastOrderId);`

orderId 用字符串传，避免 JS 精度丢失；后端 Jackson 可把数字字符串绑到 `Long`。

- [ ] **Step 3: Commit**

```bash
git add demo2/src/main/resources/static/index.html demo2/src/main/resources/static/js/tabs/member.js
git commit -m "feat(order): add shard routing debug card on member tab"
```

---

## 手工验收（Task 6 脚本已执行且应用能连上 MySQL 后）

1. `mvn -Dtest=OrderShardGeneTest,OrderIdGeneratorTest,OrderComplexShardingAlgorithmTest,OrderShardExplainCmdExeTest,OrderPlaceCmdExeTest test` 全绿。
2. 启动 demo2；热库存保持开启。
3. 登录会员下单，记下 `orderId`。
4. 右侧「计算路由」只填该 `orderId`：`ds`/`table` 与日志 `order shard route` 及 `sql-show` 改写 SQL 一致。
5. 只填该 `memberId`：同一库表。
6. 详情 / 支付 / 取消 / 超时关单：日志单库单表，无 64 表广播。
7. 列表 / 计数仍按登录会员。
8. C 端下单/支付/列表字段不变。

---

## 实施后文档（最后一个 commit）

- spec 状态已改为「已实现」。
- 归档：`docs/superpowers/archive/2026-08-30-order-sharding-gene.md`。
