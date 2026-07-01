# AutoMemoryTools Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]` / `- [ ]`) syntax for tracking.

**Goal:** 在 demo2 中实现 AutoMemoryTools 双层记忆 Demo：MySQL 短期会话记忆 + Agent 自主 Markdown 长期记忆，新增独立 Tab 与 `/agent/auto-memory/*` REST API，`mvn compile` / `mvn test` 通过。

**Status:** 代码与单测已实现（Task 1–4、Task 5 Step 1–4、Task 6 Step 1–2）；待手工冒烟（Task 5 Step 5）与用户按需 Commit（Task 6 Step 3）。

**Architecture:** `AutoMemoryTripAgentService` 按 `userId` 缓存 `AutoMemoryToolsAdvisor`（`memoriesRoot = {agent.memories.dir}/{userId}`），每次请求 `chatClientBuilder.clone()` 挂载长期 Advisor + 复用 `MysqlMemoryConfig` 的 `mysqlMessageChatMemoryAdvisor`；前端同步 HTTP 多轮对话 + 记忆文件列表刷新。

**Tech Stack:** Java 21, Spring Boot 4.1, Spring AI 2.0.0, spring-ai-agent-utils 0.10.0 (`AutoMemoryToolsAdvisor`), MySQL JDBC ChatMemory, 原生 HTML/CSS/JS

**设计规范:** [docs/superpowers/specs/2026-06-30-automemory-tools-design.md](../specs/2026-06-30-automemory-tools-design.md)

## Global Constraints

- **不新增 Maven 依赖**；使用已有 `spring-ai-agent-utils:0.10.0`
- **短期记忆**必须注入 `@Qualifier("mysqlChatMemory")` 与 `@Qualifier("mysqlMessageChatMemoryAdvisor")`，**不**使用 `MemoryConfig` 的内存 `chatMemory`
- **长期记忆**使用 `org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor`（非手动注册 6 个 Tool）
- **userId** 校验：`^[a-zA-Z0-9_-]+$`，非法返回 `400`
- **清除记忆**：`mysqlChatMemory.clear(userId)` + 删除 `{agent.memories.dir}/{userId}/` + 从 Advisor 缓存移除该 userId
- API 前缀：`/agent/auto-memory`；交互为**同步 HTTP**（非 SSE）
- 不修改现有「Agent 记忆行程」「DB 持久化记忆」Tab 的后端逻辑
- 前端：零构建、普通 `<script src>`、全局函数供 `onclick`

---

## File Structure

| 文件 | 职责 |
|------|------|
| `config/AutoMemoryAgentConfig.java` | 读取 `agent.memories.dir`（可选 `@ConfigurationProperties` 或 `@Value`） |
| `model/AutoMemoryChatRequest.java` | `POST /chat` 请求体 `{ userId, message }` |
| `service/AutoMemoryTripAgentService.java` | 双层记忆 ChatClient 组装、`listMemories`、`clearMemory` |
| `controller/AutoMemoryAgentController.java` | REST 三端点 + userId 校验 |
| `test/.../AutoMemoryTripAgentServiceTest.java` | 路径解析、非法 userId、clear 删目录与缓存 |
| `application.properties` | 新增 `agent.memories.dir` |
| `static/css/tabs/agent-auto-memory.css` | Tab 样式（对齐 agent-memory / mysql-memory 视觉） |
| `static/js/tabs/agent-auto-memory.js` | POST chat、GET memories、DELETE clear |
| `static/index.html` | Tab 按钮、面板、link/script 引用 |
| `README.md` | AutoMemory Demo 章节（可选） |

---

### Task 1: 配置与请求模型

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/config/AutoMemoryAgentConfig.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/AutoMemoryChatRequest.java`
- Modify: `demo2/src/main/resources/application.properties`

**Interfaces:**
- Produces: `agentMemoriesDir` 字符串供 Service 注入；`AutoMemoryChatRequest` 供 Controller 反序列化

- [x] **Step 1: 新增 `application.properties` 配置**

```properties
# AutoMemoryTools 长期记忆根目录（按 userId 分子目录）
agent.memories.dir=${user.home}/.agent/memories
```

- [x] **Step 2: 创建 `AutoMemoryAgentConfig`**

```java
@Configuration
public class AutoMemoryAgentConfig {
    @Value("${agent.memories.dir:${user.home}/.agent/memories}")
    private String agentMemoriesDir;

    public String getAgentMemoriesDir() {
        return agentMemoriesDir;
    }
}
```

或 Service 直接 `@Value` 注入，二选一即可（推荐 Config 类便于测试注入）。

- [x] **Step 3: 创建 `AutoMemoryChatRequest`**

```java
@Schema(description = "AutoMemory 对话请求")
public class AutoMemoryChatRequest {
    private String userId;
    private String message;
    // getters / setters
}
```

- [x] **Step 4: 编译**

Run: `cd demo2 && mvn -q compile`
Expected: BUILD SUCCESS

---

### Task 2: AutoMemoryTripAgentService 核心逻辑

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/service/AutoMemoryTripAgentService.java`

