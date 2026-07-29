# AgentScope Plan Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 AgentScope Dev Agent 上打开 Plan Mode + TaskList，使「先写 `plans/PLAN.md` 再 HITL 确认，然后分别确认 `edit_file` / `execute`」可在 Sandbox 下稳定演示。

**Architecture:** 始终 `enablePlanMode()` + `enableTaskList()`；沙箱开启时注入 Plan Mode 流程提示、投影 `plans`、保留 list/glob/grep、取消 `execute` ALLOW；`plan_exit` 继续走现有 `/confirm`。不新建 API。

**Tech Stack:** Java 21、Spring Boot 4.x、AgentScope Java 2.0.0（`HarnessAgent.Builder.enablePlanMode/enableTaskList`、`PlanModeMiddleware`、`plan_*` / `todo_write`）、现有 Permission HITL SSE、JUnit 5、AssertJ。

**设计规范:** [docs/superpowers/specs/2026-07-29-agentscope-plan-mode-design.md](../specs/2026-07-29-agentscope-plan-mode-design.md)

## Global Constraints

- AgentScope 版本保持 `2.0.0`，**不新增** Maven 依赖
- **不新建** HTTP 端点；不改 `DevAgentConfirmRequest` / confirm 整批 `approved` 语义
- Plan Mode / TaskList **始终开启**；不另加 `plan-mode.enabled`
- **不**调用 `allowShellInPlanMode()`
- 自动放行：`plan_enter`、`plan_write`、`todo_write`；**绝不**自动放行 `plan_exit`
- 完整 Plan Mode 流程提示 **仅** `sandbox.enabled=true` 时注入 `systemPrompt`
- `AGENTS.md` Plan Mode 规则常驻；并修订沙箱段，避免与规划期 list/glob/grep 冲突
- 沙箱仍移除 `write_file`；**不再**移除 `list_files` / `glob_files` / `grep_files`
- 沙箱下 **去掉** `execute` ALLOW（与 `edit_file` 一样 HITL）
- 前端只加示例 15 + 文案；确认 UI **不**内嵌 `PLAN.md`
- 编译门禁：在 `demo2` 目录 `.\mvnw.cmd -DskipTests compile`（或仓库根 `mvn -f demo2/pom.xml -DskipTests compile`）
- 单测门禁示例：`.\mvnw.cmd "-Dtest=AgentscopePlanModeAssetsTest,AgentScopeMiddlewareConfigTest" test`

---

## File Map

**Create**

