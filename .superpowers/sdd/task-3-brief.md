### Task 3: 改仍在用的公式说明

**Files:**
- Modify: `demo2/README.md`（约 1105、1110、1116、3422、3428 行）
- Modify: `demo2/docs/superpowers/specs/2026-08-30-order-sharding-gene-design.md` 第 2.2 节公式
- Modify: `demo2/docs/superpowers/specs/2026-08-31-order-id-bit-layout-design.md` 基因来源
- Modify: `demo2/docs/superpowers/specs/2026-09-23-order-shard-murmur-virtual-design.md` 状态行

**Interfaces:**
- Consumes: 槽位 `612 → 61 → order_ds_1.demo_order_30`
- Produces: 无代码

- [ ] **Step 1: 改 `demo2/README.md` 基因公式**

「基因公式」一节换成：

```markdown
`virtual = MurmurHash3(memberId) & 0x1FF` 或 `orderId & 0x1FF`；`ds = virtual % 2`；`table = (virtual / 2) % 32`。哈希是 x86 32 位、种子 0、会员号小端 8 字节。禁止 `memberId % 512`：Hutool 雪花最低 12 位是序号，逐个注册时序号恒为 0，取低位会全部进 `order_ds_0.demo_order_0`。禁止 `table = virtual % 32`（2 与 32 不互质）。
```

图里 `M[memberId] -->|mod 512| V[virtual]` 改为 `M[memberId] -->|MurmurHash3 再取低 9 位| V[virtual]`。例子改为：`612` → 槽位 `61` → `order_ds_1.demo_order_30`。

第 34 节同样改箭头文字，例子改为 `612` → `order_ds_1.demo_order_30`，并写明禁止 `memberId % 512`。

- [ ] **Step 2: 改 2026-08-30 公式段**

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

- [ ] **Step 3: 改 2026-08-31 的基因来源**

`2026-08-31-order-id-bit-layout-design.md` 中基因行和发号式的 `memberId % 512` 改为 `MurmurHash3(memberId) & 0x1FF`。`virtual = orderId & 0x1FF`、41/5/8/9 位宽、每毫秒 256 个号不改。

把 `2026-09-23-order-shard-murmur-virtual-design.md` 的状态从「待实现」改为「已实现」，并加上本 plan 的链接。

- [ ] **Step 4: 再跑四个测试类**

```powershell
mvn test "-Dtest=OrderShardGeneTest,OrderIdGeneratorTest,OrderShardExplainCmdExeTest,OrderComplexShardingAlgorithmTest" -q
```

Expected: PASS。文档改动不应让测试失败。

- [ ] **Step 5: Commit**

仅当用户要求提交时执行。本次不要提交。
