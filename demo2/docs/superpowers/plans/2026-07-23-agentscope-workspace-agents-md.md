# AgentScope Workspace / AGENTS.md Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 启用 HarnessAgent Workspace Context，使每轮推理注入 `workspace/AGENTS.md`；项目规则从 YAML 迁出，并附带空的 `MEMORY.md` / `knowledge/KNOWLEDGE.md` 骨架。

**Architecture:** `DevAgentProperties` 增加 `workspaceRoot`；`HarnessAgent.builder().workspace(Path.of(...))` 并删除 `.disableWorkspaceContext()`。仍关闭内置 filesystem / Shell / memory 工具。`system-prompt` 精简为基础角色；稳定项目约定写入 `demo2/workspace/AGENTS.md`。

**Tech Stack:** Java 21, Spring Boot 4.x, AgentScope Java 2.0.0 (`agentscope-harness`), JUnit 5, AssertJ, Spring Boot `ApplicationContextRunner`

**设计规范:** [docs/superpowers/specs/2026-07-23-agentscope-workspace-agents-md-design.md](../specs/2026-07-23-agentscope-workspace-agents-md-design.md)

## Global Constraints

- **AgentScope 版本**：`2.0.0`
- **落地方式**：Harness 原生 `.workspace(...)` + 移除 `.disableWorkspaceContext()`（非手动拼 prompt）
- **配置键**：`app.agentscope.dev-agent.workspace-root=workspace`
- **项目标识（AGENTS.md）**：名称 `agentscope-java`；任务编号 `WORKSPACE-008`
- **删除文件**：AGENTS.md 明确「不要尝试删除文件」
- **仍 disable**：`disableFilesystemTools` / `disableShellTool` / `disableMemoryTools` / `disableMemoryHooks` / `disableCompaction` / `disableSubagents` / `disableAtPathExpansion` / `disableDynamicSkills` / `disableDefaultWorkspaceSkills` / `disableToolsConfig`
- **不修改**：Controller API、SSE 事件模型、前端 Tab、Postgres、HITL 权限规则
- **编译门禁**：`mvn -f demo2/pom.xml -DskipTests compile` 必须 SUCCESS
- **单测门禁**：`mvn -f demo2/pom.xml -Dtest=DevAgentServiceTest,DevAgentPropertiesBindingTest test` 必须 SUCCESS

---

## File Structure

| 文件 | 职责 |
|------|------|
| `.../agentscope/config/DevAgentProperties.java` | 增加 `workspaceRoot` |
| `demo2/src/main/resources/application.properties` | `workspace-root=workspace` |
| `demo2/src/main/resources/application-agentscope-prompts.yml` | 精简 system-prompt |
| `.../agentscope/config/AgentScopeConfig.java` | `.workspace(...)`；去掉 `.disableWorkspaceContext()` |
| `demo2/workspace/AGENTS.md` | 项目规则（可验证字段 + 现有约定） |
| `demo2/workspace/MEMORY.md` | 空骨架 |
| `demo2/workspace/knowledge/KNOWLEDGE.md` | 空骨架 |
| `.../DevAgentServiceTest.java` | 构造器补 `workspaceRoot` 参数 |
| `.../DevAgentPropertiesBindingTest.java` | 属性绑定含 `workspaceRoot` |
| `demo2/README.md` | AgentScope 小节补 Workspace 说明 + curl |

**目标 record 签名：**

```java
public record DevAgentProperties(
        @NotBlank String name,
        @NotBlank String systemPrompt,
        @NotBlank String projectRoot,
        @NotBlank String workspaceRoot,
        @Valid Model model) { ... }
```

**目标 builder 片段：**

```java
HarnessAgent.builder()
    // ...
    .workspace(Path.of(properties.workspaceRoot()))
    // 不再调用 .disableWorkspaceContext()
    .disableFilesystemTools()
    .disableShellTool()
    // ...其余 disable 保持
    .build();
```

---

### Task 1: DevAgentProperties + 属性配置 + 测试构造修复

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/DevAgentProperties.java`
- Modify: `demo2/src/main/resources/application.properties`（`app.agentscope.dev-agent` 段）
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/DevAgentPropertiesBindingTest.java`

**Interfaces:**
- Produces: `DevAgentProperties(..., String workspaceRoot, Model model)`；配置键 `app.agentscope.dev-agent.workspace-root`
- Consumes: 无