**Interfaces:**
- Consumes: `ChatClient.Builder`, `@Qualifier("mysqlChatMemory") ChatMemory`, `@Qualifier("mysqlMessageChatMemoryAdvisor") MessageChatMemoryAdvisor`, `agent.memories.dir`
- Produces: `chat(userId, message) → String`；`listMemories(userId) → Map`；`clearMemory(userId)`

- [x] **Step 1: 定义 SYSTEM_PROMPT**

行程规划角色，明确：
1. 优先从长期记忆 Markdown（`MEMORY.md` 及关联文件）提取用户偏好；
2. 结合 MySQL 会话历史补全当前对话上下文；
3. 新偏好应通过记忆工具持久化；
4. 语言简洁、结构清晰。

- [x] **Step 2: 实现 `resolveUserMemoriesRoot(userId)`**

```java
private static final Pattern USER_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");

public Path resolveUserMemoriesRoot(String userId) {
    if (userId == null || !USER_ID_PATTERN.matcher(userId).matches()) {
        throw new IllegalArgumentException("userId 仅允许字母、数字、下划线与连字符");
    }
    Path base = Path.of(agentMemoriesDir).normalize().toAbsolutePath();
    Path userRoot = base.resolve(userId).normalize();
    if (!userRoot.startsWith(base)) {
        throw new IllegalArgumentException("非法 userId");
    }
    return userRoot;
}
```

- [x] **Step 3: Advisor 缓存**

```java
private final ConcurrentHashMap<String, AutoMemoryToolsAdvisor> advisorCache = new ConcurrentHashMap<>();

private AutoMemoryToolsAdvisor advisorFor(String userId) {
    return advisorCache.computeIfAbsent(userId, id ->
        AutoMemoryToolsAdvisor.builder()
            .memoriesRootDirectory(resolveUserMemoriesRoot(id).toString())
            .build());
}
```

- [x] **Step 4: 实现 `chat(userId, message)`**

```java
public String chat(String userId, String message) {
    try {
        AutoMemoryToolsAdvisor longTerm = advisorFor(userId);
        ChatClient client = chatClientBuilder.clone()
            .defaultAdvisors(longTerm, mysqlMessageChatMemoryAdvisor)
            .defaultSystem(SYSTEM_PROMPT)
            .build();
        return client.prompt()
            .user(message)
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, userId))
            .call()
            .content();
    } catch (Exception e) {
        log.error("AutoMemory 对话失败, userId={}", userId, e);
        return "调用 AI 模型失败：" + e.getMessage();
    }
}
```

- [x] **Step 5: 实现 `listMemories(userId)`**

遍历 `userRoot`（若不存在返回空 `files`）：
- 使用 `Files.walk(userRoot, 3)` 或递归列出 `.md` 文件；
- 返回 `{ userId, memoriesRoot, files: [{ name, relativePath, sizeBytes }] }`；
- `relativePath` 相对 `userRoot`，禁止 `..`。

- [x] **Step 6: 实现 `clearMemory(userId)`**

1. `mysqlChatMemory.clear(userId)`
2. `advisorCache.remove(userId)`
3. `Path userRoot = resolveUserMemoriesRoot(userId)`；若存在则 `Files.walkFileTree` 逆序删除

- [x] **Step 7: 编译**

Run: `cd demo2 && mvn -q compile`
Expected: BUILD SUCCESS（若 `AutoMemoryToolsAdvisor` 包路径报错，确认为 `org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor`）

---

