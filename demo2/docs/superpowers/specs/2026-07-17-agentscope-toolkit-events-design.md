# AgentScope Java 2.0 Toolkit + AgentEvent 赋能设计规范

**日期**: 2026-07-17  
**项目**: spring-ai-demo / demo2  
**状态**: 待实现  
**前置**: [2026-07-16-agentscope-harness-web-design.md](./2026-07-16-agentscope-harness-web-design.md)（已实现）  
**参考文章**:
- https://mp.weixin.qq.com/s/WVPA6hYDQE5ePggwD6Am2w（《4. Toolkit 实战：让 Agent 先查 pom.xml 再回答》）
- https://mp.weixin.qq.com/s/jM-kl4a5VLSJUV2hfZJ1pA（《5. AgentEvent 实战：让前端看见 Agent 调工具的过程》）

---

## 1. 背景与目标

### 1.1 需求

在已有 AgentScope `HarnessAgent` Web SSE（`SESSION` / `MESSAGE` / `DONE` / `ERROR`、工具全关）基础上，一次落地两篇赋能能力：

1. **Toolkit**：三个只读项目工具，让 Agent 先查真实项目再回答  
2. **AgentEvent**：把工具调用过程透传给前端，而不只是流式文字

### 1.2 已确认决策

| 维度 | 选择 |
|------|------|
| 落地方式 | **原位增强**现有 `agentscope` 包与 `/agentscope/dev-agent/ask`（方案 1） |
| 范围 | Toolkit + AgentEvent + 前端轻量工具条一次做完 |
| `project-root` | **可配置**，默认 `.`（从 `demo2` 目录启动时即为模块根） |
| Agent 职责 | **双职责**：清单整理 + 项目问答（有工具时先调用） |
| 前端 | **轻量工具条**（工具名 + 状态），文字仍流式追加 |
| 权限 | `PermissionMode.EXPLORE` |
| Tool Group | 本版不做（三工具始终可见） |
| API 路径 / 端口 | 不变：`POST /agentscope/dev-agent/ask`，8081 |

### 1.3 非目标（本版不做）

- 写文件 / Shell / 日志 / 数据库工具
- Tool Group 动态激活
- 持久化 `AgentStateStore`
- 权限确认弹窗 / 确认类事件透传
- 独立调试事件日志面板（原始全量 SSE）
- 切全站 WebFlux
- 新开 v2 接口或第二个 Agent Bean

---

## 2. 架构

```
HTTP POST /agentscope/dev-agent/ask
  → DevAgentController（@Valid + text/event-stream）
  → DevAgentService（RuntimeContext + AgentEvent → DevAgentEvent）
  → HarnessAgent
       ├─ PermissionMode.EXPLORE
       ├─ ProjectInfoTools（read_pom / list_source_folders / find_main_class）
       └─ OpenAIChatModel + DeepSeekFormatter（deepseek-v4-pro, stream=true）
  → Flux：SESSION
         → AGENT_START / MODEL_CALL_START / TOOL_* / MESSAGE* / AGENT_RESULT / AGENT_END*
         → DONE
         （异常追加 ERROR；缺 Key 仍 SESSION → ERROR）
```

与 Spring AI、Embabel 模块继续并存、互不调用。

包增量：

```
agentscope/
  config/     DevAgentProperties（+projectRoot）、AgentScopeConfig（注册工具 + EXPLORE）
  tool/       ProjectInfoTools（新建）
  model/      DevAgentRequest、DevAgentEvent（扩展字段）
  controller/ DevAgentController（基本不动）
  service/    DevAgentService（事件映射扩展）
```

---

## 3. Toolkit 设计

### 3.1 ProjectInfoTools

新建 Spring Bean，构造注入固定 `Path projectRoot`（来自配置，**不是** `@ToolParam`）。

| 工具名 | 说明 | 参数 | 标记 |
|--------|------|------|------|
| `read_pom` | 读取 `{projectRoot}/pom.xml`，按长度截断 | 可选 `max_chars`（默认 4000） | `readOnly=true` |
| `list_source_folders` | 列出 `{projectRoot}/src` 下已存在的目录 | 无 | `readOnly=true` |
| `find_main_class` | 在 `src/main/java` 查找带 `@SpringBootApplication` 的类 | 无 | `readOnly=true` |

