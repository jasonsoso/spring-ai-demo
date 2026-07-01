# Session 事件溯源短期记忆 Demo 设计规范

**日期**: 2026-07-01  
**项目**: spring-ai-demo / demo2  
**状态**: 已实现（2026-07-01）  

**原文**（已通过 wechat-reader 读取）

- 标题：**Spring AI 2.0 系列教程（九）——事件溯源的短期记忆与上下文压缩**
- 作者：第七符文
- 链接：https://mp.weixin.qq.com/s/ggsibA5simFvW7Tk2I9ddg
- 发布时间：2026-06-10

**延伸阅读**

- [Spring AI Agentic Patterns Part 7: Session API](https://spring.io/blog/2026/04/15/spring-ai-session-management)
- [spring-ai-session 官方文档](https://spring-ai-community.github.io/spring-ai-session/latest-snapshot/session-management/compaction/)

---

## 0. 文章要点 ↔ Demo 对照表

| 文章章节 | 核心概念 | 本 Demo 是否覆盖 | 实现方式 |
|----------|----------|------------------|----------|
| 问题背景：扁平消息列表 | `MessageWindowChatMemory` 截断破坏 tool call 链 | ✅ 对比说明 | 与现有三个记忆 Tab 并列；本 Tab 用 Session |
| Session API 三要素 | 结构化事件 + 轮次压缩 + 持久化 SPI | ✅ | `SessionEvent` + `SessionMemoryAdvisor` + JDBC |
| Turn 原子单位 | UserMessage → 后续全部事件 → 下一条 User | ✅ | 挂 `WeatherTool`/`AttractionTool` 产生 tool 事件 |
| SessionEvent 元数据 | id / sessionId / timestamp / branch / SYNTHETIC | ✅ 部分 | 侧栏展示 event 元数据；**不做** branch 隔离 Demo |
| 压缩触发器 | `TurnCountTrigger` / `TokenCountTrigger` / OR 组合 | ✅ | 默认 `TurnCountTrigger(15)`，参数可配置 |
| 四种压缩策略 | Sliding / Turn / Token / Recursive | ✅ 聚焦一种 | **`RecursiveSummarizationCompactionStrategy`**（用户选定） |
| Recall Storage | `SessionEventTools` + `conversation_search` | ✅ | 注册 `SessionEventTools`；Prompt 引导 Agent 搜索 |
| 事件过滤 EventFilter | 按类型/时间/关键词/分支过滤 | ⬜ 轻量 | `GET /events` 返回摘要列表，不单独做 Filter API |
| JDBC 持久化 | `AI_SESSION` + `AI_SESSION_EVENT` | ✅ | `spring-ai-starter-session-jdbc` |
| 乐观 CAS 并发 | `replaceEvents` compare-and-swap | ⬜ 框架内置 | 不单独演示，依赖 SessionService |
| 与 AutoMemoryTools 组合 | 长期 + 短期双层 Advisor 链 | ❌ 不在范围 | 用户选 **方案 A：仅短期**；见 §1.5 |
| ChatMemory vs Session | 二者不可同时使用 | ✅ | 本 Tab **不挂** `MessageChatMemoryAdvisor` |

---

## 1. 背景与目标

### 1.1 文章要解决什么问题

文章指出：把对话存成**扁平消息列表**，窗口满后按条截断，会导致：

1. 孤立的 `ToolResult`（找不到对应 `ToolCall`）
2. 一轮对话被拦腰截断
3. 无法结构化决定「保留什么、丢弃什么」

Session API 用**事件溯源（Event Sourcing）**重构短期记忆：append-only 事件日志 + **Turn 边界**压缩 + 可插拔策略。

### 1.2 本 Demo 目标

新增 Tab「**Session 事件溯源记忆**」，**只演示短期记忆层**（文章第九篇主体），与现有记忆 Tab 对比。交互为**对话式 UI + SSE 流式**，固定 `userId` 可多轮连续提问。

### 1.3 已确认决策

| 维度 | 选择 | 文章依据 |
|------|------|----------|
| 范围 | **仅 Session 短期记忆**（方案 A） | §与 AutoMemoryTools 的组合 → 本 Tab 不实现该节 |
| 压缩策略 | `RecursiveSummarizationCompactionStrategy` | §长期工作 Agent → RecursiveSummarization |
| 压缩触发 | `TurnCountTrigger(15)` | 文章组合示例用 15 轮 |
| 持久化 | JDBC（`spring-ai-starter-session-jdbc`） | §JDBC 持久化 |
| Recall | `SessionEventTools` | §Recall Storage |
| 工具 | `WeatherTool` + `AttractionTool` | §工具调用密集型 → 用 tool 事件验证 Turn 完整性 |
| 模型 | **`deepseek-v4-pro`** | 用户指定（文章示例未限定模型） |
| 交互 | 聊天气泡 + 底部输入框 + SSE | 用户指定 |
| Session 标识 | `SESSION_ID_CONTEXT_KEY = userId` | §快速接入 → 按 sessionId 传参 |
| API | `/agent/session-memory/*` | 项目惯例 |

### 1.4 依赖

文章示例 BOM 为 `0.5.0-SNAPSHOT`；实现时优先采用 **Maven Central 可解析的最新稳定版**（当前社区文档为 `0.2.0`，若本地可解析更高版本则跟随文章）。

```xml
<dependencyManagement>
  <dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-session-bom</artifactId>
    <version>0.2.0</version>  <!-- 实现时以可解析版本为准 -->
    <type>pom</type>
    <scope>import</scope>
  </dependency>
</dependencyManagement>

<dependency>
  <groupId>org.springaicommunity</groupId>
  <artifactId>spring-ai-starter-session-jdbc</artifactId>
</dependency>
```

### 1.5 成功标准

1. `mvn -f demo2/pom.xml compile` 通过
2. `mvn -f demo2/pom.xml test` 通过
3. 对话式 Tab：同 `userId` 多轮 SSE 对话
4. 15+ 轮后侧栏可见 `archived` / `synthetic` 变化
5. 压缩后问早期细节，Agent 可通过 `conversation_search` 找回
6. 重启后同 `userId` JDBC 历史仍在
7. 不出现「孤立 ToolResult」类截断问题（相对 ChatMemory Tab 的差异）

### 1.6 不在范围

- `AutoMemoryTools` / `AutoMemoryToolsAdvisor`（文章 §组合 仅作参考）
- `MessageChatMemoryAdvisor`（文章：与 Session **不可同时用**）
- 多 Agent `branch` 隔离 Demo
- `EventFilter` 独立 REST API
- TodoWrite 式「每轮新建 SSE Session」

---

## 2. 架构

### 2.1 记忆 Tab 全景（对照文章 §ChatMemory vs Session）

| Tab | 存储模型 | 压缩 | Tool 事件完整 | 长期记忆 | 交互 |
|-----|----------|------|---------------|----------|------|
| Agent 记忆行程 | 扁平 Message 列表（内存） | 删最旧 N 条 | ❌ | 无 | 同步 HTTP |
| DB 持久化记忆 | 扁平 Message 列表（MySQL） | 同上 | ❌ | 无 | 同步 HTTP |
| Agent 自主记忆 | 扁平 + AutoMemoryTools | 同上 | 部分 | ✅ | 同步 HTTP |
| **Session 事件溯源** | **Event Store（append-only）** | **RecursiveSummarization** | **✅ Turn 安全** | 无 | **SSE 对话** |

### 2.2 数据流

```
浏览器（气泡 + 输入框）
  │ POST /agent/session-memory/chat/stream  { userId, message }
  ▼
SessionMemoryTripAgentService
  ├── SessionMemoryAdvisor
  │     ① 从 JDBC 加载 SessionEvent → 注入 prompt
  │     ② 追加本轮 User/Assistant/Tool 事件
  │     ③ TurnCountTrigger 满足 → RecursiveSummarization 压缩
  ├── SessionEventTools          → conversation_search（Recall Storage）
  ├── WeatherTool / AttractionTool
  └── ChatClient.stream()        → SSE TOKEN 事件
  ▼
JdbcSessionRepository
  ├── AI_SESSION
  └── AI_SESSION_EVENT（append-only；压缩为 archived + synthetic，不删原文）
```

### 2.3 组件

| 类 | 职责 |
|----|------|
| `SessionMemoryAgentConfig` | `SessionMemoryAdvisor`、`SessionEventTools`、压缩参数 Bean |
| `SessionMemoryTripAgentService` | `streamChat` / `listEvents` / `clearSession` |
| `SessionMemoryAgentController` | REST + SSE |
| `SessionMemoryChatRequest` | `{ userId, message }` |
| `agent-session-memory.js/css` | 对话 UI + 事件侧栏 |

SSE 参考 `ChatController.chatStream`（POST 直接返回 `SseEmitter`），**不复用** `AbstractSseAgentService`（单次任务语义不同）。

---

## 3. ChatClient 组装（对齐文章代码）

### 3.1 SessionMemoryAdvisor（文章 §RecursiveSummarization + §组合示例）

```java
SessionMemoryAdvisor.builder(sessionService)
    .compactionTrigger(new TurnCountTrigger(15))
    .compactionStrategy(
        RecursiveSummarizationCompactionStrategy.builder(summarizationChatClient)
            .maxEventsToKeep(10)   // 官方 API；文章示例亦出现 maxTurns(10)，实现时以库 API 为准
            .overlapSize(2)
            .build())
    .build();
```

`summarizationChatClient`：独立 `ChatClient`（`deepseek-v4-pro`），专供摘要，避免循环依赖。

**RecursiveSummarization 行为**（文章 §）：

1. 窗口溢出时，将待淘汰事件**增量**并入既有摘要
2. LLM 重新摘要，结果标记 `METADATA_SYNTHETIC`
3. 保留窗口起点对齐 **UserMessage**（Turn 边界安全）

### 3.2 每轮 streamChat

```java
ChatClient client = chatClientBuilder.clone()
    .defaultOptions(DeepSeekChatOptions.builder().model("deepseek-v4-pro"))
    .defaultTools(sessionEventTools, weatherTool, attractionTool)
    .defaultAdvisors(
        sessionMemoryAdvisor,
        ToolCallingAdvisor.builder().build())  // Spring AI 2.0：有 MemoryAdvisor 时自动禁用内部重复历史
    .defaultSystem(SYSTEM_PROMPT)
    .build();

client.prompt()
    .user(message)
    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, userId))
    .stream().content()
    .subscribe(chunk -> sse TOKEN);
```

文章组合示例使用 `ToolCallAdvisor.disableInternalConversationHistory()`；Spring AI 2.0 等价行为由 `MemoryAdvisor` 标记触发，实现时以编译通过为准。

### 3.3 SYSTEM_PROMPT

- 行程规划；天气/景点**必须**调工具
- 优先会话历史；压缩后若缺早期细节，**主动 `conversation_search`**
- 简洁结构化输出

### 3.4 模型

```properties
agent.session-memory.chat.model=deepseek-v4-pro
```

若多轮 tool call 遇 `reasoning_content` 回放问题，可降级 `deepseek-chat`（项目 AutoMemory Tab 已有先例）。

---

## 4. API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/agent/session-memory/chat/stream` | SSE：`TOKEN` / `COMPLETED` / `FAILED` |
| GET | `/agent/session-memory/events?userId=` | 事件统计 + 最近 20 条元数据 |
| DELETE | `/agent/session-memory/clear?userId=` | 清除 Session 及全部 events |

`userId`：`^[a-zA-Z0-9_-]+$`。SSE 超时 5 分钟。

**GET /events 示例字段**：`totalEvents`、`activeEvents`、`archivedEvents`（估算：`totalEvents - promptMessageCount`）、`syntheticEvents`、`events[].{eventId, messageType, synthetic, hasToolCalls, timestamp}`

> **实现说明（0.2.0 API）**：`SessionEvent` 无 `isArchived()`；`archivedEvents` 由事件总数与当前 prompt 消息数差值估算。包名实际为 `org.springframework.ai.session.*`（非 `org.springaicommunity.ai.session.*`）。

---

## 5. 前端（对话式）

```
┌──────────────────────────────────────────────────────────┐
│ 说明：Event Store · RecursiveSummarization · Recall       │
│ userId │ [清除会话] │ [刷新事件]                            │
├────────────────────────────┬─────────────────────────────┤
│ 对话气泡（用户/助手，流式）  │ 事件侧栏：active/archived/   │
│                            │ synthetic + 最近事件列表       │
├────────────────────────────┴─────────────────────────────┤
│ [输入框                                    ] [发送]         │
│ 快捷：多轮存偏好 | 压缩后 recall 追问 | 换 userId 隔离      │
└──────────────────────────────────────────────────────────┘
```

**验证场景**（对应文章 §Recall Storage + §JDBC）：

| 步骤 | 操作 | 预期 |
|------|------|------|
| 1 | userId=1001，5+ 轮含查天气/景点 | 事件增长；可见 TOOL 类型 |
| 2 | 继续至触发压缩，问「第 1 轮饮食禁忌」 | `synthetic`↑；`conversation_search` 找回 |
| 3 | userId=1002 | 与 1001 隔离 |
| 4 | 重启应用，1001 继续聊 | JDBC 历史仍在 |

Tab：`agent-session-memory`，文案「🗂️ Session 事件溯源记忆」，插在「Agent 自主记忆」之后。

---

## 6. 配置

```properties
spring.ai.session.repository.jdbc.initialize-schema=always
spring.ai.session.repository.jdbc.platform=mysql

agent.session-memory.compaction.turn-threshold=15
agent.session-memory.compaction.max-events-to-keep=10
agent.session-memory.compaction.overlap-size=2
agent.session-memory.chat.model=deepseek-v4-pro
```

复用 `spring.datasource.*`（`spring_ai_agent2`）。Session 表（`AI_SESSION` / `AI_SESSION_EVENT`）与 `spring_ai_chat_memory` **独立**。

> **platform 说明**：`spring-ai-starter-session-jdbc` 0.2.0 仅提供 `schema-mysql.sql` / `schema-postgresql.sql` / `schema-h2.sql`，**无** `schema-mariadb.sql`。ChatMemory JDBC 仍可用 `platform=mariadb`（`initialize-schema=never`），Session JDBC 须用 `mysql`。

---

## 7. 测试

| 类型 | 内容 |
|------|------|
| 单元 | `SessionMemoryTripAgentServiceTest`：userId 校验、clear（Mock SessionService） |
| 编译 | `mvn compile` |
| 手工 | 四步验证场景 |

---

## 8. 实现检查清单

- [x] `pom.xml` → session BOM `0.2.0` + `spring-ai-starter-session-jdbc`
- [x] `SessionMemoryAgentConfig` / `SessionMemoryTripAgentService` / `SessionMemoryAgentController` / `SessionMemoryChatRequest` / `SessionMemorySseEvent`
- [x] `SessionMemoryAdvisor` + `RecursiveSummarizationCompactionStrategy` + `TurnCountTrigger(15)`
- [x] `SessionEventTools` + `WeatherTool` / `AttractionTool`
- [x] `POST /chat/stream` SSE + `GET /events` + `DELETE /clear`
- [x] `deepseek-v4-pro` + 对话式前端 Tab「🗂️ Session 事件溯源记忆」
- [x] `SessionMemoryTripAgentServiceTest`（5 用例）
- [x] `mvn compile` & `mvn test` 通过（14 tests）

---

## 9. 实现偏差与踩坑记录

| 计划假设 | 实际实现 |
|----------|----------|
| `org.springaicommunity.ai.session.*` | `org.springframework.ai.session.*` |
| `SessionService.findSession` / `deleteSession` | `findById` / `delete` / `getEvents` / `getMessages` |
| `SessionEvent.isArchived()` | 无；侧栏 `archivedEvents` 为估算值 |
| `platform=mariadb` | Session 须 `platform=mysql`（见 §6） |
| 单构造器 `@Service` 自动注入 | 双构造器时 Spring 构造器须加 `@Autowired` |
| `DeepSeekChatOptions.builder().model(...).build()` | 与项目惯例一致：**不带** `.build()` |
| `mock(Session.class)` | `Session` 为 final，单测用 `Session.builder().id(...).userId(...).build()` |
| `clearSession` 直接 `delete` | 先 `findById`，存在才 `delete` |
