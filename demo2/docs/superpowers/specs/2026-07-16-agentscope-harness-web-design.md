# AgentScope Java 2.0 HarnessAgent Web 上手设计规范

**日期**: 2026-07-16  
**项目**: spring-ai-demo / demo2  
**状态**: 待实现  
**参考文章**: https://mp.weixin.qq.com/s/G8Lh_P-iFpvb13mzfdMpeg  
（《2. AgentScope Java 2.0 上手：用 HarnessAgent 跑通第一个 Web 接口》）

---

## 1. 背景与目标

### 1.1 需求

在现有 `demo2`（Spring Boot 4.1、端口 8081）中接入 **AgentScope Java 2.0**，用克制的 `HarnessAgent` 跑通第一个 Web SSE 接口：接收研发任务描述，流式返回可执行检查清单。不向模型暴露工具；状态先放在进程内；先验证 Web 入口、流式输出、会话边界，再考虑工具与持久化。

### 1.2 已确认决策

| 维度 | 选择 |
|------|------|
| 集成位置 | 并入 `demo2`（方案 B 落点） |
| 落地风格 | 文章能力为准 + `demo2` 包/配置/Tab 习惯（方案 A） |
| AgentScope 版本 | **2.0.0** |
| 模型 | **deepseek-v4-pro**（文章为 deepseek-v4-flash；独立 AgentScope 配置，复用 `DEEPSEEK_API_KEY`） |
| API 路径 | **`POST /agentscope/dev-agent/ask`**（SSE） |
| 端口 | **8081**（demo2 现有） |
| SSE 事件 | 文章协议：`SESSION` / `MESSAGE` / `DONE`，另加流式失败用的 `ERROR` |
| 状态存储 | `InMemoryAgentStateStore`（进程内，重启丢失） |
| 前端 | 新增「AgentScope」Tab |
| 工具能力 | 全部关闭；并移除 `wait_async_results` |

### 1.3 非目标（本版不做）

- 文件系统 / Shell / 记忆工具 / 子 Agent / Workspace / 动态 Skill
- 持久化 `AgentStateStore`（JsonFile / Redis）
- 权限确认事件透传
- 切全站 WebFlux
- 复用现有 `AgentSseEvent` / Session 记忆基础设施
- CI 中打真实 DeepSeek 集成测试

---

## 2. 架构

```
HTTP POST /agentscope/dev-agent/ask
  → DevAgentController（@Valid + text/event-stream）
  → DevAgentService（RuntimeContext + 事件过滤）
  → HarnessAgent Bean（工具全关 + InMemoryAgentStateStore）
  → OpenAIChatModel + DeepSeekFormatter（deepseek-v4-pro, stream=true）
  → Flux：SESSION → MESSAGE* → DONE（异常时追加 ERROR）
```

与 Spring AI、Embabel 模块**并存、互不调用**。同一 `HarnessAgent` Bean 通过 `RuntimeContext(userId, sessionId)` 区分多用户多会话，无需每用户 new 一个 Agent。

包根：`com.jason.demo.demo2.agentscope`

```
agentscope/
  config/     DevAgentProperties, AgentScopeConfig
  controller/ DevAgentController
  service/    DevAgentService
  model/      DevAgentRequest, DevAgentEvent
```

---

## 3. 依赖与配置

### 3.1 Maven（`demo2/pom.xml`）

```xml
<properties>
    <agentscope.version>2.0.0</agentscope.version>
</properties>

<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-harness</artifactId>
    <version>${agentscope.version}</version>
</dependency>
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-openai</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

不改动现有 Spring AI / Embabel 依赖。AgentScope 官方 BOM 管理的 Boot 基线为 4.0.4；本仓库已用 Boot 4.1.0，以编译与运行验证为准。

### 3.2 应用配置

前缀：`app.agentscope.dev-agent`（写入 `application.properties` 或等价 yml）。

| 键 | 说明 | 默认/示例 |
|----|------|-----------|
| `name` | Agent 名 | `dev-task-agent` |
| `system-prompt` | 边界清晰的系统提示 | 文章「研发任务整理助手」文案（中文清单 ≤6 条；不声称已查日志/改文件） |
| `model.api-key` | API Key | `${DEEPSEEK_API_KEY:}` |
| `model.base-url` | 兼容 OpenAI 的 DeepSeek 地址 | `https://api.deepseek.com` |
| `model.name` | 模型名 | `deepseek-v4-pro` |

`DevAgentProperties` 使用 `@ConfigurationProperties` + `@Validated`；启动类或配置类 `@EnableConfigurationProperties(DevAgentProperties.class)`。

---

## 4. 组件设计

### 4.1 Model Bean

`OpenAIChatModel.builder()`：`apiKey` / `baseUrl` / `modelName` 来自配置；`formatter(new DeepSeekFormatter())`；`stream(true)`。

### 4.2 HarnessAgent Bean

与文章一致地关闭能力：

