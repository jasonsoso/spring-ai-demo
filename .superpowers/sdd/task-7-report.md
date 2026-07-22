# Task 7 Report: README + 全量回归

## Status

**COMPLETE** — `demo2/README.md` AgentScope 章节已更新 HITL confirm 流程；根 `README.md` 一行描述已同步；Task 6 review 欢迎文案已对齐；agentscope 单测 21/21 通过，compile 成功。

## Commits

| SHA | Message |
|-----|---------|
| `e60d32a` | `docs(demo2): document AgentScope permission HITL confirm flow` |

## Files Modified

| Path | Change |
|------|--------|
| `demo2/README.md` | Controller 表、`/confirm` 端点、SSE `REQUIRE_USER_CONFIRM`/`REQUEST_STOP`、`notes/` 约束、ask→confirm curl、Tab 说明 |
| `README.md` | AgentScope 模块一行描述同步 HITL |
| `demo2/src/main/resources/static/js/tabs/agentscope.js` | `resetAgentscopeConversation` 欢迎文案与 `index.html` 一致 |

## Test Results

```text
mvn -Dtest=com.jason.demo.demo2.agentscope.** test
Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

mvn -DskipTests compile
BUILD SUCCESS
```

## Deviations from Brief

无。

## Concerns / Follow-ups

- 未做浏览器/curl 手工冒烟（需运行应用 + LLM Key）
- 全 feature 分支合入主仓前建议再跑一遍完整 `mvn test`

## Verification

```powershell
Set-Location demo2
mvn "-Dtest=com.jason.demo.demo2.agentscope.**" test
mvn -DskipTests compile
```

## Task 7 Review Fix (Important finding)

**Issue:** README AgentScope 章节未说明 delete/越界 DENY 与 `REQUIRE_USER_CONFIRM` 的边界。

**Fix:** 在 `demo2/README.md` AgentScope 段落追加 1 句：`delete/remove` 及 `notes/` 外路径 → DENY → `TOOL_RESULT_END` state=`DENIED`，无确认卡片。

**Commit:** `docs(demo2): note AgentScope delete/path DENY without HITL`

### Verification (README grep)

```powershell
Select-String -Path demo2/README.md -Pattern "delete/remove|DENIED|REQUIRE_USER_CONFIRM"
```

Expected: AgentScope 段落同时包含 `delete/remove`、`DENIED`、以及「不会出现 `REQUIRE_USER_CONFIRM`」表述。
