# AgentScope Java 2.0 AG-UI 协议接入设计规范

**日期**: 2026-08-01  
**项目**: spring-ai-demo / demo2  
**状态**: 待实现  
**前置**: 现有 DevAgent SSE（`/agentscope/dev-agent/ask|confirm`）、HITL、Workspace Diff、DistributedStore/Sandbox 等已实现能力  
**参考文章**:
- [20. AgentScope Java 2.0 AG-UI 实战：把 Agent 事件接到前端页面](https://mp.weixin.qq.com/s?__biz=MzcwMjA0Njk3Nw==&mid=2247484497&idx=1&sn=4b696529c158a0d93fd641bf69a33312)

---

## 1. 背景与目标

### 1.1 需求

demo2 已通过自定义 `DevAgentEvent` 把 Agent 生命周期、工具、HITL、沙箱 diff 推到原生前端。AG-UI 是面向页面的外部协议：统一 `RunAgentInput` 与事件类型（`RUN_*` / `TEXT_*` / `TOOL_*`），由 AgentScope 的 AG-UI Spring Boot Starter 把内部 `AgentEvent` 转成前端可消费的 SSE。

本版目标：在**不破坏现有 DevAgent 协议**的前提下，接入 `/agui/run`，并在现有 AgentScope Tab 用协议开关演示文本流式与工具进度。

### 1.2 已确认决策

| 维度 | 选择 |
|------|------|
| 落地方式 | **双通道并存**（B）：保留 `/agentscope/dev-agent/*`，新增 Starter 提供的 `/agui/run` |
| 前端 | 现有 Tab 增加 **协议开关 DevAgent / AG-UI**（B2），复用气泡 + 工具条 |
| HITL / Diff | AG-UI 模式**明确降级**（H1）：提示切回 DevAgent，本轮不做确认桥接 |
| 会话身份 | `threadId = sessionId`；**切换协议时强制新开会话**（S3）；`userId` 可保留但不传给 AG-UI |
| Agent Bean | 保持 `agentscopeDevAgent`，`default-agent-id` 对齐该名（不改名为 `devAgent`） |
| 实现路径 | Starter 直挂 + Middleware `deferContextual` 修复 + 前端分流（方案 1） |
| `server-side-memory` | `false`（会话仍走现有 stateStore / DistributedStore） |
| 端口 | `8081` |

### 1.3 非目标（本版不做）

- AG-UI ↔ `REQUIRE_USER_CONFIRM` / `/confirm` / `WORKSPACE_DIFF` 桥接
- 多用户 `userId:threadId` 隔离与登录态
- 重命名 HarnessAgent Bean 为 `devAgent`
- 引入 AG-UI 官方前端 SDK / CopilotKit
- 修改 `DevAgentService` 主流程或现有 `DevAgentEvent` 类型集
- 收紧生产 CORS 白名单（演示可开 `cors-enabled`）

---

## 2. 架构

```
[AgentScope Tab]
  协议开关: DevAgent | AG-UI
        │
        ├─ DevAgent ──► POST /agentscope/dev-agent/ask|confirm|apply-diff
        │                 └─ DevAgentService ──► HarnessAgent.streamEvents
        │                      └─ DevAgentEvent SSE（含 HITL / WORKSPACE_DIFF）
        │
        └─ AG-UI ────► POST /agui/run
                          └─ agentscope-agui-spring-boot-starter
                               └─ 扫描 Bean agentscopeDevAgent
                                    └─ AG-UI 事件 SSE（RUN_* / TEXT_* / TOOL_*）
```

与 Spring AI、Embabel 模块继续并存、互不调用。Starter 不新建第二个 HarnessAgent；沿用 `AgentScopeConfig` 已装配的模型、工具、沙箱、Plan Mode、Memory、Compaction、DistributedStore。

### 2.1 组件边界

| 单元 | 职责 | 不做什么 |
|------|------|----------|
| `agentscope-agui-spring-boot-starter` | 注册 `/agui/run`（及可选 path routing），内部事件 → AG-UI | 不改 Harness 装配；不做 HITL |
| `AgentExecutionLoggingMiddleware` | AG-UI `stream()` 路径下 `context` 可能为 null 时，从 Reactor Context 取回同一份 `RuntimeContext` | 不改日志字段语义 |
| `DevAgentController` / `DevAgentService` | 保持不动，继续服务 DevAgent 协议 | 本轮不接 AG-UI |
| 前端协议开关 | 分流请求；切协议时新开会话；AG-UI 下不渲染 confirm/diff | 不做 HITL 桥接 |

---

## 3. 后端改动

### 3.1 Maven

在 `demo2/pom.xml` 增加（版本走现有 `${agentscope.version}` / BOM）：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-agui-spring-boot-starter</artifactId>
</dependency>
```

### 3.2 配置

写入现有 `application.properties`（风格与项目一致），等价于：

```properties
agentscope.agui.path-prefix=/agui
agentscope.agui.cors-enabled=true
agentscope.agui.default-agent-id=agentscopeDevAgent
agentscope.agui.agent-id-header=X-Agent-Id
agentscope.agui.enable-path-routing=true
agentscope.agui.emit-tool-call-args=true
agentscope.agui.enable-reasoning=false
agentscope.agui.server-side-memory=false
```

验收地址：`POST http://localhost:8081/agui/run`（亦可 `POST /agui/run/agentscopeDevAgent`）。

### 3.3 Middleware 空指针修复

AgentScope Java 2.0.0 在 AG-UI 走 `stream()` 兼容链路时，最外层 `onAgent` 的 `context` 参数可能为 null；`threadId` 已放入请求的 Reactor Context（`AgentBase.RUNTIME_CONTEXT_KEY`），且 `sessionId` 等于 AG-UI `threadId`。

`AgentExecutionLoggingMiddleware.onAgent` 改为 `Flux.deferContextual`：优先用方法参数 `context`，否则从 `ContextView` 取回；仍为空则抛 `IllegalStateException`，禁止 NPE。其余日志（requestId / userId / sessionId / 耗时等）语义不变。现有「参数 context 非 null」单测继续通过；新增「context=null + Reactor Context 有值」用例。

### 3.4 不改动的后端

- `AgentScopeConfig` / HarnessAgent 装配
- `DevAgentController`、`DevAgentService`、`DevAgentEvent*`
- Diff / apply-diff / confirm 流程

---

## 4. 前端改动

### 4.1 协议开关

在 AgentScope Tab 增加「协议：DevAgent | AG-UI」控件，默认 **DevAgent**。

切换时（S3）：

1. 若有 in-flight 请求：abort/忽略旧流
2. 清空消息区、复位 confirm / diff / in-flight 标志
3. 生成新 `sessionId`（`userId` 可保留）
4. 状态文案标明当前协议

### 4.2 AG-UI 请求体

每次发送：

```json
{
  "threadId": "<当前 sessionId>",
  "runId": "<新 UUID>",
  "messages": [
    { "id": "<新 UUID>", "role": "user", "content": "<输入文本>" }
  ]
}
```

请求：`POST /agui/run`，`Content-Type: application/json`，按 SSE 消费 `data:` 行 JSON。

### 4.3 事件映射

| AG-UI 事件 | 前端动作 |
|------------|----------|
| `RUN_STARTED` | 状态「运行中」；`beginAgentscopeAssistantTurn` |
| `TEXT_MESSAGE_START` | 按 `messageId` 准备拼接缓冲 |
| `TEXT_MESSAGE_CONTENT` | 追加到气泡（对齐现有 `MESSAGE`） |
| `TEXT_MESSAGE_END` | 结束该条文本 |
| `TOOL_CALL_START` | 工具条 upsert（running） |
| `TOOL_CALL_ARGS` | 可选参数摘要（配置开启时） |
| `TOOL_CALL_END` / `TOOL_CALL_RESULT` | 工具条更新完成/结果 |
| `RUN_FINISHED` | 状态「就绪」，解锁输入 |
| `RUN_ERROR`（若有） | 错误气泡 + 解锁 |

DevAgent 模式：现有 `handleAgentscopeSsePayload` 行为不变。

### 4.4 HITL / Diff 降级（H1）

AG-UI 模式下：

- 不调用 `/confirm`、`/apply-diff`
- 不渲染确认卡片 / workspace diff UI
- 写文件、Plan Mode、沙箱 diff 等示例按钮：禁用或点击后提示「当前为 AG-UI 演示模式，请切回 DevAgent 协议」

---

## 5. 错误处理

| 场景 | 行为 |
|------|------|
| Middleware `context == null` 且 Reactor Context 无值 | 抛明确异常，不 NPE |
| `/agui/run` HTTP/网络失败 | 错误气泡 + 解锁输入 |
| AG-UI `RUN_ERROR` 或流异常中断 | 同上 |
| 协议切换时仍有 in-flight | 先 abort/忽略，再清会话 |

---

## 6. 测试与验收

1. **单测**：`AgentExecutionLoggingMiddleware` — `context=null` + Reactor Context 持有 `RuntimeContext` 时执行成功且日志含 `sessionId`
2. **编译**：`mvn -f demo2/pom.xml -DskipTests compile` 在加入 starter 后通过
3. **curl**：`POST http://localhost:8081/agui/run` 可见 `RUN_STARTED` → `TEXT_MESSAGE_*` → `RUN_FINISHED`；读文件类问题可见 `TOOL_CALL_*`
4. **UI**：AG-UI 模式流式文字 + 工具条；切回 DevAgent 后 confirm/diff 仍可用；切换协议会新开会话
5. **文档**：`demo2/README.md` 补充 AG-UI 开关说明与 curl 示例（端口 8081、`agentscopeDevAgent`）

---

## 7. 文件地图（预期）

**Modify**

- `demo2/pom.xml` — 增加 agui starter
- `demo2/src/main/resources/application.properties` — `agentscope.agui.*`
- `demo2/src/main/java/.../middleware/AgentExecutionLoggingMiddleware.java` — `deferContextual`
- `demo2/src/test/java/.../middleware/AgentExecutionLoggingMiddlewareTest.java` — 空 context 用例
- `demo2/src/main/resources/static/js/tabs/agentscope.js` — 协议开关、AG-UI SSE、S3/H1
- `demo2/src/main/resources/static/index.html` — AgentScope Tab 协议开关控件
- `demo2/src/main/resources/static/css/tabs/agentscope.css` — 开关样式
- `demo2/README.md` — AG-UI 验收说明

**Create**

- 无必须新建的 Java 类（Starter 自注册端点）

---

## 8. 风险与后续

| 风险 | 缓解 |
|------|------|
| AG-UI 2.0.0 未转换 HITL | H1 明确降级；文档写清两套接口不可混用 confirm |
| Bean 名与文章示例不同 | 配置 `default-agent-id=agentscopeDevAgent` |
| 同 session 跨协议状态混乱 | S3 切换时强制新会话 |

**后续（非本版）**：自建 RequireUserConfirm → AG-UI 可展示确认 → ConfirmResult 恢复；以及登录态下的 `userId:threadId` 隔离。
