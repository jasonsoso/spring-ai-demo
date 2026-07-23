# AgentScope Workspace / AGENTS.md 设计规范

**日期**: 2026-07-23  
**项目**: spring-ai-demo / demo2  
**状态**: 已确认，待实现  
**前置**: [2026-07-16-agentscope-harness-web-design.md](./2026-07-16-agentscope-harness-web-design.md)（已实现）；工具 / HITL / PostgreSQL 会话已落地  
**参考**: [8. AgentScope Java 2.0 Workspace 实战：让 Agent 先读懂 AGENTS.md 再回答](https://mp.weixin.qq.com/s/-Jft8SlL8fKa1_IfXDOQLQ)

---

## 1. 背景与目标

### 1.1 问题

研发 Agent 已具备模型、工具、权限确认与会话持久化，但项目约定（名称、理解顺序、工具使用规则、输出要求等）仍挤在 `application-agentscope-prompts.yml` 的长 `system-prompt` 里。基础角色与项目规则混在一起，换项目或改约定时要改 YAML，且无法像 Workspace 那样运行中热更新。

当前 `AgentScopeConfig` 显式调用 `.disableWorkspaceContext()`，框架不会注入 `AGENTS.md`。

### 1.2 需求

1. 启用 AgentScope **Workspace Context**，使每轮推理前将 `workspace/AGENTS.md` 注入系统提示词
2. 将相对稳定的项目规则从 YAML 迁到 `AGENTS.md`；`system-prompt` 只保留短基础角色
3. 新增空骨架 `MEMORY.md` 与 `knowledge/KNOWLEDGE.md`（本版不填业务内容）
4. 保留现有 Web SSE、工具、HITL、PostgreSQL；**不**因启用 Workspace 而放开内置文件 / Shell 工具

### 1.3 已确认决策

| 维度 | 选择 |
|------|------|
| 落地方案 | **方案 1**：Harness 原生 `.workspace(...)` + 移除 `.disableWorkspaceContext()` |
| 范围 | 文章能力 + 空 `knowledge/` / `MEMORY.md` 骨架 |
| `AGENTS.md` 内容 | 文章可验证字段 + 现有工具/写文件/输出约定合并 |
| 项目标识 | 名称 `agentscope-java`；任务编号 `WORKSPACE-008`（便于对照文章 curl） |
| 删除文件 | **跟现有 prompt**：不要尝试删除文件（不走「删除也交 request_file_change」） |
| API / 前端 | **不改**路径、SSE 事件模型、AgentScope Tab |
| 配置键 | `app.agentscope.dev-agent.workspace-root=workspace` |

### 1.4 非目标（本版不做）

- 前端展示「当前已加载的 AGENTS 规则」
- 填满 `knowledge/` 或启用 MEMORY 写入 / memory tools
- 多 Workspace 切换、共享 Workspace、Sandbox 快照
- 改 API、SSE、HITL、Postgres 会话存储
- 用 `AGENTS.md` 替代 `FileChangeTool` / Permission 的强制校验
- 放开 `disableFilesystemTools` / `disableShellTool` 等内置危险能力

---

## 2. 架构

### 2.1 职责边界

| 组件 | 负责什么 | 本版 |
|------|----------|------|
| `application*.yml` / properties | Agent 基础角色（短 system-prompt） | 精简 |
| `workspace/` | 项目规则与工作区文件 | 新增 |
| `AgentStateStore` | 会话运行状态恢复 | 不动 |
| Permission + 工具代码 | 强制安全边界（路径、ASK/DENY） | 不动 |

### 2.2 数据流

```
HTTP POST /agentscope/dev-agent/ask
  → DevAgentService → HarnessAgent
       → system-prompt（基础角色，来自 YAML）
       → WorkspaceContextMiddleware
            读取 workspace/AGENTS.md
            （以及 MEMORY.md、knowledge/KNOWLEDGE.md，若存在）
       → 追加到系统提示词 <loaded_context><agents_context>…
       → ReActAgent 调模型（工具 / 权限 / 状态链路不变）
```

要点：

- `.workspace(path)` 指定工作区目录；去掉 `.disableWorkspaceContext()` 才会注册注入中间件
- 加载 Workspace Context **不等于**开放内置文件工具；现有 `disableFilesystemTools()` / `disableShellTool()` 等保持不变
- `AGENTS.md` 每轮重新读取；修改后下一轮生效，无需重启应用

### 2.3 改动面（克制）

| 位置 | 改动 |
|------|------|
| `DevAgentProperties` | 增加 `@NotBlank String workspaceRoot` |
| `application.properties` | `app.agentscope.dev-agent.workspace-root=workspace` |
| `application-agentscope-prompts.yml` | 精简为短角色说明 |
| `AgentScopeConfig` | `.workspace(Path.of(properties.workspaceRoot()))`；删除 `.disableWorkspaceContext()` |
| `demo2/workspace/` | 新增 `AGENTS.md`、`MEMORY.md`、`knowledge/KNOWLEDGE.md` |
| Controller / Service / 前端 | **不改** |

`project-root` 与 `workspace-root` 职责分离：前者给只读项目工具读源码；后者给 Agent 工作区文件。

---

## 3. 配置与工作区内容

### 3.1 配置

```properties
app.agentscope.dev-agent.project-root=.
app.agentscope.dev-agent.workspace-root=workspace
```

从 `demo2` 目录启动时，Workspace 解析为 `demo2/workspace`。

### 3.2 精简后的 system-prompt

```text
你是一个研发任务整理助手。
根据用户请求整理任务，必要时调用已经注册的工具。
使用中文回答，并遵守 Workspace 中的项目规则。
```

### 3.3 目录结构

```text
demo2/workspace/
├── AGENTS.md
├── MEMORY.md
└── knowledge/
    └── KNOWLEDGE.md
```

### 3.4 `AGENTS.md` 内容要求

| 区块 | 内容 |
|------|------|
| 项目背景 | 名称 `agentscope-java`；任务编号 `WORKSPACE-008`；理解顺序：先看 Maven 配置，再看源码目录，最后确认启动类 |
| 工作方式 | 询问 Maven/Java/Spring Boot/源码目录/启动类时先调用对应只读工具；当前无日志、数据库、Shell 工具，不声称已查询或执行命令；信息不足时指出缺口，不编造 |
| 文件变更 | 仅用户明确要求创建或修改时才调用 `request_file_change`；目标路径必须位于 `notes/`；用户已给出操作、路径和内容时直接调用，不要在对话里再次询问是否确认；工具进入待确认后等待 Permission 结果，确认前不声称已保存；**不要尝试删除文件** |
| 输出要求 | 回答控制在 6 条以内；汇总时区分「已经确认」和「还需要确认」；不写开场白和重复总结 |

不写入：API Key、数据库密码等秘密；大段接口/业务资料（留给日后 `knowledge/`）。

### 3.5 骨架文件

`MEMORY.md` 与 `knowledge/KNOWLEDGE.md`：仅标题 + 一句说明「本版留空，勿写入秘密」；不填业务知识，避免噪声，也避免空文件歧义。

---

## 4. 错误处理与边界

| 情况 | 行为 |
|------|------|
| `workspace/` 或 `AGENTS.md` 缺失 | 框架按空上下文继续；Agent 仍可用，但可验证字段无法答出 → **仓库内提交完整骨架**，保证默认路径存在 |
| 运行中修改 `AGENTS.md` | 下一轮推理重新读取，无需重启 |
| Workspace Context 开启 | 不放开 filesystem / Shell / memory 内置工具 |
| 模型侧规则 vs 强制边界 | Prompt 约束不能替代 `FileChangeTool` 路径校验与 Permission |

---

## 5. 测试与验收

### 5.1 手工验收（对齐文章）

PostgreSQL 与 `DEEPSEEK_API_KEY` 就绪后，用新 `sessionId`：

```bash
curl -sN -X POST \
  "http://localhost:8081/agentscope/dev-agent/ask" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "workspace-user-008",
    "sessionId": "workspace-session-008",
    "message": "按项目规则回答：当前项目名称、项目理解任务编号和三步理解顺序。不要调用工具。"
  }'
```

合并 SSE 中 `AGENT_RESULT` 后，应包含：

1. 项目名称：`agentscope-java`
2. 任务编号：`WORKSPACE-008`
3. 三步顺序：先看 Maven 配置，再看源码目录，最后确认启动类

列表样式由模型生成，不作为接口契约。  
再将任务编号改为新值，使用**新会话**请求，应反映变更且无需重启应用。

> 端口与路径以 demo2 现有为准（`8081` + `/agentscope/dev-agent/ask`），与文章示例 `8080` / `/dev-agent/ask` 不同。

### 5.2 自动化

- `DevAgentProperties` 绑定含 `workspaceRoot`
- 若现有配置/Bean 测试可扩展：断言 builder 使用 workspace 且不再调用 `disableWorkspaceContext`（不强行 mock 框架中间件注入细节）
- 不要求 CI 打真实 DeepSeek 集成测试

---

## 6. 实现顺序建议

1. 扩展 `DevAgentProperties` + properties
2. 新增 `workspace/` 三文件
3. 精简 `application-agentscope-prompts.yml`
4. 修改 `AgentScopeConfig`（workspace + 去掉 disableWorkspaceContext）
5. 补单元测试（属性绑定）
6. 手工 curl 验收与热更新抽查
7. README / AgentScope 文档补一句 Workspace 说明（若仓库已有对应章节）

---

## 7. 成功标准

- Agent 能仅凭 Workspace 回答项目名、任务编号、三步顺序（YAML 与用户问题中不含这些字段）
- 修改 `AGENTS.md` 后新会话无需重启即可看到变更
- 现有工具调用、HITL 确认、Postgres 会话行为回归正常
- 内置文件 / Shell 工具仍不可用
