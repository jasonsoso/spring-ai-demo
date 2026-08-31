# 订单号位图发号（机器 + 序号 + 基因）Implementation Plan

> **Status:** 已实现（代码落地见仓库；spec 状态已改「已实现」）。
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `OrderIdGenerator` 从「Hutool 雪花 + embed 盖低 9 位」改为自管位图 `[41 时间][5 机器][8 序号][9 基因]`，去掉撞号重试，保留 2×256 天花板与多机 `workerId` 隔离。

**Architecture:** 订单号不再调用 `SnowflakeIdGenerator`。`workerId` 取自现有 `SnowflakeNodeAllocator`（0～31）。发号进程内串行：同一毫秒 `seq` 0～255，满了等到下一毫秒；时钟回拨抛 `IllegalStateException`。`OrderShardGene` 删除 `embed`；拆基因 `& 0x1FF` 与分片算法不变。`itemId` 仍普通雪花。

**Tech Stack:** Java 21、Spring Boot 4.1、JUnit 5、现有 `SnowflakeNodeAllocator`

**Spec:** [2026-08-31-order-id-bit-layout-design.md](../specs/2026-08-31-order-id-bit-layout-design.md)

## Global Constraints

- 仅改 `demo2`；不改分片 YAML / 算法 / 建表 / HTTP / C 端报文
- 位图写死：`EPOCH=1288834974657L`，`WORKER_BITS=5`，`SEQ_BITS=8`，`GENE_BITS=9`（基因仍用 `OrderShardGene`）
- `orderId = ((now - EPOCH) << 22) | (workerId << 17) | (seq << 9) | (memberId % 512)`
- `workerId` ∈ [0, 31]；来自 `SnowflakeNodeAllocator.current().workerId()`
- **禁止**对雪花 `embed` / `while (id == lastId)` 撞号循环
- 单机每毫秒最多 **256** 单（共享序号，不按基因再乘）
- 时钟回拨：直接失败，不 sleep 追平
- 删除 `OrderShardGene.embed` 及对应单测
- PowerShell 跑测须给 `-Dtest=` 加引号；在 `demo2` 目录执行 `mvn`
- 不强制 commit；若用户要求再按任务末步提交

---

## File Structure

| 文件 | 职责 |
|------|------|
| `.../shard/OrderIdGenerator.java` | 位图发号；注入 allocator；单测用 `(workerId, LongSupplier)` |
| `.../shard/OrderShardGene.java` | 删除 `embed` |
| `.../shard/package-info.java` | 更新发号说明 |
| `.../order/OrderIdGeneratorTest.java` | 基因 / 唯一 / 机器隔离 / 序号溢出 / 回拨 |
| `.../order/OrderShardGeneTest.java` | 删除 `embed_replacesLow9BitsOnly` |
| `docs/.../specs/2026-08-31-order-id-bit-layout-design.md` | 状态 → 已实现 |
| `docs/.../archive/2026-08-30-order-sharding-gene.md` | 发号段落指向新 spec |
| `demo2/README.md` | 订单分片发号一句改为位图 |

不改：`OrderPlaceCmdExe`（仍调 `nextOrderId`）、`OrderPlaceCmdExeTest`（mock 生成器）、`OrderComplexShardingAlgorithm*`、`shardingsphere.yaml`。

---

### Task 1: 重写 OrderIdGenerator + 单测

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/shard/OrderIdGenerator.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/order/OrderIdGeneratorTest.java`

**Interfaces:**
- Consumes: `SnowflakeNodeAllocator.current().workerId()`；`OrderShardGene.virtualOfMember` / `GENE_MASK`
- Produces: `@Component OrderIdGenerator`；`long nextOrderId(long memberId)`；测试构造 `OrderIdGenerator(long workerId, LongSupplier wallClockMs)`

- [ ] **Step 1: 重写失败测试（替换整个 `OrderIdGeneratorTest`）**

```java
package com.jason.demo.demo2.order;

