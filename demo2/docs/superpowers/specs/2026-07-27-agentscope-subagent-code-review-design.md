# AgentScope SubAgent 实战：代码审查三角色设计规范

**日期**：2026-07-27  
**项目**：spring-ai-demo / demo2  
**状态**：已确认，待实现  
**前置能力**：AgentScope Toolkit、AgentEvent SSE、Permission HITL、PostgreSQL AgentStateStore、Workspace、Compaction、Middleware、MCP filesystem、Memory、Dynamic Skills（code-reviewer）  
**参考文章**：[14. AgentScope Java 2.0 SubAgent 实战：把代码审查拆给三个专门角色](https://mp.weixin.qq.com/s/NmnANCbzNZYOVT2VowksJQ)  
**相关规范**：[2026-07-26 AgentScope Skills code-reviewer 设计](./2026-07-26-agentscope-skills-code-reviewer-design.md)

---

## 1. 背景与目标

### 1.1 问题

一次 Java 代码审查通常要做三件事：读懂实现、判断风险、补齐测试。全交给一个 Agent 也能做，但同一段上下文里兼顾三种视角时容易顾此失彼。

Skill 解决的是「当前 Agent 按 SOP 做事」；SubAgent 解决的是「把可独立交付的部分交给另一个有独立提示、会话与工具边界的 Agent」。demo2 已落地 `code-reviewer` Skill，但 `AgentScopeConfig` 仍调用 `.disableSubagents()`，Harness 不会加载 `workspace/subagents/`。

### 1.2 目标

1. 在现有 AgentScope Dev Agent 上启用 SubAgent（去掉 `.disableSubagents()`）。
2. 新增三个角色定义：`workspace/subagents/code-reader.md`、`risk-reviewer.md`、`test-advisor.md`。
3. 新增 MCP 样例源码：`mcp-files/TravelBudgetService.java`（故意含除零、VIP 与 perPerson 不一致、敏感日志等问题）。
4. 在 `workspace/AGENTS.md` 写清 Skill / SubAgent 双路径路由与委派规则。
5. Permission 自动放行 `agent_spawn` / `agent_send` / `agent_list`。
6. `DevAgentEvent` 增加 `source`，`DevAgentService` 原样透传，便于区分主/子 Agent 事件。
7. 保留现有 Skill 审查能力与 `UserProfileFormatter` 样例；不新建 API / Tab。

### 1.3 已确认决策

| 维度 | 决策 |
|------|------|
| 接入位置 | 现有 AgentScope Dev Agent（非独立 Agent） |
| 与 Skill 关系 | **并存**：默认审查走 Skill；用户明确要求多角色 / SubAgent / 三角色时走 SubAgent |
| 工具边界 | 主 Agent **保留** MCP 读文件工具（不按文章在 build 后从主 Agent 移除）；MCP 等需在 **build 前** 注册进 Toolkit，以便 SubAgent 工厂快照能继承 `tools` 白名单 |
| 引导位置 | **仅**写在 `workspace/AGENTS.md`；**不改** `system-prompt` |
| 路由触发 | 默认「审查 / 评审」→ Skill；提到「多角色 / SubAgent / 三角色」→ 三 SubAgent |
| SSE | 增加 `source` 透传；本轮**不**强制前端渲染来源 |
| 样例文件 | 新增 `TravelBudgetService.java`；保留 `UserProfileFormatter.java` |
| Maven | 不新增依赖；Harness 已含 SubAgent |
| 强制编排 | 不用 Workflow 强制 `agent_spawn` 顺序；不做结构化输出校验 |

### 1.4 非目标

- 修改 `application-agentscope-prompts.yml` 中的 `system-prompt`
- 构建后从主 Agent 移除 MCP 文件工具
- 删除或停用 `code-reviewer` Skill
- 用 Workflow 或代码强制工具调用顺序
- 新建独立 Controller / 前端 Tab
- 本轮前端 UI 专门展示 `source`（可后续加）
- 用 `general-purpose` 替代三个专门角色

---

## 2. 方案选择

### 2.1 采用方案：增强版文章路径 + 与 Skill 并存

```text
去掉 disableSubagents()
  + workspace/subagents/{code-reader,risk-reviewer,test-advisor}.md
  + mcp-files/TravelBudgetService.java
  + AGENTS.md Skill/SubAgent 路由与委派规则
  + Permission 放行 agent_spawn / agent_send / agent_list
  + DevAgentEvent.source 透传
  + Toolkit：build 前注册 MCP（及现有业务工具），build 后主 Agent 仍保留（不 remove）
```

相对文章原文：引导从 system-prompt 挪到 `AGENTS.md`；不摘主 Agent MCP；与既有 Skill 审查并存并由关键词分流。相对 demo2 现状：把「build 后才 `registerTool(MCP)`」改为「build 前注册」，否则 SubAgent 工厂快照里没有 `list_directory` / `read_text_file`，角色白名单无效。

### 2.2 未采用方案

| 方案 | 原因 |
|------|------|
| 严格照文章（摘 MCP + 改 system-prompt + 审查只走 SubAgent） | 与「Skill 并存」「只改 AGENTS.md」冲突 |
| 最小可跑（只开 SubAgent、不改 Event） | 缺少 `source` 可观测性，路由易与 Skill 混淆 |

---

## 3. 总体架构

### 3.1 职责边界

| 角色 | 职责 |
|------|------|
| 主 Agent | 路由；SubAgent 路径只委派与汇总；Skill 路径按现有 SOP 读文件并输出 |
| SubAgent 定义（`*.md`） | description、tools 白名单、steps、系统提示正文 |
| Skill（`code-reviewer`） | 单 Agent 审查 SOP（本篇不改内容） |
| Tool / MCP | `list_directory` / `read_text_file` 等；子 Agent 仅继承白名单内工具 |
| Permission | `agent_*` 与只读 MCP 自动放行；写文件等仍 HITL |
| AGENTS.md | Skill / SubAgent 分流与委派规则 |
| SSE `source` | 主事件为空；子事件带路径（如 `{sessionId}/{agent_id}`） |

### 3.2 Tool / Skill / SubAgent 分工（概念）

```text
Tool     → 执行具体动作（读文件）
Skill    → 告诉当前 Agent 这件事怎么做（同一上下文）
SubAgent → 创建另一个 Agent 完成一部分任务（独立提示 / 会话 / 工具边界）
```

### 3.3 SubAgent 审查链路

```text
用户要求「用多角色 / SubAgent / 三角色」审查 TravelBudgetService.java
  → AGENTS.md 命中 SubAgent 路径
  → agent_spawn(code-reader, timeout_seconds=120) → 实现事实
  → agent_spawn(risk-reviewer, timeout_seconds=120) → 风险与合并判断素材
  → agent_spawn(test-advisor, timeout_seconds=120) → 测试建议
  → 主 Agent 汇总：严重问题 / 一般问题 / 建议测试 / 是否适合合并
  → 同 session 追问 → agent_send(原 agent_key)，不新建同角色
```

`agent_spawn` 返回中：`agent_id` 是角色名；`agent_key` 是实例句柄（供 `agent_send`）；`session_id` 为框架子会话标识。

### 3.4 Skill 审查链路（保留）

```text
用户要求审查（未提多角色 / SubAgent / 三角色）
  → load_skill_through_path → MCP 读文件 → 四段结论
```

### 3.5 一次 agent_spawn 在框架内的过程（摘要）

1. 主 Agent 依据 AGENTS.md + 角色 description 决定委派  
2. 调用 `agent_spawn(agent_id, task, timeout_seconds)`  
3. Harness 按 `agent_id` 找到 `workspace/subagents/{id}.md` 对应工厂  
4. 创建子 Agent：系统提示 = Markdown 正文 + 框架补充规则；工具 = 父 Toolkit 按 `tools` 白名单过滤  
5. `task` 作为子 Agent 第一条用户消息  
6. 子 Agent 独立跑模型与工具循环  
7. 最终回复作为 `agent_spawn` 工具结果回到主 Agent  

Harness 补充规则包括：专注当前任务、结果交给主 Agent、不直接对用户说话、不再创建下一层 SubAgent。

### 3.6 与现有能力的关系

| 能力 | 本篇关系 |
|------|----------|
| Dynamic Skills / code-reviewer | 保留；默认审查路径 |
| MCP filesystem | 主 Agent 与子 Agent 均可使用；子 Agent 仅白名单 |
| Workspace / AGENTS.md | 扩展审查路由与委派 |
| Permission HITL | 新增三个 `agent_*` 放行 |
| SSE / DevAgentEvent | 增加 `source` |
| system-prompt | **不改** |
| Compaction / Memory / Postgres | 不改语义 |

---

## 4. 组件与文件设计

### 4.1 新增文件

#### `workspace/subagents/code-reader.md`

Frontmatter 要点：

```yaml
---
description: >
  读取真实 Java 文件，整理类、方法、输入输出、分支和计算顺序。
  代码审查需要先确认实现事实时使用。
mode: subagent
tools: [list_directory, read_text_file]
steps: 4
---
```

正文要求：先 list 再 read；只整理事实（类/方法、输入输出与计算、分支与顺序、日志或副作用）；不评价、不调用其他 Agent；固定约 5 行输出，每行不宜过长。

#### `workspace/subagents/risk-reviewer.md`

- 同工具白名单；审查正确性、数据安全、边界风险；为合并结论提供依据  
- 不调用其他 Agent；基于读到的代码下判断，无法确认时写明  

#### `workspace/subagents/test-advisor.md`

- 同工具白名单；根据真实实现给出测试场景（正常路径、边界、已发现风险）  
- 不虚构已存在的测试结果；不调用其他 Agent  

文件名去掉 `.md` 即为 `agent_spawn` 的 `agent_id`。`tools` 是从父 Agent 已有工具中筛选的白名单，不是新建工具。

#### `mcp-files/TravelBudgetService.java`

与文章一致的故意缺陷示例，至少覆盖：

- `travelers == 0` 时除法异常  
- 金额除不尽时无舍入模式  
- VIP 折扣只改 `total`、不改已算好的 `perPerson`  
- `System.out.println` 输出整个 `request`（可能含 `travelerContact`）  

保留 `UserProfileFormatter.java` 与 `project-profile.md`。

### 4.2 调整文件

| 文件 | 改动 |
|------|------|
| `AgentScopeConfig.java` | 删除 `.disableSubagents()`；Permission 增加 `agent_spawn` / `agent_send` / `agent_list` 自动放行；将 `projectInfoTools` / `fileChangeTool` / MCP 的注册挪到 **`builder.build()` 之前**（通过 `HarnessAgent.builder().toolkit(...)` 或等价 API），build 后**不** `remove` MCP；可继续 `removeTool("wait_async_results")` |
| `DevAgentEvent.java` | 增加可空字段 `source`；工厂方法透传（默认 `null`） |
| `DevAgentService.java` | `mapEvent` 读取 `event.getSource()` 写入 `DevAgentEvent` |
| 相关单测 | `source` 透传；权限/中间件断言含协作工具（按现有测试风格） |
| `workspace/AGENTS.md` | 扩展「代码审查」小节（见 4.3） |
| README + 前端示例（轻量） | SubAgent 审查 curl / 示例按钮文案；不强制 UI 渲染 `source` |

**不变**

- `application-agentscope-prompts.yml` 的 `system-prompt`  
- 不按文章在 build 后从主 Agent `remove` MCP（主 Agent 与 Skill 路径仍可读文件）  
- `disableDefaultWorkspaceSkills()` / `disableFilesystemTools()` / `disableShellTool()` 等其余 disable  
- Controller 路径与 HITL 主流程  

### 4.3 `AGENTS.md` 审查小节要点

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

---

## 5. 错误处理与边界

| 场景 | 期望行为 |
|------|----------|
| 目标文件不存在 | 子 Agent 先 list；不伪造审查结论；主 Agent 汇总时说明无法确认 |
| 某次 `agent_spawn` 失败 | 不用 `general-purpose`；可对同角色重试说明任务，或明确告知失败 |
| 用户未提多角色却说「审查」 | 走 Skill 路径 |
| 模型在 SubAgent 路径自己先读了文件 | 偶发可接受；不做 Workflow 强制 |
| SubAgent `tools` 未列的工具 | 框架白名单过滤，子 Agent 不可用 |
| 新 `sessionId` | 重新 spawn；同会话追问优先 `agent_send` |
| HITL | `agent_*` 与只读 MCP 不进确认；`request_file_change` 等仍确认 |
| 无关请求（查版本等） | 不强制 spawn；按既有工具回答 |

---

## 6. 测试与验证

### 6.1 成功标准

1. SSE 出现约 3 次 `agent_spawn`（`code-reader` / `risk-reviewer` / `test-advisor`）。  
2. 读文件相关事件的 `source` 落在子 Agent（如 `…/code-reader`），而非仅主会话空 source。  
3. 最终汇总含严重问题 / 一般问题 / 建议测试 / 合并结论，能指出除零、VIP 与 perPerson 不一致、敏感日志等。  
4. 同 session 追问计算顺序时优先 `agent_send`，不无故再开同角色。  
5. **回归**：不提多角色时审查 `UserProfileFormatter` 仍出现 `load_skill_through_path`。  

模型可能省略一次 `list_directory` 或调整角色顺序；不要求工具次数固定。

### 6.2 手工验证

```bash
# SubAgent 路径
curl -sN -X POST "http://localhost:8080/dev-agent/ask" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"subagent-user-014\",\"sessionId\":\"subagent-session-014\",\"message\":\"请用 SubAgent 多角色审查 MCP 资料目录里的 TravelBudgetService.java，并给出是否适合合并的结论。\"}"

# 同会话追问
curl -sN -X POST "http://localhost:8080/dev-agent/ask" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"subagent-user-014\",\"sessionId\":\"subagent-session-014\",\"message\":\"再确认一下：VIP 折扣和 perPerson 的计算顺序是什么？优先复用刚才的 code-reader，不要新建子 Agent。\"}"

# Skill 回归
curl -sN -X POST "http://localhost:8080/dev-agent/ask" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"skill-user-013\",\"sessionId\":\"skill-session-013\",\"message\":\"请审查 MCP 资料目录里的 UserProfileFormatter.java，并给出是否适合合并的结论。\"}"
```

### 6.3 自动化（轻量）

- 资产存在性：三个 `subagents/*.md` + `TravelBudgetService.java`  
- `DevAgentEvent` / `mapEvent` 的 `source` 透传  
- Permission 规则含 `agent_spawn` / `agent_send` / `agent_list`  
- **不**新增强制「模型一定三次 spawn」的端到端 LLM 集成测试  

### 6.4 文档（实现阶段）

- 本 spec  
- 实现 plan：`docs/superpowers/plans/2026-07-27-agentscope-subagent-code-review.md`  
- README AgentScope 能力表增加 SubAgent / 三角色审查一行  

---

## 7. 实现要点清单

1. 去掉 `AgentScopeConfig` 中 `.disableSubagents()`  
2. Toolkit：build 前注册业务工具 + MCP；build 后保留 MCP（不 remove）  
3. Permission 放行 `agent_spawn` / `agent_send` / `agent_list`  
4. 写三个 `workspace/subagents/*.md`  
5. 写 `mcp-files/TravelBudgetService.java`  
6. `DevAgentEvent` + `DevAgentService` 透传 `source`；补单测  
7. 更新 `workspace/AGENTS.md` 审查路由  
8. 轻量资产/权限/事件测试；README + 前端示例文案  
9. 手工 curl 验证 SubAgent 路径与 Skill 回归  

---

## 8. 风险与约束

- 一次 SubAgent 审查会拆成多次模型调用，等待更长、Token 更多；简单审查应继续走默认 Skill。  
- 路由与委派依赖自然语言与 AGENTS.md，不能保证 100% 命中；必须保证的步骤应用代码或 Workflow，本篇刻意不做。  
- 主 Agent 保留 MCP 时，偶发可能在 SubAgent 路径自己先读文件；靠提示约束，接受偶发。  
- 文件权限仍由 MCP root + 工具白名单 + Permission 负责；SubAgent `tools` 只缩小子 Agent 可用集，不扩大可读目录。  
- `agent_key` 是主 Agent 内部句柄，不是给用户手填的参数。  