### Task 3: AutoMemoryAgentController

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/controller/AutoMemoryAgentController.java`

**Interfaces:**
- Produces: `POST /agent/auto-memory/chat`、`GET /agent/auto-memory/memories`、`DELETE /agent/auto-memory/clear-memory`

- [x] **Step 1: 创建 Controller**

```java
@Tag(name = "AutoMemory", description = "AutoMemoryTools 自主持久记忆 + MySQL 短暂记忆 Demo")
@RestController
@RequestMapping("/agent/auto-memory")
public class AutoMemoryAgentController {
    // POST /chat → Map.of(userId, message, reply, agentType)
    // GET /memories?userId=
    // DELETE /clear-memory?userId=
}
```

- [x] **Step 2: 统一 userId 校验**

`IllegalArgumentException` → `@ExceptionHandler` 或 Controller 内 try/catch 返回 `400`（与项目现有风格一致）。

- [x] **Step 3: 编译并启动验证 Swagger**

Run: `cd demo2 && mvn -q compile`
Expected: BUILD SUCCESS；启动后 Scalar 可见三个端点

---

### Task 4: 单元测试

**Files:**
- Create: `demo2/src/test/java/com/jason/demo/demo2/service/AutoMemoryTripAgentServiceTest.java`

- [x] **Step 1: 测试 `resolveUserMemoriesRoot`**

- 合法 `userId` → 路径为 `{tempDir}/1001`
- 非法 `userId`（含 `../`、空格）→ 抛 `IllegalArgumentException`

- [x] **Step 2: 测试 `clearMemory`**

- `@TempDir` 下创建 `{userId}/MEMORY.md`
- 调用 `clearMemory` 后目录不存在
- `advisorCache` 中该 userId 已移除（可通过 package-private 或反射，或测 `listMemories` 返回空）

- [x] **Step 3: 运行测试**

Run: `cd demo2 && mvn -q test "-Dtest=AutoMemoryTripAgentServiceTest"`
Expected: BUILD SUCCESS，全部通过

---

### Task 5: 前端 Tab（CSS + JS + index.html）

**Files:**
- Create: `demo2/src/main/resources/static/css/tabs/agent-auto-memory.css`
- Create: `demo2/src/main/resources/static/js/tabs/agent-auto-memory.js`
- Modify: `demo2/src/main/resources/static/index.html`

**参考:** `agent-memory.css` / `agent-memory.js` / `tab-agent-mysql-memory` 面板结构

- [x] **Step 1: 创建 `agent-auto-memory.css`**

- 头部渐变条（可与 mysql-memory 区分配色，如蓝绿系）
- `.auto-memory-flow` 数据流说明条
- `.auto-memory-answer`、`.auto-memory-file-list`、`.btn-auto-memory` 等

- [x] **Step 2: 在 `index.html` 注册 Tab**

- `<head>` 增加：`<link rel="stylesheet" href="/css/tabs/agent-auto-memory.css">`
- Tab 按钮（紧跟 DB 持久化记忆之后）：
  ```html
  <button class="tab-btn" data-tab="agent-auto-memory" onclick="switchTab('agent-auto-memory')">📂 Agent 自主记忆</button>
  ```
- `tab-content` 面板 `id="tab-agent-auto-memory"`，包含：
  - 双层记忆说明（MySQL 短期 + Markdown 长期）
  - MySQL 前置提示（`spring_ai_agent2`）
  - 三步测试说明 + 快捷按钮
  - 输入：`autoMemoryUserIdInput`、`autoMemoryMessageInput`
  - 按钮：发送、清除记忆、刷新记忆文件
  - 输出：`autoMemoryResult`、`autoMemoryFileList`

- [x] **Step 3: 创建 `agent-auto-memory.js`**

```javascript
const AUTO_MEMORY_TEST_CASES = {
    1: { userId: '1001', message: '周末两天杭州游，2人，偏好西湖人文景点，素食，地铁出行' },
    2: { userId: '1001', message: '下周三天，再规划一次杭州周边游' },
    3: { userId: '1002', message: '周末一天苏州游，1人，园林景点，不吃辣，高铁+地铁' }
};

async function sendAutoMemoryChat() { /* POST /agent/auto-memory/chat JSON */ }
async function refreshAutoMemoryFiles() { /* GET /agent/auto-memory/memories */ }
async function clearAutoMemory() { /* DELETE /agent/auto-memory/clear-memory */ }
function fillAutoMemoryTest(n) { /* 填充测试用例 */ }
```

- [x] **Step 4: 在 `index.html` 底部引入脚本**

在 `agent-memory.js` 之后：
```html
<script src="/js/tabs/agent-auto-memory.js"></script>
```

- [ ] **Step 5: 手工冒烟（需 MySQL + DeepSeek Key）**

1. 测试 1 发送 → 回复正常，刷新列表可见 `.md` 文件
2. 测试 2 同 userId → 回复体现偏好复用
3. 测试 3 换 userId → 隔离
4. 重启应用后测试 2 → Markdown 偏好仍可复用

---

### Task 6: 文档与全量验证

**Files:**
- Modify: `demo2/README.md`（可选）

- [x] **Step 1: README 增加 AutoMemory 章节**

- Tab 名称、API 路径、MySQL + `agent.memories.dir` 说明
- 三步验证指引

- [x] **Step 2: 全量编译与测试**

Run: `cd demo2 && mvn -q compile test`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit（用户要求时）**

```bash
git add demo2/src demo2/docs demo2/README.md
git commit -m "feat(demo2): add AutoMemoryTools demo with MySQL session and file long-term memory"
```

---

## 验收清单（对照 Spec §1.4）

- [x] `mvn compile` 通过
- [x] `mvn test` 通过（含 `AutoMemoryTripAgentServiceTest`）
- [ ] Tab 三步验证可跑通（需 MySQL + DeepSeek Key 手工验证）
- [ ] 重启后长期记忆仍可复用（需手工验证）
- [x] Swagger 可见 `/agent/auto-memory/*`
- [x] `GET /memories` 列出 `MEMORY.md` 与 `.md` 文件

## 常见问题排查

| 现象 | 可能原因 |
|------|----------|
| 编译找不到 `AutoMemoryToolsAdvisor` | 确认 `spring-ai-agent-utils` 版本 ≥ 0.10.0；import `org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor` |
| MySQL 连接失败 | 检查 `spring_ai_agent2` 库与 `spring.datasource.*` |
| 长期记忆目录无文件 | 模型可能未调用记忆工具；可换更明确的偏好描述重试；检查 `agent.memories.dir` 权限 |
| 工具未注册 | 确认 `clone().defaultAdvisors(longTerm, mysqlAdvisor)` 且请求带 `ToolCallingChatOptions`（Advisor 的 `before()` 会注入） |
