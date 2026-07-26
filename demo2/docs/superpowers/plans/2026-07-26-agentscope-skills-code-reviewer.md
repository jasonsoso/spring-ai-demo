# AgentScope Skills Code-Reviewer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 AgentScope Dev Agent 上启用动态 Skill，落地 `code-reviewer`（含一页 `references`）与样例 Java，使审查请求按需加载 SOP 后通过 MCP 读代码并按四段格式输出。

**Architecture:** 去掉 `.disableDynamicSkills()`，Harness 扫描 `workspace/skills/`；`AGENTS.md` 引导先 `load_skill_through_path`；样例源码放 `mcp-files/` 由既有 filesystem MCP 读取；`compaction.trigger-messages` 调至 12。不改 system-prompt、Controller、SSE、Permission。

**Tech Stack:** Java 21、Spring Boot 4.1、AgentScope Java 2.0（Harness 动态 Skill）、JUnit 6、AssertJ、既有 stdio MCP filesystem。

## Global Constraints

- 设计规范：`demo2/docs/superpowers/specs/2026-07-26-agentscope-skills-code-reviewer-design.md`
- AgentScope 保持 `2.0.0`，**不新增** Maven 依赖
- **不改** `application-agentscope-prompts.yml` 的 `system-prompt`
- **保留** `.disableDefaultWorkspaceSkills()`
- Skill 路径必须是 `workspace/skills/code-reviewer/`（共享目录），**不要**放到 `.claude/skills`
- 与 Spring AI `/agent/skills` **无关、不打通**
- **不**用 Workflow 强制工具顺序；**不**做结构化输出校验
- 编译门禁：`mvn -f demo2/pom.xml -DskipTests compile`
- 单测门禁：`mvn -f demo2/pom.xml -Dtest=AgentscopeCodeReviewerSkillAssetsTest,DevAgentPropertiesBindingTest,AgentscopeCompactionConfigTest,AgentScopeMiddlewareConfigTest test`

## File Map

**Create**

- `demo2/workspace/skills/code-reviewer/SKILL.md`
- `demo2/workspace/skills/code-reviewer/references/java-style-guide.md`
- `demo2/mcp-files/UserProfileFormatter.java`
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/skill/AgentscopeCodeReviewerSkillAssetsTest.java`

**Modify**

- `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`：去掉 `.disableDynamicSkills()`
- `demo2/workspace/AGENTS.md`：新增「代码审查」小节
- `demo2/src/main/resources/application.properties`：`trigger-messages=12` + 注释
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/DevAgentPropertiesBindingTest.java`：绑定值改为 12
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentscopeCompactionConfigTest.java`：输入改为 12
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java`：断言存在 `load_skill_through_path`
- `demo2/src/main/resources/static/index.html`：示例按钮 + 欢迎文案
- `demo2/src/main/resources/static/js/tabs/agentscope.js`：示例 11（Code Review）+ Compaction 轮次说明
- `demo2/README.md`：能力表、Skills 小节、Compaction 频率与 curl、验证命令

---

### Task 1: Skill 资产与样例 Java + 存在性测试

**Files:**
- Create: `demo2/workspace/skills/code-reviewer/SKILL.md`
- Create: `demo2/workspace/skills/code-reviewer/references/java-style-guide.md`
- Create: `demo2/mcp-files/UserProfileFormatter.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/agentscope/skill/AgentscopeCodeReviewerSkillAssetsTest.java`
- Keep: `demo2/mcp-files/project-profile.md`（勿删）

**Interfaces:**
- Produces: Skill 目录可被 Harness 发现（`name: code-reviewer`）
- Produces: MCP 可读样例 `UserProfileFormatter.java`
- Consumes: 无

- [ ] **Step 1: 写失败的存在性测试**

