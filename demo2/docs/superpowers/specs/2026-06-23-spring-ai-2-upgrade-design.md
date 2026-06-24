# Spring Boot 4 + Spring AI 2.0 升级设计规范

**日期**: 2026-06-23  
**项目**: spring-ai-demo  
**状态**: 待审阅

---

## 1. 背景与目标

### 1.1 当前状态

| 组件 | 当前版本 |
|------|---------|
| Java | 17 |
| Spring Boot | 3.5.14 |
| Spring AI | 1.1.7 |
| Jackson | 2.x |
| MCP Java SDK | 1.1.x（随 Spring AI 1.x） |

### 1.2 目标状态

| 组件 | 目标版本 |
|------|---------|
| Java | 21 |
| Spring Boot | 4.1.x（与 Spring AI 2.0.0 BOM 对齐） |
| Spring AI | 2.0.0 |
| Jackson | 3.x（Boot 4 默认） |
| MCP Java SDK | 2.0.0 |
| SpringDoc OpenAPI | 3.0.x（Boot 4 兼容） |

### 1.3 约束

- Spring AI 2.0.0 **硬性依赖** Spring Boot 4.0/4.1，无法单独在 Boot 3.x 上升级。
- 保持现有 9 大功能模块 API 路径与行为不变（演示项目，非破坏性重构）。
- 采用 **方案 A**：OpenRewrite 自动化 + 手动修补。

### 1.4 成功标准

1. `mvn clean compile` 通过
2. `mvn test` 通过（`Demo2ApplicationTests.contextLoads`）
3. 应用可启动；Milvus/MySQL 不可用时，非依赖模块仍可启动
4. 以下模块功能与升级前一致：
   - AI 聊天（同步/流式）
   - Embedding / 相似度
   - RAG 基础版 / RAG 优化版（Milvus）
   - 电商客服（精准/增强检索）
   - Agent 行程规划（无记忆 / 内存 / MySQL）
   - Agent 工具调用
   - MCP Server/Client
5. 前端 `index.html` 演示页可正常调用

### 1.5 不在范围

- 新功能开发
- 生产部署 / CI/CD 配置
- API Key 安全治理（demo 中硬编码，后续单独处理）

---

## 2. 架构变更概览

### 2.1 平台层变更

```
Boot 3.5.14  ──►  Boot 4.1.x
    │                  │
    ├── Spring Framework 6 ──► 7（JSpecify null safety）
    ├── Jackson 2 ──► 3（com.fasterxml → tools.jackson）
    ├── Spring Security 6 ──► 7
    └── 模块化 starter（部分 starter 拆分/重命名）

Spring AI 1.1.7  ──►  2.0.0
    │
    ├── Options 类：setter 移除，统一 Builder 模式
    ├── PromptChatMemoryAdvisor 移除
    ├── MCP SDK 1.1.x ──► 2.0.0
    └── 部分 auto-configuration 包路径调整
```

### 2.2 应用架构（升级后保持不变）

业务分层不变：Controller → Service/Config → Spring AI（ChatClient / Advisor / VectorStore / Tool / MCP）。

关键设计决策保留：
- Milvus 懒加载（`@SpringBootApplication(exclude = MilvusVectorStoreAutoConfiguration.class)`）
- MCP 同 JVM 延迟初始化（`ApplicationReadyEvent` + `@Order`）
- RAG 冷启动索引开关（`reindex-on-startup`）

---

## 3. 迁移策略（方案 A：OpenRewrite + 手动）

### 3.1 阶段划分

| 阶段 | 内容 | 产出 |
|------|------|------|
| **P0 准备** | JDK 21、备份分支、确认外部依赖（MySQL/Milvus/API Key） | 迁移分支 |
| **P1 依赖升级** | 手动更新 `pom.xml`：Boot 4.1.x、AI 2.0.0 BOM、Java 21、SpringDoc 3.0.x | 可解析的 POM |
| **P2 OpenRewrite** | 运行 Boot 4 + Spring AI 2.0 迁移配方 | 批量 import/API 变更 |
| **P3 手动修补** | 编译错误、Advisor 替换、MCP 验证、配置项核对 | 编译通过 |
| **P4 验证** | 单元测试 + 模块冒烟 + 前端联调 | 验收清单 |
| **P5 文档** | 更新 README 版本号与迁移说明 | 文档同步 |

