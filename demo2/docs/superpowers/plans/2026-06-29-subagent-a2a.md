# Subagent Orchestration + A2A Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]` / `- [ ]`) syntax for tracking.

**Goal:** 在 demo2 新增两个 Spring AI Agentic Patterns 教程 Demo：**Subagent Orchestration**（Architect + Builder + TaskTool）与 **A2A 跨系统对话**（内嵌天气专家 A2A Server + TaskTool 协调器）；`index.html` 各增一个 Tab；`mvn compile` 通过。

**Architecture:** Subagent Demo 使用独立 `@Qualifier("subagentOrchestratorClient")` ChatClient，仅挂 `TaskTool` + `classpath:/agents/*.md` 本地子代理。A2A Demo 在同进程启用 `spring-ai-a2a-server-autoconfigure`（`AgentCard` + `DefaultAgentExecutor` + `WeatherTool`），协调器通过 `spring-ai-agent-utils-a2a` 的 `A2ASubagentResolver` / `A2ASubagentExecutor` 跨协议委派。前端对齐 MultiAgent：**同步 GET** + loading。

**Tech Stack:** Java 21, Spring Boot 4.1, Spring AI 2.0.0, spring-ai-agent-utils 0.10.0, spring-ai-agent-utils-a2a 0.10.0, spring-ai-a2a-server-autoconfigure 0.3.0, DeepSeek, static/index.html

**设计规范:** [docs/superpowers/specs/2026-06-29-subagent-a2a-design.md](../specs/2026-06-29-subagent-a2a-design.md)

## Global Constraints

- 新增 Maven 依赖：`spring-ai-agent-utils-bom`、`spring-ai-agent-utils-a2a`、`spring-ai-a2a-server-autoconfigure`
- 服务端口 **8081**；LLM 统一 **DeepSeek**（首版不做多模型路由）
- Subagent 主代理**仅**注册 `TaskTool`；A2A 远程 Agent **仅**注册 `WeatherTool`
- A2A Tab 协调器**不**叠加 architect/builder（与 Subagent Tab 职责分离）
- 交互为同步 `GET`（非 SSE）；单次请求预计 **30～90 秒**
- API 前缀：`/agent/subagent`、`/agent/a2a`
- 中文 System Prompt 与前端文案

---

## File Structure

| 文件 | 职责 |
|------|------|
| `pom.xml` | BOM + agent-utils-a2a + a2a-server-autoconfigure |
| `application.properties` | `agent.tasks.paths`、`spring.ai.a2a.server.enabled`、`agent.a2a.remote.url` |
| `resources/agents/architect.md` | 推理子代理：产出 Blueprint |
| `resources/agents/builder.md` | 执行子代理：根据 Blueprint 生成报告 |
| `config/SubagentAgentConfig.java` | `TaskTool` + `ClaudeSubagentType` + `subagentOrchestratorClient` |
| `service/SubagentAgentService.java` | Subagent 编排业务 |
| `controller/SubagentAgentController.java` | `GET /agent/subagent/chat` |
| `config/A2aWeatherAgentConfig.java` | 内嵌 A2A Server：`AgentCard` + `AgentExecutor` |
| `config/A2aOrchestratorConfig.java` | A2A 协调器 `TaskTool` + `a2aOrchestratorClient` |
| `service/A2aOrchestratorService.java` | A2A 编排业务 |
| `controller/A2aOrchestratorController.java` | `GET /agent/a2a/chat` |
| `static/index.html` | Subagent / A2A 两个 Tab + JS |
| `docs/superpowers/specs/2026-06-29-subagent-a2a-design.md` | 设计规范 |

**复用（不修改）：**

- `tools/WeatherTool.java` — A2A 天气专家工具

---

### Task 1: Maven 依赖与配置项

**Files:**
- Modify: `demo2/pom.xml`
- Modify: `demo2/src/main/resources/application.properties`

- [x] **Step 1: `dependencyManagement` 引入 `spring-ai-agent-utils-bom:0.10.0`**

- [x] **Step 2: 添加运行时依赖**

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-agent-utils</artifactId>
</dependency>
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-agent-utils-a2a</artifactId>
</dependency>
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-a2a-server-autoconfigure</artifactId>
    <version>0.3.0</version>