```java
package com.jason.demo.demo2.agentscope.skill;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentscopeCodeReviewerSkillAssetsTest {

    private static final Path MODULE = Path.of(".").toAbsolutePath().normalize();

    @Test
    void codeReviewerSkillAndSampleJavaExist() throws Exception {
        Path skill = MODULE.resolve("workspace/skills/code-reviewer/SKILL.md");
        Path guide = MODULE.resolve(
                "workspace/skills/code-reviewer/references/java-style-guide.md");
        Path sample = MODULE.resolve("mcp-files/UserProfileFormatter.java");

        assertThat(skill).exists();
        assertThat(guide).exists();
        assertThat(sample).exists();

        String skillMd = Files.readString(skill);
        assertThat(skillMd).contains("name: code-reviewer");
        assertThat(skillMd).contains("load_skill_through_path");
        assertThat(skillMd).contains("## 严重问题");
        assertThat(skillMd).contains("references/java-style-guide.md");
        assertThat(skillMd).doesNotContain("UserProfileFormatter");

        String java = Files.readString(sample);
        assertThat(java).contains("System.out.println");
        assertThat(java).contains("user.get(\"name\")");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=AgentscopeCodeReviewerSkillAssetsTest test
```

Expected: FAIL（文件不存在或内容断言失败）

- [ ] **Step 3: 创建 SKILL.md**

写入 `demo2/workspace/skills/code-reviewer/SKILL.md`：

```markdown
---
name: code-reviewer
description: >
  审查 Java 代码的正确性、数据安全和测试缺口。
  用户要求代码评审、检查实现风险或给出测试建议时使用。
---

# Java Code Review

审查代码时按下面的顺序执行：

1. 先调用 `list_directory` 确认目标文件确实存在。
2. 再调用 `read_text_file` 读取完整代码。
3. 检查空值、异常处理、资源释放、并发和边界条件。
4. 检查日志和数据处理是否可能泄露敏感信息。
5. 根据问题补充测试建议。
6. 需要风格细则时，用 `load_skill_through_path` 读取 `references/java-style-guide.md`（相对本 Skill 目录，禁止绝对路径与 `../`）。

## 审查边界

- 没有读取到代码 → 不给出审查结论。
- 无法从代码确认 → 明确写「无法确认」。
- 只有个人风格偏好 → 不当成代码缺陷。

## 交付格式

按下面四个标题组织最终回答：

## 严重问题

只放可能导致运行失败、数据泄露、越权或数据错误的缺陷。

## 一般问题

可改进但不立刻阻断合并的问题。

## 建议测试

测试建议要能覆盖发现的问题，不能虚构已经存在的测试结果。

## 结论

明确是否适合直接合并，以及必须先处理哪些问题。
```

- [ ] **Step 4: 创建 java-style-guide.md**

写入 `demo2/workspace/skills/code-reviewer/references/java-style-guide.md`：

```markdown
# Java 审查要点（精简）

## 空值与边界

- 公共方法参数可能为 null；`Map.get` 可能返回 null。
- 对可能为 null 的值调用 `.toString()` / `.strip()` 前先判空。
- 空字符串与空白字符串要当作无效输入考虑。

## 日志与敏感信息

- 不要用 `System.out.println` 打印完整用户对象、Token、密码、手机号、邮箱。
- 日志只保留必要字段；优先使用结构化日志，避免把 Map/DTO 整对象拼接进消息。

## 职责与命名

- 一个方法避免同时承担「格式化返回值」和「副作用日志」。
- 类型尽量明确；`Map<String, Object>` 缺少字段约定时，在审查中指出风险。

## 测试缺口

至少覆盖：参数为 null、关键字段缺失/为空/仅空白、输入含敏感字段时日志是否泄露。
```

- [ ] **Step 5: 创建样例 Java**

写入 `demo2/mcp-files/UserProfileFormatter.java`：