### 3.2 OpenRewrite 执行顺序

**步骤 1 — Spring Boot 4 迁移**

```bash
# dry-run 预览变更
mvn -U org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.activeRecipes=org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0

# 确认后执行
mvn org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.activeRecipes=org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0
```

该配方包含：Boot 4 依赖升级、Jackson 3 包名迁移、配置属性重命名、SpringDoc 3.0 升级、Framework 7 / Security 7 对齐。

**步骤 2 — Spring AI 2.0 迁移**

```bash
mvn org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.configLocation=https://raw.githubusercontent.com/spring-projects/spring-ai/refs/heads/main/src/rewrite/migrate-to-2-0-0-M3.yaml \
  -Drewrite.activeRecipes=org.springframework.ai.migration.MigrateToSpringAI200M3
```

> 注：若 GA 版有更新的 rewrite 配方，优先使用官方 `upgrade-notes` 中推荐的最新 YAML。

**步骤 3 — 编译与迭代**

```bash
mvn clean compile
mvn test
```

---

## 4. 模块级变更清单

### 4.1 pom.xml

| 变更项 | 说明 |
|--------|------|
| `spring-boot-starter-parent` | 3.5.14 → **4.1.x** |
| `java.version` | 17 → **21** |
| `spring-ai.version` | 1.1.7 → **2.0.0** |
| `springdoc-openapi-starter-webmvc-ui` | 2.8.9 → **3.0.x** |
| Spring AI starter 坐标 | 验证 2.0 下 artifactId 是否 rename（参考 BOM） |

现有 Spring AI 依赖（预期仍可用，需编译验证）：

- `spring-ai-starter-model-deepseek`
- `spring-ai-starter-model-zhipuai`
- `spring-ai-starter-model-chat-memory-repository-jdbc`
- `spring-ai-starter-vector-store-milvus`
- `spring-ai-advisors-vector-store`
- `spring-ai-rag`
- `spring-ai-starter-mcp-server-webmvc`
- `spring-ai-starter-mcp-client`

### 4.2 PromptChatMemoryAdvisor 移除（必改）

**影响文件**：

- `config/MysqlMemoryConfig.java` — 删除 `mysqlPromptChatMemoryAdvisor` Bean
- `service/MysqlMemoryTripAgentService.java` — 移除 `prompt` 模式分支，统一使用 `MessageChatMemoryAdvisor`
- `static/index.html` — 更新文案（不再宣传 PromptChatMemoryAdvisor 双模式）

**迁移方式**：

```java
// Before
PromptChatMemoryAdvisor.builder(chatMemory).build()

// After
MessageChatMemoryAdvisor.builder(chatMemory).build()
```

API 层保留 `memoryType` 参数时可做兼容映射：`prompt` → `message`（行为略有差异：message 模式将历史作为 chat messages 注入，而非 system prompt 文本）。

### 4.3 Jackson 3（Boot 4 自动处理 + 少量手动）

**影响文件**：

- `controller/ChatController.java` — `com.fasterxml.jackson.databind.ObjectMapper`

**处理方式**：

- 优先依赖 Boot 4 自动配置的 `ObjectMapper`/`JsonMapper` 注入，无需手动 new
- OpenRewrite Boot 4 配方应自动迁移 import；若残留，改为 `tools.jackson.databind.json.JsonMapper` 或继续使用 Spring 注入的 `ObjectMapper` bean

### 4.4 MCP SDK 2.0

**影响文件**：

- `mcp/client/config/McpClientInitializer.java` — `io.modelcontextprotocol.client.McpSyncClient`
- `mcp/server/config/McpServerConfig.java` — `io.modelcontextprotocol.server.McpServerFeatures`

**验证点**：

- `McpSyncClient.initialize()` / `listTools()` API 是否变更
- `McpServerFeatures.SyncToolSpecification` 与 `McpToolUtils.toSyncToolSpecifications()` 兼容性
- MCP 配置属性是否 rename（对照 Spring AI 2.0 upgrade-notes）
- 同 JVM Server/Client 初始化顺序（`@Order(1)` / `@Order(2)`）是否仍有效