import com.jason.demo.demo2.order.service.infrastructure.shard.OrderIdGenerator;
import com.jason.demo.demo2.order.service.infrastructure.shard.OrderShardGene;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderIdGeneratorTest {

    private static final long EPOCH = 1288834974657L;

    @Test
    void nextOrderId_low9BitsMatchMemberVirtual() {
        AtomicLong now = new AtomicLong(EPOCH + 1_000_000L);
        OrderIdGenerator gen = new OrderIdGenerator(1L, now::get);
        long first = gen.nextOrderId(612L);
        long second = gen.nextOrderId(612L);
        assertEquals(100L, OrderShardGene.virtualOfOrderId(first));
        assertEquals(100L, OrderShardGene.virtualOfOrderId(second));
        assertNotEquals(first, second);
    }

    @Test
    void sameMillis_sequencesDifferInSeqBits() {
        AtomicLong now = new AtomicLong(EPOCH + 2_000_000L);
        OrderIdGenerator gen = new OrderIdGenerator(3L, now::get);
        long a = gen.nextOrderId(612L);
        long b = gen.nextOrderId(612L);
        assertEquals(now.get(), (a >> 22) + EPOCH);
        assertEquals(0L, (a >> 9) & 0xFFL);
        assertEquals(1L, (b >> 9) & 0xFFL);
        assertEquals(3L, (a >> 17) & 0x1FL);
    }

    @Test
    void differentWorkers_sameMillisSeqGene_differ() {
        long ts = EPOCH + 3_000_000L;
        OrderIdGenerator w1 = new OrderIdGenerator(1L, () -> ts);
        OrderIdGenerator w2 = new OrderIdGenerator(2L, () -> ts);
        long a = w1.nextOrderId(612L);
        long b = w2.nextOrderId(612L);
        assertEquals(100L, OrderShardGene.virtualOfOrderId(a));
        assertEquals(100L, OrderShardGene.virtualOfOrderId(b));
        assertNotEquals(a, b);
        assertEquals(1L, (a >> 17) & 0x1FL);
        assertEquals(2L, (b >> 17) & 0x1FL);
    }

    @Test
    void sequenceOverflow_waitsNextMillis() throws Exception {
        AtomicLong now = new AtomicLong(EPOCH + 4_000_000L);
        OrderIdGenerator gen = new OrderIdGenerator(1L, now::get);
        for (int i = 0; i < 256; i++) {
            gen.nextOrderId(1L);
        }
        Thread advancer = new Thread(() -> {
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            now.incrementAndGet();
        });
        advancer.start();
        long id = gen.nextOrderId(1L);
        advancer.join(1000);
        assertEquals(EPOCH + 4_000_001L, (id >> 22) + EPOCH);
        assertEquals(0L, (id >> 9) & 0xFFL);
        assertEquals(1L, OrderShardGene.virtualOfOrderId(id));
    }

    @Test
    void clockMovedBackward_throws() {
        AtomicLong now = new AtomicLong(EPOCH + 5_000_000L);
        OrderIdGenerator gen = new OrderIdGenerator(1L, now::get);
        gen.nextOrderId(1L);
        now.set(EPOCH + 4_000_000L);
        assertThrows(IllegalStateException.class, () -> gen.nextOrderId(1L));
    }

    @Test
    void manyIds_allUnique() {
        AtomicLong tick = new AtomicLong(0);
        OrderIdGenerator gen = new OrderIdGenerator(7L, () -> EPOCH + 6_000_000L + tick.getAndIncrement() / 200);
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(ids.add(gen.nextOrderId(612L)));
        }
        assertEquals(1000, ids.size());
    }
}
```

- [ ] **Step 2: 跑测确认失败**

Run（`demo2` 目录）:

```powershell
mvn "-Dtest=OrderIdGeneratorTest" test
```

Expected: FAIL（构造器 / 行为与旧实现不符）

- [ ] **Step 3: 实现 `OrderIdGenerator`**

完整替换为：

```java
package com.jason.demo.demo2.order.service.infrastructure.shard;

