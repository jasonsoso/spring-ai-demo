# Spring Boot 4 + Spring AI 2.0 升级 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 demo2 从 Java 17 + Spring Boot 3.5.14 + Spring AI 1.1.7 升级到 Java 21 + Spring Boot 4.1.x + Spring AI 2.0.0，保持全部 REST API 与演示功能可用。

**Architecture:** 采用 OpenRewrite 自动化处理 Boot 4 / Jackson 3 / Spring AI 2.0 批量变更，再手动修补 PromptChatMemoryAdvisor 移除、MCP SDK 2.0 兼容、Milvus exclude 等编译/运行时问题。业务分层（Controller → Service/Config → Spring AI）不变。

**Tech Stack:** Java 21, Spring Boot 4.1.x, Spring Framework 7, Spring AI 2.0.0, Jackson 3, MCP Java SDK 2.0.0, SpringDoc OpenAPI 3.0.x, Maven, OpenRewrite

**设计规范:** [docs/superpowers/specs/2026-06-23-spring-ai-2-upgrade-design.md](../specs/2026-06-23-spring-ai-2-upgrade-design.md)

## Global Constraints

- Spring AI 版本必须为 **2.0.0**；Spring Boot 必须为 **4.0.x 或 4.1.x**（与 AI BOM 对齐，目标 **4.1.x**）
- Java 版本必须为 **21**
- 所有 REST 路径与 JSON 响应结构保持不变
- 不在本次范围：新功能、CI/CD、API Key 安全治理
- 迁移策略：**OpenRewrite 自动化 + 手动修补**（方案 A）
- 保留 Milvus 懒加载、MCP 同 JVM 延迟初始化（`@Order(1)`/`@Order(2)`）
- `memoryType=prompt` API 参数保留，内部统一映射为 `MessageChatMemoryAdvisor`

---

## File Structure（变更映射）

| 文件 | 职责 | 变更 |
|------|------|------|
| `pom.xml` | 依赖与构建 | Boot 4.1、AI 2.0、Java 21、SpringDoc 3、OpenRewrite 插件 |
| `Demo2Application.java` | 启动类 | 可能更新 Milvus auto-config exclude 类名 |
| `config/MysqlMemoryConfig.java` | MySQL 记忆 Bean | 删除 `PromptChatMemoryAdvisor` Bean |
| `service/MysqlMemoryTripAgentService.java` | MySQL Agent 业务 | 统一 `MessageChatMemoryAdvisor`，兼容 `memoryType` |
| `controller/ChatController.java` | 流式聊天 | Jackson 3 import 修复 |
| `mcp/client/config/McpClientInitializer.java` | MCP Client 初始化 | MCP SDK 2.0 API 验证 |
| `mcp/server/config/McpServerConfig.java` | MCP Server 工具注册 | MCP SDK 2.0 API 验证 |
| `controller/MysqlAgentController.java` | MySQL Agent API | 更新 Swagger 描述（prompt 模式说明） |
| `resources/application.properties` | 配置 | 对照 upgrade-notes 核对 |
| `resources/static/index.html` | 前端演示 | 更新 PromptChatMemoryAdvisor 文案 |
| `README.md` | 文档 | 版本号与 JDK 21 说明 |
| 其余 Java 文件 | 各模块业务 | OpenRewrite 自动迁移 + 编译验证 |

---

### Task 1: 准备迁移分支与环境

**Files:**
- Create: （无新文件）
- Modify: （无）

**Interfaces:**
- Consumes: 无
- Produces: Git 分支 `feature/spring-ai-2-upgrade`；本地 JDK 21 可用

- [ ] **Step 1: 确认 JDK 21**

Run:
```powershell
java -version
```
Expected: 输出包含 `version "21` 或更高

- [ ] **Step 2: 创建迁移分支**

Run:
```powershell
cd d:\ai\demo2\demo2
git checkout -b feature/spring-ai-2-upgrade
```
Expected: `Switched to a new branch 'feature/spring-ai-2-upgrade'`

- [ ] **Step 3: 记录基线编译状态（可选，用于对比）**

