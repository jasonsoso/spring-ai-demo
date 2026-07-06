# 瑞幸 MCP 点单 · 功能归档

**归档日期**: 2026-07-06  
**项目**: spring-ai-demo / demo2  
**状态**: 初版已实现，可联调演示

---

## 1. 功能概述

在 demo2 首页新增 **「☕ 瑞幸 MCP 点单」** Tab，通过 Spring AI MCP Client 对接：

- **瑞幸 MCP Server** — 8 个点单工具（查店、搜品、预览、下单等）
- **高德 MCP Server** — 地理编码（地址 → 经纬度）
- **官方 My Coffee Skill v0.8.2** — 内嵌为 System Prompt，编排点单流程

用户以 **SSE 多轮对话** 完成：查门店 → 选品 → 预览 → 确认 → 支付二维码。

---

## 2. 架构（当前实现）

```
用户（浏览器 Tab）
    │ POST /agent/lkcoffee/chat/stream
    ▼
LkCoffeeAgentController → LkCoffeeAgentService
    │ System Prompt = SKILL.md + 项目覆盖规则
    │ ChatClient + ChatMemory(20) + ToolCallingAdvisor
    │ Tools: LkCoffeeMcpToolCallbacksProvider（白名单过滤）
    ▼
┌─────────────────────┬─────────────────────┐
│ 瑞幸 MCP            │ 高德 MCP            │
│ gwmcp.lkcoffee.com  │ mcp.amap.com        │
│ Bearer: LKCOFFEE_TOKEN                  │
└─────────────────────┴─────────────────────┘
```

**SSE 事件类型**: `RUNNING` · `TOOL_CALL` · `TOKEN` · `ORDER_PREVIEW` · `PAYMENT_QR` · `COMPLETED` · `FAILED`

---

## 3. 关键设计决策（与初稿差异）

| 维度 | 初稿设计 | 当前实现 |
|------|----------|----------|
| Token 传递 | 请求体 / Tab 输入 + ThreadLocal | **仅环境变量** `LKCOFFEE_TOKEN`（运行时优先于 `lkcoffee.token`）；`LkCoffeeTokenResolver` 注入 MCP HTTP Header |
| MCP 初始化 | 启动时同步 | **`McpClientLifecycle`**：local-server 同步；lkcoffee/amap 后台延迟，失败不阻塞启动 |
| 工具加载 | `SyncMcpToolCallbackProvider` Bean | **`LkCoffeeMcpToolCallbacksProvider`** 延迟 `listTools`，避免 Spring 刷新阶段超时 |
| 工具白名单 | 全局 `McpToolFilter` | **`LkCoffeeMcpConfig.isAllowedTool()`** 在 Provider 内过滤 |
| Bearer 注入 | `McpSyncHttpClientRequestCustomizer` | **`LkCoffeeMcpTransportConfig`** + `httpRequestCustomizer` |
| 前端 Token | sessionStorage 输入框 | **已移除**；启动前配置环境变量并重启 |
| 回复渲染 | 纯文本流式 | **Markdown 流式渲染**（`createMarkdownStreamRenderer`） |
| 地理编码 | 返回 `{ raw }` | 后端 + 前端均增强 JSON/regex 解析，提取 `longitude`/`latitude` |

---

## 4. 文件清单

### 后端

| 文件 | 职责 |
|------|------|
| `config/LkCoffeeAgentConfig.java` | ChatMemory、模型配置、Token 状态日志 |
| `service/LkCoffeeSkillLoader.java` | 加载 SKILL.md + 项目覆盖规则 |
| `service/LkCoffeeAgentService.java` | SSE 流式对话、geocode、会话清除 |
| `controller/LkCoffeeAgentController.java` | REST + SSE 端点 |
| `model/LkCoffeeChatRequest.java` | 请求 DTO（sessionId, message, 坐标, address） |
| `model/LkCoffeeSseEvent.java` | SSE 事件工厂 |
| `sse/LkCoffeeStreamContext.java` | ThreadLocal SSE 桥接（TOOL_CALL / PREVIEW / QR） |
| `mcp/client/LkCoffeeTokenResolver.java` | Token 解析与 Bearer 格式化 |
| `mcp/client/LkCoffeeMcpToolCallbacksProvider.java` | 延迟加载白名单 MCP 工具 |
| `mcp/client/LkCoffeeToolCallbackWrapper.java` | 工具调用包装，推送结构化 SSE |
| `mcp/client/LkCoffeeMcpLoggingHttpClient.java` | MCP HTTP 请求/响应调试日志 |
| `mcp/client/McpConnection.java` | 连接名枚举（lkcoffee / amap / local-server） |
| `mcp/client/config/LkCoffeeMcpConfig.java` | 工具名白名单 |
| `mcp/client/config/LkCoffeeMcpTransportConfig.java` | Transport Customizer、Bearer、超时 |
| `mcp/client/config/McpClientLifecycle.java` | MCP 连接生命周期管理 |

### 前端

| 文件 | 职责 |
|------|------|
| `static/index.html` | Tab 入口与 UI 结构 |
| `static/js/tabs/lkcoffee.js` | SSE 对话、定位、地址解析、Markdown 渲染 |
| `static/css/tabs/lkcoffee.css` | Tab 样式（瑞幸蓝 `#0022AB`） |