- `stateStore(new InMemoryAgentStateStore())`
- `enableAgentTracingLog(false)`
- `disableFilesystemTools()` / `disableShellTool()` / `disableMemoryTools()` / `disableMemoryHooks()`
- `disableCompaction()` / `disableSubagents()` / `disableWorkspaceContext()` / `disableAtPathExpansion()`
- `disableDynamicSkills()` / `disableDefaultWorkspaceSkills()` / `disableToolsConfig()`
- 构建后：`agent.getToolkit().removeTool("wait_async_results")`

目标：Toolkit 中无可供模型调用的工具。

### 4.3 API 模型

```java
public record DevAgentRequest(
        String userId,              // 可选；本地可空
        @NotBlank String sessionId,
        @NotBlank String message) {}

public record DevAgentEvent(String type, String sessionId, String content) {
    // session / message / done / error 工厂方法
}
```

`userId` 不加 `@NotBlank`（便于本地测试）。真实系统应从登录态取 userId，本版不实现鉴权。

### 4.4 Service

1. 用 `sessionId` 与可选 `userId` 构建 `RuntimeContext`
2. `devAgent.streamEvents(message, context).ofType(TextBlockDeltaEvent.class)` → `MESSAGE`
3. `Flux.concat(SESSION, messages, DONE)`
4. `onErrorResume`：发射 `ERROR` 后结束流

仍使用 Spring MVC；`Flux` 作为 SSE 返回值，不改为 WebFlux 应用。

### 4.5 Controller

```text
POST /agentscope/dev-agent/ask
Content-Type: application/json
Accept / produces: text/event-stream
```

Controller 只做校验委托，保持薄。

---

## 5. 前端 Tab

对齐现有 Tab 模式（参考 Embabel / 聊天）：

| 文件 | 作用 |
|------|------|
| `static/index.html` | 导航按钮 + `tab-agentscope` 面板 |
| `static/js/tabs/agentscope.js` | SSE 消费、气泡追加、换会话 |
| `static/css/tabs/agentscope.css` | 少量样式（可复用通用 message 样式） |

交互：

- 输入：`userId`（可空）、`sessionId`（可一键生成新 UUID）、`message`、示例文案按钮
- 输出：助手气泡流式追加 `MESSAGE.content`；状态显示 SESSION / 流式中 / DONE / ERROR
- 「换会话」：新 `sessionId` + 清空对话区，便于验证会话隔离

---

## 6. 错误处理

| 场景 | 行为 |
|------|------|
| `sessionId` / `message` 为空 | Bean Validation → HTTP 400，不进入 Agent |
| `DEEPSEEK_API_KEY` 未配置 | 不因缺 Key 阻止整个 `demo2` 启动；首次进入 `ask` 时返回明确失败（HTTP 或 SSE `ERROR`），不 silent 空转 |
| 流式中模型/网络异常 | SSE 追加 `type=ERROR`，前端展示错误态 |
| 模型越权声称 | 靠 system prompt 约束（本版无工具可拦） |

本版不新增专属全局 `@ControllerAdvice`。

---

## 7. 测试与验收

### 7.1 自动化

- 单元测试：`DevAgentService` 对 mock 事件流的映射（SESSION → MESSAGE → DONE；异常 → ERROR）
- 不强制真实 DeepSeek 集成测试（避免 CI 依赖外网 Key）

### 7.2 手工

```bash
# 1) 首轮清单
curl -N -X POST "http://localhost:8081/agentscope/dev-agent/ask" \
  -H "Content-Type: application/json" \
  -d '{"userId":"dev-user-001","sessionId":"dev-session-001","message":"帮我整理一份今天排查订单接口超时的执行清单"}'

# 2) 同 session 追问（应记得上一轮）
# 3) 换 sessionId（不应串话）
```

另：Web Tab 对照同一路径。

### 7.3 验收清单

1. `demo2` 编译通过，依赖无冲突到无法启动
2. Tab / curl 能收到流式清单，且不声称已查日志/执行命令
3. 同 `userId`+`sessionId` 有会话记忆；换 `sessionId` 无串话
4. 启动后 Toolkit 无可用工具（或测试断言工具列表为空）
5. README（根目录与/或 `demo2`）补充模块一行说明与 curl 示例

---

## 8. 文档与后续扩展点

实现完成后更新 README 功能表（路径前缀 `/agentscope/dev-agent/*`）。

刻意留空、便于后续单独加的扩展：

- 日志查询等自定义工具 → 只看工具调用/结果事件
- 持久化 → 替换 `AgentStateStore`
- 权限确认 → 透传确认类 `AgentEvent` 到前端

---

## 9. 参考

- 微信文章：https://mp.weixin.qq.com/s/G8Lh_P-iFpvb13mzfdMpeg
- AgentScope Java 2.0 / HarnessAgent / RuntimeContext / TextBlockDeltaEvent
- 仓库内对照模块：`demo2` Embabel 包隔离与 Tab 模式