Run:
```powershell
mvnw.cmd clean compile -q
```
Expected: 当前基线 BUILD SUCCESS（若失败记录原因，不影响后续）

- [ ] **Step 4: Commit（若创建了分支且工作区干净）**

```powershell
git status
```
若仅有分支切换无文件变更，跳过 commit。

---

### Task 2: 升级 pom.xml 核心依赖

**Files:**
- Modify: `pom.xml`

**Interfaces:**
- Consumes: Task 1 的 JDK 21 环境
- Produces: Maven 可解析 Boot 4.1 + AI 2.0 BOM；`java.version=21`

- [ ] **Step 1: 更新 parent 与 properties**

在 `pom.xml` 中修改：

```xml
<!-- parent -->
<version>4.1.0</version>

<!-- properties -->
<java.version>21</java.version>
<spring-ai.version>2.0.0</spring-ai.version>
```

- [ ] **Step 2: 升级 SpringDoc 依赖**

将：
```xml
<artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
<version>2.8.9</version>
```
改为：
```xml
<artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
<version>3.0.0</version>
```

- [ ] **Step 3: 添加 OpenRewrite Maven 插件（用于 Task 3/4）**

在 `<build><plugins>` 内、`spring-boot-maven-plugin` 之后添加：

```xml
<plugin>
    <groupId>org.openrewrite.maven</groupId>
    <artifactId>rewrite-maven-plugin</artifactId>
    <version>6.32.0</version>
    <configuration>
        <exportDatatables>true</exportDatatables>
    </configuration>
    <dependencies>
        <dependency>
            <groupId>org.openrewrite.recipe</groupId>
            <artifactId>rewrite-spring</artifactId>
            <version>6.30.4</version>
        </dependency>
    </dependencies>
</plugin>
```

- [ ] **Step 4: 验证 POM 可解析**

Run:
```powershell
mvnw.cmd -U dependency:resolve -q
```
Expected: BUILD SUCCESS（此时源码可能尚未编译通过，属正常）

- [ ] **Step 5: Commit**

```powershell
git add pom.xml
git commit -m "chore: upgrade to Spring Boot 4.1, Spring AI 2.0, Java 21"
```

---

### Task 3: OpenRewrite — Spring Boot 4 迁移

**Files:**
- Modify: `pom.xml`（OpenRewrite 可能自动调整版本/BOM）
- Modify: 可能批量修改 Java 源文件（Jackson import 等）
- Modify: 可能修改 `application.properties`

**Interfaces:**
- Consumes: Task 2 的 pom.xml
- Produces: Boot 4 对齐的依赖与 Jackson 3 import 迁移

- [ ] **Step 1: Dry-run 预览变更**

Run:
```powershell
mvnw.cmd org.openrewrite.maven:rewrite-maven-plugin:run `
  -Drewrite.activeRecipes=org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0
```
Review `target/rewrite/` 或控制台输出的变更文件列表。

- [ ] **Step 2: 执行 Boot 4 迁移**

Run:
```powershell
mvnw.cmd org.openrewrite.maven:rewrite-maven-plugin:run `
  -Drewrite.activeRecipes=org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0
```
Expected: `BUILD SUCCESS`，源文件与 pom 被修改

- [ ] **Step 3: 审查 diff**

Run:
```powershell
git diff --stat
```
重点检查：`pom.xml`、`ChatController.java`、测试类、配置文件

- [ ] **Step 4: Commit**

```powershell
git add -A
git commit -m "refactor: apply OpenRewrite Spring Boot 4 migration"
```

---

### Task 4: OpenRewrite — Spring AI 2.0 迁移

**Files:**
- Modify: 可能批量修改 Spring AI 相关 Java 源文件

**Interfaces:**
- Consumes: Task 3 的 Boot 4 基线
- Produces: Spring AI 2.0 API 对齐（Options builder 等）

- [ ] **Step 1: 执行 Spring AI 2.0 迁移配方**

