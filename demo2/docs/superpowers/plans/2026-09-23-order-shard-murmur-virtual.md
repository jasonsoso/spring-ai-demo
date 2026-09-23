# 订单分片槽位改为 MurmurHash3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 会员号仍是 Hutool 雪花，槽位改为 MurmurHash3 再取低 9 位，避免序号恒为 0 时订单全进 `order_ds_0.demo_order_0`。

**Architecture:** 只改 `OrderShardGene.virtualOfMember`。哈希是 x86 32 位 MurmurHash3，种子 0，会员号小端 8 字节，结果 `& 0x1FF`。库表拆法和 `virtualOfOrderId` 不动。`OrderIdGenerator` 已经调用 `virtualOfMember`，发号不用改。

**Tech Stack:** Java 21、JUnit 5、Maven。不新增依赖。

**Spec:** [2026-09-23-order-shard-murmur-virtual-design.md](../specs/2026-09-23-order-shard-murmur-virtual-design.md)

## Global Constraints

- 会员号继续 `SnowflakeIdGenerator.nextId()`，不把槽位写进会员号
- 哈希写死在 `OrderShardGene`：MurmurHash3_x86_32，seed `0`，小端 8 字节，`c1 = 0xcc9e2d51`，`c2 = 0x1b873593`
- `virtual = murmur3(memberId) & 0x1FF`；禁止 Java `%`（哈希 `int` 可能为负）
- 只拿订单号时仍 `orderId & 0x1FF`
- `ds = virtual % 2`，`table = (virtual / 2) % 32`；禁止 `table = virtual % 32`
- 不改 `shardingsphere.yaml`，不换成 `HASH_MOD`
- 不迁移、不清表、不改历史订单号
- 不改 `docs/superpowers/archive/` 与历史 plan
- 在 `demo2` 目录跑测；PowerShell 里 `-Dtest=` 必须加引号
- 每步末尾的 commit 仅在用户要求提交时执行

---

## File Structure

| 文件 | 职责 |
|------|------|
| `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/shard/OrderShardGene.java` | `virtualOfMember` 改为 MurmurHash3；私有 `murmur3` / `mix` / `fmix` |
| `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/shard/OrderComplexShardingAlgorithm.java` | 只改「`memberId % 512`」那行注释 |
| `demo2/src/test/java/com/jason/demo/demo2/order/OrderShardGeneTest.java` | 槽位向量、加 1 毫秒必须换槽 |
| `demo2/src/test/java/com/jason/demo/demo2/order/OrderIdGeneratorTest.java` | 订单号低 9 位等于新槽位 |
| `demo2/src/test/java/com/jason/demo/demo2/order/OrderShardExplainCmdExeTest.java` | 会员 `612` 的调试结果 |
| `demo2/src/test/java/com/jason/demo/demo2/order/OrderComplexShardingAlgorithmTest.java` | 会员路由到新库表；只带订单号仍走旧基因 |
| `demo2/README.md` | 两处基因公式 |
| `demo2/docs/superpowers/specs/2026-08-30-order-sharding-gene-design.md` | 公式段改为指向本算法 |
| `demo2/docs/superpowers/specs/2026-08-31-order-id-bit-layout-design.md` | 基因来源从 `% 512` 改为 MurmurHash3 |
| `demo2/docs/superpowers/specs/2026-09-23-order-shard-murmur-virtual-design.md` | 状态改为已实现 |

`bothDatabasesUseAll32Tables` 与 `wrongModulo32_wouldLeaveOddTablesEmptyOnOneDs` 保持原样。`0..511` 经 MurmurHash3 后两库仍各覆盖 32 张表。

---

