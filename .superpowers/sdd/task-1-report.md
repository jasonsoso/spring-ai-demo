# Task 1 Report: 槽位改为 MurmurHash3

## Status

DONE

## TDD RED

**Command (from `demo2`):**

```powershell
Set-Location D:/ai/spring-ai-demo/demo2; mvn test "-Dtest=OrderShardGeneTest" -q
```

**Result:** FAIL — Tests run: 5, Failures: 3

**Relevant output:**

```
OrderShardGeneTest.virtual612_routesToDs1Table30:18 expected: <61> but was: <100>
OrderShardGeneTest.boundaries_zeroAnd511:30 expected: <252> but was: <0>
OrderShardGeneTest.snowflakeZeroSequence_plusOneMillisChangesSlot:44 expected: <44> but was: <0>
```

Matches brief expectation: `virtualOfMember(612L)` still `100` (modulo), not `61`.

## TDD GREEN

**Command:**

```powershell
mvn test "-Dtest=OrderShardGeneTest" -q
```

**Result:** PASS — exit code 0, no failure output (5 tests).

## Files changed

| File | Change |
|------|--------|
| `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/shard/OrderShardGene.java` | Replaced `memberId % VIRTUAL_COUNT` with `murmur3(memberId) & GENE_MASK`; added `murmur3`, `mix`, `fmix` and Javadoc per brief. |
| `demo2/src/test/java/com/jason/demo/demo2/order/OrderShardGeneTest.java` | Renamed/updated `virtual612_routesToDs1Table30`; updated `boundaries_zeroAnd511` assertions for hash slots; added `snowflakeZeroSequence_plusOneMillisChangesSlot`. Left `bothDatabasesUseAll32Tables` and `wrongModulo32_wouldLeaveOddTablesEmptyOnOneDs` unchanged. |

## Out of scope (not touched)

- `OrderComplexShardingAlgorithm.java`
- Other order tests, README, specs
- Git commit (user did not request)

## Self-review

1. **Scope:** Only `virtualOfMember` and `OrderShardGeneTest` per task; routing helpers (`dsIndex`, `tableIndex`, `geneBits`, etc.) unchanged.
2. **Algorithm:** MurmurHash3 x86 32-bit, seed 0, 8-byte little-endian input from `long`, constants and `fmix` match brief verbatim.
3. **Mask:** `& GENE_MASK` yields `0..511` as required.
4. **Comments:** Brief’s “禁止 table = virtual % 32” class comment retained; new member-hash Javadoc explains why not `% 512` / snowflake low bits.
5. **Tests:** `virtualOfOrderId((55L << 9) | 100L)` still asserts `100` — order-id gene path independent of member hash. Boundary test still validates `dsIndex(0L)` / `tableIndex(0L)` / `geneBits(511L)` on literal virtual values, not member hash.
6. **Distribution:** `bothDatabasesUseAll32Tables` still passes with MurmurHash3 over member ids 0..511 — both DS use all 32 tables.
7. **Concerns:** None for this task. Downstream tests (Task 2) may still assume old modulo slots until updated.

## Commits

none
