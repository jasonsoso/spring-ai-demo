# Subagent Orchestration + A2A 跨系统对话 Demo 设计规范

**日期**: 2026-06-29  
**项目**: spring-ai-demo / demo2  
**状态**: 已实现

**实现计划:** [docs/superpowers/plans/2026-06-29-subagent-a2a.md](../plans/2026-06-29-subagent-a2a.md)

---

## 1. 背景与目标

### 1.1 需求

在 `demo2` 中新增两个 Spring AI Agentic Patterns 教程 Demo：

| Demo | 系列篇目 | 核心能力 |
|------|----------|----------|
| **Subagent Orchestration** | 第 5 篇 | 主协调器通过 `TaskTool` 委派 `architect` / `builder` 子代理，独立上下文、结果回传 |
| **A2A 跨系统对话** | 第 6 篇 | 主协调器通过 `TaskTool` + A2A 协议调用**远程**天气专家 Agent |

参考：

- 微信文章：[Spring AI 2.0 系列教程（五）——Subagent Orchestration](https://mp.weixin.qq.com/s/tn6_dT5sCa-9RcdzM-2qpA)
- 微信文章：[Spring AI 2.0 系列教程（六）——用 A2A 协议让 AI Agent 跨系统对话](https://mp.weixin.qq.com/s/MOGmfbZFiC8k07rbmpOX4Q)
- 官方示例：[subagent-demo](https://github.com/spring-ai-community/spring-ai-agent-utils/tree/main/examples/subagent-demo)
- 官方示例：[subagent-a2a-demo](https://github.com/spring-ai-community/spring-ai-agent-utils/tree/main/examples/subagent-a2a-demo)
- A2A Server：[spring-ai-a2a-server-autoconfigure](https://github.com/spring-ai-community/spring-ai-a2a)

### 1.2 已确认决策

| 维度 | 选择 |
|------|------|
| A2A 部署形态 | **方案 A：单应用内嵌 A2A Server**（一个 `spring-boot:run` 即可测两个 Tab） |
| Subagent 角色 | **Architect + Builder**（文章 Architect-Builder 模式，非 spring-ai-expert） |
| 前端集成 | `static/index.html` 新增 **两个 Tab** |
| 交互方式 | **同步 HTTP GET**（对齐 `SkillsAgent` / `MultiAgent`，非 SSE） |
| LLM | 统一使用项目已有 **DeepSeek**（首版不做多模型路由） |
| 工具范围 | Subagent：主代理仅挂 `TaskTool`；A2A 远程 Agent 挂 `WeatherTool` |

### 1.3 与现有 Demo 的关系

| 已有 Demo | 编排方式 | 本设计差异 |
|-----------|----------|------------|
| `MultiAgentService` | Java 代码写死 Supervisor → 3 Worker → 汇总 | LLM **自主决定**何时委派、委派给谁 |
| `TodoAgentService` | 单 Agent + TodoWriteTool 自拆自执行 | 多 Agent **独立上下文**，主会话不被污染 |
| `SkillsAgentService` | 单 Agent + Skills 匹配 | 层级化子代理，Task 工具驱动 |

### 1.4 成功标准

1. `mvn compile` 编译通过
2. `index.html` 新增 Tab「Subagent 编排」「A2A 跨系统对话」可发起请求并展示结果
3. Subagent Tab：复杂任务触发 `architect` → `builder` 链式委派（日志可见 Task 调用）
4. A2A Tab：`GET /.well-known/agent-card.json` 可访问；协调器通过 A2A 调用内嵌天气 Agent
5. Swagger 可查看新增 REST 端点

### 1.5 不在范围

- 多模型路由（haiku/sonnet/opus 或 o3-mini + gpt-4o-mini）
- SSE 实时展示子代理委派过程（首版用 loading + 最终结果）
- `BraveWebSearchTool`、`ShellTools`、`FileSystemTools` 叠加（避免安全与 Token 开销）
- 独立进程多端口部署（airbnb-planner 三进程模式）
- 后台异步子代理 + `TaskOutputTool`
- 生产级鉴权、限流、分布式追踪

---

## 2. Maven 依赖

### 2.1 新增依赖

在 `demo2/pom.xml` 的 `dependencyManagement` 中引入 BOM，并添加：

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-agent-utils-bom</artifactId>
    <version>0.10.0</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

```xml
<!-- 已有，改为由 BOM 管理版本（可选） -->
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-agent-utils</artifactId>
</dependency>

<!-- A2A 子代理客户端 -->
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-agent-utils-a2a</artifactId>
</dependency>

<!-- A2A Server（内嵌天气专家 Agent） -->
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-a2a-server-autoconfigure</artifactId>
    <version>0.3.0</version>
</dependency>
```

### 2.2 关键 API（0.10.0）

| 类 | 模块 | 用途 |
|----|------|------|
| `TaskTool` | spring-ai-agent-utils | 主代理委派子代理 |
| `ClaudeSubagentType` | spring-ai-agent-utils | 本地 Markdown 子代理类型 |
| `ClaudeSubagentReferences` | spring-ai-agent-utils | 从 classpath 加载 `agents/*.md` |
| `SubagentReference` | spring-ai-agent-utils-common | A2A 远程 Agent URI |
| `SubagentType` | spring-ai-agent-utils-common | 绑定 Resolver + Executor |
| `A2ASubagentDefinition` / `A2ASubagentResolver` / `A2ASubagentExecutor` | spring-ai-agent-utils-a2a | A2A 客户端 |
| `AgentCard` / `DefaultAgentExecutor` | spring-ai-a2a-server | A2A Server 端 |

---

## 3. Demo 一：Subagent Orchestration

### 3.1 架构

```
用户 → SubagentAgentController
         → SubagentAgentService（主协调器 ChatClient + TaskTool）
              → LLM 评估任务
              → Task(subagent_type="architect", prompt=...)
                   → architect 子代理（独立上下文 + architect.md 系统提示）
                   → 返回结构化 Blueprint
              → Task(subagent_type="builder", prompt=Blueprint...)
                   → builder 子代理（独立上下文 + builder.md 系统提示）
                   → 返回最终内容
              → 主代理汇总 → 响应用户
```

### 3.2 子代理定义

路径：`src/main/resources/agents/`

**`architect.md`**（YAML frontmatter + Markdown 正文）：

```yaml
---
name: architect
description: Use for complex analysis requiring deep reasoning. Produces a structured Blueprint. Do NOT write the final user-facing response.
model: default
---
```

职责：分析用户输入，输出结构化蓝图（章节大纲、关键论点、数据要点），**不**写最终润色文稿。

**`builder.md`**：

```yaml
---
name: builder
description: Generate polished final content from a Blueprint provided by architect. Use after architect for complex writing tasks.
model: default
---
```

职责：仅根据 Blueprint 生成精美、可直接交付的中文 Markdown 报告，不添加蓝图外信息。

### 3.3 主协调器 System Prompt 要点

- 你是任务协调器，通过 Task 工具访问子代理
- 可用子代理：`architect`（深度分析）、`builder`（内容生成）
- **复杂写作/分析任务**：先 `architect` 再 `builder`
- **简单问答**：可直接回答，不必委派
- 始终将**最终整合结果**返回给用户（中文）

### 3.4 配置类 `SubagentAgentConfig`

```java
@Bean
@Qualifier("subagentTaskTool")
ToolCallback subagentTaskTool(ChatClient.Builder chatClientBuilder,
                              @Value("${agent.tasks.paths}") List<Resource> agentPaths) {
    var taskTool = TaskTool.builder()
        .subagentTypes(ClaudeSubagentType.builder()
            .chatClientBuilder("default", chatClientBuilder.clone())
            .build())
        .subagentReferences(ClaudeSubagentReferences.fromResources(agentPaths))
        .build();
    return taskTool; // 或 defaultToolCallbacks 注册方式
}
```

主 `ChatClient`（`SubagentAgentService` 注入）：

- `defaultSystem(ORCHESTRATOR_PROMPT)`
- `defaultToolCallbacks(subagentTaskTool)` — **仅 TaskTool，不叠加其他工具**

### 3.5 配置项

```properties
# application.properties
agent.tasks.paths=classpath:/agents/architect.md,classpath:/agents/builder.md
```

---

## 4. Demo 二：A2A 跨系统对话

### 4.1 架构（单应用内嵌）

```
┌─────────────────────────────────────────────────────────────┐
│  demo2 (port 8081)                                          │
│                                                             │
│  ┌─────────────────────┐    A2A JSON-RPC    ┌────────────┐ │
│  │ A2aOrchestrator     │ ─────────────────► │ A2A Server │ │
│  │ (TaskTool + A2A     │   POST /           │ 天气专家    │ │
│  │  SubagentExecutor)  │                    │ AgentCard  │ │
│  └─────────────────────┘                    │ + Weather  │ │
│         ▲                                   │   Tool     │ │
│         │ HTTP GET                          └────────────┘ │
│  A2aOrchestratorController                                 │
│  /.well-known/agent-card.json ◄── 发现远程 Agent 能力       │
└─────────────────────────────────────────────────────────────┘
```

逻辑上「跨系统」：协调器通过 A2A 协议（HTTP + JSON-RPC + AgentCard 发现）调用远程 Agent；物理上同进程，降低 Demo 运维成本。

### 4.2 A2A Server：天气专家 Agent

**配置类 `A2aWeatherAgentConfig`**（独立 Bean，不与 Subagent 配置混用）：

| Bean | 说明 |
|------|------|
| `AgentCard` | name=`Weather Agent`，url=`http://localhost:{port}/`，skills 含 `weather_search` |
| `AgentExecutor` | `DefaultAgentExecutor` + 专用 `ChatClient`（system: 天气助手，tools: `WeatherTool`） |

复用现有 `com.jason.demo.demo2.tools.WeatherTool`（模拟城市天气 JSON）。

**application.properties**：

```properties
spring.ai.a2a.server.enabled=true
agent.a2a.remote.url=http://localhost:${server.port:8081}
```

### 4.3 A2A 端点与路由注意

`spring-ai-a2a-server` 自动注册：

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/.well-known/agent-card.json` | Agent 发现 |
| GET | `/card` | AgentCard 备用路径 |
| POST | `/` | JSON-RPC `sendMessage` |

与现有路由关系：

- `GET /` → `index.html`（静态资源），**无冲突**
- `POST /` → A2A MessageController；仅处理 `Content-Type: application/json` 的 JSON-RPC，不影响其他 POST 端点（如 `/agent/todo/chat`）

`A2ASubagentResolver` 从 `agent.a2a.remote.url` 拉取 `/.well-known/agent-card.json`。

### 4.4 A2A 协调器 `A2aOrchestratorConfig`

```java
var taskTool = TaskTool.builder()
    // 本地 architect/builder（可选：A2A Tab 仅注册 A2A 子代理，保持职责单一）
    .subagentTypes(
        ClaudeSubagentType.builder()
            .chatClientBuilder("default", chatClientBuilder.clone())
            .build(),
        new SubagentType(new A2ASubagentResolver(), new A2ASubagentExecutor()))
    .subagentReferences(
        ClaudeSubagentReferences.fromResources(agentPaths),  // 若不需要可省略
        new SubagentReference(a2aRemoteUrl, A2ASubagentDefinition.KIND))
    .build();
```

**首版决策**：A2A Tab 的协调器**仅注册 A2A 远程天气子代理**（不叠加 architect/builder），避免用户混淆两个 Demo 边界。Subagent Tab 仅本地子代理。

A2A 协调器 System Prompt 要点：

- 你是跨系统任务协调器
- 天气相关问题必须通过 Task 工具委派给远程 **Weather Agent**（A2A）
- 收到远程结果后整理成中文旅行/出行建议返回用户

### 4.5 Bean 隔离

| Bean | Qualifier | 用途 |
|------|-----------|------|
| `subagentOrchestratorClient` | — | Subagent Demo 专用 ChatClient |
| `a2aOrchestratorClient` | — | A2A Demo 专用 ChatClient |
| `a2aWeatherAgentExecutor` | — | A2A Server 端执行器（`DefaultAgentExecutor`） |

避免两个 Demo 共用同一 `ChatClient` 导致工具/提示词污染。

---

## 5. API 设计

### 5.1 GET `/agent/subagent/chat`

**参数**：`message`（必填）

**示例**：

```
GET /agent/subagent/chat?message=分析 Spring AI RAG 架构并写一份入门指南
```

**响应**：

```json
{
  "message": "分析 Spring AI RAG 架构并写一份入门指南",
  "response": "（主协调器整合后的最终 Markdown 报告）",
  "agentType": "Subagent Orchestration · Architect-Builder · TaskTool"
}
```

**行为**：同步调用，预计耗时 30～90 秒（多次 LLM + Task 委派）。

### 5.2 GET `/agent/a2a/chat`

**参数**：`message`（必填）

**示例**：

```
GET /agent/a2a/chat?message=查北京和上海的天气，并给出周末出行建议
```

**响应**：

```json
{
  "message": "查北京和上海的天气，并给出周末出行建议",
  "response": "（协调器整合 A2A 天气 Agent 结果后的建议）",
  "agentType": "A2A Orchestration · TaskTool + Weather Agent (embedded)"
}
```

### 5.3 GET `/.well-known/agent-card.json`（A2A 发现）

供 A2A 客户端（含 `A2ASubagentResolver`）自动发现；手动验证：

```bash
curl http://localhost:8081/.well-known/agent-card.json
```

---

## 6. 前端设计

### 6.1 新增 Tab

| Tab ID | 按钮文案 | 配色 |
|--------|----------|------|
| `subagent` | 🔗 Subagent 编排 | 沿用 agent-tools 紫色系 |
| `a2a` | 🌐 A2A 跨系统对话 | 沿用 multi-agent 色系 |

### 6.2 Subagent Tab 布局

- 标题：Subagent Orchestration（子代理编排）
- 说明：主协调器通过 Task 工具委派 architect / builder，独立上下文、结果回传
- 流程示意：`用户提问 → architect 分析 → builder 生成 → 汇总回复`
- 示例按钮：
  - 「分析 Spring AI RAG 架构并写入门指南」
  - 「对比 TodoWrite 与 Subagent 的适用场景并写总结」
- 输入框 +「开始编排」按钮
- 结果区：`pre-wrap` 展示 `response`；请求期间按钮 disabled +「编排中，请耐心等待…」

### 6.3 A2A Tab 布局

- 标题：A2A 跨系统对话
- 说明：协调器通过 A2A 协议调用内嵌天气专家 Agent（`/.well-known/agent-card.json`）
- 流程示意：`用户提问 → 协调器 → A2A 远程调用 → 天气专家 → 汇总回复`
- 示例按钮：
  - 「查北京和上海天气，给周末出行建议」
  - 「深圳天气怎么样，适合户外跑步吗」
- 输入框 +「开始 A2A 对话」按钮
- 结果区：同上

### 6.4 交互实现

对齐 `multi-agent` Tab 的 `fetch` + loading 模式：

```javascript
async function startSubagentChat() {
  // fetch GET /agent/subagent/chat?message=...
  // 展示 loading，完成后渲染 response
}
```

不使用 SSE（子代理中间步骤首版不推送；后续可扩展 `TASK_DELEGATED` 事件）。

---

## 7. 错误处理

| 场景 | 处理 |
|------|------|
| `DEEPSEEK_API_KEY` 未配置 | 返回可读错误：`调用 AI 模型失败：...` |
| Task 委派超时 | 依赖 Spring AI / HTTP 默认超时；前端提示重试 |
| A2A AgentCard 拉取失败 | 启动时 `A2ASubagentResolver` 失败 → 日志 ERROR；运行时返回「远程 Agent 不可用」 |
| A2A `POST /` 与静态资源 | 仅 JSON-RPC 请求命中 MessageController；不影响 `index.html` |
| 子代理 `.md` 未找到 | 启动时 `ClaudeSubagentReferences` 解析失败 → 应用启动失败（快速暴露配置问题） |

---

## 8. 文件清单

| 操作 | 文件 |
|------|------|
| 修改 | `pom.xml`（BOM + a2a 依赖） |
| 修改 | `application.properties`（`agent.tasks.paths`、`spring.ai.a2a.server.enabled`、`agent.a2a.remote.url`） |
| 新增 | `resources/agents/architect.md` |
| 新增 | `resources/agents/builder.md` |
| 新增 | `config/SubagentAgentConfig.java` |
| 新增 | `config/A2aWeatherAgentConfig.java` |
| 新增 | `config/A2aOrchestratorConfig.java` |
| 新增 | `service/SubagentAgentService.java` |
| 新增 | `service/A2aOrchestratorService.java` |
| 新增 | `controller/SubagentAgentController.java` |
| 新增 | `controller/A2aOrchestratorController.java` |
| 修改 | `static/index.html`（两个 Tab + JS） |
| 修改 | `README.md`（新增两章说明，实现阶段） |

---

## 9. 测试计划

### 9.1 编译

```bash
cd demo2 && mvn compile -q
```

### 9.2 Subagent 手动测试

1. 配置 `DEEPSEEK_API_KEY`，启动应用（8081）
2. 打开 Subagent Tab，点击示例 1
3. 等待 30～90 秒，确认返回结构化中文报告
4. 查看日志是否出现 Task 工具调用 / architect、builder 委派痕迹

### 9.3 A2A 手动测试

1. `curl http://localhost:8081/.well-known/agent-card.json` 返回 Weather Agent 元数据
2. A2A Tab 发送「查北京天气」
3. 确认响应含北京天气信息（来自 `WeatherTool`）
4. 日志可见 A2A JSON-RPC 请求处理

### 9.4 边界测试

- 空 `message` → 400 或友好提示
- API Key 缺失 → 错误信息可读
- 连续点击按钮 → 禁用按钮防止重复请求

---

## 10. 方案对比记录

| 方案 | 结论 |
|------|------|
| **A. 单应用内嵌 A2A Server** | **已选用** — 一个进程测两个 Tab |
| B. 多进程（10000/10001/10002） | 与官方 airbnb-planner 一致，运维成本高，未选用 |
| C. Mock A2A 响应 | 无教学价值，未选用 |
| Subagent：Architect + Builder | **已选用** |
| Subagent：spring-ai-expert | 用户未选 |
| 交互：SSE 实时委派 | Token/复杂度高，首版用同步 GET + loading |
| 交互：同步 GET | **已选用** — 对齐 SkillsAgent / MultiAgent |
| A2A Tab 叠加 architect/builder | 职责混淆，**未选用** — 仅 A2A 天气子代理 |

---

## 11. 实现顺序建议

1. `pom.xml` 依赖 + `application.properties`
2. `architect.md` / `builder.md`
3. Subagent 后端（Config → Service → Controller）+ 编译
4. A2A Server（Weather AgentCard + Executor）+ A2A Orchestrator + 编译
5. `index.html` 两个 Tab
6. 手动联调 + README 补充

---

## 12. 后续可扩展（非本期）

- SSE 推送 `TASK_STARTED` / `TASK_COMPLETED` 子代理事件
- DeepSeek + 备用模型多路由（architect 用推理模型，builder 用快速模型）
- `spring-ai-agent-utils` 内置 Explore / Plan / Bash 子代理演示
- 独立端口部署真实「跨进程」A2A 拓扑图