Run:
```powershell
mvnw.cmd org.openrewrite.maven:rewrite-maven-plugin:run `
  -Drewrite.configLocation=https://raw.githubusercontent.com/spring-projects/spring-ai/refs/heads/main/src/rewrite/migrate-to-2-0-0-M3.yaml `
  -Drewrite.activeRecipes=org.springframework.ai.migration.MigrateToSpringAI200M3
```
Expected: `BUILD SUCCESS`

> 若官方 GA 提供了更新的 YAML/recipe 名称，优先替换为 upgrade-notes 推荐版本。

- [ ] **Step 2: 首次编译，收集错误**

Run:
```powershell
mvnw.cmd clean compile 2>&1 | Select-String -Pattern "ERROR"
```
记录所有编译错误文件列表（预期：`PromptChatMemoryAdvisor` 相关）

- [ ] **Step 3: Commit OpenRewrite 变更**

```powershell
git add -A
git commit -m "refactor: apply OpenRewrite Spring AI 2.0 migration"
```

---

### Task 5: 移除 PromptChatMemoryAdvisor，统一 MessageChatMemoryAdvisor

**Files:**
- Modify: `src/main/java/com/jason/demo/demo2/config/MysqlMemoryConfig.java`
- Modify: `src/main/java/com/jason/demo/demo2/service/MysqlMemoryTripAgentService.java`
- Modify: `src/main/java/com/jason/demo/demo2/controller/MysqlAgentController.java`

**Interfaces:**
- Consumes: `ChatMemory mysqlChatMemory` Bean（`MysqlMemoryConfig`）
- Produces: `MysqlMemoryTripAgentService.planTripWithMysqlMemory(String userId, String demand, String memoryType)` — `memoryType` 任意值均走 `MessageChatMemoryAdvisor`

- [ ] **Step 1: 重写 MysqlMemoryConfig.java**

完整替换为：

```java
package com.jason.demo.demo2.config;

import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MySQL 持久化聊天记忆配置：JDBC 存储 + MessageChatMemoryAdvisor。
 */
@Configuration
public class MysqlMemoryConfig {

    @Bean("mysqlChatMemory")
    public ChatMemory mysqlChatMemory(JdbcChatMemoryRepository jdbcChatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(jdbcChatMemoryRepository)
                .maxMessages(30)
                .build();
    }

    @Bean("mysqlMessageChatMemoryAdvisor")
    public MessageChatMemoryAdvisor mysqlMessageChatMemoryAdvisor(
            @Qualifier("mysqlChatMemory") ChatMemory mysqlChatMemory) {
        return MessageChatMemoryAdvisor.builder(mysqlChatMemory).build();
    }
}
```

- [ ] **Step 2: 重写 MysqlMemoryTripAgentService.java 记忆相关部分**

关键变更：
1. 删除 `PromptChatMemoryAdvisor` import 与字段
2. 构造函数只注入 `messageChatMemoryAdvisor`
3. `planTripWithMysqlMemory` 忽略 `memoryType` 分支差异，统一使用 `messageChatMemoryAdvisor`

```java
// 构造函数签名改为：
public MysqlMemoryTripAgentService(
        ChatClient.Builder chatClientBuilder,
        @Qualifier("mysqlChatMemory") ChatMemory mysqlChatMemory,
        JdbcChatMemoryRepository jdbcChatMemoryRepository,
        @Qualifier("mysqlMessageChatMemoryAdvisor") MessageChatMemoryAdvisor messageChatMemoryAdvisor) {
    this.chatClient = chatClientBuilder.build();
    this.mysqlChatMemory = mysqlChatMemory;
    this.jdbcChatMemoryRepository = jdbcChatMemoryRepository;
    this.messageChatMemoryAdvisor = messageChatMemoryAdvisor;
}