- `demo2/workspace/plans/.gitkeep`
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/plan/AgentscopePlanModeAssetsTest.java`

**Modify**

- `demo2/.gitignore`：放行 `workspace/plans/.gitkeep`（`PLAN.md` 仍忽略）
- `demo2/workspace/AGENTS.md`：Plan Mode 规则 + 修订沙箱工具边界
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`：enablePlanMode/TaskList、投影、ALLOW、工具裁剪、提示注入
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java`：Plan Mode 装配断言
- `demo2/src/main/resources/static/js/tabs/agentscope.js`：示例 15
- `demo2/src/main/resources/static/index.html`：示例 15 按钮
- `demo2/README.md`：Plan Mode 说明与示例 13 行为变化

**不改**

- `DevAgentController` / `DevAgentService` / `DevAgentConfirmRequest`
- `application-agentscope-prompts.yml` 的基座 `system-prompt`（流程段在 Java 里按沙箱开关追加，与现有沙箱硬约束同一模式）

---

### Task 1: plans 工作区骨架 + AGENTS.md + 资产测试

**Files:**
- Create: `demo2/workspace/plans/.gitkeep`
- Create: `demo2/src/test/java/com/jason/demo/demo2/agentscope/plan/AgentscopePlanModeAssetsTest.java`
- Modify: `demo2/.gitignore`
- Modify: `demo2/workspace/AGENTS.md`

**Interfaces:**
- Produces: 宿主可跟踪的 `workspace/plans/.gitkeep`；AGENTS.md 中 Plan Mode 与沙箱规则文案
- Consumes: 无

- [ ] **Step 1: 写失败的资产测试**

```java
package com.jason.demo.demo2.agentscope.plan;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentscopePlanModeAssetsTest {

    private static final Path MODULE = Path.of(".").toAbsolutePath().normalize();

    @Test
    void plansDirectorySkeletonExists() {
        Path keep = MODULE.resolve("workspace/plans/.gitkeep");
        assertThat(keep).exists();
    }

    @Test
    void agentsMdDocumentsPlanModeFlow() throws Exception {
        String agents = Files.readString(MODULE.resolve("workspace/AGENTS.md"));
        assertThat(agents).contains("Plan Mode");
        assertThat(agents).contains("plan_write");
        assertThat(agents).contains("plans/PLAN.md");
        assertThat(agents).contains("plan_exit");
        // 规划阶段允许沙箱只读列举/搜索；不得再写「禁止 grep_files」这类与 Plan Mode 冲突的硬句
        assertThat(agents).contains("list_files");
        assertThat(agents).doesNotContain("禁止调用任何 MCP 文件工具，包括");
    }
}
```

- [ ] **Step 2: 运行确认失败**

```powershell
cd D:\ai\spring-ai-demo\demo2
.\mvnw.cmd "-Dtest=AgentscopePlanModeAssetsTest" test
```

Expected: FAIL（`plans/.gitkeep` 不存在或 AGENTS.md 缺关键字）。

- [ ] **Step 3: 更新 `.gitignore`**

在 `workspace/project/**` 白名单段后追加：

```gitignore
!workspace/plans/
!workspace/plans/.gitkeep
```

说明：`workspace/**` 仍忽略运行时 `PLAN.md`；只跟踪空目录占位。

- [ ] **Step 4: 创建 `workspace/plans/.gitkeep`**

空文件即可。

- [ ] **Step 5: 更新 `workspace/AGENTS.md`**

在「沙箱修复」节前或其后新增一节（保持现有其它节不变）：

```markdown
## Plan Mode（先方案后执行）

- 用户要求先写计划再修复、先调查再改代码、进入 Plan Mode，或明确要求方案确认后再修改时，先进入 Plan Mode：
  只读调查，用 `plan_write` 写 `plans/PLAN.md`，再通过 `plan_exit` 申请执行。
- 规划阶段优先使用 `read_file` / `list_files` / `glob_files` / `grep_files` 与 `plan_enter` / `plan_write`；不要调用 `edit_file` / `execute`，也不要创建子 Agent 或调用 MCP 工具。
- 用户批准 `plan_exit` 后才进入执行：用 `todo_write` 建清单，再 `edit_file` / `execute`；批准方案不等于放行后续危险操作。
```

并**修订**现有「沙箱修复」节中与 Plan Mode 冲突的句子：

1. 将「只使用 Docker 沙箱内置工具 `read_file`、`edit_file`、`execute`」改为：

```markdown
- 沙箱修复默认使用内置 `read_file` / `edit_file` / `execute`；若走 Plan Mode，规划阶段还可使用 `list_files` / `glob_files` / `grep_files` 与 `plan_*` / `todo_write`。
```

2. 将「沙箱流程中禁止调用任何 MCP 文件工具，包括 `list_allowed_directories`、`list_directory`、`read_text_file`、`grep_files`、`read_file` 等 MCP 同名或近似工具」改为：

```markdown
- 沙箱流程中禁止调用 MCP 文件工具（`list_allowed_directories`、`list_directory`、`read_text_file` 等）；规划/修复请用沙箱内置文件工具，不要因为 MCP 工具可见就改走 `mcp-files`。
```

保留：`working_directory=project`、`edit_file`/`execute` 需 Permission 确认、不改宿主机源码等原有硬约束。

- [ ] **Step 6: 运行资产测试通过**

```powershell
.\mvnw.cmd "-Dtest=AgentscopePlanModeAssetsTest" test
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`。

- [ ] **Step 7: Commit**

```powershell
git add demo2/.gitignore demo2/workspace/plans/.gitkeep demo2/workspace/AGENTS.md demo2/src/test/java/com/jason/demo/demo2/agentscope/plan/AgentscopePlanModeAssetsTest.java
git commit -m "docs(demo2): add Plan Mode workspace skeleton and AGENTS rules"
```

---

### Task 2: AgentScopeConfig 打开 Plan Mode 并调整沙箱边界

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java`

**Interfaces:**
- Consumes: `HarnessAgent.Builder.enablePlanMode()` / `enableTaskList()`；现有 `dockerFilesystemSpec` / `permissionContext` / `agentscopeDevAgent`
- Produces:
  - toolkit 始终含 `plan_enter`、`plan_write`、`plan_exit`、`todo_write`
  - `sandboxWorkspaceProjectionRoots()` 含 `"plans"`
  - ALLOW：`plan_enter`/`plan_write`/`todo_write`；沙箱下另有 `read_file` + list/glob/grep；**无** `plan_exit`、**无** `execute`
  - 沙箱开时 `getSysPrompt()`/`getSystemPrompt()` 含 `plan_enter` 与 `plans/PLAN.md`；关时基座 prompt 不含完整流程段

- [ ] **Step 1: 先写/扩展失败测试**

在 `AgentScopeMiddlewareConfigTest` 增加（复用已有 `buildAgent` / `propertiesWithSandbox`）：

```java
@Test
void agentscopeDevAgent_alwaysRegistersPlanModeAndTaskListTools() throws Exception {
    try (HarnessAgent off = buildAgent(propertiesWithSandbox(false));
         HarnessAgent on = buildAgent(propertiesWithSandbox(true))) {
        assertThat(off.getToolkit().getToolNames())
                .contains("plan_enter", "plan_write", "plan_exit", "todo_write");
        assertThat(on.getToolkit().getToolNames())
                .contains("plan_enter", "plan_write", "plan_exit", "todo_write");
        assertThat(on.getDelegate().getMiddlewares())
                .anyMatch(m -> m.getClass().getSimpleName().equals("PlanModeMiddleware"));
    }
}

@Test
void agentscopeDevAgent_sandboxEnabled_keepsPlanReadToolsAndDropsWriteFile() throws Exception {
    try (HarnessAgent agent = buildAgent(propertiesWithSandbox(true))) {
        assertThat(agent.getToolkit().getToolNames())
                .contains("read_file", "edit_file", "execute", "list_files", "glob_files", "grep_files")
                .doesNotContain("write_file");
    }
}

@Test
void permissionContext_allowsPlanToolsButNotPlanExitOrExecute() {
    DevAgentProperties sandboxOn = propertiesWithSandbox(true);
    var ctx = AgentScopeConfig.permissionContext(
            sandboxOn, AgentscopeMcpClientRegistry.create(sandboxOn));
    assertThat(ctx.getAllowRules().keySet())
            .contains("plan_enter", "plan_write", "todo_write", "read_file",
                    "list_files", "glob_files", "grep_files")
            .doesNotContain("plan_exit", "execute");
}

@Test
void sandboxProjectionRoots_includePlans() {
    assertThat(AgentScopeConfig.sandboxWorkspaceProjectionRoots())
            .contains("plans", "project", "AGENTS.md");
}

@Test
void agentscopeDevAgent_sandboxEnabled_injectsPlanModePrompt() throws Exception {
    try (HarnessAgent on = buildAgent(propertiesWithSandbox(true));
         HarnessAgent off = buildAgent(propertiesWithSandbox(false))) {
        String onPrompt = on.getDelegate().getSysPrompt();
        String offPrompt = off.getDelegate().getSysPrompt();
        assertThat(onPrompt).contains("plan_enter");
        assertThat(onPrompt).contains("plans/PLAN.md");
        assertThat(onPrompt).contains("plan_exit");
        assertThat(offPrompt).doesNotContain("plans/PLAN.md");
    }
}
```

同时**更新**现有用例 `agentscopeDevAgent_sandboxEnabled_exposesSandboxToolsWithoutWriteFile`：断言集合改为与「保留 list/glob/grep」一致（可与上面新用例合并，避免重复则删除旧用例或改成调用同一断言）。

- [ ] **Step 2: 将 `permissionContext` 改为 package-visible，并抽出投影根列表**

`AgentScopeConfig` 中：

```java
static final List<String> PLAN_AUTO_APPROVED_TOOL_NAMES =
        List.of("plan_enter", "plan_write", "todo_write");

static final List<String> SANDBOX_PLAN_READ_TOOL_NAMES =
        List.of("list_files", "glob_files", "grep_files");

static List<String> sandboxWorkspaceProjectionRoots() {
    return List.of(
            "AGENTS.md",
            "skills",
            "subagents",
            "knowledge",
            ".skills-cache",
            "plans",
            "project");
}

/** package-visible for tests */
static PermissionContextState permissionContext(
        DevAgentProperties properties,
        AgentscopeMcpClientRegistry agentscopeMcpClientRegistry) {
    // 原 private 方法体迁到此处
}
```

`dockerFilesystemSpec` 内改为：

```java
filesystem.workspaceProjectionRoots(sandboxWorkspaceProjectionRoots());
```

- [ ] **Step 3: 运行测试确认当前失败**

```powershell
.\mvnw.cmd "-Dtest=AgentScopeMiddlewareConfigTest" test
```

Expected: 新断言 FAIL（尚无 plan 工具 / 仍 remove list_* / 仍 ALLOW execute / 无提示）。

- [ ] **Step 4: 实现装配改动**

1. `agentscopeDevAgent` 的 `HarnessAgent.Builder` 在 `build()` 前增加：

```java
builder.enablePlanMode()
        .enableTaskList();