import com.jason.demo.demo2.framework.id.SnowflakeNodeAllocator;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * 订单号位图发号：{@code [41 时间][5 机器][8 序号][9 基因]}。
 * 不调用 {@link com.jason.demo.demo2.framework.id.SnowflakeIdGenerator}；{@code itemId} 仍走雪花。
 */
@Component
public class OrderIdGenerator {

    static final long EPOCH = 1288834974657L;

    private static final int WORKER_BITS = 5;
    private static final int SEQ_BITS = 8;
    private static final int GENE_BITS = OrderShardGene.GENE_BITS;
    private static final int WORKER_SHIFT = SEQ_BITS + GENE_BITS;
    private static final int TIMESTAMP_SHIFT = WORKER_BITS + WORKER_SHIFT;
    private static final long SEQ_MASK = (1L << SEQ_BITS) - 1L;
    private static final long MAX_WORKER = (1L << WORKER_BITS) - 1L;

    private final long workerId;
    private final LongSupplier wallClockMs;

    private long lastTimestamp = -1L;
    private long sequence;

    public OrderIdGenerator(SnowflakeNodeAllocator allocator) {
        this(allocator.current().workerId(), System::currentTimeMillis);
    }

    /** 单测：固定 worker + 可控时钟。 */
    public OrderIdGenerator(long workerId, LongSupplier wallClockMs) {
        if (workerId < 0 || workerId > MAX_WORKER) {
            throw new IllegalArgumentException("workerId out of range: " + workerId);
        }
        this.workerId = workerId;
        this.wallClockMs = wallClockMs;
    }