// planTripWithMysqlMemory 方法体改为：
public String planTripWithMysqlMemory(String userId, String demand, String memoryType) {
    try {
        // memoryType 保留 API 兼容；Spring AI 2.0 已移除 PromptChatMemoryAdvisor，统一使用 message 模式
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(demand)
                .advisors(messageChatMemoryAdvisor)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, userId))
                .call()
                .content();
    } catch (Exception e) {
        log.error("MySQL 持久化记忆行程规划失败, userId={}, memoryType={}", userId, memoryType, e);
        return "调用 AI 模型失败：" + e.getMessage();
    }
}
```

- [ ] **Step 3: 更新 MysqlAgentController Swagger 描述**

将 `@Operation` description 从：
```
支持 message / prompt 两种记忆模式
```
改为：
```
memoryType 参数保留兼容；Spring AI 2.0 统一使用 MessageChatMemoryAdvisor（message 模式）
```

- [ ] **Step 4: 编译验证**

Run:
```powershell
mvnw.cmd compile -pl . -am -q
```
Expected: 无 `PromptChatMemoryAdvisor` 相关 ERROR

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/jason/demo/demo2/config/MysqlMemoryConfig.java `
        src/main/java/com/jason/demo/demo2/service/MysqlMemoryTripAgentService.java `
        src/main/java/com/jason/demo/demo2/controller/MysqlAgentController.java
git commit -m "fix: replace removed PromptChatMemoryAdvisor with MessageChatMemoryAdvisor"
```

---

### Task 6: 修复 Jackson 3 与 Demo2Application

**Files:**
- Modify: `src/main/java/com/jason/demo/demo2/controller/ChatController.java`
- Modify: `src/main/java/com/jason/demo/demo2/Demo2Application.java`（若编译报错）

**Interfaces:**
- Consumes: Spring Boot 4 自动配置的 `ObjectMapper` Bean
- Produces: 流式 SSE JSON 序列化正常工作

- [ ] **Step 1: 检查 ChatController import**

若仍为 `com.fasterxml.jackson.databind.ObjectMapper` 且编译失败，改为 Spring 注入方式不变，仅更新 import：

```java
import tools.jackson.databind.json.JsonMapper;
```

并在字段注入处二选一（优先 A）：

**A — 继续使用 ObjectMapper 类型（Boot 4 仍注册该 bean）：**
```java
@Autowired
private ObjectMapper objectMapper;
```
确保 import 为 `tools.jackson.databind.json.JsonMapper` 或其父类型。

**B — 显式使用 JsonMapper：**
```java
private final JsonMapper jsonMapper;

public ChatController(ChatClient.Builder chatClientBuilder, JsonMapper jsonMapper) {
    this.chatClient = chatClientBuilder.build();
    this.jsonMapper = jsonMapper;
}
```
并将 `objectMapper.writeValueAsString(...)` 改为 `jsonMapper.writeValueAsString(...)`。

- [ ] **Step 2: 检查 Demo2Application Milvus exclude**

若编译报错 `MilvusVectorStoreAutoConfiguration` 找不到，在 IDE 或 Maven 错误信息中查找新类全名，更新：

```java
@SpringBootApplication(exclude = MilvusVectorStoreAutoConfiguration.class)
```

常见路径仍为 `org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreAutoConfiguration`，以编译器提示为准。

- [ ] **Step 3: 编译**

