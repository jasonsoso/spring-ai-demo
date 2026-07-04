# 瑞幸 MCP 点单 · SSE 对话 Demo 设计规范

**日期**: 2026-07-04  
**项目**: spring-ai-demo / demo2  
**状态**: 待实现

---

## 1. 背景与目标

### 1.1 需求

在 `demo2` 中新增 **「☕ 瑞幸 MCP 点单」** Tab，通过 Spring AI MCP Client 对接 [瑞幸咖啡 AI 开放平台](https://open.lkcoffee.com/mcp) 远程 MCP Server，结合 [高德地图 MCP Server](https://lbs.amap.com/api/mcp-server) 完成地址地理编码，以 **SSE 对话式** UI 演示完整点单链路：查门店 → 选品 → 预览 → 用户确认 → 下单/支付二维码。

### 1.2 已确认决策

| 维度 | 选择 |
|------|------|
| 演示范围 | **完整点单链路**（含 `createOrder` 与支付二维码） |
| 交互方式 | **SSE 对话式**（聊天气泡 + 底部输入栏，对齐 `tool-reasoning`） |
| 多轮对话 | **支持**（`sessionId` + 独立内存 `ChatMemory`，窗口 20 条） |
| Token | **环境变量默认 + Tab 可覆盖**（`LKCOFFEE_TOKEN` / 请求体 `token` / `sessionStorage`） |
| 定位 | 浏览器定位优先 → 手动经纬度 → 地址解析 |
| 地理编码 | **高德官方 MCP**（非自写 REST Tool）；`McpToolFilter` 仅放行 geocode / reverse_geocode |
| 下单确认 | **`previewOrder` 后必须等用户明确确认**才允许 `createOrder` |
| 瑞幸 MCP 方案 | Spring AI 声明式 Streamable HTTP + `LkCoffeeTokenContext`（ThreadLocal Bearer） |
| 模型 | `agent.lkcoffee.chat.model=deepseek-v4-pro` |

### 1.3 外部服务

| 服务 | 地址 | 认证 |
|------|------|------|
| 瑞幸 MCP | `https://gwmcp.lkcoffee.com/order/user/mcp` | `Authorization: Bearer <Token>` |
| 高德 MCP | `https://mcp.amap.com/mcp?key=<AMAP_API_KEY>` | Key 拼在 URL 查询参数 |
| LLM | DeepSeek API（已有） | `DEEPSEEK_API_KEY` |

Token 获取：[open.lkcoffee.com/mcp](https://open.lkcoffee.com/mcp) 登录后复制，有效期约 30 天。

### 1.4 依赖

- Spring AI 2.0.0（已有 `spring-ai-starter-mcp-client`）
- 现有本地 MCP Server + Client（**不修改**，与新 Tab 隔离）
- 无需新增 Maven 依赖

### 1.5 成功标准

1. 用户在「☕ 瑞幸 MCP 点单」Tab 通过自然语言完成：查附近门店 → 搜商品 → 预览订单 → 确认 → 生成支付二维码
2. 多轮对话可修改规格（如「改成少冰」）或切换地址后重新查店
3. 浏览器定位失败时，可手动输入经纬度或通过地址解析获取坐标
4. SSE 实时展示工具调用进度与流式回复；`previewOrder` / `createOrder` 结果有结构化 UI（价格卡片、支付二维码）
5. Swagger / Scalar 可查看 REST 端点

### 1.6 不在范围

- 替换或合并现有「🔌 MCP Client 聊天」Tab（本地天气/景点）
- Tool Argument Augmentation / 推理捕获
- Token 持久化到 DB、生产级鉴权与限流
- 高德 MCP 全量 12 工具（仅地理编码 2 个）

### 1.7 已知风险

`deepseek-v4-pro` 为 thinking 模型，多轮 ToolCall 存在 `reasoning_content` 回放兼容问题（项目内 `session-memory` / `auto-memory` 已改用 `deepseek-chat`）。本 Tab 使用内存 `ChatMemory` + 多轮 MCP 工具链，**联调中若出现工具链中断，降级方案为改回 `deepseek-chat`**。

---

## 2. 架构设计

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│  前端 Tab「☕ 瑞幸 MCP 点单」                                  │
│  ┌──────────────┐  ┌─────────────────────────────────────┐  │
│  │ 设置区        │  │ SSE 聊天气泡                          │  │
│  │ Token/定位/地址│  │ RUNNING → TOOL_CALL → TOKEN → DONE  │  │
│  └──────────────┘  └─────────────────────────────────────┘  │
└───────────────────────────┬─────────────────────────────────┘
                            │ POST /agent/lkcoffee/chat/stream
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  LkCoffeeAgentController + LkCoffeeAgentService             │
│  ChatClient + ChatMemory + ToolCallingAdvisor               │
│  Tools: 瑞幸 MCP (8) + 高德 MCP (geocode × 2，经 ToolFilter) │
└───────────────┬─────────────────────────┬───────────────────┘
                │ Streamable HTTP          │ Streamable HTTP
                ▼                          ▼
   gwmcp.lkcoffee.com/order/user/mcp    mcp.amap.com/mcp
   (Bearer Token)                       (?key=AMAP_API_KEY)
```

### 2.2 与现有 Tab 的关系

| 维度 | 现有「MCP Client 聊天」 | 新增「瑞幸 MCP 点单」 |
|------|-------------------------|----------------------|
| MCP 目标 | 本地 MCP Server（天气/景点） | 瑞幸 + 高德远程 MCP |
| 协议 | 同步 `GET /mcp/client/chat` | `POST /agent/lkcoffee/chat/stream` SSE |
| UI | 表单 + 单次结果 | 聊天气泡 + 设置区 |
| 多轮 | 无 | `sessionId` + ChatMemory |

两者 **完全独立**，互不影响。

### 2.3 点单数据流

```
用户输入「帮我来一杯冰美式」
    │
    ├─ 前端附带：sessionId, message, token?, longitude?, latitude?, address?
    │
    ▼
LkCoffeeAgentService.streamChat()
    ├─ 解析 Token（请求体 token > 环境变量 LKCOFFEE_TOKEN）
    ├─ LkCoffeeTokenContext.set(token)  // ThreadLocal，finally 清除
    ├─ 解析位置（请求体经纬度；无则依赖 Agent 调高德 geocode）
    │
    ▼
典型工具调用链
    1. [可选] 高德 geocode(address)           → 经纬度
    2. queryShopList(longitude, latitude)     → deptId
    3. searchProductForMcp(deptId, query)     → 商品
    4. queryProductDetailInfo / switchProduct → 规格（switchProduct 已知偶发参数错误）
    5. previewOrder(deptId, productList)      → 价格/优惠 → SSE ORDER_PREVIEW
    │
    ├─ Agent 询问用户确认（System Prompt 硬性规则）
    │
    ▼ 用户「确认下单」
    6. createOrder(...)                       → 支付二维码 → SSE PAYMENT_QR
    7. queryOrderDetailInfo / cancelOrder     → 按需
```

### 2.4 核心组件

| 组件 | 包/路径 | 职责 |
|------|---------|------|
| `LkCoffeeAgentConfig` | `config` | 独立 ChatMemory、MessageChatMemoryAdvisor、模型配置 |
| `LkCoffeeMcpConfig` | `mcp/client/config` | Token Customizer、McpToolFilter、McpClientCustomizer（禁用 sampling） |
| `LkCoffeeTokenContext` | `mcp/client` | ThreadLocal 传递 Bearer Token |
| `LkCoffeeAgentService` | `service` | SSE 流式对话、System Prompt、结构化事件解析 |
| `LkCoffeeAgentController` | `controller` | SSE 端点、geocode 薄封装、clear/tools |
| `LkCoffeeChatRequest` | `model` | 请求 DTO |
| `LkCoffeeSseEvent` | `model` | SSE 事件 DTO |
| 前端 Tab | `static` | `lkcoffee.js` / `lkcoffee.css` |

---

## 3. MCP 配置详细设计

### 3.1 application.properties

```properties
# 瑞幸 MCP（Streamable HTTP）
spring.ai.mcp.client.streamable-http.connections.lkcoffee.url=https://gwmcp.lkcoffee.com/order/user
spring.ai.mcp.client.streamable-http.connections.lkcoffee.endpoint=/mcp
lkcoffee.token=${LKCOFFEE_TOKEN:}

# 高德 MCP（Streamable HTTP，Key 拼 URL）
spring.ai.mcp.client.streamable-http.connections.amap.url=https://mcp.amap.com/mcp?key=${AMAP_API_KEY:}

# Agent 模型
agent.lkcoffee.chat.model=deepseek-v4-pro
```

现有 `spring.ai.mcp.client.sse.connections.local-server` **保留**，供本地 MCP Tab 使用。

### 3.2 瑞幸 Bearer Token（ThreadLocal）

```java
// 请求进入 streamChat 时
String effective = StringUtils.hasText(request.getToken()) ? request.getToken() : configuredToken;
if (!StringUtils.hasText(effective)) { /* FAILED: Token 缺失 */ }
LkCoffeeTokenContext.set(effective);
try {
    // ChatClient 流式调用
} finally {
    LkCoffeeTokenContext.clear();
}

// McpSyncHttpClientRequestCustomizer（仅 connectionName == "lkcoffee"）
request.setHeader("Authorization", "Bearer " + LkCoffeeTokenContext.get());
```

### 3.3 McpToolFilter 白名单

| connection | 允许的工具 |
|------------|-----------|
| `lkcoffee` | `queryShopList`, `searchProductForMcp`, `queryProductDetailInfo`, `switchProduct`, `previewOrder`, `createOrder`, `queryOrderDetailInfo`, `cancelOrder` |
| `amap` | 地理编码、逆地理编码（以 MCP `listTools` 返回的实际 tool name 为准，实现时 log 确认） |
| `local-server` 及其他 | **排除**（本 Agent 不可见） |

`LkCoffeeAgentService` 的 `ChatClient` 仅挂载经 Filter 后的 `SyncMcpToolCallbackProvider`，不混入 `WeatherTool` 等本地 Bean。

### 3.4 MCP 初始化与兼容性

- 复用现有 `McpClientInitializer`（`ApplicationReadyEvent` Order(1)）
- 瑞幸 / 高德连接初始化失败 **不阻塞** 应用启动；Tab 显示警告，`GET /agent/lkcoffee/tools` 返回空或部分列表
- `McpClientCustomizer` 对瑞幸连接 **不注册 sampling handler**（规避瑞幸 Java SDK 与部分客户端的 sampling 握手兼容问题，参考社区反馈）

---

## 4. 后端详细设计

### 4.1 System Prompt 核心规则

1. 你是瑞幸咖啡点单助手，仅通过 MCP 工具获取门店、商品、价格信息，禁止编造
2. 标准流程：定位/查店 → 搜品 → 确认规格 → `previewOrder` → **等用户明确确认** → `createOrder`
3. **禁止**在用户未明确肯定（如「确认下单」「好的」「就这个」）前调用 `createOrder`
4. `previewOrder` 后清晰展示：门店、商品、规格、原价、优惠、实付，并询问是否下单
5. 用户给出地址时，调用高德地理编码工具获取经纬度，再 `queryShopList`
6. 改规格优先 `switchProduct`；失败时引导用户重新描述或换商品
7. 优惠券：从 `previewOrder` 返回的 `couponCodeList` 传给 `createOrder`
8. 每次请求若前端已提供经纬度，优先使用，避免重复 geocode

### 4.2 ChatClient 构建

```java
chatClientBuilder.clone()
    .defaultSystem(SYSTEM_PROMPT)
    .defaultOptions(DeepSeekChatOptions.builder().model(lkCoffeeChatModel))
    .defaultTools(filteredMcpToolCallbackProvider)  // 瑞幸 + 高德 geocode
    .defaultAdvisors(
        lkCoffeeMessageChatMemoryAdvisor,
        ToolCallingAdvisor.builder().build())
    .build();
```

独立 Bean：`lkCoffeeChatMemory`（`MessageWindowChatMemory`，maxMessages=20）、`lkCoffeeMessageChatMemoryAdvisor`。

### 4.3 SSE 事件（`LkCoffeeSseEvent`）

| type | 字段 | 说明 |
|------|------|------|
| `RUNNING` | — | 本轮开始 |
| `TOOL_CALL` | `toolName`, `callIndex` | 工具调用进度提示 |
| `TOKEN` | `content` | 流式文本 |
| `ORDER_PREVIEW` | `payload` (JSON) | `previewOrder` 结构化结果 |
| `PAYMENT_QR` | `qrUrl` | `createOrder` 支付二维码 URL |
| `COMPLETED` | — | 本轮结束 |
| `FAILED` | `error` | 错误信息 |

`ORDER_PREVIEW` / `PAYMENT_QR`：Service 在检测到对应 MCP 工具返回后，除 TOKEN 流式文本外额外推送结构化事件（实现时可从 tool result 解析或让 Agent 总结后由 hook 推送）。

### 4.4 Controller 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/agent/lkcoffee/chat/stream` | Body: `LkCoffeeChatRequest`，`produces: text/event-stream` |
| `DELETE` | `/agent/lkcoffee/clear?sessionId=` | 清除 ChatMemory |
| `GET` | `/agent/lkcoffee/tools` | 当前可用 MCP 工具列表（健康检查） |
| `GET` | `/agent/lkcoffee/geocode?address=&city=` | 前端设置区地址解析（内部调高德 MCP geocode） |

**`LkCoffeeChatRequest`**

```json
{
  "sessionId": "uuid",
  "message": "帮我来一杯冰美式",
  "token": "可选",
  "longitude": 118.089,
  "latitude": 24.479,
  "address": "可选"
}
```

### 4.5 `streamChat` 流程

1. 校验 `sessionId`（`^[a-zA-Z0-9_-]+$`）、`message` 非空
2. 解析并设置 `LkCoffeeTokenContext`
3. 可选：将 `longitude`/`latitude` 写入 user message 前缀或 `toolContext` 供 Agent 引用
4. `SseEmitter` 推送 `RUNNING`
5. `chatClient.prompt().user(message).advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId)).stream().content()`
6. 工具调用时推送 `TOOL_CALL`（可通过 `ToolCallingAdvisor` 回调或 wrapper 实现）
7. TOKEN chunk → SSE；识别 preview/create 结果 → `ORDER_PREVIEW` / `PAYMENT_QR`
8. 完成 → `COMPLETED`；异常 → `FAILED`
9. `finally` → `LkCoffeeTokenContext.clear()`

SSE 超时：5 分钟（与 `tool-reasoning` 一致）。

### 4.6 错误处理

| 场景 | 行为 |
|------|------|
| Token 缺失/无效 | `FAILED` + 提示前往 open.lkcoffee.com/mcp |
| 瑞幸 MCP 未就绪 | Tab 警告；chat 返回友好错误 |
| 高德 Key 未配置 | geocode 端点 503；Agent 侧 geocode 工具不可用 |
| 定位/地址均缺失且用户未说明 | Agent 引导用户提供位置或地址 |
| `createOrder` 失败 | Agent 说明原因，不自动重试下单 |
| sessionId 非法 | HTTP 400 |

---

## 5. 前端详细设计

### 5.1 Tab 入口

- Tab ID: `lkcoffee`
- 按钮: `☕ 瑞幸 MCP 点单`
- 位置: 建议紧跟「MCP Client 聊天」之后

### 5.2 布局

```
┌─ Header + 双 MCP 架构说明 ──────────────────────┐
│  用户 → DeepSeek Agent → 瑞幸 MCP / 高德 MCP      │
├─ 设置区（可折叠）────────────────────────────────┤
│  Token [________] [保存]  （sessionStorage）     │
│  定位：✅ 118.09, 24.48  [重新定位]              │
│  手动：经度 [___] 纬度 [___]                     │
│  地址：[___________] [解析经纬度]                │
│  sessionId: ...  [清除会话]                      │
├─ 聊天气泡区 + 快捷示例按钮 ──────────────────────┤
│  「冰美式」「查附近门店」「查看最近订单」          │
├─ 输入栏 + 发送 ─────────────────────────────────┤
└──────────────────────────────────────────────────┘
```

### 5.3 前端逻辑（`lkcoffee.js`）

- **Token**：`sessionStorage.lkcoffee_token`；发送时带入 `token` 字段；无则依赖后端 `LKCOFFEE_TOKEN`
- **定位**：`navigator.geolocation.getCurrentPosition`；失败显示手动/地址输入
- **地址解析**：`GET /agent/lkcoffee/geocode?address=...` → 填入经纬度字段
- **SSE**：`fetch` + `ReadableStream` 解析 `data:`（对齐 `tool-reasoning.js`）
- **渲染**：
  - `TOOL_CALL` → 气泡内标签「🔧 queryShopList…」
  - `ORDER_PREVIEW` → 价格卡片（门店、商品、实付）
  - `PAYMENT_QR` → `<img src="{qrUrl}">` 或链接
  - `TOKEN` → 流式追加 `.response-text`

### 5.4 样式

- 新建 `css/tabs/lkcoffee.css`
- 主色点缀瑞幸蓝 `#0022AB`；聊天气泡复用 `components.css` 现有 message 类

### 5.5 快捷示例

| 按钮 | 填入内容 |
|------|----------|
| 冰美式 | 帮我来一杯冰美式 |
| 查附近门店 | 找一下附近的瑞幸门店 |
| 最近订单 | 帮我看一下最近的订单 |

---

## 6. 文件清单

| 操作 | 路径 |
|------|------|
| 新增 | `config/LkCoffeeAgentConfig.java` |
| 新增 | `mcp/client/config/LkCoffeeMcpConfig.java` |
| 新增 | `mcp/client/LkCoffeeTokenContext.java` |
| 新增 | `controller/LkCoffeeAgentController.java` |
| 新增 | `service/LkCoffeeAgentService.java` |
| 新增 | `model/LkCoffeeChatRequest.java` |
| 新增 | `model/LkCoffeeSseEvent.java` |
| 新增 | `static/js/tabs/lkcoffee.js` |
| 新增 | `static/css/tabs/lkcoffee.css` |
| 修改 | `static/index.html`（Tab + script/css） |
| 修改 | `application.properties`（MCP 连接 + 模型 + Key） |
| 修改 | `src/test/resources/application-test.properties`（测试跳过远程 MCP 或 mock） |
| 不修改 | 现有 `McpChatController`、本地 MCP Server、`WeatherTool` 等 |

---

## 7. 测试计划

| # | 场景 | 预期 |
|---|------|------|
| 1 | Token 缺失 | `FAILED` 或 HTTP 400，提示配置 Token |
| 2 | 浏览器定位 + 查店 | `queryShopList` 被调用，返回门店列表 |
| 3 | 地址解析 | geocode 端点返回经纬度；对话中 Agent 可调高德 MCP |
| 4 | 搜品 + 预览 | `ORDER_PREVIEW` SSE 或文本含价格信息 |
| 5 | 未确认不下单 | 仅 preview，不调用 `createOrder` |
| 6 | 确认后下单 | `PAYMENT_QR` 展示二维码 |
| 7 | 多轮改规格 | ChatMemory 保留上下文 |
| 8 | 清除会话 | `DELETE /clear` 后历史不影响 |
| 9 | 编译 | `mvn -pl demo2 -DskipTests compile` 通过 |

手工联调需有效 `LKCOFFEE_TOKEN` 与 `AMAP_API_KEY`。

---

## 8. 修订记录

| 日期 | 变更 |
|------|------|
| 2026-07-04 | 初稿：瑞幸 MCP + 高德 MCP 双远程连接；SSE 对话；完整点单；Token/定位/确认策略 |
| 2026-07-04 | 地理编码由自写 GeocodingTool 改为高德官方 MCP + ToolFilter |
| 2026-07-04 | 模型定为 `deepseek-v4-pro`，补充 reasoning_content 风险说明 |