</dependency>
```

- [x] **Step 3: `application.properties` 追加**

```properties
agent.tasks.paths=classpath:/agents/architect.md,classpath:/agents/builder.md
spring.ai.a2a.server.enabled=true
agent.a2a.remote.url=http://localhost:${server.port:8081}
```

- [x] **Step 4: 编译验证**

```bash
cd demo2 && mvn compile -q
```

Expected: BUILD SUCCESS

---

### Task 2: 子代理 Markdown 定义

**Files:**
- Create: `demo2/src/main/resources/agents/architect.md`
- Create: `demo2/src/main/resources/agents/builder.md`

- [x] **Step 1: `architect.md`**

YAML frontmatter：`name: architect`，`description` 说明复杂分析产出 Blueprint，`model: default`。正文：战略推理代理，输出结构化 Blueprint，禁止写最终润色文稿。

- [x] **Step 2: `builder.md`**

YAML frontmatter：`name: builder`，`description` 说明根据 Blueprint 生成内容。正文：仅基于 Blueprint 生成中文 Markdown 报告。

---

### Task 3: Subagent 后端

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/config/SubagentAgentConfig.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/service/SubagentAgentService.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/controller/SubagentAgentController.java`

**关键 import（0.10.0）：**

```java
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentReferences;
```

- [x] **Step 1: `SubagentAgentConfig`**

- `@Bean @Qualifier("subagentOrchestratorClient") ChatClient`
- `TaskTool.builder()` + `ClaudeSubagentType` + `ClaudeSubagentReferences.fromResources(agentPaths)`
- `defaultSystem(ORCHESTRATOR_PROMPT)` + `defaultTools(taskTool)` **仅此工具**
- 协调器 Prompt：复杂任务先 architect 再 builder；简单问题自答；最终中文回复

- [x] **Step 2: `SubagentAgentService`**

注入 `@Qualifier("subagentOrchestratorClient") ChatClient`，`chat(message)` 同步 `call().content()`，异常返回可读错误。

- [x] **Step 3: `SubagentAgentController`**

- `@RequestMapping("/agent/subagent")`
- `GET /chat?message=` → `Map.of("message", "response", "agentType", ...)`
- Swagger `@Tag` / `@Operation`

- [x] **Step 4: 编译**

```bash
cd demo2 && mvn compile -q
```

Expected: BUILD SUCCESS

---

### Task 4: A2A Server（内嵌天气专家）

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/config/A2aWeatherAgentConfig.java`

**关键 import：**

```java
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentSkill;
import io.a2a.server.agentexecution.AgentExecutor;
import org.springaicommunity.a2a.server.executor.DefaultAgentExecutor;
```

- [x] **Step 1: `AgentCard` Bean**

- `name`: Weather Agent
- `url`: `http://localhost:{port}/`
- `protocolVersion`: `0.3.0`
- `skills`: `weather_search`

- [x] **Step 2: `AgentExecutor` Bean**

`DefaultAgentExecutor` + 专用 `ChatClient`（system: 天气助手，tools: `WeatherTool`）

- [x] **Step 3: 编译**

```bash
cd demo2 && mvn compile -q
```

Expected: BUILD SUCCESS；全局仅一个 `AgentCard` Bean

---

### Task 5: A2A 协调器后端

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/config/A2aOrchestratorConfig.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/service/A2aOrchestratorService.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/controller/A2aOrchestratorController.java`

**关键 import：**

```java
import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springaicommunity.agent.common.task.subagent.SubagentType;
import org.springaicommunity.agent.subagent.a2a.A2ASubagentDefinition;
import org.springaicommunity.agent.subagent.a2a.A2ASubagentResolver;
import org.springaicommunity.agent.subagent.a2a.A2ASubagentExecutor;
```

- [x] **Step 1: `A2aOrchestratorConfig`**

```java
var taskTool = TaskTool.builder()
    .subagentTypes(new SubagentType(new A2ASubagentResolver(), new A2ASubagentExecutor()))
    .subagentReferences(new SubagentReference(a2aRemoteUrl, A2ASubagentDefinition.KIND))
    .build();
```

- `@Bean @Qualifier("a2aOrchestratorClient") ChatClient`
- 协调器 Prompt：天气问题委派远程 Weather Agent；整合中文回复

- [x] **Step 2: `A2aOrchestratorService` + `A2aOrchestratorController`**

- `GET /agent/a2a/chat?message=`

- [x] **Step 3: 编译**

```bash
cd demo2 && mvn compile -q
```

Expected: BUILD SUCCESS

---

### Task 6: 前端两个 Tab

**Files:**
- Modify: `demo2/src/main/resources/static/index.html`

- [x] **Step 1: tab-nav 增加按钮**

- `🔗 Subagent 编排`（`data-tab="subagent"`）
- `🌐 A2A 跨系统对话`（`data-tab="a2a"`）

- [x] **Step 2: Subagent Tab 面板**

- 流程示意 + 2 个示例按钮 + `#subagentMessageInput` + `#subagentStartBtn` + `#subagentResult`
- JS：`fillSubagentMessage`、`startSubagentChat` → `fetch('/agent/subagent/chat?message=...')`

