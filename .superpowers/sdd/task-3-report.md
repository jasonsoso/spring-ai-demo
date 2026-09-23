# Task 3 Report: 改仍在用的公式说明

## Status

DONE

## Test run

**Command (from `demo2`):**

```powershell
mvn test "-Dtest=OrderShardGeneTest,OrderIdGeneratorTest,OrderShardExplainCmdExeTest,OrderComplexShardingAlgorithmTest" -q
```

**Result:** PASS — exit code 0

**Tests run line (same `-Dtest`, without `-q`):**

```
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
```

## Files changed

| File | Change |
|------|--------|
| `demo2/README.md` | 「基因公式」与 §34：MurmurHash3 公式、禁止 `memberId % 512`、图箭头、示例 `612` → `61` → `order_ds_1.demo_order_30` |
| `demo2/docs/superpowers/specs/2026-08-30-order-sharding-gene-design.md` | §2.2 公式、禁止说明、图/发号/复合分片表/流程图、调试示例 612、下单时序 Note |
| `demo2/docs/superpowers/specs/2026-08-31-order-id-bit-layout-design.md` | 基因来源与发号式中的 `memberId % 512` → `MurmurHash3(memberId) & 0x1FF` |
| `demo2/docs/superpowers/specs/2026-09-23-order-shard-murmur-virtual-design.md` | 状态「已实现」+ plan 链接 |
| `demo2/docs/superpowers/plans/2026-09-23-order-shard-murmur-virtual.md` | Task 3 Step 1–4 checkbox 勾选 |

## Out of scope (not touched)

- Java / YAML / tests
- Git commit

## Self-review (`memberId % 512` / `mod 512`)

| Doc | In-scope sections |
|-----|-------------------|
| `README.md` | 仅保留「禁止 `memberId % 512`」说明；无 `mod 512` 箭头残留 |
| `2026-08-30-order-sharding-gene-design.md` | 仅保留禁止句；公式/图/调试示例已更新 |
| `2026-08-31-order-id-bit-layout-design.md` | 无 `memberId % 512` 残留 |
| `2026-09-23-order-shard-murmur-virtual-design.md` | 背景段仍描述旧问题（设计上下文），未改 |

**Follow-up:** §6 `OrderIdGenerator` 测试表单元格已改为 `MurmurHash3(memberId) & 0x1FF`（review 前修正）。

**Follow-up:** `2026-08-31-order-id-bit-layout-design.md` §5 基因行 `nextOrderId(612)` 低 9 位示例 `100` → `61`（review 修正）。

## Concerns

None.

## Commits

none