```java
package com.example.profile;

import java.util.Map;

public class UserProfileFormatter {

    public String displayName(Map<String, Object> user) {
        System.out.println("Formatting user profile: " + user);
        return user.get("name").toString().strip();
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=AgentscopeCodeReviewerSkillAssetsTest test
```

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add demo2/workspace/skills/code-reviewer/SKILL.md \
  demo2/workspace/skills/code-reviewer/references/java-style-guide.md \
  demo2/mcp-files/UserProfileFormatter.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/skill/AgentscopeCodeReviewerSkillAssetsTest.java
git commit -m "$(cat <<'EOF'
feat(demo2): add AgentScope code-reviewer skill assets

Add shared SKILL.md, style guide reference, and intentional NPE/leak
sample Java for dynamic Skills code-review demos.
EOF
)"
```

---

### Task 2: 启用动态 Skill + AGENTS.md 引导 + Toolkit 断言

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`
- Modify: `demo2/workspace/AGENTS.md`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java`

**Interfaces:**
- Produces: Harness 注册 `load_skill_through_path`（动态 Skill 开启）
- Consumes: Task 1 的 `workspace/skills/code-reviewer/`（运行时发现；本 Task 单测用 TempDir 空 workspace 仍应有加载工具）

- [ ] **Step 1: 在 Middleware 测试中增加失败断言**

在 `AgentScopeMiddlewareConfigTest` 的 `agentscopeDevAgent_registersCustomLoggingAndDisablesDefaultTrace`（或新建同结构测试）末尾增加：

```java
assertThat(agent.getToolkit().getToolNames())
        .contains("load_skill_through_path");
```

若当前仍调用 `.disableDynamicSkills()`，本断言应失败。

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=AgentScopeMiddlewareConfigTest test
```

Expected: FAIL（缺少 `load_skill_through_path`）

- [ ] **Step 3: 去掉 disableDynamicSkills**

在 `AgentScopeConfig.java` 的 `HarnessAgent.Builder` 链中，**删除**这一行：

```java
.disableDynamicSkills()
```

保留其前后相邻调用不变，例如：

```java
.disableSubagents()
.disableAtPathExpansion()
.disableDefaultWorkspaceSkills()
.disableToolsConfig();
```

- [ ] **Step 4: 更新 AGENTS.md**

在 `demo2/workspace/AGENTS.md` 的「工作方式」与「文件变更」之间（或「输出要求」之前）插入：

```markdown
## 代码审查

- 用户要求审查代码、检查实现风险或给出测试建议时，先用 `load_skill_through_path` 加载与代码审查匹配的 Skill，再按 Skill 中的步骤调用工具并组织结论。
```

**不要**改 `application-agentscope-prompts.yml`。

- [ ] **Step 5: 运行测试确认通过**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=AgentScopeMiddlewareConfigTest,AgentscopeCodeReviewerSkillAssetsTest test
```

Expected: PASS

若工具名不是 `load_skill_through_path`（例如带前缀），用调试打印 `getToolNames()` 对齐真实名称后再改断言，但不要重新关掉动态 Skill。

- [ ] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java \
  demo2/workspace/AGENTS.md \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java
git commit -m "$(cat <<'EOF'
feat(demo2): enable AgentScope dynamic Skills

Remove disableDynamicSkills, guide code review via AGENTS.md, and
assert load_skill_through_path is registered on the toolkit.
EOF
)"
```

---

### Task 3: Compaction 频率改为 12 + 同步测试与 Demo 文案