约束：

- 不接收任意文件路径
- 不执行命令、不修改文件
- 模型只能调用代码预先划定的能力

### 3.2 参数边界

| 参数 | 谁决定 | 是否进 Schema |
|------|--------|----------------|
| `projectRoot` | 服务端配置 | 否 |
| `max_chars` | 模型可选填写 | 是（`@ToolParam`） |

原则：**服务端决定读哪里，模型决定返回多少。**

### 3.3 配置

前缀仍为 `app.agentscope.dev-agent`：

| 键 | 说明 | 默认 |
|----|------|------|
| `project-root` | 工具可读的项目根 | `.` |
| `system-prompt` | 双职责中文 Prompt（YAML） | 见下 |
| 其余 `name` / `model.*` | 与上一版相同 | — |

`DevAgentProperties` 增加 `@NotBlank String projectRoot`（或等价校验）。

### 3.4 Prompt（双职责）

写入 `application-agentscope-prompts.yml`，大意：

1. 用户要排查清单时：整理 ≤6 条可执行检查项；不编造已查结果  
2. 用户问依赖 / Java / Spring Boot 版本 / 源码结构 / 启动类时：**先调用对应工具，再基于工具结果回答**  
3. 当前没有日志、数据库、写文件、Shell；不要声称已完成这些操作  
4. 使用中文

### 3.5 Agent 装配变更

在现有 `AgentScopeConfig.agentscopeDevAgent` 上：

1. 构建时设置 `permissionContext(PermissionMode.EXPLORE)`（按 AgentScope 2.0 API 实际 builder 方法写入）  
2. 构建后：`agent.getToolkit().registerTool(projectInfoTools)`  
3. 仍关闭 filesystem / shell / memory / subagent / workspace / dynamic skills 等  
4. 仍 `removeTool("wait_async_results")`  
5. 本版不分组工具

---

## 4. SSE 事件协议

### 4.1 DevAgentEvent 扩展

