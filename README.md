# spring-ai-demo

Spring AI 学习与实验仓库，包含两个独立子项目：基于 **Spring AI 1.x** 的稳定演示，以及面向 **Spring AI 2.x** 升级的实验沙箱。

## 子项目概览

| 子项目 | 端口 | Spring Boot | Spring AI | Java | 说明 |
|--------|------|-------------|-----------|------|------|
| [demo](demo/) | 8080 | 3.5.x | 1.1.7 | 17 | 原版演示，Swagger UI |
| [demo2](demo2/) | 8081 | 4.1.x | 2.0.0 | 21 | 升级实验，虚拟线程 + OpenTelemetry + Scalar 文档 |

两个子项目功能基本对齐，`demo2` 额外包含结构化输出与可观测性集成，并针对 Spring AI 2.0 API 变更做了适配。

## 功能模块

| 模块 | 路径前缀 | 说明 |
|------|----------|------|
| 基础聊天 | `/ai/chat`、`/ai/chatStream` | DeepSeek 同步 / 流式对话 |
| Embedding | `/ai/embedding`、`/ai/similarity` | 文本向量化与相似度计算 |
| 结构化输出 | `/ai/structured/*` | 结构化 JSON 输出（仅 demo2） |
| 基础 RAG | `/rag/ask` | 内存向量检索 |
| 优化 RAG | `/rag/optimized/ask` | Milvus 向量库检索 |
| 电商客服 | `/ecommerce/service/chat/*` | QuestionAnswerAdvisor + RetrievalAugmentationAdvisor |
| 旅行 Agent | `/agent/trip/*` | 单 Agent 行程规划 |
| 工具调用 Agent | `/agent/tool/plan` | Function Calling 行程规划 |
| 多 Agent | `/agent/multi/plan` | Supervisor 协调多子 Agent |
| MySQL 记忆 Agent | `/agent/mysql/trip/*` | JDBC 持久化聊天记忆 |
| MCP | `/mcp/client/*` | 同 JVM 内 MCP Server + Client |

启动后访问 `http://localhost:{port}/` 可使用内置 Web 测试页面。

## 环境要求

- JDK 17（demo）/ JDK 21（demo2）
- Maven 3.9+
- MySQL 8.x
- Docker（Milvus 向量库；demo2 可观测性栈可选）

### 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `DEEPSEEK_API_KEY` | DeepSeek 聊天 API Key | 空（必填） |
| `ZHIPUAI_API_KEY` | 智谱 Embedding API Key | 空（必填） |
| `DB_PASSWORD` | MySQL 密码 | `123456` |

### MySQL 初始化

分别创建独立数据库（与 `application.properties` 对应）：

```sql
CREATE DATABASE spring_ai_agent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE spring_ai_agent2 CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

ChatMemory 表结构由 Spring AI JDBC Repository 自动管理（`initialize-schema=never` 时需自行执行官方 schema 脚本）。

### Milvus（RAG 优化版 / 电商客服）

```bash
# demo 或 demo2 均可使用，共用同一 Milvus 实例、不同 collection
docker compose -f demo/docker/milvus/docker-compose.yml up -d
```

首次使用 RAG 优化版或电商客服时，将对应配置设为 `true` 完成向量入库：

```properties
rag.optimized.reindex-on-startup=true
ecommerce.reindex-on-startup=true
```

入库完成后改回 `false`，避免重复写入。

### 可观测性（仅 demo2，可选）

```bash
docker compose -f demo2/docker/observability/docker-compose.yml up -d
```

- Grafana：http://localhost:3000（默认 `admin/admin`）
- Prometheus 指标：http://localhost:8081/actuator/prometheus
- OTLP HTTP：`4318`

## 快速启动

```bash
# demo（Spring AI 1.x）
cd demo
./mvnw spring-boot:run

# demo2（Spring AI 2.x）
cd demo2
./mvnw spring-boot:run
```

Windows 使用 `mvnw.cmd` 替代 `./mvnw`。

## API 文档

| 子项目 | 文档地址 |
|--------|----------|
| demo | http://localhost:8080/swagger-ui.html |
| demo2 | http://localhost:8081/scalar |

## demo 与 demo2 主要差异

| 方面 | demo | demo2 |
|------|------|-------|
| 智谱 Embedding | `spring-ai-starter-model-zhipuai` | OpenAI 兼容 API（`spring-ai-starter-model-openai`） |
| 向量 Advisor | `spring-ai-advisors-vector-store` | `spring-ai-vector-store-advisor` |
| API 文档 | springdoc + Swagger UI | springdoc + Scalar |
| 虚拟线程 | — | `spring.threads.virtual.enabled=true` |
| 可观测性 | — | Actuator + Micrometer + OpenTelemetry |
| 数据库 | `spring_ai_agent` | `spring_ai_agent2` |
| Milvus Collection | `travel_safety_embedding` | `travel_safety_embedding2` |

## 项目结构

```
spring-ai-demo/
├── demo/                  # Spring AI 1.x 演示
│   ├── docker/milvus/     # Milvus Docker Compose
│   └── src/main/java/     # Controller / Agent / Service / Config
├── demo2/                 # Spring AI 2.x 升级沙箱
│   ├── docker/
│   │   ├── milvus/
│   │   └── observability/ # Grafana LGTM 栈
│   └── src/main/java/
└── README.md
```
