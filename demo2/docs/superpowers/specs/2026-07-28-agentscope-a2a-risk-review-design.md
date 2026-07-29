# AgentScope A2A 风险审查设计

## 目标

在现有 `demo2` Spring Boot 应用中，将文章中的风险审查 AgentScope A2A Client/Server 融合到同一个 Spring 容器。风险审查通过现有 `/agentscope/dev-agent/ask` 接口触发，保持 SSE 协议，不新增 HTTP 接口。

风险审查只处理用户手动输入的改动说明，与 Docker Sandbox、WorkspaceDiff、文件回写、Permission 和现有开发 Agent 执行流程解耦。

## 已确认的范围

- Client 和 Server 都位于 `demo2`，同一个 Spring 容器、同一个进程。
- 风险审查 Server 使用独立的 Agent，但复用现有 AgentScope DeepSeek 模型配置。
- Server 初始化并确认可用后，Client 才初始化。
- Client 通过 AgentCard 发现 Server，并通过 A2A `message/send` 调用。
- 现有 `/ask` 是唯一入口，`DevAgentRequest` 保持不变。
- 风险审查通过自然语言触发：主 Agent 根据用户意图调用 `risk_review` 工具。
- 风险审查请求不传递 Workspace、Sandbox 快照、Diff、文件内容或会话状态。
- 风险审查结果保持现有 `Flux<DevAgentEvent>` SSE 响应格式。
- 前端在现有 AgentScope Tab 增加风险审查自然语言示例。
- 不修改现有 `/confirm`、`/apply-diff` 语义。
- A2A Server/Client 不放入 `com.jason.demo.demo2.agentscope`，使用与其同级的 `com.jason.demo.demo2.agentscopea2a` 包。
- AgentScope A2A Server 使用 `/agentscope-a2a` 前缀；现有 Spring AI A2A 继续占用根路径。

## 架构

```text
浏览器
  -> POST /agentscope/dev-agent/ask
  -> DevAgentController
  -> DevAgentService
  -> HarnessAgent
       ├─ 普通自然语言 -> 现有工具与流程
       └─ 风险审查意图 -> risk_review 工具
                              -> RemoteRiskReviewService
                              -> AgentCard Resolver
                              -> A2A Client
                              -> RiskReviewAgent Server
                              -> RiskReviewResponse
```

风险审查 Server 的 Agent 使用空 Toolkit，只接收改动说明，不读取主应用项目。系统提示要求固定输出：

```text
## 结论
## 风险
## 建议
```

主 Agent 的 `risk_review` 工具是本地桥接工具。它只负责接收模型提取的改动说明并调用 `RemoteRiskReviewService`，不读取文件、不执行命令、不访问 Sandbox。该工具在 Sandbox 开关打开或关闭时都可注册，但不属于 Sandbox 工具集合。

推荐的包结构：

```text
com.jason.demo.demo2
├── agentscope
└── agentscopea2a
    ├── server
    │   └── Controller 前缀：/agentscope-a2a
    └── client
```

## 初始化生命周期

1. Spring 创建复用现有 DeepSeek 配置的风险审查模型。
2. 创建独立 `RiskReviewAgent`。
3. A2A Server 注册 AgentCard 和 JSON-RPC 接收端点，Controller 使用 `/agentscope-a2a` 前缀。
4. Server 就绪组件确认 AgentCard 可访问，并发布就绪状态。
5. A2A Client 通过 `SmartLifecycle` 或就绪事件启动，在 Server 就绪后从 `http://localhost:${server.port}/agentscope-a2a` 读取 AgentCard 并创建远程 Agent 客户端。
6. 注册 `risk_review` 本地桥接工具，并允许主 Agent 调用。
7. 请求到达时，如果 Client 尚未就绪，`risk_review` 工具返回可识别错误，由主 Agent 通过既有 SSE 输出；不影响主开发 Agent。

Client 初始化不得在 Bean 构造阶段发起网络调用，以避免 Server 尚未启动、循环依赖和应用启动顺序问题。

