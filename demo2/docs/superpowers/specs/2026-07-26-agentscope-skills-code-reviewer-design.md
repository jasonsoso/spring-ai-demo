# AgentScope Skills 实战：Code Review SOP 写入 SKILL.md 设计规范

**日期**：2026-07-26  
**项目**：spring-ai-demo / demo2  
**状态**：已确认，待实现  
**前置能力**：AgentScope Toolkit、AgentEvent SSE、Permission HITL、PostgreSQL AgentStateStore、Workspace、Compaction、Middleware、MCP filesystem、Memory  
**参考文章**：[13. AgentScope Java 2.0 Skills 实战：把标准的流程写进 SKILL.md](https://mp.weixin.qq.com/s/GeKa3hS5bMcq_Xsla8D6Mg)

---

## 1. 背景与目标

### 1.1 问题

让 Agent 做一次 Java Code Review 不难，难的是把团队审查 SOP 稳定用在每次任务里。把整套规则塞进 system prompt 不合适：用户只查项目版本时，模型没必要带着完整审查规范。

当前 Dev Agent 已具备 Workspace、MCP 读文件、SSE 与 Permission，但 `AgentScopeConfig` 仍调用 `.disableDynamicSkills()`，Harness 不会发现/加载共享目录中的 `SKILL.md`。

### 1.2 目标

1. 在现有 AgentScope Dev Agent 上启用动态 Skill（去掉 `disableDynamicSkills()`）。
2. 新增共享 Skill：`workspace/skills/code-reviewer/`（`SKILL.md` + `references/java-style-guide.md`）。
3. 新增 MCP 样例源码：`mcp-files/UserProfileFormatter.java`（故意含空值与敏感日志问题）。
4. 在 `workspace/AGENTS.md` 引导：审查时先 `load_skill_through_path`，再按 Skill 调工具并组织结论。
5. 将 `compaction.trigger-messages` 从 `6` 调整为 `12`，降低审查中途压缩掉刚加载 Skill 的概率。
6. Controller / SSE / HITL / MCP / Memory / Postgres 主流程不改；不新建 API / Tab。

### 1.3 已确认决策

| 维度 | 决策 |
|------|------|
| 接入位置 | 现有 AgentScope Dev Agent（非独立 Agent） |
| 方案 | 增强版文章路径：文章 SOP 骨架 + `references/java-style-guide.md` |
| Skill 引导 | **仅**写在 `workspace/AGENTS.md`；**不改** `system-prompt` |
| 动态 Skill | 去掉 `.disableDynamicSkills()` |
| 默认用户 Skill | 继续 `.disableDefaultWorkspaceSkills()` |
| Compaction | `trigger-messages: 12`（原为 6） |
| 参考资料 | 新建约一页的 `references/java-style-guide.md`；不引入 Cursor `code-standards-review` 全文 |
| 样例文件 | `mcp-files/UserProfileFormatter.java` |
| Maven | 不新增依赖；Harness 已含动态 Skill |
| 输出校验 | 不做结构化强制校验；Skill 仍是交给模型理解的任务说明 |

### 1.4 非目标

- 修改 `application-agentscope-prompts.yml` 中的 `system-prompt`
- 启用 per-user 默认 Workspace Skills
- 接入 / 同步 Cursor `code-standards-review` skill 全文
- 用 Workflow 或代码强制工具调用顺序
- 新建独立 Controller / 前端 Tab
- 把 Skill 挂到 `.claude/skills` 或 Spring AI `SkillsTool` 路径（与 `/agent/skills` 保持隔离）
- 把 Skill 当输出 schema 校验器

---

## 2. 方案选择

### 2.1 采用方案：共享 workspace Skill + AGENTS.md 引导 + 精简 references

```text
去掉 disableDynamicSkills()
  + workspace/skills/code-reviewer/SKILL.md
  + workspace/skills/code-reviewer/references/java-style-guide.md
  + mcp-files/UserProfileFormatter.java
  + AGENTS.md 审查引导
  + compaction.trigger-messages = 12
```

相对文章原文：引导从 system-prompt 挪到 `AGENTS.md`（与 demo2 Workspace 约定一致）；SOP 增加可选 `references/` 一页风格要点。

### 2.2 未采用方案

| 方案 | 原因 |
|------|------|
| 严格照文章（引导写 system-prompt） | 与已确认决策冲突；demo2 项目规则已集中在 AGENTS.md |
| Skill 挂 classpath / `.claude/skills` | 偏离 Harness「workspace 共享 Skill」模型；与 Spring AI Skills 目录混淆 |
| 增强为完整团队规范 skill | 超出本轮范围；维护成本高 |

---

## 3. 总体架构

### 3.1 职责边界

| 角色 | 职责 |
|------|------|
| Skill（`SKILL.md` + references） | 审查顺序、判断标准、边界、交付格式 |
| Tool / MCP | `list_directory` / `read_text_file` 等具体动作 |
| Permission / 白名单 | 文件范围与操作权限（Skill 不授予额外权限） |
| AGENTS.md | 提醒审查任务先加载匹配 Skill |

只有 Skill：知道流程却读不到代码。只有 Tool：能打开文件却未必按标准检查。

### 3.2 目标链路

```text
用户要求审查 Java 文件
  → Harness 注入 <available_skills>（仅 name / description / skill-id）
  → 模型按 AGENTS.md 调用 load_skill_through_path
  → 读取 code-reviewer/SKILL.md（必要时再读 references/java-style-guide.md）
  → list_directory 确认文件存在
  → read_text_file 读取完整源码
  → 按 SOP 输出：严重问题 / 一般问题 / 建议测试 / 结论
```

### 3.3 上下文加载时序

AgentScope 不把完整 `SKILL.md` 一开始塞进上下文，而是：

1. **发现**：只提供 name 与 description  
2. **加载**：任务匹配后 `load_skill_through_path(skillId, path="SKILL.md")`  
3. **执行**：按需读取 `references/...`（路径相对 Skill 目录；禁止绝对路径与 `../` 跳出）并调用 MCP 工具  

新的 Agent 调用会重新整理可用 Skill；修改 `SKILL.md` 无需重新编译；已开始的调用不会中途切换规则。

### 3.4 与现有能力的关系

| 能力 | 本篇关系 |
|------|----------|
| MCP filesystem | 复用；读 `mcp-files/` 下样例 |
| Workspace / AGENTS.md | 增加审查引导段落 |
| Compaction | 仅调高 `trigger-messages` 至 12 |
| Permission HITL | 只读 MCP 工具不进确认 |
| Spring AI `/agent/skills` | 无关；保持隔离 |

---

## 4. 组件与文件设计

### 4.1 新增文件

#### `workspace/skills/code-reviewer/SKILL.md`

Frontmatter：

```yaml
---
name: code-reviewer
description: >
  审查 Java 代码的正确性、数据安全和测试缺口。
  用户要求代码评审、检查实现风险或给出测试建议时使用。
---
```

正文必须包含：

1. 审查顺序：`list_directory` → `read_text_file` → 正确性/边界 → 敏感信息 → 测试建议  
2. 边界：未读到代码不给结论；无法确认写「无法确认」；纯风格偏好不当缺陷  
3. 交付格式：`## 严重问题` / `## 一般问题` / `## 建议测试` / `## 结论`  
4. 提示：需要风格细则时可 `load_skill_through_path` 读取 `references/java-style-guide.md`  
5. **不**写死目标文件名（换类仍可复用）

#### `workspace/skills/code-reviewer/references/java-style-guide.md`

约一页，覆盖：

- 空值与边界（参数、Map 取值、`.toString()`）  
- 日志与敏感信息（禁止打印完整用户/凭证对象；优先结构化日志字段）  
- 职责与命名（单方法避免混格式化与副作用日志）  
- 测试缺口（null / 缺字段 / 含敏感字段的数据）  

不写死具体样例类名；不复制阿里巴巴规范全文。

#### `mcp-files/UserProfileFormatter.java`

故意缺陷（与文章一致）：

- `user` / `name` 为空时可能 NPE  
- `System.out.println` 输出整个用户 Map，可能泄露敏感信息  

保留现有 `mcp-files/project-profile.md`，二者共存。

### 4.2 调整文件

| 文件 | 改动 |
|------|------|
| `AgentScopeConfig.java` | 删除 `.disableDynamicSkills()` 调用 |
| `application.properties` | `app.agentscope.dev-agent.compaction.trigger-messages=12` |
| `workspace/AGENTS.md` | 新增「代码审查」小节（见下） |

`AGENTS.md` 新增内容要点：

```markdown
## 代码审查

- 用户要求审查代码、检查实现风险或给出测试建议时，
  先用 load_skill_through_path 加载与代码审查匹配的 Skill，
  再按 Skill 中的步骤调用工具并组织结论。
```

### 4.3 不变

- `application-agentscope-prompts.yml` 的 `system-prompt`  
- MCP Client 配置与工具白名单  
- `disableDefaultWorkspaceSkills()` / `disableFilesystemTools()` / `disableShellTool()` 等其余 disable  
- Distributed / Memory 开关语义  

---

## 5. 错误处理与边界

| 场景 | 期望行为 |
|------|----------|
| 目标文件不存在 | Skill 要求先 list；不伪造审查结论 |
| MCP 关闭 / 读失败 | 明确说明无法读取，不给「适合合并」结论 |
| 模型跳过 load_skill | 偶发可接受；靠 AGENTS.md 与 description 引导，不用 Workflow 强制 |
| Skill 内 `../` 或绝对路径 | Harness 拒绝跳出 Skill 目录 |
| 无关请求（查版本等） | 仅看到 available_skills 摘要，不加载完整 SOP |
| 需要固定 JSON 字段的接口 | 本篇不做；另加结构化输出，不靠 Markdown 指令 |

---

## 6. 测试与验证

### 6.1 成功标准

1. SSE 出现 `load_skill_through_path` SUCCESS  
2. 出现对样例的 `list_directory` / `read_text_file` SUCCESS（只读，不进 HITL）  
3. 最终回复含四段结构，能指出空值风险与敏感日志，结论偏「不宜直接合并」  
4. 不要求工具调用次数固定  

### 6.2 手工验证

```bash
curl -sN -X POST "http://localhost:8080/dev-agent/ask" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"skill-user-013\",\"sessionId\":\"skill-session-013\",\"message\":\"请审查 MCP 资料目录里的 UserProfileFormatter.java，并给出是否适合合并的结论。\"}"
```

### 6.3 自动化（轻量）

- 扩展现有 compaction 相关测试：断言 `trigger-messages == 12`  
- 可选：资源/文件存在性检查（`SKILL.md`、`java-style-guide.md`、`UserProfileFormatter.java`）  
- **不**新增强制「模型一定调用 Skill」的端到端 LLM 集成测试  

### 6.4 文档（实现阶段）

- 本 spec  
- 实现 plan：`docs/superpowers/plans/2026-07-26-agentscope-skills-code-reviewer.md`  
- README AgentScope 能力表增加 Dynamic Skills / code-reviewer 一行  

---

## 7. 实现要点清单

1. 去掉 `AgentScopeConfig` 中 `.disableDynamicSkills()`  
2. 写 `workspace/skills/code-reviewer/SKILL.md`  
3. 写 `workspace/skills/code-reviewer/references/java-style-guide.md`  
4. 写 `mcp-files/UserProfileFormatter.java`  
5. 更新 `workspace/AGENTS.md`  
6. `compaction.trigger-messages` → `12`；同步相关测试期望  
7. 手工 curl 验证 + 轻量单测  
8. 更新 README（实现阶段）  

---

## 8. 风险与约束

- Skill 是自然语言任务说明，不能保证 100% 按步骤执行。  
- 必须保证的步骤应用代码或 Workflow；本篇刻意不做强制编排。  
- 文件权限仍由 MCP root + 工具白名单 + Permission 负责；Skill 正文写「检查敏感信息」不会扩大可读目录。  
- Compaction=12 会略推迟摘要；对短会话影响可忽略，长会话仍依赖既有 Compaction 摘要质量。  