// 不要调用 allowShellInPlanMode()
```

2. 沙箱开时的 `systemPrompt +=`：在现有「沙箱硬约束」之后（或合并修订）追加 Plan Mode 段，并**改写**旧硬约束中「只允许 execute/read_file/edit_file」为允许规划期只读工具与 plan/todo。推荐最终沙箱追加文本包含以下要点（可多行 text block）：

```text
【Plan Mode】
用户要求「先写计划再修复」「先调查再改代码」「进入 Plan Mode」或明确要求先规划时：
1. 第一项工具调用必须是 plan_enter；
2. 进入后只做只读调查：优先 read_file / list_files / glob_files / grep_files；
3. 规划阶段不要调用 execute、edit_file、write_file，也不要创建子 Agent 或调用 MCP 工具；
4. 调查完成后调用 plan_write，把完整计划写入 plans/PLAN.md（目标、已确认事实、拟改文件、步骤、验证、风险）；不要编造未查到的结论；
5. 写完计划后立刻调用 plan_exit，等待用户确认；
6. 用户批准后进入执行：先用 todo_write 按计划建任务列表，始终只保留一个 in_progress；
7. 执行阶段项目位于 /workspace/project：先读已批准计划；改已有文件必须 edit_file；execute 仅用于构建/测试/查看环境；working_directory 用工作区相对路径 project，不要在 command 里 cd；若计划依据不成立则停止并重新规划；
8. 最终说明计划文件、批准结果、修改文件和测试结果。
```

同时保留：`working_directory` 必须为 `project`、禁止宿主机绝对路径、禁止用 Shell 重定向/sed 绕过 `edit_file`。可将「每次回复最多一个沙箱工具」保留（与现网一致）。

3. `permissionContext` 中：

```java
PLAN_AUTO_APPROVED_TOOL_NAMES.forEach(
        toolName -> builder.addAllowRule(toolName, allowRule(toolName)));