**已知 SDK 2.0 变更**（需运行时验证）：

- Server 端 tool input 默认开启校验
- HTTP transport 移除 `customizeRequest()`
- Spring 专用 transport 已迁入 `org.springframework.ai`（本项目使用 starter，影响较小）

### 4.5 无需改动的模块（预期）

以下模块使用 Builder 模式，与 Spring AI 2.0 方向一致，OpenRewrite 后编译验证即可：

| 模块 | 关键 API |
|------|---------|
| Chat / Agent | `ChatClient.Builder`, `@Tool` |
| RAG | `QuestionAnswerAdvisor`, `RetrievalAugmentationAdvisor`, `VectorStoreDocumentRetriever` |
| Embedding | `EmbeddingModel` |
| Memory（内存版） | `MessageChatMemoryAdvisor`, `MessageWindowChatMemory` |
| Logging | `SimpleLoggerAdvisor` |
| Milvus 懒加载 | `@Lazy VectorStore`, 手动 `@Bean` |

### 4.6 Demo2Application

保留 `exclude = MilvusVectorStoreAutoConfiguration.class`。升级后验证 auto-configuration 类全限定名是否变化；若 rename，同步更新 exclude。

### 4.7 application.properties

逐项对照 Spring AI 2.0 / Boot 4 upgrade-notes 验证以下配置项：

| 配置前缀 | 风险 |
|---------|------|
| `spring.ai.deepseek.*` | Options 与 Properties 分离，setter 移除 |
| `spring.ai.zhipuai.*` | 同上 |
| `spring.ai.vectorstore.milvus.*` | auto-config 包路径可能变化 |
| `spring.ai.chat.memory.repository.jdbc.*` | JDBC ChatMemory 配置 |
| `spring.ai.mcp.server.*` / `spring.ai.mcp.client.*` | MCP 2.0 配置可能调整 |
| `spring.jackson.*` | 若有自定义 Jackson 配置，迁移到 `spring.jackson.json.*` |

当前项目无自定义 Jackson 配置，风险较低。

### 4.8 README 与前端

- README：技术栈版本号、前置条件（JDK 21）、迁移说明
- `index.html`：MySQL Agent 模块描述中 PromptChatMemoryAdvisor 相关文案

---

## 5. 数据流与兼容性

### 5.1 对外 API 契约

所有 REST 路径、请求/响应 JSON 结构保持不变。流式聊天 SSE 格式不变（`ChatResponse` JSON chunk）。

### 5.2 持久化数据

- MySQL `chat_memory` 表：Spring AI JDBC ChatMemory schema 若未变，数据可复用；若 schema 变更，需对照 2.0 DDL 迁移
- Milvus collection：embedding 维度（1024）与 metric 不变，索引数据可复用

### 5.3 行为差异（已知、可接受）

| 场景 | 1.x 行为 | 2.0 行为 | 处理 |
|------|---------|---------|------|
| `memoryType=prompt` | PromptChatMemoryAdvisor 注入 system prompt | 统一为 MessageChatMemoryAdvisor | API 参数保留，内部映射 |
| MCP tool 校验 | 较宽松 | SDK 2.0 默认开启 input 校验 | 确保 `@Tool` 参数 schema 正确 |

---

## 6. 错误处理

- 编译阶段：逐模块修复，优先 pom → 核心 config → controller/service → MCP
- 启动失败：检查 auto-configuration exclude、MCP 循环依赖、Milvus 连接
- 运行时 API 失败：保留现有 try/catch + 友好错误消息模式，不引入新异常体系
- OpenRewrite 误改：通过 `git diff` 审查，必要时手动 revert 单文件

---

## 7. 测试计划

### 7.1 自动化

| 测试 | 命令 | 期望 |
|------|------|------|
| 编译 | `mvn clean compile` | BUILD SUCCESS |
| 上下文加载 | `mvn test` | `contextLoads` 通过 |

### 7.2 冒烟清单（需外部服务）

