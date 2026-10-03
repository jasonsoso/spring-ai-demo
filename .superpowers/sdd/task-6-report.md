# Task 6 Report: 文档与 spec 状态

## Status

**DONE**

## Summary

README 热库存：`demo2:stock:outbox` 行对齐 Stream 容器拉新、`XACKDEL(ACKED)` 与独立 PEL 补发；读写分工图改为 `StreamMessageListenerContainer → sendNow`；配置块补全 outbox/claim/watchdog 项并注明 `outbox-consumer` 仅为前缀。`2026-08-27` spec §6 指向 `2026-09-29` 规范并改为 `XACKDEL(ACKED)`（未改 Lua/applyDelta/对账）。`2026-09-29` spec 状态改为已实现。

## Files Changed

| File | Action |
|------|--------|
| `demo2/README.md` | Modified — outbox 表、分工图、配置块 |
| `demo2/docs/superpowers/specs/2026-08-27-redis-stock-consistency-design.md` | Modified — §6 链路与交叉引用 |
| `demo2/docs/superpowers/specs/2026-09-29-redis-stock-outbox-stream-listener-design.md` | Modified — 状态已实现 |

## Verification

文档-only；未跑 Java 测试（brief：No Java changes）。

## Commit

- **Subject:** `docs(product): record stream listener stock outbox relay`
- **Scope:** 上述三文件

## Concerns

- README 未追加 `2026-09-29` spec/plan 链接（brief 未要求；仍保留 2026-08-27 三件套引用）。
- §2 时序图仍写「XACK Stream」，与 §6/README 的 `XACKDEL` 措辞不完全一致（brief 禁止改对账以外大段，未动 §2）。
