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

仅当用户要求提交时执行。本次不要提交。