- [ ] **Step 1: 写失败的属性绑定测试**

创建 `DevAgentPropertiesBindingTest.java`：

```java
package com.jason.demo.demo2.agentscope.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DevAgentPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "app.agentscope.dev-agent.name=dev-task-agent",
                    "app.agentscope.dev-agent.system-prompt=short",
                    "app.agentscope.dev-agent.project-root=.",
                    "app.agentscope.dev-agent.workspace-root=workspace",
                    "app.agentscope.dev-agent.model.api-key=",
                    "app.agentscope.dev-agent.model.base-url=https://api.deepseek.com",
                    "app.agentscope.dev-agent.model.name=deepseek-v4-pro");

    @Test
    void bindsWorkspaceRoot() {
        runner.run(ctx -> {
            DevAgentProperties props = ctx.getBean(DevAgentProperties.class);
            assertThat(props.workspaceRoot()).isEqualTo("workspace");
            assertThat(props.projectRoot()).isEqualTo(".");
        });
    }

    @EnableConfigurationProperties(DevAgentProperties.class)
    static class TestConfig {
    }
}
```

- [ ] **Step 2: 运行测试，确认当前失败（缺字段 / 绑定不全）**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=DevAgentPropertiesBindingTest test
```

Expected: FAIL（`DevAgentProperties` 尚无 `workspaceRoot`，或构造/绑定不匹配）

- [ ] **Step 3: 扩展 `DevAgentProperties`**

将 record 改为：

```java
@Validated
@ConfigurationProperties(prefix = "app.agentscope.dev-agent")
public record DevAgentProperties(
        @NotBlank String name,
        @NotBlank String systemPrompt,
        @NotBlank String projectRoot,
        @NotBlank String workspaceRoot,
        @Valid Model model) {

    /**
     * apiKey 允许为空：缺 DEEPSEEK_API_KEY 时不阻止应用启动，由 Service 在 ask 时返回 ERROR。
     */
    public record Model(
            String apiKey,
            @NotBlank String baseUrl,
            @NotBlank String name) {
    }
}
```

- [ ] **Step 4: 写入 application.properties**

在 `app.agentscope.dev-agent.project-root=.` 后增加：

```properties
app.agentscope.dev-agent.workspace-root=workspace
```

- [ ] **Step 5: 修复 `DevAgentServiceTest` 所有构造调用**

每个 `new DevAgentProperties(...)` 在 `projectRoot` 后插入 `"workspace"`，例如：

```java
properties = new DevAgentProperties(
        "dev-task-agent",
        "prompt",
        ".",
        "workspace",
        new DevAgentProperties.Model("sk-test", "https://api.deepseek.com", "deepseek-v4-pro"));
```

（含 `ask_whenApiKeyBlank_emitsError` 等处的第二处构造，同样插入。）

- [ ] **Step 6: 运行测试确认通过**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=DevAgentPropertiesBindingTest,DevAgentServiceTest test
```

Expected: BUILD SUCCESS，测试全绿

- [ ] **Step 7: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/DevAgentProperties.java \
  demo2/src/main/resources/application.properties \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/config/DevAgentPropertiesBindingTest.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java
git commit -m "feat(demo2): add AgentScope workspace-root to DevAgentProperties"
```

---

### Task 2: 新增 workspace 文件树

**Files:**
- Create: `demo2/workspace/AGENTS.md`
- Create: `demo2/workspace/MEMORY.md`
- Create: `demo2/workspace/knowledge/KNOWLEDGE.md`

**Interfaces:**
- Produces: 默认相对路径 `workspace/` 下可读的 AGENTS / MEMORY / knowledge 入口
- Consumes: 无

- [ ] **Step 1: 写入 `AGENTS.md`（完整内容，勿截断）**

```markdown
# Dev Project Agent

## 项目背景

- 当前项目名称是 `agentscope-java`。
- 当前项目理解任务编号是 `WORKSPACE-008`。
- 项目理解顺序是：先看 Maven 配置，再看源码目录，最后确认启动类。

## 工作方式

- 用户询问 Maven 配置、Java 版本、Spring Boot 版本、源码目录或启动类时，先调用对应的只读工具。
- 当前没有日志、数据库和 Shell 工具，不要声称已经查询日志、数据库或执行命令。
- 信息不足时，直接指出还缺什么，不编造排查结果。

## 文件变更