## 请求与事件

请求示例：

```json
{
  "userId": "risk-user-001",
  "sessionId": "risk-session-001",
  "message": "请审查 RetryPolicy.delayMillis 的这次修改……"
}
```

`message` 必填并设置最大长度。`userId` 与 `sessionId` 仅用于请求上下文、日志和链路追踪，不写入远端业务审查材料，除非后续明确扩展协议。

风险审查仍沿用主 Agent 的现有 SSE 事件流。典型顺序为：

```text
SESSION
REQUEST_CONTEXT
TOOL_CALL_START（risk_review）
TOOL_RESULT_END（risk_review）
MESSAGE（主 Agent 整理后的风险审查内容）
DONE
```

远端结果先作为 `risk_review` 工具结果返回给主 Agent，再由主 Agent 生成最终回复；不新增前端事件类型。远端 Server 产生的结果仍要求包含“结论、风险、建议”三个标题。

## 错误处理

- 输入校验失败：HTTP 400 或现有错误事件。
- Server/Client 未就绪：`risk_review` 工具返回错误，主 Agent 通过现有 SSE 输出“风险审查服务初始化中”。
- AgentCard 获取失败：发送 `ERROR`，记录内部原因，不向用户暴露内部地址或密钥。
- A2A 请求超时：发送 `ERROR`，记录超时原因。
- 远端 Agent 执行失败：发送 `ERROR`，保留可读的失败摘要。
- 风险审查工具失败不得伪装成审查结论；主 Agent 应明确说明审查未完成。
- 普通自然语言请求的错误和既有行为保持不变。

## 前端

在现有 AgentScope Tab 中：

- 继续调用 `/agentscope/dev-agent/ask`。
- 复用现有 SSE 消费器和消息气泡。
- 风险审查结果以一条完整消息展示。
- 增加一个 RetryPolicy 风险审查示例。
- 不显示或操作 Sandbox Diff、回写按钮；风险审查仅作为对话中的工具调用。

## 测试策略

### Server 与 Client

- 风险审查 Agent 使用独立名称、系统提示和空 Toolkit。
- `risk_review` 工具注册在 `agentscopea2a.client`，并在 Sandbox 开关两种状态下都可用。
- AgentCard 包含名称、描述、版本、输入输出模式和调用地址。
- Client 只有在 Server 就绪后初始化。
- Client 未就绪时不发起调用，并返回可识别错误。

### Service

- 普通自然语言请求继续调用现有 HarnessAgent 流程。
- 明确的风险审查自然语言请求触发 `risk_review` 工具。
- 请求中的用户改动说明被传递给 A2A Client。
- userId、sessionId 不被拼接到远端业务任务。
- SSE 中能观察到 `risk_review` 工具开始、结果和最终消息。
- AgentCard、超时和远端异常均转换为 `ERROR`。

### Controller 与兼容性

- `/ask` 仍返回 `text/event-stream`。
- 旧请求格式保持不变，普通自然语言行为不变。
- `/confirm` 与 `/apply-diff` 继续工作。

### 前端

- 风险审查自然语言示例能触发 `risk_review` 工具。
- 风险审查结果正常显示。
- SSE 错误正常显示。
- 风险审查不出现 Sandbox Diff 回写操作。

## 非目标

- 不新增 `/risk-review` 接口。
- 不新增独立 Maven 子模块或独立进程。
- 不把风险审查绑定到 Sandbox 或 WorkspaceDiff。
- 不让风险审查 Agent 或 `risk_review` 工具读取主应用文件。
- 不把当前 Spring AI 天气 A2A 示例替换为本功能。
- 不实现认证、TLS、跨主机部署和生产级服务发现；这些属于后续生产化工作。

## 依赖验证

实现计划阶段必须先验证 AgentScope 2.0.0 实际发布的 A2A Server Starter、A2A Client 和 AgentCard API 名称。若文章中的依赖名与当前 BOM 不一致，应优先采用同版本可用 API；不得静默改用 Spring AI A2A 来代替 AgentScope A2A。
