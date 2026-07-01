# AutoMemoryTools Demo 设计规范

**日期**: 2026-06-30  
**项目**: spring-ai-demo / demo2  
**状态**: 已实现  

**参考**

- [AutoMemoryTools](https://spring-ai-community.github.io/spring-ai-agent-utils/latest-snapshot/tools/AutoMemoryTools/)
- [AutoMemoryToolsAdvisor](https://spring-ai-community.github.io/spring-ai-agent-utils/latest-snapshot/tools/AutoMemoryToolsAdvisor/)
- Agent 自主记忆的持久记忆 + 短暂记忆双层模型（微信技术文思路，与官方 Advisor 文档一致）

---

## 1. 背景与目标

### 1.1 需求

在 `demo2` 中新增 **AutoMemoryTools** 演示：Agent 通过工具自主管理**持久记忆**（Markdown 文件落盘）与 **短暂记忆**（MySQL JDBC `ChatMemory` 会话窗口），并与现有「Agent 记忆行程」「DB 持久化记忆」Tab 并列，突出三种记忆机制的对比。

### 1.2 已确认决策

| 维度 | 选择 |
|------|------|
| 落地形式 | **方案 A**：新增独立 Tab「Agent 自主记忆」 |
| API 路径 | `/agent/auto-memory/*` |
| 长期记忆目录 | `agent.memories.dir` + `/{userId}` 子目录隔离 |
| 工具集成 | `AutoMemoryToolsAdvisor`（非手动注册 6 个 Tool） |
| 短期记忆 | `MessageChatMemoryAdvisor` + `@Qualifier("mysqlChatMemory")`（复用 `MysqlMemoryConfig`） |
| 验证场景 | 行程规划（与现有记忆 Tab 业务一致） |
| 记忆文件 API | **提供** `GET /agent/auto-memory/memories?userId=` |
| 交互方式 | **同步 HTTP**（对齐「Agent 记忆行程」Tab） |
| 会话模式 | **多轮** `POST` 同 `userId`，`conversationId=userId` |
| 清除记忆 | `DELETE` 删除该 `userId` 整个记忆目录并 `mysqlChatMemory.clear(userId)` |
| 实现结构 | **方案 1**：独立 `AutoMemoryTripAgentService` + `AutoMemoryAgentController` |

### 1.3 依赖

- 已具备：`spring-ai-agent-utils:0.10.0`（含 `AutoMemoryTools`、`AutoMemoryToolsAdvisor`）
- 已具备：`spring-ai-starter-model-chat-memory-repository-jdbc`、MySQL 驱动
- 已具备：`MysqlMemoryConfig` 中的 `mysqlChatMemory`、`mysqlMessageChatMemoryAdvisor`
- **无需**新增 Maven 依赖

### 1.4 成功标准

1. `mvn -f demo2/pom.xml compile` 通过
2. `mvn -f demo2/pom.xml test` 通过（含 `AutoMemoryTripAgentServiceTest`）
3. 新 Tab 可完成三步验证：存偏好 → 复用长期记忆 → 多 `userId` 隔离
4. 重启应用后，同 `userId` 第二步仍能复用 Markdown 中的偏好（验证持久记忆）
5. Swagger 可查看 `/agent/auto-memory/*` 端点
6. `GET /agent/auto-memory/memories` 可列出 `MEMORY.md` 与子目录内 `.md` 文件

### 1.5 不在范围

- 替换或合并现有「Agent 记忆行程」「DB 持久化记忆」Tab
- `memoryConsolidationTrigger` 周期性整理（默认关闭）
- SSE / 记忆文件变更实时推送
- 生产级鉴权、限流、分布式记忆存储

---

## 2. 架构设计

### 2.1 三种记忆 Tab 对比

| Tab | 短期（会话历史） | 长期（跨会话事实） |
|-----|------------------|-------------------|
| Agent 记忆行程 | 内存 `InMemoryChatMemoryRepository` | 无 |
| DB 持久化记忆 | MySQL `mysqlChatMemory` | 无（完整对话历史在 DB，非 Agent 策展） |
| **Agent 自主记忆** | **MySQL `mysqlChatMemory`** | **AutoMemoryTools Markdown 文件** |

### 2.2 双层记忆模型

| 层级 | 机制 | 存储 | 生命周期 | 管理者 |
|------|------|------|----------|--------|
| 短暂记忆 | `MessageChatMemoryAdvisor` + `mysqlChatMemory` | MySQL `spring_ai_chat_memory` | 进程重启后仍在 DB；受 `maxMessages=30` 窗口约束 | Spring AI 自动注入历史消息 |
| 持久记忆 | `AutoMemoryToolsAdvisor` → `AutoMemoryTools` | `${agent.memories.dir}/{userId}/` 下 Markdown | 跨会话、跨重启 | **模型**通过 `MemoryView` / `MemoryCreate` 等工具读写 |

Rule of thumb（与官方文档一致）：

- 当前会话的逐轮对话、进行中任务细节 → **短暂记忆**（MySQL）
- 下周仍应记住的用户偏好、项目决策、行为反馈 → **持久记忆**（`MEMORY.md` + 类型化 `.md`）

### 2.3 组件职责

```
index.html (Tab: agent-auto-memory)
    ├── agent-auto-memory.js   → POST /chat, GET /memories, DELETE /clear-memory
    └── agent-auto-memory.css

AutoMemoryAgentController      → REST，参数校验，调用 Service
AutoMemoryTripAgentService     → 组装 ChatClient advisors，解析 userId 记忆根路径
AutoMemoryAgentConfig          → @Value agent.memories.dir（可选 Advisor 工厂辅助）
MysqlMemoryConfig              → 复用 mysqlChatMemory、mysqlMessageChatMemoryAdvisor（不修改）
```

### 2.4 按 userId 挂载 AutoMemoryToolsAdvisor

`AutoMemoryToolsAdvisor` 构建时需绑定单个 `memoriesRootDirectory`。本 Demo 采用：

- `userRoot = Path.of(agentMemoriesDir, userId).normalize()`
- Service 内 `ConcurrentHashMap<String, AutoMemoryToolsAdvisor>` 缓存已构建的 Advisor，避免每请求重复 `build()`
- 每次 `chat()`：`chatClientBuilder.clone()`，挂载该 userId 的 `AutoMemoryToolsAdvisor` + `mysqlMessageChatMemoryAdvisor`

### 2.5 ChatClient 组装

每次 `chat(userId, message)`：

1. `userRoot = resolveUserMemoriesRoot(userId)`（校验 `userId` 匹配 `^[a-zA-Z0-9_-]+$`）
2. `advisor = advisorCache.computeIfAbsent(userId, id -> AutoMemoryToolsAdvisor.builder().memoriesRootDirectory(userRoot).build())`
3. `client = chatClientBuilder.clone().defaultAdvisors(advisor, mysqlMessageChatMemoryAdvisor).defaultSystem(SYSTEM_PROMPT).build()`
4. `client.prompt().user(message).advisors(a -> a.param(ChatMemory.CONVERSATION_ID, userId)).call()`

`SYSTEM_PROMPT`：行程规划角色，说明优先从长期记忆文件与 MySQL 会话历史中复用用户偏好。

### 2.6 记忆目录约定

- 配置项：`agent.memories.dir`，默认 `${user.home}/.agent/memories`
- 实际根路径：`{agent.memories.dir}/{userId}/`
- 目录结构遵循 Agent Utils：`MEMORY.md` 索引 + `user` / `feedback` / `project` / `reference` 类型 Markdown
- 应用启动不预创建子目录；首次由 `AutoMemoryTools` 或模型工具调用创建

### 2.7 前置条件

- 本地 MySQL 库 `spring_ai_agent2` 可用（与「DB 持久化记忆」Tab 相同）
- 配置见 `spring.datasource.*`、`spring.ai.chat.memory.repository.jdbc.*`

---

## 3. REST API

| 方法 | 路径 | 说明 | 响应要点 |
|------|------|------|----------|
| POST | `/agent/auto-memory/chat` | 带双层记忆的行程/偏好对话 | Body: `{ userId, message }` → `{ userId, message, reply, agentType }` |
| GET | `/agent/auto-memory/memories?userId=` | 列出该用户记忆目录 | `{ userId, memoriesRoot, files: [{ name, relativePath, sizeBytes }] }` |
| DELETE | `/agent/auto-memory/clear-memory?userId=` | 清除短期 + 长期 | `{ userId, message }`；`mysqlChatMemory.clear(userId)` + 删除 `userRoot` 目录；Advisor 缓存移除该 userId |

**安全**：`GET memories` 与 `DELETE` 仅操作 `userRoot` 下路径；`userId` 非法返回 `400`。列表 API 由服务端读取目录，不暴露任意文件读取接口。

**错误**：模型/工具失败或 MySQL 不可用时返回可读错误信息（对齐 `MysqlMemoryTripAgentService`）。

---

## 4. 前端 Tab

### 4.1 位置与资源

- Tab 文案：**Agent 自主记忆**（`data-tab: agent-auto-memory`）
- 插入位置：紧跟「DB 持久化记忆」之后
- 新建：`css/tabs/agent-auto-memory.css`、`js/tabs/agent-auto-memory.js`
- `index.html`：按钮、`tab-content` 面板、`<link>` / `<script>`（顺序在 `agent-memory.js` 之后）

### 4.2 页面元素

- 说明条：MySQL 会话记忆 + Agent Markdown 长期记忆双层数据流
- 前置提示：需本地 MySQL `spring_ai_agent2`
- 三步快捷填充（对齐 `agent-memory` Tab）：
  1. 首次存偏好（素食、地铁等）
  2. 复用记忆（简化需求，不写重复偏好）
  3. 多用户隔离（切换 `userId`）
- 输入：`userId`、`message`（多轮：同页连续发送）
- 按钮：发送、清除该用户记忆、刷新记忆文件列表
- 输出：回复区 + 记忆文件列表区（文件名、相对路径、大小）

### 4.3 约束

- 不引入构建工具；全局函数供 `onclick` 使用
- API 路径与本文 §3 一致

---

## 5. 配置

```properties
# application.properties（新增）
agent.memories.dir=${user.home}/.agent/memories
```

---

## 6. 测试

| 类型 | 内容 |
|------|------|
| 单元 | `AutoMemoryTripAgentServiceTest`：`@TempDir` 验证 `resolveUserMemoriesRoot`、非法 `userId`、`clear` 删除目录与缓存失效 |
| 编译 | `mvn compile` |
| 手工 | 三步场景 + 重启进程后步骤 2 仍能复用偏好 |

集成测试调用真实 DeepSeek **非必须**；可 Mock `ChatClient` 或仅测路径与清除逻辑。

---

## 7. 实现检查清单（供 implementation plan 引用）

- [ ] `AutoMemoryAgentConfig` / `AutoMemoryTripAgentService` / `AutoMemoryAgentController` / `AutoMemoryChatRequest`
- [ ] 注入 `@Qualifier("mysqlChatMemory")` 与 `mysqlMessageChatMemoryAdvisor`
- [ ] `application.properties` → `agent.memories.dir`
- [ ] `index.html` + `agent-auto-memory.css` + `agent-auto-memory.js`
- [ ] `AutoMemoryTripAgentServiceTest`
- [ ] `mvn compile` & `mvn test` 通过