**Files:**
- Modify: `demo2/src/main/resources/application.properties`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/DevAgentPropertiesBindingTest.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentscopeCompactionConfigTest.java`
- Modify: `demo2/src/main/resources/static/index.html`（Compaction 按钮/欢迎文案）
- Modify: `demo2/src/main/resources/static/js/tabs/agentscope.js`（欢迎文案中的「四轮」）
- Modify: `demo2/README.md`（Compaction 小节里依赖阈值 6 的说明）

**Interfaces:**
- Produces: 默认 `triggerMessages == 12`
- Consumes: 既有 `DevAgentProperties.Compaction`

- [ ] **Step 1: 更新失败的绑定/组装期望**

`DevAgentPropertiesBindingTest`：

- runner 默认属性改为 `"app.agentscope.dev-agent.compaction.trigger-messages=12"`
- `bindsCompaction` 中 `assertThat(c.triggerMessages()).isEqualTo(12);`

`AgentscopeCompactionConfigTest`：

```java
DevAgentProperties.Compaction input = new DevAgentProperties.Compaction(
        12, 2, "请整理：{messages}");
// ...
assertThat(config.getTriggerMessages()).isEqualTo(12);
```

其他测试里手动 `new Compaction(6, ...)` **可保留**（那是局部夹具，不是默认配置）。

- [ ] **Step 2: 运行测试确认当前配置下失败**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=DevAgentPropertiesBindingTest,AgentscopeCompactionConfigTest test
```

Expected: `bindsCompaction` FAIL（properties 仍是 6）和/或组装测试已先改期望则仅绑定失败。

- [ ] **Step 3: 改 application.properties**

将：

```properties
# Demo 默认偏低，便于四轮触发；正式环境请按上下文窗口 / 工具结果大小 / 平均轮数上调（勿贴模型上限）
app.agentscope.dev-agent.compaction.trigger-messages=6
```

改为：

```properties
# Skills 审查会连续加载 Skill + 读文件；12 降低刚加载规则被压缩的概率。Compaction Demo 需约七轮（user+assistant）才易触发。
app.agentscope.dev-agent.compaction.trigger-messages=12
```

- [ ] **Step 4: 同步前端/README 中「四轮」表述**

`index.html`：按钮文案改为「示例：Compaction 七轮」；欢迎区把「四轮」改为「七轮（阈值 12）」。

`agentscope.js` 欢迎字符串中「Compaction 四轮」改为「Compaction 七轮」。

`README.md` Compaction curl 注释：把「四轮 / 前三轮共 6 条」改为与 `trigger-messages=12` 一致（例如「前六轮共 12 条消息 + 本轮 User 达阈值」），并说明示例按钮仍可用同一 session 连发确认消息观察 `COMPACTION`。

- [ ] **Step 5: 运行测试确认通过**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=DevAgentPropertiesBindingTest,AgentscopeCompactionConfigTest,AgentScopeMiddlewareConfigTest test
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add demo2/src/main/resources/application.properties \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/config/DevAgentPropertiesBindingTest.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentscopeCompactionConfigTest.java \
  demo2/src/main/resources/static/index.html \
  demo2/src/main/resources/static/js/tabs/agentscope.js \
  demo2/README.md
git commit -m "$(cat <<'EOF'
fix(demo2): raise AgentScope compaction trigger to 12

