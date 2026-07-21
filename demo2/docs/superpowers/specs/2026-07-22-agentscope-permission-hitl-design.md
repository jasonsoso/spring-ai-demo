# AgentScope Java 2.0 Permission HITL 设计规范

**日期**: 2026-07-22  
**项目**: spring-ai-demo / demo2  
**状态**: 已确认，待实现  
**前置**: [2026-07-17-agentscope-toolkit-events-design.md](./2026-07-17-agentscope-toolkit-events-design.md)（已实现）  
**参考文章**: [6. AgentScope Java 2.0 Permission 权限实战：Agent 写文件前先问你](https://mp.weixin.qq.com/s/RJq3el6CMNoWM__cUXtahg)

---

## 1. 背景与目标

### 1.1 需求

在已有 AgentScope Dev Agent（只读 Toolkit + AgentEvent SSE + AgentScope Tab）上，落地第六篇权限实战：

1. 给 Agent 增加受控写文件能力（`request_file_change`）
2. 写入前经 `PermissionEngine`：合法 `notes/` 写入 **ASK**，删除/越界 **DENY**，只读三工具 **ALLOW**
3. ASK 时暂停本轮，经 SSE 展示待确认参数；用户批准/拒绝后通过 `/confirm` 恢复同一会话

### 1.2 已确认决策

| 维度 | 选择 |
|------|------|
| 落地方式 | **方案 1：原位增强**现有 `agentscope` 包与 `/agentscope/dev-agent/*` |
| 交互 | **API + AgentScope Tab**（确认卡片 + 批准/拒绝） |
| 规则范围 | **严格对齐文章 demo**：只写 `notes/`、整批 `approved`、内存 pending |
| 权限模式 | `PermissionMode.EXPLORE` → `PermissionMode.DEFAULT` + 只读 ALLOW 规则 |
| API 前缀 / 端口 | 不变：`/agentscope/dev-agent`，8081 |

### 1.3 非目标（本版不做）

- 按 `toolCallId` 分别审批
- 确认过期、幂等状态机、pending / AgentState 持久化
- 放开 `notes/` 以外路径
- 启用官方 filesystem / shell 工具
- 新开 v2 接口或第二个 Agent Bean
- 切全站 WebFlux

---

## 2. 架构与数据流

```
用户消息
  → POST /agentscope/dev-agent/ask
  → DevAgentService.ask()
  → HarnessAgent.streamEvents()
       ├─ PermissionMode.DEFAULT
       ├─ ALLOW：read_pom / list_source_folders / find_main_class
       ├─ ProjectInfoTools（@Tool，只读）
       └─ FileChangeTool（ToolBase，request_file_change）
            checkPermissions() → ASK | DENY
            callAsync()        → 仅批准后写 notes/

遇 ASK：
  RequireUserConfirmEvent
    → pendingConfirmations[userId|sessionId] = ToolUseBlock[]
    → SSE：REQUIRE_USER_CONFIRM + pendingToolCalls
  RequestStopEvent(PERMISSION_ASKING) → REQUEST_STOP
  → DONE（本轮结束，文件未写）

用户批准/拒绝：
  → POST /agentscope/dev-agent/confirm { userId, sessionId, approved }
  → ConfirmResult 列表 → Msg.METADATA_CONFIRM_RESULTS
  → 同一 RuntimeContext 恢复 streamEvents
  → 批准：callAsync() 写文件 → TOOL_* / MESSAGE / AGENT_RESULT
  → 拒绝：DENIED，不写文件
  → DONE
```

### 2.1 包增量（原位）

```
agentscope/
  config/     AgentScopeConfig（DEFAULT + ALLOW + 注册 FileChangeTool）
              application-agentscope-prompts.yml（写文件约束）
  tool/       FileChangeTool（新建）
  model/      DevAgentConfirmRequest、PendingToolCall
              DevAgentEvent / DevAgentEventType（扩展）
  service/    DevAgentService（确认事件映射 + confirm()）
  controller/ DevAgentController（+ POST /confirm）
```

前端：`static/js/tabs/agentscope.js`、`agentscope.css`、`index.html` 示例话术。

### 2.2 关键约定

- 确认 key = `userId + "|" + sessionId`；confirm 必须与 ask 使用相同二者
- `userId` 为空时使用占位 `_anonymous`（与 ask / confirm 一致）
- 整批 `approved`：本轮所有 pending 一同批准或拒绝
- `InMemoryAgentStateStore` + 内存 `ConcurrentHashMap` 待确认表（重启丢失）
- 批准只允许继续执行，不放开路径 / 符号链接限制；`callAsync()` 写盘前再校验

---

## 3. FileChangeTool

### 3.1 形态

| 项 | 约定 |
|----|------|
| 基类 | AgentScope `ToolBase`（非 `@Tool` 注解工具） |
| 工具名 | `request_file_change` |
| Schema | `operation`、`path`、`content` |
| 注册 | `agent.getToolkit().registerAgentTool(fileChangeTool)` |
| `projectRoot` | 来自 `DevAgentProperties.projectRoot()`，构造注入，不进 Schema |

写入操作集合（与文章同款）：`create` / `write` / `update` / `append`（实现时以文章示例 `WRITE_OPERATIONS` 为准；大小写不敏感）。

### 3.2 `checkPermissions()` 决策

| 条件 | 结果 |
|------|------|
| `operation` ∈ {delete, remove} | **DENY** |
| `operation` ∉ 写入集合 | **DENY** |
| 路径非法（空、绝对路径、规范化后离开 `notes/`） | **DENY** |
| 合法写入 `notes/...` | **ASK**（`decisionReason` 含 `safety:`，防止被后续 ALLOW 规则放行） |

`content` 不参与权限判断；批准后由 `callAsync()` 写入。

### 3.3 路径与符号链接

`resolveTarget(path)`：

1. 拒绝空路径、绝对路径
2. 相对 `projectRoot` 规范化
3. 结果必须仍位于 `{projectRoot}/notes/` 下（防 `notes/../application.yml`）

`callAsync()` 写盘前再检查：`notes/`、父目录、目标是否为符号链接；是则失败返回，不写文件。

### 3.4 执行与日志

- ASK / DENY 时不调用 `callAsync()`
- 批准后：按需创建父目录 → UTF-8 写入 → 返回成功摘要（不含全文 dump）
- 日志阶段：`Checking file change permission` → `Executing confirmed file change` → `File change completed`
- **日志不记录文件正文**

### 3.5 只读工具与权限模式

- 保留 `ProjectInfoTools`（`read_pom` / `list_source_folders` / `find_main_class`）
- `PermissionContextState.builder().mode(PermissionMode.DEFAULT)`
- 对上述三工具名分别 `addAllowRule`（DEFAULT 不会仅凭 `readOnly=true` 自动放行）
- 仍关闭官方 filesystem / shell / memory / subagent / workspace / dynamic skills 等
- 仍 `removeTool("wait_async_results")`

### 3.6 Prompt

在 `application-agentscope-prompts.yml` 补充：

- 仅当用户明确要求写文件时选用 `request_file_change`
- 只能写 `notes/` 下相对路径
- 不要尝试删除文件
- 真正限制由 Java 权限与工具实现，不依赖模型自觉

---

## 4. SSE 事件与 API

### 4.1 事件类型增量

`DevAgentEventType` 新增：

| type | 含义 |
|------|------|
| `REQUIRE_USER_CONFIRM` | 需要人工确认；携带 `pendingToolCalls` |
| `REQUEST_STOP` | 本轮因权限询问停止（如 `PERMISSION_ASKING`） |

保留既有：`SESSION` / `MESSAGE` / `DONE` / `ERROR` / `AGENT_*` / `TOOL_*` / `AGENT_RESULT`。

### 4.2 DevAgentEvent 扩展

向后兼容：旧字段继续可用。

新增：

- `List<PendingToolCall> pendingToolCalls`（仅确认事件有值；`@JsonInclude(NON_NULL)`）
- 工厂方法 `DevAgentEvent.confirmation(sessionId, eventId, pendingToolCalls)`
- 工厂方法 `DevAgentEvent.requestStop(sessionId, eventId, content)`（可选，或复用 lifecycle）

`PendingToolCall` record：

| 字段 | 说明 |
|------|------|
| `toolCallId` | 工具调用 ID |
| `name` | 工具名（如 `request_file_change`） |
| `input` | `Map<String, Object>`（operation / path / content） |

### 4.3 Service 映射

| 框架事件 | 业务事件 |
|----------|----------|
| `RequireUserConfirmEvent` | 存 pending → `REQUIRE_USER_CONFIRM` |
| `RequestStopEvent` | `REQUEST_STOP`（content 可含 stop reason） |
| 其余 | 沿用第五篇映射 |

`ask()` 流组装仍为：`SESSION` → mapped events → `DONE`；异常 → `ERROR`。

### 4.4 Confirm API

```http
POST /agentscope/dev-agent/confirm
Content-Type: application/json
Accept: text/event-stream

{
  "userId": "dev-user-001",
  "sessionId": "permission-write-001",
  "approved": true
}
```

`DevAgentConfirmRequest`：`userId`（可空→`_anonymous`）、`@NotBlank sessionId`、`boolean approved`。

`confirm()` 步骤：

1. 校验 API Key（与 ask 相同；缺 Key → `SESSION` + `ERROR`）
2. `pendingConfirmations.remove(key)`；无记录 → `SESSION` + `ERROR`（无待确认）
3. `pending.stream().map(tc -> new ConfirmResult(approved, tc))`
4. 构建 `Msg`：`textContent` = `approved` / `denied`；`metadata` 含 `Msg.METADATA_CONFIRM_RESULTS`
5. `streamEvents(resumeMessage, RuntimeContext{userId, sessionId})` 映射为业务事件
6. `Flux.concat(SESSION, events, DONE).onErrorResume(ERROR)`

说明：本版按文章 demo，confirm 前先 remove pending；恢复失败不可直接同记录重试（生产增强列为非目标）。

### 4.5 手工验证（curl）

写文件 ASK → 批准：

```bash
curl -sN -X POST "http://localhost:8081/agentscope/dev-agent/ask" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"dev-user-001\",\"sessionId\":\"permission-write-001\",\"message\":\"请创建 notes/permission-demo.txt，内容是：AgentScope Permission HITL 已通过。\"}"

curl -sN -X POST "http://localhost:8081/agentscope/dev-agent/confirm" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"dev-user-001\",\"sessionId\":\"permission-write-001\",\"approved\":true}"

# 检查 {projectRoot}/notes/permission-demo.txt
```

删除应 DENY（不进确认）：`TOOL_RESULT_END` state=`DENIED`，文件仍在。

---

## 5. 前端（AgentScope Tab）

文件：`static/js/tabs/agentscope.js`、`static/css/tabs/agentscope.css`、`index.html`。

行为：

1. 收到 `REQUIRE_USER_CONFIRM`：在当前助手轮次渲染确认卡片  
   - 展示每个 pending：工具名、operation、path、content 摘要（过长截断）  
   - 「批准」「拒绝」按钮  
2. 点击后：`POST /agentscope/dev-agent/confirm`，复用同一 SSE 解析逻辑，继续更新工具条与流式文字  
3. 确认进行中：禁用输入框、发送按钮与批准/拒绝，防重复提交  
4. `REQUEST_STOP`：状态栏提示即可  
5. 「换会话」：新 `sessionId`，清空消息、工具条与确认卡片（旧 pending 留在服务端直至被新同 key 覆盖或进程重启——demo 可接受）  
6. 示例话术新增：创建 `notes/permission-demo.txt`（HITL 演示）

样式：轻量确认条/卡片，复用现有 agentscope 色板，不引入新设计体系。

---

## 6. 错误处理

| 场景 | 行为 |
|------|------|
| 缺 `DEEPSEEK_API_KEY` | ask / confirm 均 `SESSION` → `ERROR` |
| 路径越界 / 删除 | 工具侧 DENY；SSE `TOOL_RESULT_END` state=`DENIED`；无确认卡片 |
| confirm 无 pending | `SESSION` → `ERROR`（文案说明无待确认） |
| confirm 恢复抛错 | `onErrorResume` → `ERROR` |
| 符号链接 / 写盘 IO 失败 | 工具结果失败；不落盘或部分失败由工具返回明确信息 |

---

## 7. 测试计划

| 类型 | 覆盖 |
|------|------|
| 单测 `FileChangeTool` | 合法 `notes/` → ASK；delete → DENY；`notes/../x` → DENY；绝对路径 → DENY |
| 单测事件 DTO | `confirmation` 含 `pendingToolCalls`；序列化非空字段 |
| 单测 `DevAgentService.confirm` | 无 pending → ERROR；有 pending 时构造 resume 路径（可 mock Agent） |
| 手工 | curl ASK→批准落盘；Tab 批准/拒绝；删除 DENY |

`notes/` 写入产物：建议 gitignore `demo2/notes/` 或仅提交 `.gitkeep`，避免演示文件污染仓库（实现时二选一，默认 `notes/.gitkeep` + ignore `notes/*`）。

---

## 8. 成功标准

1. 用户请求创建 `notes/permission-demo.txt` → SSE 出现 `REQUIRE_USER_CONFIRM`，此时文件不存在  
2. 同一 `userId`+`sessionId` 批准 → 文件内容正确，后续有 `TOOL_RESULT_END` SUCCESS 与模型总结  
3. 拒绝 → 文件仍不存在，工具结果为 DENIED  
4. 删除 / 越界写入 → 直接 DENY，不出现确认卡片  
5. 只读三工具仍可直接执行（ALLOW）  
6. AgentScope Tab 可完成批准/拒绝闭环，无需 curl  

---

## 9. 参考

- AgentScope Permission：`PermissionMode.DEFAULT`、`PermissionDecision` / `PermissionBehavior`、`ConfirmResult`、`Msg.METADATA_CONFIRM_RESULTS`
- 事件：`RequireUserConfirmEvent`、`RequestStopEvent`
- 系列前置设计：Harness Web、Toolkit + AgentEvent
