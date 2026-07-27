# AgentScope SubAgent Code-Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 AgentScope Dev Agent 上启用 SubAgent，落地 code-reader / risk-reviewer / test-advisor 三角色与 `TravelBudgetService` 样例，使「多角色/SubAgent」审查走委派汇总，默认审查仍走既有 Skill，并透传 SSE `source`。

**Architecture:** 去掉 `.disableSubagents()`；`workspace/subagents/*.md` 定义角色；Toolkit 在 `build()` 前注册业务工具 + MCP（build 后保留，不 remove）；Permission 放行 `agent_*`；`AGENTS.md` 写 Skill/SubAgent 路由；`DevAgentEvent.source` 原样透传。不改 `system-prompt`。

**Tech Stack:** Java 21、Spring Boot 4.1、AgentScope Java 2.0（Harness SubAgent）、JUnit 6、AssertJ、既有 stdio MCP filesystem。

## Global Constraints

- 设计规范：`demo2/docs/superpowers/specs/2026-07-27-agentscope-subagent-code-review-design.md`
- AgentScope 保持 `2.0.0`，**不新增** Maven 依赖
- **不改** `application-agentscope-prompts.yml` 的 `system-prompt`
- **保留** `.disableDefaultWorkspaceSkills()`；**不要**再调用 `.disableSubagents()`
- 主 Agent **保留** MCP 读文件工具（build 后**不** `remove` MCP）
- SubAgent 路径必须是 `workspace/subagents/*.md`（第一层），**不要**放到 skills 目录
- 与 Spring AI `/agent/skills`、既有 `/agent/subagent` Tab **无关、不打通**
- **不**用 Workflow 强制 `agent_spawn` 顺序；**不**做结构化输出校验；本轮**不**改前端渲染 `source`
- 编译门禁：`mvn -f demo2/pom.xml -DskipTests compile`
- 单测门禁：`mvn -f demo2/pom.xml -Dtest=AgentscopeSubagentCodeReviewAssetsTest,DevAgentEventTest,DevAgentServiceTest,AgentScopeMiddlewareConfigTest test`

## File Map

**Create**