### Task 1: 槽位改为 MurmurHash3

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/shard/OrderShardGene.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/order/OrderShardGeneTest.java`

**Interfaces:**
- Consumes: 现有 `GENE_MASK`、`dsIndex`、`tableIndex`、`geneBits`、`dsName`、`orderTableName`
- Produces: `public static long virtualOfMember(long memberId)`，返回 `0..511`

- [ ] **Step 1: 把失败测试写进 `OrderShardGeneTest`**

替换 `virtual612_routesToDs0Table18` 与 `boundaries_zeroAnd511` 里对 `virtualOfMember` 的断言。`dsIndex(0L)`、`dsIndex(511L)`、`geneBits(511L)` 测的是原始槽位拆法，不要改。

```java
@Test
void virtual612_routesToDs1Table30() {
    long virtual = OrderShardGene.virtualOfMember(612L);
    assertEquals(61L, virtual);
    assertEquals(100L, OrderShardGene.virtualOfOrderId((55L << 9) | 100L));
    assertEquals(1, OrderShardGene.dsIndex(virtual));
    assertEquals(30, OrderShardGene.tableIndex(virtual));
    assertEquals("000111101", OrderShardGene.geneBits(virtual));
    assertEquals("order_ds_1", OrderShardGene.dsName(virtual));
    assertEquals("demo_order_30", OrderShardGene.orderTableName(virtual));
    assertEquals("demo_order_item_30", OrderShardGene.itemTableName(virtual));
}

@Test
void boundaries_zeroAnd511() {
    assertEquals(252L, OrderShardGene.virtualOfMember(0L));
    assertEquals(0, OrderShardGene.dsIndex(0L));
    assertEquals(0, OrderShardGene.tableIndex(0L));
    assertEquals(60L, OrderShardGene.virtualOfMember(511L));
    assertEquals(1, OrderShardGene.dsIndex(511L));
    assertEquals(255 % 32, OrderShardGene.tableIndex(511L));
    assertEquals("111111111", OrderShardGene.geneBits(511L));
}

@Test
void snowflakeZeroSequence_plusOneMillisChangesSlot() {
    long first = 0x1D090DD4C4000000L;
    long second = 0x1D090E2966800000L;
    long plusOneMillis = first + (1L << 22);
    assertEquals(44L, OrderShardGene.virtualOfMember(first));
    assertEquals("order_ds_0", OrderShardGene.dsName(OrderShardGene.virtualOfMember(first)));
    assertEquals("demo_order_22", OrderShardGene.orderTableName(OrderShardGene.virtualOfMember(first)));
    assertEquals(23L, OrderShardGene.virtualOfMember(second));
    assertEquals("order_ds_1", OrderShardGene.dsName(OrderShardGene.virtualOfMember(second)));
    assertEquals("demo_order_11", OrderShardGene.orderTableName(OrderShardGene.virtualOfMember(second)));
    assertEquals(487L, OrderShardGene.virtualOfMember(plusOneMillis));
    assertNotEquals(OrderShardGene.virtualOfMember(first), OrderShardGene.virtualOfMember(plusOneMillis));
}
```

- [ ] **Step 2: 跑测试，确认失败**

在 `demo2` 目录：

```powershell
mvn test "-Dtest=OrderShardGeneTest" -q
```

Expected: FAIL。`virtualOfMember(612L)` 仍是 `100`，不是 `61`。

- [ ] **Step 3: 实现哈希**

把 `virtualOfMember` 换成下面的方法，并加上三个私有方法。类上已有的「禁止 `table = virtual % 32`」注释保留。

```java
/**
 * MurmurHash3 后再取低 9 位。种子、端序、常数写死，改了已发出的订单号会对不上会员。
 *
 * <p>不要写成 {@code memberId % 512}。Hutool 雪花最低 12 位是序号，逐个注册时序号恒为 0，
 * 取低位会把订单全部打进 {@code order_ds_0.demo_order_0}。
 */
public static long virtualOfMember(long memberId) {
    return murmur3(memberId) & GENE_MASK;
}

/** x86 32 位，种子 0。会员号小端 8 字节：低 32 位在前。 */
private static int murmur3(long value) {
    int hash = mix(0, (int) value);
    hash = mix(hash, (int) (value >>> 32));
    hash ^= 8;
    return fmix(hash);
}

private static int mix(int hash, int block) {
    final int c1 = 0xcc9e2d51;
    final int c2 = 0x1b873593;
    int k = block * c1;
    k = Integer.rotateLeft(k, 15);
    k *= c2;
    hash ^= k;
    hash = Integer.rotateLeft(hash, 13);
    return hash * 5 + 0xe6546b64;
}