### Skill 资源

| 文件 | 说明 |
|------|------|
| `resources/.claude/skills/my-coffee/SKILL.md` | 官方 My Coffee Skill v0.8.2 |
| `resources/.claude/skills/my-coffee/manifest.json` | 版本元数据 |

### 测试

| 文件 | 覆盖 |
|------|------|
| `test/.../LkCoffeeSkillLoaderTest.java` | Skill 加载与覆盖规则 |
| `test/.../LkCoffeeAgentServiceTest.java` | sessionId 校验 |

---

## 5. 配置与启动

### 必需环境变量

```bash
# LLM（已有）
DEEPSEEK_API_KEY=sk-...

# 瑞幸 MCP Token（https://open.lkcoffee.com/mcp 获取，约 30 天有效）
LKCOFFEE_TOKEN=your-token

# 高德 MCP（地址解析）
AMAP_API_KEY=your-amap-key
```

### application.properties 要点

```properties
spring.ai.mcp.client.streamable-http.connections.lkcoffee.url=https://gwmcp.lkcoffee.com
spring.ai.mcp.client.streamable-http.connections.lkcoffee.endpoint=/order/user/mcp
lkcoffee.token=${LKCOFFEE_TOKEN:}

spring.ai.mcp.client.streamable-http.connections.amap.url=https://mcp.amap.com
spring.ai.mcp.client.streamable-http.connections.amap.endpoint=/mcp?key=${AMAP_API_KEY:}

agent.lkcoffee.chat.model=deepseek-v4-pro
agent.lkcoffee.skill=classpath:/.claude/skills/my-coffee/SKILL.md
agent.lkcoffee.enabled=true   # 测试环境设为 false
```

### 启动与访问

```bash
cd demo2
mvn spring-boot:run
# 打开 http://localhost:8081 → 「☕ 瑞幸 MCP 点单」Tab
```

启动日志应包含：

- `[LkCoffee] 已加载 My Coffee Skill`
- `[LkCoffee] LKCOFFEE_TOKEN 已配置（来源: ...）` 或警告未配置
- 首次对话时 `[LkCoffee] 已加载 N 个 MCP 工具`

---

## 6. API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/agent/lkcoffee/chat/stream` | SSE 流式对话 |
| `DELETE` | `/agent/lkcoffee/clear?sessionId=` | 清除 ChatMemory |
| `GET` | `/agent/lkcoffee/tools` | 可用 MCP 工具列表 |
| `GET` | `/agent/lkcoffee/geocode?address=&city=` | 地址转经纬度 |

Swagger / Scalar 可查看完整 API 文档。

---

## 7. 点单流程（典型）

1. 用户打开 Tab → 浏览器定位或手动输入经纬度 / 地址解析
2. 输入「帮我来一杯冰美式」
3. Agent 调用 `queryShopList` → 展示门店列表 → **用户确认门店**
4. `searchProductForMcp` → 选规格 → `previewOrder`
5. SSE 推送 `ORDER_PREVIEW` 价格卡片 → Agent 询问确认
6. 用户回复「确认下单」→ `createOrder` → SSE 推送 `PAYMENT_QR`
7. 用户扫码支付

**强制规则**（项目覆盖 Skill）：`previewOrder` 后必须用户明确确认才允许 `createOrder`。

---

## 8. Git 提交记录

| Commit | 日期 | 说明 |
|--------|------|------|
| `74fc611` | 2026-07-04 | 初版：SSE Tab + 双远程 MCP + Skill 内嵌 |
| `77c5f18` | 2026-07-05 | TokenResolver、McpClientLifecycle、地理编码增强、移除前端 Token |
| `042e0ce` | 2026-07-05 | Markdown 流式渲染（跨 Tab） |
| `16acbdf` | 2026-07-05 | MCP HTTP 响应体调试日志 |

---

## 9. 已知限制与后续改进

| 项 | 说明 |
|----|------|
| Token 管理 | 仅环境变量，不支持 Tab 内切换；修改 Token 需重启进程 |
| 模型兼容性 | `deepseek-v4-pro` 多轮 ToolCall 可能有 reasoning 回放问题；联调失败可降级 `deepseek-chat` |
| `switchProduct` | 瑞幸 MCP 已知偶发参数错误，需 Agent 重试或换规格 |
| 外送 | 不支持，仅到店自取（Skill 限制） |
| 生产就绪 | 无 DB 持久化、无鉴权限流，仅 Demo 用途 |
| 手工联调 | Task 8 Step 4 联调清单尚未全部勾选验证 |

---

## 10. 相关文档

- 设计规范：[specs/2026-07-04-lkcoffee-mcp-design.md](../specs/2026-07-04-lkcoffee-mcp-design.md)
- 实现计划：[plans/2026-07-04-lkcoffee-mcp.md](../plans/2026-07-04-lkcoffee-mcp.md)
- 官方 Skill：https://open.lkcoffee.com/skill
- 官方 MCP：https://open.lkcoffee.com/mcp

---

## 修订记录

| 日期 | 变更 |
|------|------|
| 2026-07-06 | 初版归档：汇总实现状态、与初稿差异、配置说明、已知限制 |