- `demo2/workspace/subagents/code-reader.md`
- `demo2/workspace/subagents/risk-reviewer.md`
- `demo2/workspace/subagents/test-advisor.md`
- `demo2/mcp-files/TravelBudgetService.java`
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/subagent/AgentscopeSubagentCodeReviewAssetsTest.java`

**Modify**

- `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`：去掉 `.disableSubagents()`；build 前组装 `Toolkit`；放行 `agent_*`
- `demo2/workspace/AGENTS.md`：扩展「代码审查」双路径
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentEvent.java`：增加 `source`
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java`：`mapEvent` 透传 `event.getSource()`
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/model/DevAgentEventTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java`（若工厂签名变更导致编译失败则同步）
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java`：断言 `agent_spawn` 等
- `demo2/src/main/resources/static/index.html`：示例 12 按钮 + 欢迎文案
- `demo2/src/main/resources/static/js/tabs/agentscope.js`：示例 12
- `demo2/README.md`：SubAgent 能力说明 + curl

**Keep（勿删）**

- `demo2/workspace/skills/code-reviewer/`、`demo2/mcp-files/UserProfileFormatter.java`、`demo2/mcp-files/project-profile.md`

---

### Task 1: SubAgent 角色资产 + TravelBudgetService + 存在性测试

**Files:**
- Create: `demo2/workspace/subagents/code-reader.md`
- Create: `demo2/workspace/subagents/risk-reviewer.md`
- Create: `demo2/workspace/subagents/test-advisor.md`
- Create: `demo2/mcp-files/TravelBudgetService.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/agentscope/subagent/AgentscopeSubagentCodeReviewAssetsTest.java`
- Keep: `demo2/mcp-files/UserProfileFormatter.java`、`project-profile.md`

**Interfaces:**
- Produces: Harness 可发现三个 `agent_id`：`code-reader` / `risk-reviewer` / `test-advisor`
- Produces: MCP 可读样例 `TravelBudgetService.java`
- Consumes: 无

- [ ] **Step 1: 写失败的存在性测试**

```java
package com.jason.demo.demo2.agentscope.subagent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentscopeSubagentCodeReviewAssetsTest {

    private static final Path MODULE = Path.of(".").toAbsolutePath().normalize();

    @Test
    void subagentRolesAndTravelBudgetSampleExist() throws Exception {
        Path codeReader = MODULE.resolve("workspace/subagents/code-reader.md");
        Path riskReviewer = MODULE.resolve("workspace/subagents/risk-reviewer.md");
        Path testAdvisor = MODULE.resolve("workspace/subagents/test-advisor.md");
        Path sample = MODULE.resolve("mcp-files/TravelBudgetService.java");

        assertThat(codeReader).exists();
        assertThat(riskReviewer).exists();
        assertThat(testAdvisor).exists();
        assertThat(sample).exists();

        String reader = Files.readString(codeReader);
        assertThat(reader).contains("mode: subagent");
        assertThat(reader).contains("list_directory");
        assertThat(reader).contains("read_text_file");
        assertThat(reader).contains("steps:");

        String risk = Files.readString(riskReviewer);
        assertThat(risk).contains("mode: subagent");
        assertThat(risk).contains("tools:");

        String advisor = Files.readString(testAdvisor);
        assertThat(advisor).contains("mode: subagent");
        assertThat(advisor).contains("tools:");

        String java = Files.readString(sample);
        assertThat(java).contains("class TravelBudgetService");
        assertThat(java).contains("System.out.println");
        assertThat(java).contains("travelerContact");
        assertThat(java).contains("request.vip()");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=AgentscopeSubagentCodeReviewAssetsTest test
```

Expected: FAIL（文件不存在）

- [ ] **Step 3: 创建 `code-reader.md`**

写入 `demo2/workspace/subagents/code-reader.md`：

```markdown
---
description: >
  读取真实 Java 文件，整理类、方法、输入输出、分支和计算顺序。
  代码审查需要先确认实现事实时使用。
mode: subagent
tools: [list_directory, read_text_file]
steps: 4
---

你是代码事实整理子 Agent。

任务中会给出目标文件的完整路径。先调用 `list_directory` 确认文件存在，
再调用 `read_text_file` 读取完整内容。

只整理工具返回的事实：

1. 类和公开方法；
2. 输入、输出和主要计算；
3. 判断分支与执行顺序；
4. 外部可见的日志或副作用。

不要评价代码好坏，不要补写未读取的实现，也不要调用其他 Agent。
不要生成代码或表格。

固定输出 5 行：

文件：
类：
方法：
计算顺序：
副作用：

每行不超过 80 个字。
```

- [ ] **Step 4: 创建 `risk-reviewer.md`**

写入 `demo2/workspace/subagents/risk-reviewer.md`：

```markdown
---
description: >
  审查 Java 代码的正确性、数据安全和边界风险，并判断是否适合合并。
  代码审查需要评估实现风险时使用。
mode: subagent
tools: [list_directory, read_text_file]
steps: 6
---

你是风险审查子 Agent。

任务中会给出目标文件的完整路径。先确认文件存在并读取完整内容。
根据真实代码检查：

1. 空值与边界（除零、负数、除不尽）；
2. 逻辑一致性（折扣前后字段是否同步）；
3. 日志与敏感信息泄露；
4. 其他会导致运行失败或数据错误的问题。

不要调用其他 Agent。无法从代码确认时写「无法确认」。
不要生成补丁代码或表格。

按下面结构输出（尽量简短）：

## 严重问题
## 一般问题
## 合并建议
```

- [ ] **Step 5: 创建 `test-advisor.md`**

写入 `demo2/workspace/subagents/test-advisor.md`：

```markdown
---
description: >
  根据真实 Java 实现补充测试场景，覆盖正常路径、边界输入和已发现风险。
  代码审查需要测试建议时使用。
mode: subagent
tools: [list_directory, read_text_file]
steps: 5
---

你是测试建议子 Agent。

任务中会给出目标文件的完整路径。先确认文件存在并读取完整内容。
根据真实实现给出可执行的测试场景，至少覆盖：

1. 正常路径；
2. 边界输入（如除数为 0、金额除不尽）；
3. 已暴露的逻辑风险（如折扣与人均费用不一致）；
4. 日志脱敏相关断言思路。

不要虚构已经存在的测试结果，不要调用其他 Agent，不要生成完整测试类代码或表格。

输出格式：

## 建议测试
- （每条一行，说明输入与期望）
```

- [ ] **Step 6: 创建 `TravelBudgetService.java`**

写入 `demo2/mcp-files/TravelBudgetService.java`：

```java
package com.example.travel;

import java.math.BigDecimal;
import java.util.Map;

public class TravelBudgetService {

    public TravelBudget calculate(TravelRequest request) {
        BigDecimal hotelCost = request.hotelNightPrice()
                .multiply(BigDecimal.valueOf(request.nights()));
        BigDecimal total = request.transportCost()
                .add(hotelCost)
                .add(request.activityCost());
        BigDecimal perPerson = total.divide(
                BigDecimal.valueOf(request.travelers()));

        if (request.vip()) {
            total = total.multiply(new BigDecimal("0.90"));
        }

        System.out.println("Calculating travel budget: " + request);
        return new TravelBudget(total, perPerson);
    }

    public record TravelRequest(
            BigDecimal transportCost,
            BigDecimal hotelNightPrice,
            int nights,
            BigDecimal activityCost,
            int travelers,
            boolean vip,
            Map<String, String> travelerContact) {
    }

    public record TravelBudget(
            BigDecimal total,
            BigDecimal perPerson) {
    }
}
```

- [ ] **Step 7: 运行测试确认通过**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=AgentscopeSubagentCodeReviewAssetsTest test
```

Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add demo2/workspace/subagents/code-reader.md \
  demo2/workspace/subagents/risk-reviewer.md \
  demo2/workspace/subagents/test-advisor.md \
  demo2/mcp-files/TravelBudgetService.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/subagent/AgentscopeSubagentCodeReviewAssetsTest.java
git commit -m "$(cat <<'EOF'
feat(demo2): add AgentScope SubAgent code-review role assets

Add three workspace subagent definitions and TravelBudgetService
sample for multi-role review demos.
EOF
)"
```

---

### Task 2: 启用 SubAgent + build 前 Toolkit + Permission + AGENTS.md

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`
- Modify: `demo2/workspace/AGENTS.md`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java`

**Interfaces:**
- Consumes: Task 1 的 `workspace/subagents/*.md`（Harness 在 workspace 下扫描）
- Produces: 主 Agent Toolkit 含 `agent_spawn` / `agent_send` / `agent_list`；MCP 工具在 build 前入 Toolkit 且 build 后仍保留
- Produces: `AGENTS.md` 双路径路由文案

- [ ] **Step 1: 先改中间件测试断言（期望启用后含协作工具）**

在 `AgentScopeMiddlewareConfigTest.agentscopeDevAgent_registersCustomLoggingAndDisablesDefaultTrace` 末尾（`assertThat(agent.getToolkit()...)` 附近）追加：

```java
assertThat(agent.getToolkit().getToolNames())
        .contains("agent_spawn", "agent_send", "agent_list");
```

保留现有「mcp 关闭时不含 `list_directory`」断言。

- [ ] **Step 2: 运行相关测试确认当前失败或仍无 agent_spawn**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=AgentScopeMiddlewareConfigTest test
```

Expected: 新增断言 FAIL（仍调用 `.disableSubagents()` 时通常没有这三个工具名）

- [ ] **Step 3: 改写 `AgentScopeConfig.agentscopeDevAgent`**

要点（完整替换该方法体逻辑，保留方法签名与其它 Bean 不变）：

1. 增加 import：`io.agentscope.core.tool.Toolkit`
2. 常量增加：

```java
private static final List<String> SUBAGENT_COLLAB_TOOL_NAMES =
        List.of("agent_spawn", "agent_send", "agent_list");
```

3. 在 `builder` 组装前创建 Toolkit 并注册工具：

```java
Toolkit toolkit = new Toolkit();
toolkit.registerTool(projectInfoTools);
toolkit.registerAgentTool(fileChangeTool);
for (AgentscopeMcpClientRegistry.Entry entry : agentscopeMcpClientRegistry.entries()) {
    toolkit.registerTool(entry.tools());
}
```

4. `HarnessAgent.builder()` 增加 `.toolkit(toolkit)`，**删除** `.disableSubagents()` 行；其余 disable / memory / distributed 分支保持原样。

5. `build()` 之后仅：

```java
agent.getToolkit().removeTool("wait_async_results");
return agent;
```

**不要**再 `registerTool` MCP；**不要** `remove` MCP 工具。

6. `permissionContext` 中在只读工具规则之后增加：

```java
SUBAGENT_COLLAB_TOOL_NAMES.forEach(
        toolName -> builder.addAllowRule(toolName, allowRule(toolName)));
```

参考目标结构（示意，按现有字段拼齐）：

```java
Toolkit toolkit = new Toolkit();
toolkit.registerTool(projectInfoTools);
toolkit.registerAgentTool(fileChangeTool);
for (AgentscopeMcpClientRegistry.Entry entry : agentscopeMcpClientRegistry.entries()) {
    toolkit.registerTool(entry.tools());
}

HarnessAgent.Builder builder = HarnessAgent.builder()
        .name(properties.name())
        .sysPrompt(systemPrompt)
        .model(agentscopeDeepSeekModel)
        .toolkit(toolkit)
        .workspace(Path.of(properties.workspaceRoot()))
        .permissionContext(permissionContext(properties, agentscopeMcpClientRegistry))
        .middleware(agentExecutionLoggingMiddleware)
        .enableAgentTracingLog(false)
        .disableFilesystemTools()
        .disableShellTool()
        .compaction(agentscopeCompactionConfig)
        // 不再调用 disableSubagents()
        .disableAtPathExpansion()
        .disableDefaultWorkspaceSkills()
        .disableToolsConfig();
// ... distributed / memory 分支不变 ...
HarnessAgent agent = builder.build();
agent.getToolkit().removeTool("wait_async_results");
return agent;
```

- [ ] **Step 4: 更新 `AGENTS.md`「代码审查」小节**

将现有「代码审查」整节替换为：

```markdown
## 代码审查

- 用户要求审查代码、检查实现风险或给出测试建议时：
  - **默认**先用 `load_skill_through_path` 加载与代码审查匹配的 Skill，
    再按 Skill 中的步骤调用工具并组织结论。
  - 用户**明确**要求多角色 / SubAgent / 三角色审查时：
    - 主 Agent 只负责委派和汇总，不直接读取目标文件，
      不要使用内置的 `general-purpose`，也不要为此调用 `load_skill_through_path`。
    - 只创建下面三个子 Agent，并且每个只创建一次；
      三次调用都使用 `timeout_seconds=120`，不要设置 label：
      1. `code-reader`：读取文件并整理代码事实；
      2. `risk-reviewer`：检查正确性、数据安全和边界风险；
      3. `test-advisor`：根据真实代码给出测试建议。
    - 把目标文件的完整路径写进每个 `task`。
    - 记住每次 `agent_spawn` 返回的 `agent_key` 及其对应角色；
      若结果缺少汇总所需事实，用对应 `agent_key` 调用 `agent_send` 追问，
      不要新建同角色或其他 SubAgent；子 Agent 失败时也不用 `general-purpose` 补位。
    - 收到三个结果后，再汇总严重问题、一般问题、建议测试和是否适合合并；
      汇总时保留子 Agent 返回的类名、方法签名和字段类型，不自行改写。
```

- [ ] **Step 5: 运行测试确认通过**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=AgentScopeMiddlewareConfigTest,AgentscopeSubagentCodeReviewAssetsTest test
```

Expected: PASS（含 `agent_spawn` 断言）

若 FAIL 因工具名不同（例如缺少 `agent_list`），先用调试打印 `agent.getToolkit().getToolNames()` 核对实际名称，仅调整断言字符串，不改产品行为。

- [ ] **Step 6: 编译门禁**

Run:

```bash
mvn -f demo2/pom.xml -DskipTests compile
```

Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java \
  demo2/workspace/AGENTS.md \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java
git commit -m "$(cat <<'EOF'
feat(demo2): enable AgentScope SubAgents with pre-build Toolkit

Register tools before Harness build so subagent factories inherit MCP
readers, auto-allow agent_* collaboration tools, and document dual
Skill/SubAgent review routing in AGENTS.md.
EOF
)"
```

---

### Task 3: SSE `source` 透传

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentEvent.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/model/DevAgentEventTest.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java`（仅当工厂签名变更导致编译失败时）

**Interfaces:**
- Consumes: `io.agentscope.core.event.AgentEvent#getSource()`
- Produces: `DevAgentEvent.source()` 可空；主事件为 `null`；子事件为框架路径字符串

- [ ] **Step 1: 更新 `DevAgentEvent` record**

在 `sessionId` 之后增加 `String source`：

```java
public record DevAgentEvent(
        DevAgentEventType type,
        String sessionId,
        String source,
        String content,
        String eventId,
        String toolCallId,
        String name,
        String state,
        List<PendingToolCall> pendingToolCalls,
        String requestId,
        String traceId,
        String spanId) {
```

所有现有工厂方法在构造时对 `source` 传入 `null`（保持主 Agent 行为）。  
为工具/生命周期事件增加可带 `source` 的重载（或统一在原方法增加 `String source` 参数并改调用方）。推荐最小改动：

- 保留无 `source` 的工厂（内部传 `null`）供主路径与单测使用  
- 新增带 `source` 的重载供 `DevAgentService.mapEvent` 使用，例如：

```java
public static DevAgentEvent toolCallStart(
        String sessionId,
        String source,
        String eventId,
        String toolCallId,
        String name,
        String content) {
    return new DevAgentEvent(
            DevAgentEventType.TOOL_CALL_START,
            sessionId,
            source,
            content == null ? "" : content,
            eventId,
            toolCallId,
            name,
            null,
            null,
            null,
            null,
            null);
}

public static DevAgentEvent toolCallStart(
        String sessionId,
        String eventId,
        String toolCallId,
        String name,
        String content) {
    return toolCallStart(sessionId, null, eventId, toolCallId, name, content);
}
```

对 `toolResultEnd`、`lifecycle`、`agentResult`、`message` 同样处理（至少 mapEvent 会用到的那些）。  
`session` / `done` / `error` / `confirmation` / `requestStop` / `compaction` / `requestContext` 可继续固定 `source=null`。

注意：`@JsonInclude(NON_NULL)` 已存在，`source == null` 时不会出现在 JSON 中。

- [ ] **Step 2: 更新 `DevAgentEventTest`**

`legacyFactories_keepNullOptionalFields` 中完整构造需在 `sessionId` 后多一个 `null`（source）。  
新增用例：

```java
@Test
void toolCallStart_withSource_preservesSource() {
    DevAgentEvent start = DevAgentEvent.toolCallStart(
            "s1",
            "s1/risk-reviewer",
            "e1",
            "call-1",
            "read_text_file",
            "准备调用工具：read_text_file");
    assertThat(start.source()).isEqualTo("s1/risk-reviewer");
    assertThat(start.name()).isEqualTo("read_text_file");
}

@Test
void json_omitsNullSource() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    String session = objectMapper.writeValueAsString(DevAgentEvent.session("s1"));
    assertThat(session).doesNotContain("source");
}
```

- [ ] **Step 3: 改 `DevAgentService.mapEvent`**

在方法开头：

```java
String source = event.getSource();
```

各分支调用带 `source` 的工厂，例如：

```java
case TOOL_CALL_START -> {
    ToolCallStartEvent e = (ToolCallStartEvent) event;
    yield DevAgentEvent.toolCallStart(
            sessionId,
            source,
            e.getId(),
            e.getToolCallId(),
            e.getToolCallName(),
            "准备调用工具：" + e.getToolCallName());
}
case TOOL_RESULT_END -> {
    ToolResultEndEvent e = (ToolResultEndEvent) event;
    yield DevAgentEvent.toolResultEnd(
            sessionId,
            source,
            e.getId(),
            e.getToolCallId(),
            e.getToolCallName(),
            e.getState() == null ? null : e.getState().name());
}
case AGENT_RESULT -> {
    AgentResultEvent e = (AgentResultEvent) event;
    String text = e.getResult() == null ? "" : e.getResult().getTextContent();
    yield DevAgentEvent.agentResult(sessionId, source, e.getId(), text);
}
case TEXT_BLOCK_DELTA -> DevAgentEvent.message(
        sessionId, source, ((TextBlockDeltaEvent) event).getDelta());
```

`AGENT_START` / `MODEL_CALL_START` / `AGENT_END` / `REQUIRE_USER_CONFIRM` / `REQUEST_STOP` 也传入 `source`（子 Agent 生命周期需要可区分）。

- [ ] **Step 4: 修复 `DevAgentServiceTest` 编译**

若 `DevAgentEvent` 全参构造或工厂签名变更导致测试编译失败，按「source 为 null」对齐期望对象；不改测试业务语义。

- [ ] **Step 5: 运行测试**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=DevAgentEventTest,DevAgentServiceTest test
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentEvent.java \
  demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/model/DevAgentEventTest.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java
git commit -m "$(cat <<'EOF'
feat(demo2): pass AgentEvent source through DevAgent SSE

Expose nullable source on DevAgentEvent so SubAgent tool and lifecycle
events can be distinguished from the primary agent stream.
EOF
)"
```

---

### Task 4: README + 前端示例 + 验证清单

**Files:**
- Modify: `demo2/README.md`
- Modify: `demo2/src/main/resources/static/index.html`
- Modify: `demo2/src/main/resources/static/js/tabs/agentscope.js`

**Interfaces:**
- Produces: 文档与 UI 可演示 SubAgent 三角色审查
- Consumes: Task 1–3 全部产物

- [ ] **Step 1: 更新 README**

在 AgentScope Harness 能力表 / `DevAgentController` 描述中追加 **SubAgent 三角色审查**（与 Dynamic Skills 并列简述）。

在 `/agentscope/dev-agent` 文档区新增 **SubAgent（code-reader / risk-reviewer / test-advisor）** 小节，要点：

- 角色目录：`workspace/subagents/`（三个 md）
- 引导在 `AGENTS.md`：默认 Skill；提「多角色 / SubAgent / 三角色」才委派
- 样例：`mcp-files/TravelBudgetService.java`
- Toolkit：build 前注册 MCP；主 Agent 保留读文件工具；Permission 放行 `agent_*`
- SSE：`DevAgentEvent.source` 区分主/子事件（前端本轮可不渲染）
- 不改 `system-prompt`；与 Spring AI Subagent Tab 隔离

追加 curl（端口以本仓库为准 **8081**）：

```bash
# SubAgent 路径
curl -sN -X POST "http://localhost:8081/agentscope/dev-agent/ask" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"subagent-user-014\",\"sessionId\":\"subagent-session-014\",\"message\":\"请用 SubAgent 多角色审查 MCP 资料目录里的 TravelBudgetService.java，并给出是否适合合并的结论。\"}"

# 同会话追问
curl -sN -X POST "http://localhost:8081/agentscope/dev-agent/ask" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"subagent-user-014\",\"sessionId\":\"subagent-session-014\",\"message\":\"再确认一下：VIP 折扣和 perPerson 的计算顺序是什么？优先复用刚才的 code-reader，不要新建子 Agent。\"}"
```

成功标准（写入 README 即可）：约 3 次 `agent_spawn`；读文件事件 `source` 落在子 Agent；汇总含严重/一般/建议测试/合并结论；能点出除零、VIP 与 perPerson 不一致、敏感日志。Skill 回归仍用既有示例 11 / `UserProfileFormatter` curl。

- [ ] **Step 2: 前端示例 12**

`index.html` 在 Code Review Skill 按钮旁增加：

```html
<button type="button" onclick="fillAgentscopeSample(12)">示例：Code Review SubAgent</button>
```

欢迎文案（两处：静态 welcome + `resetAgentscopeConversation`）追加：可用「Code Review SubAgent」验证三角色委派与 `source`。

`agentscope.js` 的 `samples` 增加：

```javascript
12: '请用 SubAgent 多角色审查 MCP 资料目录里的 TravelBudgetService.java，并给出是否适合合并的结论。'
```

并在 `n === 12` 时设置：

```javascript
if (n === 12) {
    const userId = document.getElementById('agentscopeUserId');
    const sessionId = document.getElementById('agentscopeSessionId');
    if (userId) userId.value = 'subagent-user-014';
    if (sessionId) sessionId.value = 'subagent-session-014';
}
```

- [ ] **Step 3: 跑单测门禁**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=AgentscopeSubagentCodeReviewAssetsTest,DevAgentEventTest,DevAgentServiceTest,AgentScopeMiddlewareConfigTest test
```

Expected: PASS

- [ ] **Step 4: 手工验证（需 DEEPSEEK_API_KEY + MCP/npx）**

1. 启动：`mvn -f demo2/pom.xml spring-boot:run`
2. 执行 Step 1 的 SubAgent curl（或前端示例 12）
3. 确认 SSE 含约三次 `agent_spawn`，子 Agent 读文件事件带非空 `source`
4. 确认最终汇总与关键缺陷
5. 同 session 追问 VIP/perPerson，优先看到 `agent_send`
6. 回归：前端示例 11 / Skill curl，仍出现 `load_skill_through_path`

不要求工具次数固定；不做强制 LLM 集成测试入库。

- [ ] **Step 5: Commit**

```bash
git add demo2/README.md \
  demo2/src/main/resources/static/index.html \
  demo2/src/main/resources/static/js/tabs/agentscope.js
git commit -m "$(cat <<'EOF'
docs(demo2): document AgentScope SubAgent code-review demo

Add README curl recipes and frontend sample 12 for multi-role
SubAgent review alongside the existing Skill path.
EOF
)"
```

---

## Spec Coverage Checklist

| Spec 要求 | Task |
|-----------|------|
| 去掉 `.disableSubagents()` | Task 2 |
| `workspace/subagents/{code-reader,risk-reviewer,test-advisor}.md` | Task 1 |
| `mcp-files/TravelBudgetService.java` | Task 1 |
| build 前注册 Toolkit/MCP；build 后保留 | Task 2 |
| Permission 放行 `agent_spawn` / `agent_send` / `agent_list` | Task 2 |
| `AGENTS.md` 双路径；不改 system-prompt | Task 2 |
| `DevAgentEvent.source` 透传 | Task 3 |
| 保留 Skill / UserProfileFormatter | Task 1 Keep + Task 4 回归 |
| 轻量资产/事件/中间件测试 | Task 1、2、3 |
| README + 前端示例 + 手工 curl | Task 4 |
| 不新建 API/Tab/Workflow；不强制渲染 source | 全任务遵守 Global Constraints |

## Self-Review Notes

- 无 TBD/TODO 占位；角色 md 与样例 Java 全文已写入 Task 1。
- `HarnessAgent.Builder.toolkit(Toolkit)` 已在 2.0.0 确认存在；`AgentEvent.getSource()` 已确认。
- 本仓库端口为 **8081**（不是文章里的 8080）；curl 已按此写。
- Windows PowerShell 下 HEREDOC 可能不可用：执行 commit 时可用 `git commit -m "feat(demo2): ..."` 单行等价信息，或按仓库既有 PowerShell here-string 习惯。
- 存在性测试 `Path.of(".")` 依赖 surefire 模块 cwd（demo2），与 `AgentscopeCodeReviewerSkillAssetsTest` 一致。
- 若启用 SubAgent 后工具名与断言不完全一致，仅改测试字符串；产品侧仍以 Harness 内置名为准。