// ... 现有规则 ...
if (properties.sandbox().enabled()) {
    builder.addAllowRule("read_file", allowRule("read_file"));
    SANDBOX_PLAN_READ_TOOL_NAMES.forEach(
            toolName -> builder.addAllowRule(toolName, allowRule(toolName)));
    // 不要再 addAllowRule("execute", ...)
}
```

4. 沙箱工具裁剪改为**只**删 `write_file`：

```java
if (sandbox.enabled()) {
    agent.getToolkit().removeTool("write_file");
}
```

删除对 `list_files` / `glob_files` / `grep_files` 的 `removeTool`。

- [ ] **Step 5: 运行测试通过**

```powershell
.\mvnw.cmd "-Dtest=AgentScopeMiddlewareConfigTest,AgentscopePlanModeAssetsTest" test
```

Expected: 全部 PASS。

- [ ] **Step 6: 编译门禁**

```powershell
.\mvnw.cmd -DskipTests compile
```

Expected: BUILD SUCCESS。

- [ ] **Step 7: Commit**

```powershell
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java
git commit -m "feat(demo2): enable AgentScope Plan Mode with sandbox HITL for execute"
```

---

### Task 3: Tab 示例 15 + README

**Files:**
- Modify: `demo2/src/main/resources/static/js/tabs/agentscope.js`
- Modify: `demo2/src/main/resources/static/index.html`
- Modify: `demo2/README.md`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/plan/AgentscopePlanModeAssetsTest.java`（可选：断言 index/js 含示例 15）

**Interfaces:**
- Produces: 前端示例 15（`plan-user-017` / `plan-session-017`）；README 三段确认与示例 13 行为说明
- Consumes: 现有 `fillAgentscopeSample`、`/ask`/`/confirm` UI

- [ ] **Step 1: 扩展资产测试（前端文案）**

在 `AgentscopePlanModeAssetsTest` 增加：

```java
@Test
void frontendExposesPlanModeSample() throws Exception {
    String js = Files.readString(MODULE.resolve("src/main/resources/static/js/tabs/agentscope.js"));
    String html = Files.readString(MODULE.resolve("src/main/resources/static/index.html"));
    assertThat(js).contains("plan-user-017");
    assertThat(js).contains("plan-session-017");
    assertThat(js).contains("方案确认前不要改代码");
    assertThat(html).contains("fillAgentscopeSample(15)");
}
```

Run 确认失败：

```powershell
.\mvnw.cmd "-Dtest=AgentscopePlanModeAssetsTest#frontendExposesPlanModeSample" test
```

- [ ] **Step 2: 更新 `agentscope.js`**

在 `samples` 增加：

```javascript
15: '请先调查 workspace/project 里 RetryPolicy 第一次重试延迟错误，整理修复方案。方案确认前不要改代码，等我确认后再执行。',
```