Run:
```powershell
mvnw.cmd compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```powershell
git add src/main/java/com/jason/demo/demo2/controller/ChatController.java
# 若 Demo2Application 有改动也 add
git commit -m "fix: align Jackson 3 imports and Milvus auto-config exclude"
```

---

### Task 7: 修复 MCP SDK 2.0 编译问题（若有）

**Files:**
- Modify: `src/main/java/com/jason/demo/demo2/mcp/client/config/McpClientInitializer.java`
- Modify: `src/main/java/com/jason/demo/demo2/mcp/server/config/McpServerConfig.java`
- Modify: `src/main/java/com/jason/demo/demo2/mcp/client/controller/McpChatController.java`（若 API 变更）
- Modify: `src/main/resources/application.properties`（若 MCP 配置项 rename）

**Interfaces:**
- Consumes: `List<McpSyncClient>`、`SyncMcpToolCallbackProvider`
- Produces: MCP Client 在 `ApplicationReadyEvent` 后正常初始化；`/mcp/client/tools` 可列出工具

- [ ] **Step 1: 编译并定位 MCP 相关错误**

Run:
```powershell
mvnw.cmd compile 2>&1 | Select-String -Pattern "mcp|Mcp"
```

- [ ] **Step 2: 若无编译错误，跳过代码修改，进入 Step 4**

- [ ] **Step 3: 若有 API 变更，按编译器提示修复**

常见修复点：
- `client.listTools().tools()` — 若返回类型变化，按 IDE 提示调整
- `McpServerFeatures.SyncToolSpecification` — 若包名变化，更新 import
- `SyncMcpToolCallbackProvider.getToolCallbacks()` — 若 rename，同步更新 `McpChatController`

保留现有初始化顺序：
- `McpClientInitializer` — `@Order(1)`
- `McpChatController.init()` — `@Order(2)`

- [ ] **Step 4: 对照 Spring AI 2.0 upgrade-notes 核对 MCP 配置**

确认以下配置项仍有效（若 rename 则更新）：
```properties
spring.ai.mcp.server.name=demo2-mcp-server
spring.ai.mcp.server.tool-callback-converter=false
spring.ai.mcp.client.enabled=true
spring.ai.mcp.client.initialized=false
spring.ai.mcp.client.sse.connections.local-server.url=http://localhost:8081
```

- [ ] **Step 5: 编译 + Commit（若有改动）**

```powershell
mvnw.cmd compile -q
git add -A
git commit -m "fix: align MCP SDK 2.0 APIs and configuration"
```

---

### Task 8: 全量编译与单元测试

**Files:**
- Test: `src/test/java/com/jason/demo/demo2/Demo2ApplicationTests.java`

**Interfaces:**
- Consumes: Task 5–7 的全部修复
- Produces: `mvn test` BUILD SUCCESS

- [ ] **Step 1: 全量编译**

Run:
```powershell
mvnw.cmd clean compile
```
Expected:
```
BUILD SUCCESS
```

- [ ] **Step 2: 运行单元测试**

Run:
```powershell
mvnw.cmd test
```
Expected: `Demo2ApplicationTests.contextLoads` PASS

若 `contextLoads` 失败：
1. 阅读 Caused by 链
2. 优先检查：Milvus exclude、MCP bean 循环依赖、数据源配置
3. 修复后重复 Step 2

- [ ] **Step 3: Commit（若有测试相关修复）**

```powershell
git add -A
git commit -m "test: fix context loading after Spring Boot 4 / Spring AI 2 upgrade"
```

---

### Task 9: 更新前端与 README 文档

**Files:**
- Modify: `src/main/resources/static/index.html`
- Modify: `README.md`

**Interfaces:**
- Consumes: Task 8 通过编译测试
- Produces: 文档与 UI 文案反映 Spring AI 2.0 / JDK 21

- [ ] **Step 1: 更新 index.html MySQL 记忆模块文案**

在 `index.html` 中搜索并替换：

| 原文 | 新文 |
|------|------|
| `message / prompt 模式` | `MessageChatMemoryAdvisor 模式` |
| `支持 MessageChatMemoryAdvisor 和 PromptChatMemoryAdvisor 动态切换` | `使用 MessageChatMemoryAdvisor 持久化记忆（Spring AI 2.0）` |
| `测试 3（prompt 模式）` | `测试 3（memoryType 兼容参数）` |
| `测试3：Prompt 模式` | `测试3：memoryType 兼容测试` |

保留 `memoryType=prompt` 的前端测试用例与 API 参数（后端已兼容映射）。

- [ ] **Step 2: 更新 README.md 技术栈表格**

```markdown
| 运行环境 | Java | 21 |
| 核心框架 | Spring Boot | 4.1.x |
| AI 框架 | Spring AI | 2.0.0 |
| API 文档 | SpringDoc OpenAPI | 3.0.x |
```

- [ ] **Step 3: 更新 README 前置条件**

将 `JDK 17+` 改为 `JDK 21+`。

- [ ] **Step 4: 在 README 末尾添加迁移说明小节**

```markdown
## 升级说明（Spring AI 2.0）