Align default trigger-messages with Skills code-review demos and update
Compaction sample copy for the higher threshold.
EOF
)"
```

---

### Task 4: README Skills 小节 + 前端 Code Review 示例 + 手工验证清单

**Files:**
- Modify: `demo2/README.md`
- Modify: `demo2/src/main/resources/static/index.html`
- Modify: `demo2/src/main/resources/static/js/tabs/agentscope.js`

**Interfaces:**
- Produces: 文档与 UI 可演示审查链路
- Consumes: Task 1–3 全部产物

- [ ] **Step 1: 更新 README 能力描述**

在 AgentScope Harness 能力表一行中追加 **Dynamic Skills / code-reviewer**（保持与 Memory、MCP 同级简述）。

在 `/agentscope/dev-agent` 文档区新增 **Dynamic Skills（code-reviewer）** 小节，要点：

- 共享目录：`workspace/skills/code-reviewer/`（`SKILL.md` + `references/java-style-guide.md`）
- 引导在 `AGENTS.md`，不在 system-prompt
- 样例：`mcp-files/UserProfileFormatter.java`
- 与 Spring AI `/agent/skills` 隔离
- 继续 `disableDefaultWorkspaceSkills()`

追加 curl：

```bash
curl -sN -X POST "http://localhost:8081/agentscope/dev-agent/ask" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"skill-user-013\",\"sessionId\":\"skill-session-013\",\"message\":\"请审查 MCP 资料目录里的 UserProfileFormatter.java，并给出是否适合合并的结论。\"}"
```

注意：本仓库应用端口以 README/配置为准（常见 `8081`）；若本地是 `8080` 按实际替换。成功标准：SSE 含 `load_skill_through_path`、`list_directory`、`read_text_file`；最终回答含四段标题，并指出空值与敏感日志，结论偏不宜直接合并。

同步更新 `DevAgentController` 表行描述，带上 Dynamic Skills。

- [ ] **Step 2: 前端示例 11**

`index.html` 增加按钮：

```html
<button type="button" onclick="fillAgentscopeSample(11)">示例：Code Review Skill</button>
```

欢迎文案追加一句：可用「Code Review Skill」验证动态 Skill + MCP 读样例。

`agentscope.js` 的 `samples` 增加：

```javascript
11: '请审查 MCP 资料目录里的 UserProfileFormatter.java，并给出是否适合合并的结论。'
```

并在 `n === 11` 时设置：

```javascript
userId.value = 'skill-user-013';
sessionId.value = 'skill-session-013';
```

- [ ] **Step 3: 跑单测门禁**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=AgentscopeCodeReviewerSkillAssetsTest,DevAgentPropertiesBindingTest,AgentscopeCompactionConfigTest,AgentScopeMiddlewareConfigTest test
```

Expected: PASS

- [ ] **Step 4: 手工验证（需 DEEPSEEK_API_KEY + MCP/npx）**

1. 启动应用：`mvn -f demo2/pom.xml spring-boot:run`
2. 执行 Step 1 的 curl（或前端示例 11）
3. 确认 SSE/前端工具事件中出现 Skill 加载与 MCP 读文件
4. 确认回答结构与关键缺陷命中

不要求工具调用次数固定；不做强制 LLM 集成测试入库。

- [ ] **Step 5: Commit**

```bash
git add demo2/README.md \
  demo2/src/main/resources/static/index.html \
  demo2/src/main/resources/static/js/tabs/agentscope.js
git commit -m "$(cat <<'EOF'
docs(demo2): document AgentScope dynamic Skills code-reviewer

Add README section, curl recipe, and frontend sample for the
code-reviewer Skill demo path.
EOF
)"
```

---

## Spec Coverage Checklist

| Spec 要求 | Task |
|-----------|------|
| 去掉 `disableDynamicSkills()` | Task 2 |
| 保留 `disableDefaultWorkspaceSkills()` | Task 2（显式保留） |
| `workspace/skills/code-reviewer/SKILL.md` | Task 1 |
| `references/java-style-guide.md` | Task 1 |
| `mcp-files/UserProfileFormatter.java` | Task 1 |
| `AGENTS.md` 引导；不改 system-prompt | Task 2 |
| `trigger-messages=12` | Task 3 |
| 轻量存在性/绑定测试 | Task 1、2、3 |
| README + 手工 curl | Task 4 |
| 不新建 API/Tab/Workflow/结构化校验 | 全任务遵守 Global Constraints |

## Self-Review Notes

- 无 TBD/TODO 占位。
- Compaction Demo「四轮」与阈值 12 冲突已在 Task 3 同步文案，避免实现后文档说谎。
- 工具名以 `load_skill_through_path` 为准；若 Harness 实际命名不同，仅改测试断言字符串。
- Windows 下 `mvn -f demo2/pom.xml` 工作目录为仓库根；存在性测试用 `Path.of(".")` 依赖 surefire 模块 cwd（demo2），与现有模块测试一致。