- [x] **Step 3: A2A Tab 面板**

- 流程示意 + 2 个示例按钮 + `#a2aMessageInput` + `#a2aStartBtn` + `#a2aResult`
- JS：`fillA2aMessage`、`startA2aChat` → `fetch('/agent/a2a/chat?message=...')`

- [x] **Step 4: loading 与防重复提交**

请求期间 `btn.disabled = true`，文案提示约 30～90 秒

---

### Task 7: 联调与文档

**Files:**
- Modify: `demo2/docs/superpowers/specs/2026-06-29-subagent-a2a-design.md`（状态 → 已实现）
- Modify: `demo2/README.md`（可选：补充两节说明）

- [x] **Step 1: 全量编译**

```bash
cd demo2 && mvn clean compile -q
```

Expected: BUILD SUCCESS

- [x] **Step 2: 启动后验证 AgentCard**

```bash
curl -s http://localhost:8081/.well-known/agent-card.json
```

Expected: JSON 含 `"name":"Weather Agent"`（或等效字段）

- [ ] **Step 3: 浏览器手动测两个 Tab**（需 `DEEPSEEK_API_KEY`）

1. Subagent Tab → 示例1 → 等待返回 Markdown 报告
2. A2A Tab → 示例1 → 返回含北京/上海天气的建议

- [ ] **Step 4: README 补充 Subagent / A2A 章节**（端点、示例 prompt、依赖说明）

---

## 依赖关系

```
Task 1 (pom + properties)
    ├── Task 2 (agents/*.md)
    │       └── Task 3 (Subagent 后端)
    ├── Task 4 (A2A Server)
    │       └── Task 5 (A2A 协调器)
    └── Task 6 (前端) ← 依赖 Task 3 + 5
            └── Task 7 (联调 + 文档)
```

Task 3 与 Task 4/5 可并行（均依赖 Task 1 + 2 中 architect/builder 仅 Task 3 需要）。

---

## 风险与排错

| 问题 | 处理 |
|------|------|
| `TaskTool` import 404 | 确认 `spring-ai-agent-utils:0.10.0`；包路径为 `org.springaicommunity.agent.tools.task.*` |
| 启动失败：多个 `AgentCard` | 全局仅保留 `A2aWeatherAgentConfig` 中一个 `AgentCard` Bean |
| A2A 调用失败 | 确认 `agent.a2a.remote.url` 端口与 `server.port` 一致（默认 8081） |
| `POST /` 与静态资源 | A2A `MessageController` 仅处理 JSON-RPC POST；`GET /` 仍为 `index.html` |
| 请求超时 | 前端无特殊超时配置；浏览器默认可能较长；日志查看 Task 委派进度 |
| API Key 未配置 | 响应含「调用 AI 模型失败」 |

---

## Spec 覆盖自检

| Spec 要求 | 对应 Task | 状态 |
|-----------|-----------|------|
| 方案 A：单应用内嵌 A2A | Task 4 + 5 | [x] |
| Architect + Builder 子代理 | Task 2 + 3 | [x] |
| 同步 GET 交互 | Task 3 + 5 + 6 | [x] |
| 两个 index.html Tab | Task 6 | [x] |
| `mvn compile` 通过 | Task 1 + 7 | [x] |
| Bean 隔离（两个 ChatClient） | Task 3 + 5 | [x] |
| A2A Tab 不叠加本地子代理 | Task 5 | [x] |
| Swagger 端点文档 | Task 3 + 5 | [x] |
| README 文档 | Task 7 Step 4 | [x] |

---

## 参考链接

- [Subagent Orchestration 教程（微信）](https://mp.weixin.qq.com/s/tn6_dT5sCa-9RcdzM-2qpA)
- [A2A 跨系统对话教程（微信）](https://mp.weixin.qq.com/s/MOGmfbZFiC8k07rbmpOX4Q)
- [subagent-demo](https://github.com/spring-ai-community/spring-ai-agent-utils/tree/main/examples/subagent-demo)
- [subagent-a2a-demo](https://github.com/spring-ai-community/spring-ai-agent-utils/tree/main/examples/subagent-a2a-demo)
- [spring-ai-a2a README](https://github.com/spring-ai-community/spring-ai-a2a)