向后兼容：旧客户端只读 `type` / `sessionId` / `content` 仍可用。

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DevAgentEvent(
        String type,
        String sessionId,
        String content,
        String eventId,
        String toolCallId,
        String name,
        String state) {
    // session / message / done / error 工厂保留
    // 新增 lifecycle / toolCall / toolResult / agentResult 等工厂
}
```

| 字段 | 含义 |
|------|------|
| `type` | 事件类型 |
| `sessionId` | 会话 |
| `content` | 文本分片、提示文案或错误信息 |
| `eventId` | 单条事件 ID（可空） |
| `toolCallId` | 同一次工具调用关联键（可空） |
| `name` | 工具名（可空） |
| `state` | 工具结束状态名（可空） |

### 4.2 转发映射

| type | 来源 | 说明 |
|------|------|------|
| `SESSION` / `DONE` / `ERROR` | 业务侧 | 保留上一版行为 |
| `AGENT_START` / `MODEL_CALL_START` / `AGENT_END` | 生命周期事件 | 供状态栏轻量展示 |
| `TOOL_CALL_START` | `ToolCallStartEvent` | 准备调用；填 `toolCallId` + `name` |
| `TOOL_RESULT_END` | `ToolResultEndEvent` | 同 `toolCallId`；`state` = SUCCESS / ERROR / DENIED / INTERRUPTED / RUNNING |
| `MESSAGE` | `TextBlockDeltaEvent` | 流式文字增量 |
| `AGENT_RESULT` | `AgentResultEvent` | 完整结果；**前端不追加到聊天气泡**（防重复） |

不转发：工具参数增量、结果分片、`ToolResultStartEvent`。未知类型丢弃。

### 4.3 Service 流组装

```
Flux.concat(
  SESSION,
  streamEvents(...).handle(mapEvent),  // null 跳过
  DONE
).onErrorResume → ERROR
```

缺 `DEEPSEEK_API_KEY`：仍 `SESSION` + `ERROR`，不进入 Agent。

Controller 路径与签名不变。

---

## 5. 前端（AgentScope Tab）

文件：`static/js/tabs/agentscope.js`、`static/css/tabs/agentscope.css`、`index.html`（示例按钮如需）。

行为：

1. 每轮助手回复：流式文字气泡 + 该轮工具条列表（气泡上方或气泡顶部）  
2. `TOOL_CALL_START`：按 `toolCallId` 新增「准备调用：{name}」  
3. `TOOL_RESULT_END`：同 `toolCallId` 更新为 `{name} · {state}`  
4. `MESSAGE`：追加到助手气泡  
5. `AGENT_RESULT`：不追加聊天内容  
6. 状态栏：可反映 `AGENT_START` / `MODEL_CALL_START` / `DONE` / `ERROR`  
7. 「换会话」：新 `sessionId`，清空消息与工具条  
8. 示例：保留清单类 1～2 条；新增项目问答示例（版本 + 启动类 / 源码目录）

欢迎文案改为同时提示「清单整理」与「项目问答」。

---

## 6. 错误处理

| 场景 | 行为 |
|------|------|
| 校验失败 | HTTP 400（不变） |
| 缺 API Key | SSE `SESSION` + `ERROR`（不变） |
| 流中异常 | SSE `ERROR` 后结束（不变） |
| 工具执行失败 | `TOOL_RESULT_END.state=ERROR`；模型可据此解释；不因此中断整条 SSE（除非上层抛错） |
| 写类工具被选中 | EXPLORE 下 Permission 拒绝 → `DENIED`（本版无写工具，仅作为框架行为说明） |

---

## 7. 测试与验收

### 7.1 自动化

- `ProjectInfoToolsTest`：临时目录写入假 `pom.xml` / 假启动类，断言三工具输出  
- `DevAgentServiceTest`：mock `streamEvents`  
  - 映射 `TOOL_CALL_START` / `TOOL_RESULT_END`  
  - `MESSAGE` 仍来自文字增量  
  - `AGENT_RESULT` 发出对应 type，且测试/约定前端不重复渲染为 MESSAGE  
  - 异常 → `ERROR`  
- 更新 `DevAgentEventTest` 覆盖新工厂与可空字段

### 7.2 手工

```bash
# 项目问答（应出现 TOOL_* 事件后再答）
curl -N -X POST "http://localhost:8081/agentscope/dev-agent/ask" \
  -H "Content-Type: application/json" \
  -d '{"userId":"dev-user-001","sessionId":"toolkit-session-001","message":"帮我看一下这个项目用了哪个 Java 版本、Spring Boot 版本，以及启动类在哪里"}'

# 清单类（可不调用工具）
curl -N -X POST "http://localhost:8081/agentscope/dev-agent/ask" \
  -H "Content-Type: application/json" \
  -d '{"userId":"dev-user-001","sessionId":"checklist-session-001","message":"帮我整理一份今天排查订单接口超时的执行清单"}'
```

Web Tab：工具条可见；换会话隔离。

### 7.3 验收清单

1. 编译通过；缺 Key 时行为与上一版一致  
2. 项目问答能基于 `pom.xml` / 源码真实回答，而非臆造  
3. SSE 含 `TOOL_CALL_START` / `TOOL_RESULT_END`，且同 `toolCallId` 可关联  
4. Tab 显示工具名与状态；回答不因 `AGENT_RESULT` 重复两遍  
5. 清单场景仍可用；Prompt 不声称已查日志/改文件  
6. `project-root` 可配置；路径不进 Tool Schema  
7. README / 功能说明补充 Toolkit + 事件透传一行（若仓库已有 AgentScope 条目则更新）

---

## 8. 与上一版关系

| 上一版 | 本版 |
|--------|------|
| Toolkit 无可用工具 | 注册 3 个只读项目工具 |
| 仅转发文字增量 | 转发生命周期 + 工具事件 |
| `DevAgentEvent` 三字段 | 七字段（后四可空） |
| Prompt：无工具清单助手 | Prompt：双职责 |
| 扩展点「自定义工具 / 事件透传」 | 本规范兑现这两点 |

后续仍可单独加：Tool Group、写工具、持久化、确认事件。

---

## 9. 参考

- 微信文章 4 / 5（见文首链接）  
- AgentScope Java 2.0：`Toolkit`、`@Tool`、`PermissionMode.EXPLORE`、`streamEvents()`、`AgentEvent`  
- 仓库对照：`demo2/.../agentscope/*`、现有 AgentScope Tab