- 只有用户明确要求创建或修改文件时，才调用 `request_file_change`。
- 目标路径必须位于 `notes/`。
- 用户已经给出操作、路径和内容时，直接调用 `request_file_change`，不要在对话里再次询问是否确认。
- 工具进入待确认状态后，等待 Permission System 返回确认结果；确认前不能声称文件已经保存。
- 不要尝试删除文件。

## 输出要求

- 回答控制在 6 条以内。
- 汇总项目理解结果时，区分“已经确认”和“还需要确认”。
- 不写开场白和重复总结。
```

- [ ] **Step 2: 写入骨架 `MEMORY.md`**

```markdown
# MEMORY

本版留空。对话中形成的长期记忆尚未启用写入。勿在此文件写入 API Key 或其他秘密。
```

- [ ] **Step 3: 写入骨架 `knowledge/KNOWLEDGE.md`**

```markdown
# KNOWLEDGE

本版留空。大段项目资料与接口文档可在后续放入 `knowledge/`。勿在此文件写入 API Key 或其他秘密。
```

- [ ] **Step 4: 确认文件存在**

Run (PowerShell):

```powershell
Get-ChildItem -Recurse demo2/workspace | Select-Object FullName
```

Expected: 列出 `AGENTS.md`、`MEMORY.md`、`knowledge\KNOWLEDGE.md`

- [ ] **Step 5: Commit**

```bash
git add demo2/workspace/AGENTS.md demo2/workspace/MEMORY.md demo2/workspace/knowledge/KNOWLEDGE.md
git commit -m "feat(demo2): add AgentScope workspace AGENTS.md and skeletons"
```

---

### Task 3: 精简 prompt + 启用 Workspace Context

**Files:**
- Modify: `demo2/src/main/resources/application-agentscope-prompts.yml`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`

**Interfaces:**
- Consumes: `DevAgentProperties.workspaceRoot()`（Task 1）
- Produces: `HarnessAgent` 使用 `.workspace(Path.of(workspaceRoot))` 且不再 `disableWorkspaceContext`

- [ ] **Step 1: 精简 `application-agentscope-prompts.yml`**

整文件替换为：

```yaml
# AgentScope DevAgent 中文 Prompt（YAML 保证 UTF-8）
# 项目规则见 workspace/AGENTS.md（由 Workspace Context 注入）
app:
  agentscope:
    dev-agent:
      system-prompt: |
        你是一个研发任务整理助手。
        根据用户请求整理任务，必要时调用已经注册的工具。
        使用中文回答，并遵守 Workspace 中的项目规则。
```

注意：YAML 中**不得**再出现 `agentscope-java`、`WORKSPACE-008`、三步理解顺序等可验证字段（这些只属于 `AGENTS.md`）。

- [ ] **Step 2: 修改 `AgentScopeConfig.agentscopeDevAgent`**

将 builder 改为（保留其余 disable 与工具注册逻辑）：

```java
HarnessAgent agent = HarnessAgent.builder()
        .name(properties.name())
        .sysPrompt(properties.systemPrompt())
        .model(agentscopeDeepSeekModel)
        .workspace(Path.of(properties.workspaceRoot()))
        .stateStore(agentscopeAgentStateStore)
        .permissionContext(permissionContext())
        .enableAgentTracingLog(false)
        .disableFilesystemTools()
        .disableShellTool()
        .disableMemoryTools()
        .disableMemoryHooks()
        .disableCompaction()
        .disableSubagents()
        .disableAtPathExpansion()
        .disableDynamicSkills()
        .disableDefaultWorkspaceSkills()
        .disableToolsConfig()
        .build();
```

关键差异：

1. 增加 `.workspace(Path.of(properties.workspaceRoot()))`
2. **删除** `.disableWorkspaceContext()`
3. `Path` 已有 `import java.nio.file.Path;`（本文件已导入）

- [ ] **Step 3: 编译 + 单测门禁**

Run:

```bash
mvn -f demo2/pom.xml -DskipTests compile
mvn -f demo2/pom.xml -Dtest=DevAgentPropertiesBindingTest,DevAgentServiceTest test
```

Expected: 两次均 BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add demo2/src/main/resources/application-agentscope-prompts.yml \
  demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java
