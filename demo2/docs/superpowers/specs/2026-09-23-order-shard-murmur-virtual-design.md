# demo2 订单分片槽位改为 MurmurHash3 设计规范

**日期**: 2026-09-23  
**项目**: spring-ai-demo / demo2  
**状态**: 待实现  
**前置**: [2026-08-30-order-sharding-gene-design.md](./2026-08-30-order-sharding-gene-design.md)、[2026-08-31-order-id-bit-layout-design.md](./2026-08-31-order-id-bit-layout-design.md)  
**范围**: 只改「会员号 → 槽位」的算法。会员号生成、库表拆法、订单号位图、只拿订单号时的拆法都不改。

---

## 1. 背景与目标

### 1.1 背景

会员号是 Hutool 雪花：`[41 时间][5 机房][5 机器][12 序号]`。现在 `virtual = memberId % 512`，余数只来自最低 9 位，而这 9 位落在序号里。会员逐个注册时序号恒为 0，槽位恒为 0，订单全进 `order_ds_0.demo_order_0`。

库下标 `virtual % 2`、表下标 `(virtual / 2) % 32` 已经能把 512 个槽铺满两库 64 张表。问题只在槽位本身不散。

### 1.2 目标

1. 会员号继续用 Hutool 雪花原样，不改注册发号。
2. 槽位改为对整个会员号做 MurmurHash3，再取 0～511。同一个会员永远同一个槽。
3. 新订单号最低 9 位仍写入这个槽位。只拿 `orderId` 时仍读这 9 位，不再哈希。
4. 库、表公式不变，天花板仍是 2 库 × 256 表。

### 1.3 已确认决策

| 维度 | 选择 |
|------|------|
| 会员号 | 不改，仍 `SnowflakeIdGenerator.nextId()` |
| 槽位 | MurmurHash3（x86 32 位，种子 0）再和 `0x1FF` 按位与 |
| 不用 | ShardingSphere `HASH_MOD`；`Long.hashCode`；黄金分割乘法 |
| 库 / 表 | 仍 `virtual % 2`、`(virtual / 2) % 32` |
| 只拿订单号 | 仍 `orderId & 0x1FF` |
| 已有分片订单 | 不迁移、不改号。分片库中此前 7 笔已删除 |
| 依赖 | 不引入 Guava。算法写在 `OrderShardGene` 内 |

不用 `HASH_MOD`：它是单列标准算法，接不上现在「有会员号用会员号、只有订单号读末尾余数」的复合策略；库 `% 2`、表 `% 32` 分开取模还会让一个库只落偶数表。

不用 `Long.hashCode`：序号为 0 时，加 1 毫秒槽位不变。MurmurHash3 会把这 1 毫秒卷进余数。

---

## 2. 公式

```text
hash    = MurmurHash3_x86_32(会员号的 8 字节, seed = 0)
virtual = hash & 0x1FF                 // 0..511
        = orderId & 0x1FF              // 只拿订单号时

ds      = virtual % 2
table   = (virtual / 2) % 32
```

会员号按**小端**、无符号 8 字节送入。块内 4 字节也按小端拼，每个字节先 `& 0xFF`，避免 Java 字节的符号位进高位。常数用 Austin Appleby 的 x86 32 位版本：`c1 = 0xcc9e2d51`，`c2 = 0x1b873593`，尾部 `fmix` 用 `0x85ebca6b` 与 `0xc2b2ae35`。种子固定 0。

余数用按位与，不用 Java 的 `%`。哈希的 `int` 可能为负，负数取模会得到负数。

这个函数写死。换种子、换端序或改成别的哈希，已发出的订单号会对不上会员。

发号仍走 `OrderIdGenerator.nextOrderId`，它已经调用 `virtualOfMember`。订单号位图不变：`[41 时间][5 机器][8 序号][9 基因]`，基因改成上面的 `virtual`。

---

## 3. 改动边界

只改 `OrderShardGene.virtualOfMember`（及同类里的私有哈希方法）。

不改：

- `MemberRegisterCmdExe` 与 `SnowflakeIdGenerator`
- `virtualOfOrderId`、`dsIndex`、`tableIndex`
- `OrderIdGenerator` 的位运算
- `OrderComplexShardingAlgorithm` 的路由分支（注释里的 `memberId % 512` 改成哈希取余）
- `shardExplain` 的入参和前端。调试页继续展示接口返回的库表
- `shardingsphere.yaml` 的 `CLASS_BASED` 复合算法

按会员号的列表、数量、详情、支付、取消，SQL 里有 `member_id` 时仍走会员槽位。新订单的插入和这些查询去同一张表。

只带 `order_id` 的超时关单仍读订单号末尾 9 位。

---

## 4. 已有数据

不写迁移，不清表，不改历史订单号。

分片库里原先那 7 笔订单和明细已经删除。`spring_ai_agent2.demo_order` 的旧单表不在分片路径上，不动。`delay_task` 里已取消的关单任务不动。

若以后库里又出现「基因按 `memberId % 512` 写入」的订单：按订单号仍能找到；按会员号会去新槽，列表和详情都看不到。本 spec 不处理这种情况。

---

## 5. 测试向量

`OrderShardGene.virtualOfMember` 必须等于下表。库、表由现有 `dsIndex` / `tableIndex` 推出。

| 会员号 | 槽位 | 库 | 表 |
|--------|------|----|----|
| `0` | 252 | `order_ds_0` | `demo_order_30` |
| `1` | 324 | `order_ds_0` | `demo_order_2` |
| `511` | 60 | `order_ds_0` | `demo_order_30` |
| `612` | 61 | `order_ds_1` | `demo_order_30` |
| `2092218709362868224`（`0x1D090DD4C4000000`，序号全 0） | 44 | `order_ds_0` | `demo_order_22` |
| 上一号时间加 1 毫秒（`+ 1 << 22`） | 487 | `order_ds_1` | `demo_order_19` |
| `2092219072866418688`（`0x1D090E2966800000`，序号全 0） | 23 | `order_ds_1` | `demo_order_11` |

两个序号全 0 的雪花号不得落在同一张表。同一会员号加 1 毫秒后槽位必须变化，用来挡住退回 `Long.hashCode`。

只带订单号、低 9 位仍是 100 的用例继续期望 `order_ds_0.demo_order_18`，证明拆订单号的路径没变。

需要改断言的测试：`OrderShardGeneTest`、`OrderIdGeneratorTest`、`OrderShardExplainCmdExeTest`、`OrderComplexShardingAlgorithmTest`。会员 `612` 的库表改为上表，不再是槽位 100 / `demo_order_18`。

---

## 6. 文档

实施时改仍在用的公式说明，不改 `docs/superpowers/archive/` 与历史 plan：

- `demo2/README.md` 中 `virtual = memberId % 512` 与 `612 → demo_order_18` 两处
- [2026-08-30-order-sharding-gene-design.md](./2026-08-30-order-sharding-gene-design.md) 的基因公式段
- [2026-08-31-order-id-bit-layout-design.md](./2026-08-31-order-id-bit-layout-design.md) 里「基因 = `memberId % 512`」改为本 spec 的槽位。位宽、位移、每毫秒序号上限不改

---

## 7. 非目标

- 不改会员号生成，不把槽位写进会员号
- 不把分片策略换成 ShardingSphere `HASH_MOD`
- 不调整 `DB_COUNT`、`TABLE_COUNT`、基因 9 位
- 不迁移、不双写、不按会员号回扫旧槽