| # | 模块 | 接口 | 前置 |
|---|------|------|------|
| 1 | 聊天 | `POST /ai/chat` | DeepSeek API Key |
| 2 | 流式 | `POST /ai/chatStream` | DeepSeek API Key |
| 3 | Embedding | `POST /ai/embedding` | 智谱 API Key |
| 4 | RAG 基础 | `GET /rag/ask` | 智谱 API Key |
| 5 | RAG 优化 | `GET /rag/optimized/ask` | 智谱 + Milvus |
| 6 | 电商精准 | `GET /ecommerce/service/chat/precise` | 智谱 + Milvus |
| 7 | 电商增强 | `GET /ecommerce/service/chat/enhanced` | 智谱 + Milvus |
| 8 | Agent 无记忆 | `GET /agent/trip/plan` | DeepSeek |
| 9 | Agent 内存 | `GET /agent/trip/plan-with-memory` | DeepSeek |
| 10 | Agent MySQL | `GET /agent/mysql/trip/plan` | DeepSeek + MySQL |
| 11 | 工具调用 | `GET /agent/tool/plan` | DeepSeek |
| 12 | MCP 聊天 | `GET /mcp/client/chat` | DeepSeek + 应用完全启动 |
| 13 | MCP 工具列表 | `GET /mcp/client/tools` | 应用完全启动 |
| 14 | Swagger | `GET /swagger-ui.html` | 无 |
| 15 | 前端 | `http://localhost:8081` | 无 |

### 7.3 Jackson 3 专项

- 流式聊天 SSE 返回的 JSON 字段顺序/格式与升级前一致
- 若有集成测试依赖 JSON 快照，需更新快照

---

## 8. 回滚方案

1. 迁移在独立 Git 分支进行（如 `feature/spring-ai-2-upgrade`）
2. 每阶段完成后 commit，便于 bisect
3. 若升级阻塞，回退分支即可；MySQL/Milvus 数据无破坏性变更时可无缝回退

---

## 9. 风险与缓解

| 风险 | 等级 | 缓解 |
|------|------|------|
| Jackson 3 序列化行为差异 | 高 | OpenRewrite + 流式接口冒烟测试 |
| Spring AI 2.0 starter 坐标变更 | 中 | 对照 BOM，编译验证 |
| MCP SDK 2.0 API 不兼容 | 中 | 单独验证 MCP 模块，保留初始化顺序 |
| Milvus auto-config 类路径变化 | 低 | 编译期即可发现，更新 exclude |
| SpringDoc 3.0 与 Swagger UI 路径变化 | 低 | OpenRewrite 配方含 SpringDoc 升级 |
| DeepSeek/智谱 starter 2.0 兼容性 | 中 | 编译 + 聊天/embedding 冒烟 |

---

## 10. 实施顺序摘要

```
P0 准备（JDK 21 + 分支）
  ↓
P1 pom.xml 手动升级（Boot 4.1 / AI 2.0 / Java 21 / SpringDoc 3）
  ↓
P2 OpenRewrite：Boot 4 → Spring AI 2.0
  ↓
P3 手动修补：PromptChatMemoryAdvisor / MCP / Milvus exclude / 编译错误
  ↓
P4 mvn test + 冒烟清单
  ↓
P5 README / index.html 更新
```

---

## 附录 A：受影响文件清单

| 文件 | 变更类型 |
|------|---------|
| `pom.xml` | 依赖版本 |
| `Demo2Application.java` | 可能更新 exclude 类名 |
| `config/MysqlMemoryConfig.java` | 删除 PromptChatMemoryAdvisor Bean |
| `service/MysqlMemoryTripAgentService.java` | 统一 MessageChatMemoryAdvisor |
| `controller/ChatController.java` | Jackson 3 import |
| `mcp/client/config/McpClientInitializer.java` | MCP SDK 验证 |
| `mcp/server/config/McpServerConfig.java` | MCP SDK 验证 |
| `resources/application.properties` | 配置项核对 |
| `resources/static/index.html` | 文案更新 |
| `README.md` | 版本与说明更新 |
| 其余 ~30 Java 文件 | OpenRewrite 自动 + 编译验证 |
