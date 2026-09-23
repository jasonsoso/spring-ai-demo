- Consumes: 鐜版湁 `GENE_MASK`銆乣dsIndex`銆乣tableIndex`銆乣geneBits`銆乣dsName`銆乣orderTableName`
- Produces: `public static long virtualOfMember(long memberId)`锛岃繑鍥?`0..511`

- [ ] **Step 1: 鎶婂け璐ユ祴璇曞啓杩?`OrderShardGeneTest`**

鏇挎崲 `virtual612_routesToDs0Table18` 涓?`boundaries_zeroAnd511` 閲屽 `virtualOfMember` 鐨勬柇瑷€銆俙dsIndex(0L)`銆乣dsIndex(511L)`銆乣geneBits(511L)` 娴嬬殑鏄師濮嬫Ы浣嶆媶娉曪紝涓嶈鏀广€?
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

- [ ] **Step 2: 璺戞祴璇曪紝纭澶辫触**

鍦?`demo2` 鐩綍锛?
```powershell
mvn test "-Dtest=OrderShardGeneTest" -q
```

Expected: FAIL銆俙virtualOfMember(612L)` 浠嶆槸 `100`锛屼笉鏄?`61`銆?
- [ ] **Step 3: 瀹炵幇鍝堝笇**

鎶?`virtualOfMember` 鎹㈡垚涓嬮潰鐨勬柟娉曪紝骞跺姞涓婁笁涓鏈夋柟娉曘€傜被涓婂凡鏈夌殑銆岀姝?`table = virtual % 32`銆嶆敞閲婁繚鐣欍€?
```java
/**
 * MurmurHash3 鍚庡啀鍙栦綆 9 浣嶃€傜瀛愩€佺搴忋€佸父鏁板啓姝伙紝鏀逛簡宸插彂鍑虹殑璁㈠崟鍙蜂細瀵逛笉涓婁細鍛樸€? *
 * <p>涓嶈鍐欐垚 {@code memberId % 512}銆侶utool 闆姳鏈€浣?12 浣嶆槸搴忓彿锛岄€愪釜娉ㄥ唽鏃跺簭鍙锋亽涓?0锛? * 鍙栦綆浣嶄細鎶婅鍗曞叏閮ㄦ墦杩?{@code order_ds_0.demo_order_0}銆? */
public static long virtualOfMember(long memberId) {
    return murmur3(memberId) & GENE_MASK;
}

/** x86 32 浣嶏紝绉嶅瓙 0銆備細鍛樺彿灏忕 8 瀛楄妭锛氫綆 32 浣嶅湪鍓嶃€?*/
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

- [ ] **Step 4: 鍐嶈窇 `OrderShardGeneTest`**

```powershell
mvn test "-Dtest=OrderShardGeneTest" -q
```

Expected: PASS锛? 涓祴璇曪級銆?
- [ ] **Step 5: Commit**

浠呭綋鐢ㄦ埛瑕佹眰鎻愪氦鏃讹細

```powershell
git add demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/shard/OrderShardGene.java demo2/src/test/java/com/jason/demo/demo2/order/OrderShardGeneTest.java
git commit -m "fix(order): hash member id into shard slot with MurmurHash3"
```

---

### Task 2: 鍙戝彿銆佽皟璇曞拰鍒嗙墖娴嬭瘯璺熶笂鏂版Ы浣?
**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/shard/OrderComplexShardingAlgorithm.java`锛堢害绗?50 琛屾敞閲婏級
- Test: `demo2/src/test/java/com/jason/demo/demo2/order/OrderIdGeneratorTest.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/order/OrderShardExplainCmdExeTest.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/order/OrderComplexShardingAlgorithmTest.java`

**Interfaces:**
- Consumes: `OrderShardGene.virtualOfMember(long)`锛屼細鍛?`612` 鈫?`61`锛屼細鍛?`1` 鈫?`324`
- Produces: 鏃犳柊鏂规硶銆傚彧甯?`orderId` 涓斾綆 9 浣嶄负 100 鏃朵粛鏄?`order_ds_0` / `demo_order_18`

- [ ] **Step 1: 鏀?`OrderIdGeneratorTest` 鐨勫熀鍥犳柇瑷€**
