# AgentScope MCP 实战：外部文件工具接进 Toolkit 设计规范

**日期**：2026-07-24  
**项目**：spring-ai-demo / demo2  
**状态**：待用户审阅  
**前置能力**：AgentScope Toolkit、AgentEvent SSE、Permission HITL、PostgreSQL AgentStateStore、Workspace、Compaction、Middleware  
**参考文章**：[11. AgentScope Java 2.0 MCP 实战：把外部文件工具接进 Toolkit](https://mp.weixin.qq.com/s/3r6RS9z37fMJpG5EXJ3wqg)

---

## 1. 背景与目标

### 1.1 问题

当前 AgentScope Dev Agent 已通过 Java `@Tool` / `AgentTool` 注册项目只读工具与受控写文件工具。若再增加「读项目档案」一类能力，可以继续写 Java 方法，也可以复用官方 filesystem MCP Server 已提供的列目录 / 读文件能力。

后者更符合 MCP 接入路径：Server 公布工具，Java 侧用 AgentScope `McpClientBuilder` 连接，再把筛选后的工具注册进 `Toolkit`。模型调用方式不变，执行位置移到 MCP 子进程。

### 1.2 目标

1. 在现有 Dev Agent Toolkit 中接入 `@modelcontextprotocol/server-filesystem`（stdio / `npx`），只注册只读工具白名单。
2. 提供 `mcp-files/project-profile.md`，演示「先 `list_directory` 再 `read_text_file`」回答项目档案。
3. **配置支持多个 MCP Client**（列表），本次只落地 filesystem 一个实例；总开关 + 单 Client 开关，测试环境默认可关。
4. system prompt 注入 `{mcpRoot}`（主资料目录绝对路径），避免模型用相对路径试错。
5. Controller / SSE / HITL 流程不改；继续 `disableFilesystemTools()`，与 Spring AI MCP（瑞幸 / 本地 Tab）隔离。

### 1.3 已确认决策

| 维度 | 决策 |
|------|------|
| 接入位置 | 现有 AgentScope Dev Agent（非独立 Agent） |
| 可开关 | 总开关 `mcp.enabled` + 单 Client `clients[].enabled` |
| 多 Client | `mcp.clients[]` 列表，后续加 Server 只加配置项 |
| 传输 | 本次仅 stdio；远程 SSE / Streamable HTTP 留扩展点但不实现 |
| 工具白名单 | `list_allowed_directories`、`list_directory`、`read_text_file` |
| 包版本 | `@modelcontextprotocol/server-filesystem@2026.7.10`（与文章一致） |
| Maven | 不新增依赖；`agentscope-harness:2.0.0` 已带 `McpClientBuilder` |
| 权限 | 只读工具依赖 Server `readOnlyHint`；不在 `permissionContext` 为 MCP 逐条加规则 |
| 测试 | `application-test.properties` 中 `mcp.enabled=false` |
| Windows | 先用 `npx`；若本机验证失败再补 `cmd /c` 适配 |

### 1.4 非目标

- 启用写文件 / 移动 / 创建目录等 MCP 工具
- 打开内置 filesystem / shell 工具
- 改造 Spring AI MCP Client（瑞幸、local-server）
- 工具重名自动加 Server 前缀
- 本篇接入第二个真实 MCP Server
- 为 MCP 单独新建前端 Tab 或 API

---

## 2. 方案选择

### 2.1 采用方案：可开关的多 Client 列表 + stdio filesystem

相对文章单对象 `mcp { name, command, ... }`，demo2 采用：

```text
mcp.enabled                 # 总开关
mcp.clients[]               # 0..N 个 Client
  name / enabled / command / arguments / root? / enabled-tools
```

- `enabled=false`（总开关或测试）：不创建任何 `McpClientWrapper`，不注册 MCP 工具；prompt 可不强调 MCP 目录。
- `enabled=true`：对每个 `clients[i].enabled=true` 的项创建 Client，并 `toolkit.registration().mcpClient(...).enableTools(...).apply()`。
- filesystem 类 Client：配置可选 `root`；解析为绝对路径后追加到 `arguments` 末尾（与文章一致）。

### 2.2 未采用方案

| 方案 | 原因 |
|------|------|
| 严格单 Client（文章原样） | 后续加 Server 要改 Properties 形状，扩展成本高 |
| 启动失败软降级 | 行为不确定，测试难断言 |
| Spring profile 才加载 MCP | 本地/文档多一套 profile，摩擦大 |

---

## 3. 总体架构

```text
用户问项目档案
  → POST /agentscope/dev-agent/ask（现有）
  → HarnessAgent / Toolkit
       ├─ Java 工具：read_pom / list_source_folders / find_main_class / apply_file_change
       └─ MCP 工具：list_directory / read_text_file / list_allowed_directories
            → McpClientWrapper (stdio)
            → npx @modelcontextprotocol/server-filesystem@2026.7.10 <mcpRoot>
            → 仅允许访问 mcp-files/
```

调用链对比（与文章一致）：

```text
Java @Tool → Toolkit → ReflectiveFunctionTool → 当前 JVM 方法
MCP Tool   → Toolkit → McpTool → MCP Client → MCP Server 子进程
```

三层边界：

1. **白名单**：未列入 `enabled-tools` 的工具不注册，模型不可见。
2. **权限**：只读 `readOnlyHint` → 直接执行，不出现 `REQUIRE_USER_CONFIRM`。
3. **目录**：Server 仅允许 `root`（`mcp-files` 绝对路径）及其子路径；越界返回 Access denied。

---

## 4. 配置设计

### 4.1 Properties 模型

`DevAgentProperties` 增加嵌套：

```java
public record DevAgentProperties(
        ...,
        @Valid McpSettings mcp) {

    public record McpSettings(
            boolean enabled,
            List<@Valid McpClientConfig> clients) {}

    public record McpClientConfig(
            @NotBlank String name,
            boolean enabled,                 // 默认 true（绑定层注意）
            @NotBlank String command,
            @NotEmpty List<@NotBlank String> arguments,
            String root,                     // 可选；有则解析为绝对路径追加到 arguments
            @NotEmpty List<@NotBlank String> enabledTools) {}
}
```

绑定约定：

- 前缀仍为 `app.agentscope.dev-agent`。
- `mcp` 必须可绑定且带安全默认：`enabled=false`、`clients=[]`（实现可用 record 紧凑构造或 `@DefaultValue`），保证现有未声明 `mcp.*` 的单元测试仍能启动。
- **本地演示**在 `application.properties` 显式 `enabled=true` 并配置 filesystem Client。
- 测试：`app.agentscope.dev-agent.mcp.enabled=false`（可省略 clients）。
- 单 Client 的 `enabled` 缺省为 `true`（仅当该项已出现在列表中时）。

### 4.2 示例配置（properties）

```properties
app.agentscope.dev-agent.mcp.enabled=true
app.agentscope.dev-agent.mcp.clients[0].name=project-files
app.agentscope.dev-agent.mcp.clients[0].enabled=true
app.agentscope.dev-agent.mcp.clients[0].command=npx
app.agentscope.dev-agent.mcp.clients[0].arguments[0]=-y
app.agentscope.dev-agent.mcp.clients[0].arguments[1]=@modelcontextprotocol/server-filesystem@2026.7.10
app.agentscope.dev-agent.mcp.clients[0].root=mcp-files
app.agentscope.dev-agent.mcp.clients[0].enabled-tools[0]=list_allowed_directories
app.agentscope.dev-agent.mcp.clients[0].enabled-tools[1]=list_directory
app.agentscope.dev-agent.mcp.clients[0].enabled-tools[2]=read_text_file
```

也可等价写入 YAML（若后续把 MCP 段挪到 `application-agentscope-*.yml`）；形状与上表一致。

### 4.3 System Prompt

在 `application-agentscope-prompts.yml` 追加（对齐文章意图）：

- MCP 资料目录是 `{mcpRoot}`。
- 询问项目档案时：先 `list_directory`，再 `read_text_file`；使用完整路径，不猜内容。
- 用户明确要求用 `read_text_file` 验证越界路径时，按原路径调用，不改用 `list_allowed_directories`，不提前判断结果。

`AgentScopeConfig` 构建 Agent 时：

```java
String systemPrompt = properties.systemPrompt()
        .replace("{mcpRoot}", primaryMcpRoot(properties).toString());
```

`primaryMcpRoot`：取第一个「`enabled=true` 且 `root` 非空」的 Client 解析路径；若无（总开关关 / 无 root Client），替换为占位文案如 `(MCP 未启用)` 或空串，避免残留字面量 `{mcpRoot}`。

---

## 5. 组件与改动清单

| 路径 | 改动 |
|------|------|
| `mcp-files/project-profile.md` | 新建；档案字段与文章一致（编号 AJS-MCP-011 等；Java 版本可按 demo2 写 21，或严格跟文章 17——**实现时与 demo2 实际 Java 21 对齐，档案写 21**） |
| `DevAgentProperties.java` | 增加 `McpSettings` / `McpClientConfig` |
| `AgentScopeConfig.java` | Client 生命周期管理 + 注册 + prompt 替换 |
| `application.properties` | MCP 列表配置，本地默认启用 |
| `application-agentscope-prompts.yml` | MCP 使用说明 + `{mcpRoot}` |
| `application-test.properties` | `mcp.enabled=false` |
| `DevAgentPropertiesBindingTest` | 绑定 clients / enabled |
| 可选：`AgentscopeMcpClientRegistry` | 若 Config 过长，抽出「解析 root / 建 Client / close」辅助类 |
| `README.md` | 补一行：AgentScope MCP filesystem、需 Node/`npx`、测试可关 |

不改：

- `DevAgentController` / `DevAgentService` / 前端 `agentscope.js`
- `permissionContext` 中 MCP 工具 ALLOW 规则（依赖 `readOnlyHint`）
- Spring AI `mcp/client/**`

### 5.1 Client 生命周期

推荐一个可关闭的注册表 Bean，避免多个 `McpClientWrapper` 各自 `@Bean` 难以销毁：

```text
@Bean(destroyMethod = "close")
AgentscopeMcpClientRegistry agentscopeMcpClientRegistry(DevAgentProperties props)

Registry.close() → 依次 close 所有已创建的 McpClientWrapper
```

创建逻辑：

1. 若 `!mcp.enabled()` → 空注册表。
2. 否则遍历 `clients`，跳过 `!enabled`。
3. `arguments` 拷贝后，若 `root` 非空则 `arguments.add(resolveRoot(...).toString())`。
4. `McpClientBuilder.create(name).stdioTransport(command, args...).buildAsync().block()`。
5. Agent Bean 注入 Registry，对每个 Client 执行 registration + enableTools + apply。

`resolveRoot`：绝对路径 normalize；相对路径基于 `projectRoot` resolve（与文章一致）。

### 5.2 工具名冲突

AgentScope Java 2.0.0 **保留 Server 原工具名，不加前缀**。多 Client 时若工具重名会冲突。本篇仅一个 filesystem Client；后续接入时由配置方保证 `enabled-tools` 不重名，或换 Server / 过滤。

---

## 6. 资料文件

路径：`demo2/mcp-files/project-profile.md`

内容（字段对齐文章，Java 版本对齐本仓库）：

```markdown
# 项目档案

- 项目编号：AJS-MCP-011
- Java 版本：21
- Spring Boot 版本：4.1.0
- 维护团队：平台工程组
- 发布窗口：每周四 20:00
```

---

## 7. 验证计划

### 7.1 自动化

- `DevAgentPropertiesBindingTest`：绑定 `mcp.enabled` 与 `clients[0].*`。
- 现有 `mvn test`：因测试关 MCP，不依赖 Node；全绿。
- 可选单元测试：`resolveRoot` 相对/绝对路径（若抽出 helper）。

### 7.2 手工（`mcp.enabled=true`，需 Node/`npx`）

1. 启动 Postgres + 应用；日志中出现三个工具名注册成功。
2. curl（端口以 demo2 为准，默认 8081）：

```bash
curl -sN -X POST "http://localhost:8081/agentscope/dev-agent/ask" \
  -H "Content-Type: application/json" \
  -d '{"userId":"mcp-user-011","sessionId":"mcp-session-011","message":"请先列出 MCP 资料目录，再读取 project-profile.md，告诉我项目编号、Java 版本、Spring Boot 版本和维护团队。"}'
```

期望：`TOOL_CALL_START/RESULT` 含 `list_directory`、`read_text_file`；回答含档案四字段；无 `REQUIRE_USER_CONFIRM`。

3. 越界：

```bash
# message 要求必须调用 read_text_file 读 /etc/hosts（或 Windows 下等价越界路径）
```

期望：工具调用返回 Access denied；`TOOL_RESULT_END` 可为 SUCCESS（协议成功），`AGENT_RESULT` 含拒绝信息。

4. `mcp.enabled=false`：启动无 `npx` 子进程；Toolkit 无上述三工具。

---

## 8. 风险与注意

| 风险 | 缓解 |
|------|------|
| 本机无 Node / npx | 文档说明；测试关 MCP；启用时启动失败应快速暴露 |
| Windows 上 `npx` 启动异常 | 验证后必要时改 `command=cmd` + `arguments=/c,npx,...` |
| 首次 `npx -y` 下载慢 | 可接受；可文档提示预装包 |
| 与 Spring AI MCP 混淆 | README 标明「AgentScope Toolkit MCP」独立于「🔌 MCP Client 聊天」 |
| `readOnlyHint` 不可盲目信任 | 白名单 + 目录 root 双保险；第三方 Server 仍需人工核对 |

---

## 9. 实现顺序（供后续 plan）

1. Properties + 绑定测试 + test 关开关  
2. `mcp-files/project-profile.md` + application / prompt 配置  
3. Registry + `AgentScopeConfig` 注册与 `{mcpRoot}`  
4. 编译 / 测试  
5. 手工 curl 档案查询 + 越界  
6. README 一行说明  

---

## 10. 开放扩展（本篇之后）

后续新增 Client 时，在 `mcp.clients` 追加即可，例如：

```properties
app.agentscope.dev-agent.mcp.clients[1].name=some-other
app.agentscope.dev-agent.mcp.clients[1].command=...
app.agentscope.dev-agent.mcp.clients[1].arguments[0]=...
app.agentscope.dev-agent.mcp.clients[1].enabled-tools[0]=...
```

若需 SSE / Streamable HTTP，再为 `McpClientConfig` 增加 `transport` 枚举与 URL/headers 字段；创建分支走 `sseTransport` / `streamableHttpTransport`。本篇不实现。