在 `n === 14` 分支后增加：

```javascript
if (n === 15) {
    const userId = document.getElementById('agentscopeUserId');
    const sessionId = document.getElementById('agentscopeSessionId');
    if (userId) userId.value = 'plan-user-017';
    if (sessionId) sessionId.value = 'plan-session-017';
}
```

- [ ] **Step 3: 更新 `index.html`**

在示例 14 按钮后增加：

```html
<button type="button" onclick="fillAgentscopeSample(15)">示例：Plan Mode 先确认方案</button>
```

- [ ] **Step 4: 更新 README**

在 Docker Sandbox 小节附近增加 **Plan Mode** 小节，至少包含：

1. 能力：`enablePlanMode` + `enableTaskList`；计划文件 `plans/PLAN.md`；与 Permission 分层。
2. 演示前置：`sandbox.enabled=true`、PG、沙箱镜像。
3. 流程：确认① `plan_exit` → 确认② `edit_file` → 确认③ `execute`。
4. Tab 示例 15；curl 可用同一 `userId`/`sessionId` 多次 `/confirm`。
5. **行为变化**：沙箱下 `execute` 不再自动放行；示例 13 也会对 `edit_file`/`execute` 弹 HITL。

示例 curl（端口以 README 现用 `8081` 为准）：

```bash
curl -sN -X POST "http://localhost:8081/agentscope/dev-agent/ask" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"plan-user-017\",\"sessionId\":\"plan-session-017\",\"message\":\"请先调查 workspace/project 里 RetryPolicy 第一次重试延迟错误，整理修复方案。方案确认前不要改代码，等我确认后再执行。\"}"

curl -sN -X POST "http://localhost:8081/agentscope/dev-agent/confirm" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"plan-user-017\",\"sessionId\":\"plan-session-017\",\"approved\":true}"
```

（后两次 confirm 命令相同，分别批准改文件与跑测。）

- [ ] **Step 5: 运行资产测试**

```powershell
.\mvnw.cmd "-Dtest=AgentscopePlanModeAssetsTest,AgentScopeMiddlewareConfigTest" test
```

Expected: PASS。

- [ ] **Step 6: Commit**

```powershell
git add demo2/src/main/resources/static/js/tabs/agentscope.js demo2/src/main/resources/static/index.html demo2/README.md demo2/src/test/java/com/jason/demo/demo2/agentscope/plan/AgentscopePlanModeAssetsTest.java
git commit -m "docs(demo2): add Plan Mode sample and README for three-step confirm"
```

---

### Task 4: 手工验收清单（不写代码）

**Files:** 无

- [ ] **Step 1: 前置**

- `app.agentscope.dev-agent.sandbox.enabled=true`
- PostgreSQL 可用
- 沙箱镜像：`docker compose -f demo2/docker/sandbox/docker-compose.yml build`（按仓库现有注释）
- 启动 `demo2`，配置 DeepSeek API Key

- [ ] **Step 2: Tab 示例 15**

1. 点击「Plan Mode 先确认方案」→ 发送  
2. 出现 `plan_exit` 的 `REQUIRE_USER_CONFIRM`；确认 `RetryPolicy.java` 尚未被改  
3. 批准 → 出现 `edit_file` 确认  
4. 批准 → 出现 `execute`（`mvn test`）确认  
5. 批准 → 测试通过；回复提及计划/改动

- [ ] **Step 3: 对照示例 13**

同一沙箱配置下，示例 13 应对 `edit_file` 和/或 `execute` 出现 HITL（不再静默跑完）。

- [ ] **Step 4: 若手工失败**

回到 Task 2 检查提示注入、ALLOW 列表、工具裁剪；不要用 `allowShellInPlanMode` 或把 `plan_exit` 加入 ALLOW 来「绕过」。

---

## Spec Coverage Checklist

| Spec 要求 | Task |
|-----------|------|
| `enablePlanMode` + `enableTaskList` 始终开 | Task 2 |
| 不 `allowShellInPlanMode` | Task 2 Global + 步骤 |
| 投影 `plans` | Task 1 + Task 2 |
| ALLOW plan_enter/write/todo；不放行 plan_exit | Task 2 |
| 取消 execute ALLOW | Task 2 |
| 保留 list/glob/grep | Task 2 |
| 沙箱提示注入 Plan Mode 流程 | Task 2 |
| AGENTS.md 常驻规则 | Task 1 |
| 示例 15 + README | Task 3 |
| 不改 ask/confirm 协议 | Global / 未列入改动文件 |
| 装配单测 | Task 1–3 |
| 手工三段确认 | Task 4 |