本项目已升级至 **Java 21 + Spring Boot 4.1 + Spring AI 2.0.0**。

主要变更：
- `PromptChatMemoryAdvisor` 已移除，MySQL 记忆统一使用 `MessageChatMemoryAdvisor`
- Jackson 3（Boot 4 默认）
- MCP Java SDK 2.0.0

详细设计见 `docs/superpowers/specs/2026-06-23-spring-ai-2-upgrade-design.md`。
```

- [ ] **Step 5: Commit**

```powershell
git add src/main/resources/static/index.html README.md
git commit -m "docs: update README and frontend for Spring AI 2.0 upgrade"
```

---

### Task 10: 冒烟验证（手动）

**Files:**
- 无代码变更（除非冒烟发现问题）

**Interfaces:**
- Consumes: 全部 Task 完成；外部服务：DeepSeek API Key、智谱 API Key、MySQL、Milvus（按模块）

- [ ] **Step 1: 启动应用**

Run:
```powershell
mvnw.cmd spring-boot:run
```
Expected: 应用在 `:8081` 启动，无 ERROR 级 bean 初始化失败

- [ ] **Step 2: 基础模块冒烟（无需 Milvus）**

```powershell
# Swagger
curl -s -o NUL -w "%{http_code}" http://localhost:8081/swagger-ui.html
# 期望 200 或 302

# 聊天（需 DeepSeek Key）
curl -s -X POST http://localhost:8081/ai/chat -H "Content-Type: application/json" -d "{\"message\":\"hello\"}"
```

- [ ] **Step 3: MCP 冒烟（应用完全启动后等待 3–5 秒）**

```powershell
curl "http://localhost:8081/mcp/client/tools"
curl "http://localhost:8081/mcp/client/chat?message=北京天气怎么样"
```

- [ ] **Step 4: Milvus 依赖模块（需 Docker Milvus + 智谱 Key）**

```powershell
curl "http://localhost:8081/rag/optimized/ask?question=户外登山需要什么装备"
curl "http://localhost:8081/ecommerce/service/chat/precise?question=退换货政策是什么"
```

- [ ] **Step 5: MySQL 记忆模块（需 MySQL）**

```powershell
curl "http://localhost:8081/agent/mysql/trip/plan?userId=1001&demand=周末厦门游&memoryType=message"
curl "http://localhost:8081/agent/mysql/trip/list-conversations"
```

- [ ] **Step 6: 前端整体验证**

浏览器打开 `http://localhost:8081`，逐 Tab 点击测试。

- [ ] **Step 7: 记录验证结果**

在 PR 或 commit message 中注明通过的冒烟项；未测项注明原因（如缺少 API Key）。

---

## Plan Self-Review

| 设计规范章节 | 对应 Task |
|-------------|-----------|
| P0 准备 | Task 1 |
| P1 pom 升级 | Task 2 |
| P2 OpenRewrite Boot 4 | Task 3 |
| P2 OpenRewrite AI 2.0 | Task 4 |
| P3 PromptChatMemoryAdvisor | Task 5 |
| P3 Jackson / Milvus exclude | Task 6 |
| P3 MCP SDK 2.0 | Task 7 |
| P4 mvn test | Task 8 |
| P5 文档/前端 | Task 9 |
| 冒烟清单 §7.2 | Task 10 |
| application.properties 核对 | Task 7 Step 4 |
| 回滚方案 | Task 1 独立分支 |

**Placeholder 扫描:** 无 TBD/TODO/“适当处理”类占位。

**类型一致性:** `MysqlMemoryTripAgentService.planTripWithMysqlMemory(String, String, String)` 签名全 Task 保持一致。

---

## 执行选项

Plan complete and saved to `docs/superpowers/plans/2026-06-23-spring-ai-2-upgrade.md`.

**Two execution options:**

1. **Subagent-Driven (recommended)** — 每个 Task 派发独立 subagent，Task 间做 review，迭代快
2. **Inline Execution** — 在本会话中按 Task 顺序直接执行，每 2–3 个 Task 设 checkpoint 供你 review

**Which approach?**