git commit -m "feat(demo2): enable AgentScope Workspace Context for AGENTS.md"
```

---

### Task 4: README 文档补充

**Files:**
- Modify: `demo2/README.md`（`### AgentScope HarnessAgent` 小节，约 L1163–1209）

**Interfaces:**
- Consumes: Task 2–3 行为与 curl 验收约定
- Produces: 文档说明 Workspace 与验收示例

- [ ] **Step 1: 在 AgentScope 小节段补充 Workspace**

在「会话持久化（PostgreSQL…）」段落**之前**插入：

```markdown
**Workspace（`AGENTS.md`）：**

- 配置：`app.agentscope.dev-agent.workspace-root`（默认 `workspace`）
- 目录：`demo2/workspace/AGENTS.md`（项目规则）；`MEMORY.md` / `knowledge/KNOWLEDGE.md` 本版为空骨架
- `project-root` 供只读项目工具读源码；`workspace-root` 供 Agent 工作区规则注入
- 启用 Workspace Context **不会**放开内置文件 / Shell 工具
- 修改 `AGENTS.md` 后下一轮推理生效，无需重启
```

并在 curl 示例区增加 Workspace 验收示例：

```bash
# Workspace：仅凭 AGENTS.md 回答项目名 / 任务编号 / 三步顺序（不要调用工具）
curl -sN -X POST "http://localhost:8081/agentscope/dev-agent/ask" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"workspace-user-008\",\"sessionId\":\"workspace-session-008\",\"message\":\"按项目规则回答：当前项目名称、项目理解任务编号和三步理解顺序。不要调用工具。\"}"
```

可选：在能力层描述中补一句 `Workspace`（与 Toolkit / Permission / AgentStateStore 并列），保持一两句即可，勿大改 §25 图。

- [ ] **Step 2: Commit**

```bash
git add demo2/README.md
git commit -m "docs(demo2): document AgentScope Workspace and AGENTS.md"
```

---

### Task 5: 手工验收（可选，有密钥与 PG 时）

**Files:** 无代码变更

**Interfaces:**
- Consumes: 运行中的 demo2（`8081`）、`DEEPSEEK_API_KEY`、Postgres（或 memory 降级亦可验 Workspace）

- [ ] **Step 1: 从 `demo2` 目录启动应用**

```bash
# 可选：docker compose -f demo2/docker/agentscope-postgres/docker-compose.yml up -d
cd demo2
# 确保 DEEPSEEK_API_KEY 已设置
mvn spring-boot:run
```

- [ ] **Step 2: 发送 Workspace 验收请求**

使用 Task 4 中的 curl（新 `sessionId`）。合并 SSE 中 `AGENT_RESULT`，应包含：

1. `agentscope-java`
2. `WORKSPACE-008`
3. 先看 Maven 配置 → 再看源码目录 → 最后确认启动类

列表样式不作为契约。确认 YAML prompt 中无上述字段。

- [ ] **Step 3: 热更新抽查**

将 `AGENTS.md` 中 `WORKSPACE-008` 改为 `WORKSPACE-008-HOT`，换**新** `sessionId` 再请求；应返回新编号且无需重启。验完可改回 `WORKSPACE-008`（若改回，单独 commit 或保持与设计一致）。

- [ ] **Step 4: 回归抽查（简短）**

任选一条既有 curl（项目问答或写 notes HITL），确认工具 / 确认流仍正常。

---

## Spec coverage（自检）

| Spec 要求 | Task |
|-----------|------|
| `workspaceRoot` 配置 | Task 1 |
| `workspace/AGENTS.md` + MEMORY + knowledge 骨架 | Task 2 |
| 精简 system-prompt | Task 3 |
| `.workspace` + 去掉 `disableWorkspaceContext` | Task 3 |
| 仍 disable 文件/Shell 等 | Task 3（显式保留） |
| 删除规则「不要尝试删除」 | Task 2 AGENTS.md |
| 可验证字段 agentscope-java / WORKSPACE-008 / 三步 | Task 2 |
| 属性绑定测试 | Task 1 |
| README | Task 4 |
| curl 验收 / 热更新 | Task 5 |
| 不改 API/SSE/前端/Postgres/HITL | 全任务遵守 Global Constraints |

**Placeholder scan:** 无 TBD /「类似 Task N」/ 空测试步骤。

**Type consistency:** `workspaceRoot` 贯穿 properties、record、`Path.of(properties.workspaceRoot())`、绑定测试与 README 配置键一致。