private static int fmix(int hash) {
    hash ^= hash >>> 16;
    hash *= 0x85ebca6b;
    hash ^= hash >>> 13;
    hash *= 0xc2b2ae35;
    hash ^= hash >>> 16;
    return hash;
}
```

- [ ] **Step 4: 再跑 `OrderShardGeneTest`**

```powershell
mvn test "-Dtest=OrderShardGeneTest" -q
```

Expected: PASS（5 个测试）。

- [ ] **Step 5: Commit**

仅当用户要求提交时：

```powershell
git add demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/shard/OrderShardGene.java demo2/src/test/java/com/jason/demo/demo2/order/OrderShardGeneTest.java
git commit -m "fix(order): hash member id into shard slot with MurmurHash3"
```

---

### Task 2: 发号、调试和分片测试跟上新槽位

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/shard/OrderComplexShardingAlgorithm.java`（约第 50 行注释）
- Test: `demo2/src/test/java/com/jason/demo/demo2/order/OrderIdGeneratorTest.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/order/OrderShardExplainCmdExeTest.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/order/OrderComplexShardingAlgorithmTest.java`

**Interfaces:**
- Consumes: `OrderShardGene.virtualOfMember(long)`，会员 `612` → `61`，会员 `1` → `324`
- Produces: 无新方法。只带 `orderId` 且低 9 位为 100 时仍是 `order_ds_0` / `demo_order_18`

- [ ] **Step 1: 改 `OrderIdGeneratorTest` 的基因断言**

`nextOrderId_low9BitsMatchMemberVirtual` 与 `differentWorkers_sameMillisSeqGene_differ` 里两处 `100L` 改为 `61L`。`sequenceOverflow_waitsNextMillis` 末尾：

```java
assertEquals(324L, OrderShardGene.virtualOfOrderId(id));
```

序号位、机器位断言不动。

- [ ] **Step 2: 改 `OrderShardExplainCmdExeTest.memberOnly`**

```java
assertEquals(61L, res.getVirtual());
assertEquals("000111101", res.getGeneBits());
assertEquals("order_ds_1", res.getDs());
assertEquals("demo_order_30", res.getTable());
assertEquals("demo_order_item_30", res.getItemTable());
assertEquals(OrderShardSourceEnum.MEMBER_ID.name(), res.getSource());
assertEquals(61L, res.getMemberVirtual());
```

`orderOnly` 保持订单号低 9 位 `100`、表 `demo_order_18`。

`both_matchAndMismatch`：匹配用例的订单号改为 `(1L << 9) | 61L`，表改为 `demo_order_30`。不匹配用例的表也改为 `demo_order_30`（会员优先）。

- [ ] **Step 3: 改 `OrderComplexShardingAlgorithmTest`**

`memberIdOnly_routesDbAndTable`：

```java
assertEquals(List.of("order_ds_1"), algorithm.doSharding(List.of("order_ds_0", "order_ds_1"),
        value("demo_order", "member_id", 612L)));
assertEquals(List.of("demo_order_30"), algorithm.doSharding(orderTables(),
        value("demo_order", "member_id", 612L)));
assertEquals(List.of("demo_order_item_30"), algorithm.doSharding(itemTables(),
        value("demo_order_item", "member_id", 612L)));
```

`orderIdOnly_extractsGene` 保持基因 `100`、`order_ds_0`、`demo_order_18`。

`bothPresent_usesMemberId` 的表改为 `demo_order_30`。

`OrderComplexShardingAlgorithm` 循环内注释改为：

```java
// 会员：MurmurHash3 后取低 9 位；只有订单号：取低 9 位基因。同一个 virtual 再拆库和表。
```

- [ ] **Step 4: 跑四个测试类**

```powershell
mvn test "-Dtest=OrderShardGeneTest,OrderIdGeneratorTest,OrderShardExplainCmdExeTest,OrderComplexShardingAlgorithmTest" -q
```

Expected: PASS。`Tests run: 19`（基因 5 + 发号 6 + 调试 4 + 算法 4）。

- [ ] **Step 5: Commit**

仅当用户要求提交时：

```powershell
git add demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/shard/OrderComplexShardingAlgorithm.java demo2/src/test/java/com/jason/demo/demo2/order/OrderIdGeneratorTest.java demo2/src/test/java/com/jason/demo/demo2/order/OrderShardExplainCmdExeTest.java demo2/src/test/java/com/jason/demo/demo2/order/OrderComplexShardingAlgorithmTest.java
git commit -m "test(order): expect MurmurHash3 shard slots"
```

---

### Task 3: 改仍在用的公式说明