    public synchronized long nextOrderId(long memberId) {
        long gene = OrderShardGene.virtualOfMember(memberId);
        long now = wallClockMs.getAsLong();
        if (now < lastTimestamp) {
            throw new IllegalStateException(
                    "clock moved backward, last=" + lastTimestamp + ", now=" + now);
        }
        if (now == lastTimestamp) {
            sequence = (sequence + 1) & SEQ_MASK;
            if (sequence == 0L) {
                now = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = now;
        return ((now - EPOCH) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_SHIFT)
                | (sequence << GENE_BITS)
                | gene;
    }

    private long waitNextMillis(long last) {
        long now = wallClockMs.getAsLong();
        while (now <= last) {
            now = wallClockMs.getAsLong();
        }
        return now;
    }
}
```

注意：`TIMESTAMP_SHIFT = 5+8+9 = 22`，`WORKER_SHIFT = 8+9 = 17`，与 spec 一致。

- [ ] **Step 4: 再跑测**

```powershell
mvn "-Dtest=OrderIdGeneratorTest" test
```

Expected: PASS（`sequenceOverflow` 依赖短 sleep，若偶发失败可把 sleep 调到 50ms）

- [ ] **Step 5: Commit（若用户要求）**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/shard/OrderIdGenerator.java demo2/src/test/java/com/jason/demo/demo2/order/OrderIdGeneratorTest.java
git commit -m "feat(order): generate order ids with worker-seq-gene bit layout"
```

---

### Task 2: 删除 embed + 更新注释

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/shard/OrderShardGene.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/order/OrderShardGeneTest.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/shard/package-info.java`

**Interfaces:**
- Removes: `OrderShardGene.embed(long, long)`
- Keeps: `virtualOfMember` / `virtualOfOrderId` / 路由辅助

- [ ] **Step 1: 删掉 `OrderShardGeneTest.embed_replacesLow9BitsOnly` 整个方法**

- [ ] **Step 2: 从 `OrderShardGene` 删除 `embed` 方法**；类注释改为「只负责基因与路由公式；发号见 `OrderIdGenerator`」

- [ ] **Step 3: 更新 `package-info.java`**

```java
/**
 * 订单分库分表与订单号基因。
 *
 * <p>{@link OrderShardGene}：9 bit / 512 虚拟分片 / 2 库 / 32 表纯函数。
 * {@link OrderIdGenerator}：位图 {@code [41 时间][5 机器][8 序号][9 基因]}，低 9 位为基因。
 * {@link OrderComplexShardingAlgorithm}：有 member_id 用会员，只有 order_id 拆基因。
 *
 * <p>{@code ds = virtual % 2}，{@code table = (virtual / 2) % 32}。禁止 {@code table = virtual % 32}。
 */
package com.jason.demo.demo2.order.service.infrastructure.shard;
```

- [ ] **Step 4: 跑测**

```powershell
mvn "-Dtest=OrderShardGeneTest,OrderIdGeneratorTest,OrderPlaceCmdExeTest" test
```

Expected: PASS（Place 仍 mock 生成器）

- [ ] **Step 5: Commit（若用户要求）**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/shard/OrderShardGene.java demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/shard/package-info.java demo2/src/test/java/com/jason/demo/demo2/order/OrderShardGeneTest.java
git commit -m "refactor(order): remove snowflake gene embed helper"
```

---

### Task 3: 文档同步

**Files:**
- Modify: `demo2/docs/superpowers/specs/2026-08-31-order-id-bit-layout-design.md`（状态 → 已实现）
- Modify: `demo2/docs/superpowers/archive/2026-08-30-order-sharding-gene.md`（发号小节）
- Modify: `demo2/README.md`（订单分库分表「基因公式」段）

- [ ] **Step 1: spec 状态**

把文首改成：

```markdown
**状态**: 已实现  
```

并在文首加一行：归档说明可写在本文件变更记录或单独 archive；本阶段至少改状态。

- [ ] **Step 2: archive 发号段**

将 `2026-08-30-order-sharding-gene.md` 中「发号：`(rawSnowflake & ~0x1FF) | …` 撞号重试」一段改为：

```markdown
发号（2026-08-31 起）：位图 `[41 时间][5 机器][8 序号][9 基因]`，见
[2026-08-31-order-id-bit-layout-design.md](../specs/2026-08-31-order-id-bit-layout-design.md)。
低 9 位仍为基因；`itemId` 仍普通雪花。旧 embed 单只读仍可按基因路由。
```

变更记录表追加一行：`2026-08-31 | 订单号改为机器+序号+基因位图，去掉 embed 撞号重试`。

- [ ] **Step 3: README**

在「订单分库分表（基因法）」→「基因公式」中，把：

`发号 (raw & ~0x1FF) | virtual；同一毫秒撞号则重取雪花`

换成：

`发号 ((ts-epoch)<<22)|(worker<<17)|(seq<<9)|virtual；单机 256/ms，多机靠 workerId；itemId 仍普通雪花`

- [ ] **Step 4: 全量相关单测**

```powershell
mvn "-Dtest=OrderShardGeneTest,OrderIdGeneratorTest,OrderComplexShardingAlgorithmTest,OrderShardExplainCmdExeTest,OrderPlaceCmdExeTest" test
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit（若用户要求）**

```bash
git add demo2/docs/superpowers/specs/2026-08-31-order-id-bit-layout-design.md demo2/docs/superpowers/archive/2026-08-30-order-sharding-gene.md demo2/README.md
git commit -m "docs(order): record worker-seq-gene order id layout"
```

---

## Spec 覆盖自检

| Spec 项 | Task |
|---------|------|
| 位图公式 / EPOCH | Task 1 |
| worker 来自 Allocator | Task 1 Spring 构造 |
| 无 embed / 无撞号循环 | Task 1–2 |
| 回拨失败 | Task 1 测试 |
| 序号溢出等下一毫秒 | Task 1 测试 |
| 删 embed | Task 2 |
| 文档 / README | Task 3 |
| 分片 / PlaceCmdExe 不变 | 约束 + Task 2 回归 |

---

## 实施后手工（可选）

启动应用下一单，调试台只填 `orderId`：低 9 位基因与会员一致；日志仍单库单表。