**Files:**
- Modify: `demo2/README.md`（约 1105、1110、1116、3422、3428 行）
- Modify: `demo2/docs/superpowers/specs/2026-08-30-order-sharding-gene-design.md` 第 2.2 节公式
- Modify: `demo2/docs/superpowers/specs/2026-08-31-order-id-bit-layout-design.md` 基因来源
- Modify: `demo2/docs/superpowers/specs/2026-09-23-order-shard-murmur-virtual-design.md` 状态行

**Interfaces:**
- Consumes: 槽位 `612 → 61 → order_ds_1.demo_order_30`
- Produces: 无代码

- [x] **Step 1: 改 `demo2/README.md` 基因公式**

「基因公式」一节换成：

```markdown
`virtual = MurmurHash3(memberId) & 0x1FF` 或 `orderId & 0x1FF`；`ds = virtual % 2`；`table = (virtual / 2) % 32`。哈希是 x86 32 位、种子 0、会员号小端 8 字节。禁止 `memberId % 512`：Hutool 雪花最低 12 位是序号，逐个注册时序号恒为 0，取低位会全部进 `order_ds_0.demo_order_0`。禁止 `table = virtual % 32`（2 与 32 不互质）。
```

图里 `M[memberId] -->|mod 512| V[virtual]` 改为 `M[memberId] -->|MurmurHash3 再取低 9 位| V[virtual]`。例子改为：`612` → 槽位 `61` → `order_ds_1.demo_order_30`。

第 34 节同样改箭头文字，例子改为 `612` → `order_ds_1.demo_order_30`，并写明禁止 `memberId % 512`。

- [x] **Step 2: 改 2026-08-30 公式段**

`2026-08-30-order-sharding-gene-design.md` 里：

```text
virtual = memberId % 512
        = orderId  & 0x1FF   // 只拿订单号时
```

换成：

```text
virtual = MurmurHash3_x86_32(memberId 小端 8 字节, seed 0) & 0x1FF
        = orderId & 0x1FF    // 只拿订单号时
```

紧接着加一句：禁止 `memberId % 512`，原因与 README 相同。同文件其余公式改为：

- 图：`M[memberId] -->|MurmurHash3 再取低 9 位| V[virtual 0..511]`
- 发号说明里的 `memberId % 512` 改为 `MurmurHash3(memberId) & 0x1FF`
- 表「有 `member_id`」改为 `` `MurmurHash3(memberId) & 0x1FF` ``
- 流程图 `virtual = memberId % 512` 改为 `virtual = MurmurHash3(memberId) AND 0x1FF`
- 调试示例 `612` 的响应：`virtual` / `memberVirtual` / `orderVirtual` 为 `61`，`geneBits` 为 `000111101`，`ds` 为 `order_ds_1`，`table` 为 `demo_order_30`，`itemTable` 为 `demo_order_item_30`。括注改为 `ds = 61 % 2 = 1`，`table = (61 / 2) % 32 = 30`

- [x] **Step 3: 改 2026-08-31 的基因来源**

`2026-08-31-order-id-bit-layout-design.md` 中基因行和发号式的 `memberId % 512` 改为 `MurmurHash3(memberId) & 0x1FF`。`virtual = orderId & 0x1FF`、41/5/8/9 位宽、每毫秒 256 个号不改。

把 `2026-09-23-order-shard-murmur-virtual-design.md` 的状态从「待实现」改为「已实现」，并加上本 plan 的链接。

- [x] **Step 4: 再跑四个测试类**

```powershell
mvn test "-Dtest=OrderShardGeneTest,OrderIdGeneratorTest,OrderShardExplainCmdExeTest,OrderComplexShardingAlgorithmTest" -q
```

Expected: PASS。文档改动不应让测试失败。

- [ ] **Step 5: Commit**

仅当用户要求提交时：

```powershell
git add demo2/README.md demo2/docs/superpowers/specs/2026-08-30-order-sharding-gene-design.md demo2/docs/superpowers/specs/2026-08-31-order-id-bit-layout-design.md demo2/docs/superpowers/specs/2026-09-23-order-shard-murmur-virtual-design.md demo2/docs/superpowers/plans/2026-09-23-order-shard-murmur-virtual.md
git commit -m "docs(order): record MurmurHash3 shard slot formula"
```
