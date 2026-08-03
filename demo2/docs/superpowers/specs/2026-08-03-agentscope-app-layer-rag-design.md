# AgentScope Harness 应用层 RAG 设计规范

**日期**: 2026-08-03  
**项目**: spring-ai-demo / demo2  
**状态**: 已确认，待实现  
**前置**: [2026-07-16-agentscope-harness-web-design.md](./2026-07-16-agentscope-harness-web-design.md)；[2026-07-22-agentscope-postgres-session-design.md](./2026-07-22-agentscope-postgres-session-design.md)  
**官方参考**:
- [RAG Knowledge Base overview](https://java.agentscope.io/v2/en/integration/rag/overview.html)
- [Simple Knowledge](https://java.agentscope.io/v2/en/integration/rag/simple.html)
- [V1 迁移指南 · B.5 RAG 模块](https://java.agentscope.io/v2/zh/docs/change-log.html)

---

## 1. 背景与目标

### 1.1 问题

demo2 已有完整 **Spring AI** RAG（基础版 / Milvus / 电商客服），但 **AgentScope Harness Tab** 没有向量检索增强问答示例。  
AgentScope Java 2.0 的 `Knowledge` / `RAGMode` / `KnowledgeRetrievalTools` / `GenericRAGHook` 已标记 `@Deprecated(forRemoval = true)`，官方要求新代码不要依赖；`HarnessAgent.Builder` 也不再暴露 `.knowledge()` / `.ragMode()`。v2 知识库 API 重写进行中，尚无公开 ETA。

### 1.2 目标

1. 在现有 **AgentScope Harness Tab** 上提供可切换的 RAG 演示（`NONE` / `GENERIC` / `AGENTIC`）
2. 检索实现暂用 **`agentscope-extensions-rag-simple`**（`SimpleKnowledge` + `PgVectorStore`），但 **Agent 接入走应用层**（自定义 Tool + Service 拼装），不调用废弃的 builder RAG API
3. 向量库复用现有 **`demo2-agentscope-postgres`**（升级为带 pgvector 的镜像），不另起 Postgres
4. 新建一份与研发助手人设匹配的短知识库文档
5. 将检索边界收口到单一服务，便于官方 v2 RAG 落地后替换

### 1.3 已确认决策

| 维度 | 选择 |
|------|------|
| 入口 | 挂进现有 AgentScope Harness Tab（非独立 Tab） |
| 知识库内容 | 新建研发/AgentScope 约定文档（非户外安全指南） |
| 模式 | `NONE` / `GENERIC` / `AGENTIC`，前端可切换；默认 `NONE` |
| 向量存储 | PgVector，复用 `app.agentscope.datasource` 对应实例 |
| 检索引擎 | `agentscope-extensions-rag-simple`（临时） |
| Agent 接线 | **不用** `.knowledge()` / `.ragMode()` / `KnowledgeRetrievalTools` 作为挂载方式；自有 Tool + Service |
| Embedding | OpenAI 兼容接口指向智谱（与现有 `ZHIPUAI_API_KEY` / embedding-2 对齐） |
| PG 容器 | 同一 compose service；镜像改为 `pgvector/pgvector:pg16` + `CREATE EXTENSION vector` |

### 1.4 非目标

- 不改 Spring AI 三套 RAG Tab / 接口
- 不引入 Bailian / Dify / RAGFlow 托管知识库
- 不把 RAG 绑进沙箱文件系统或 Skills 渐进加载（Skills 是另一条官方知识路径，本版不做）
- 不等待官方 v2 RAG 再交付例子
- 不改变现有 SSE 事件模型与确认/权限主链路语义（仅请求体多一个可选字段）

---

## 2. 架构

```
AgentScope Tab
  ragMode = NONE | GENERIC | AGENTIC
        │
        ▼
POST /agentscope/dev-agent/ask  (DevAgentRequest + ragMode)
        │
        ▼
DevAgentService.ask
  ├─ NONE     → message 原样
  ├─ GENERIC  → AgentscopeRagService.retrieve → 拼装上下文 → message'
  └─ AGENTIC  → message 原样；依赖 Toolkit 中的 retrieve_knowledge
        │
        ▼
HarnessAgent（现有 Bean）
  toolkit += KnowledgeRetrieveTool  →  AgentscopeRagService.retrieve
        │
        ▼
AgentscopeRagService
  SimpleKnowledge
    ├─ OpenAITextEmbedding（智谱兼容）
    └─ PgVectorStore（同一 agentscope PG）
```

模式语义：

| `ragMode` | 行为 |
|-----------|------|
| `NONE`（默认） | 与现网完全一致，零检索 |
| `GENERIC` | Service 在调用 Agent 前检索 Top-K，将片段包进用户消息（`【知识库检索】…【用户问题】`） |
| `AGENTIC` | 系统提示引导：涉及项目约定/知识库问答时先调用 `retrieve_knowledge`；工具轨迹可见检索 |

---

## 3. 组件与文件改动

| 动作 | 路径 | 说明 |
|------|------|------|
| 依赖 | `demo2/pom.xml` | 增加 `agentscope-extensions-rag-simple`（版本由 `agentscope-bom` 管理）；按需补 `pgvector` JDBC 侧依赖（以扩展模块传递依赖为准） |
| 改 | `docker/agentscope-postgres/docker-compose.yml` | `image: pgvector/pgvector:pg16`；注释说明需重建/迁移卷时注意 |
| 增（可选） | `docker/agentscope-postgres/init/` 或启动探测 SQL | `CREATE EXTENSION IF NOT EXISTS vector` |
| 增 | `src/main/resources/agentscope-dev-knowledge.txt` | 短知识库：会话、沙箱、Plan Mode、ragMode 说明等，用清晰分隔符切块 |
| 增 | `agentscope/rag/AgentscopeRagProperties.java` | top-K、阈值、知识文件、reindex、表/集合名、embedding 模型与维度 |
| 增 | `agentscope/rag/AgentscopeRagService.java` | 初始化 Knowledge、入库、retrieve、拼装 GENERIC 上下文；降级为空结果 |
| 增 | `agentscope/rag/AgentscopeRagMode.java` | 枚举 `NONE` / `GENERIC` / `AGENTIC` |
| 增 | `agentscope/tool/KnowledgeRetrieveTool.java` | `@Tool(name="retrieve_knowledge", readOnly=true)`，委托 `AgentscopeRagService` |
| 改 | `AgentScopeConfig` | 注册 Tool；只读工具名加入默认 ALLOW；沙箱模式下是否注册：本版 **始终注册**（检索走 PG/Embedding，不依赖沙箱 FS） |
| 改 | `DevAgentRequest` | 可选 `String ragMode`（缺省按 `NONE` 解析；非法值按 `NONE` + WARN） |
| 改 | `DevAgentService` | `ask` 分支：GENERIC 改写 message；AGENTIC 不改写 |
| 改 | `application.properties` | `app.agentscope.rag.*` 配置项 |
| 改 | `static/index.html` + `js/tabs/agentscope.js` | ragMode 下拉 + 2～3 个示例问题 |
| 增 | 单测 | mode 解析与 GENERIC 拼装；Tool 委托；Service 在 store 不可用时返回空 |

**明确禁止**：

- `HarnessAgent.builder().knowledge(...)` / `.ragMode(...)` / `.retrieveConfig(...)`
- 依赖 `GenericRAGHook` 实现 GENERIC
- 把废弃的 `KnowledgeRetrievalTools` 直接 `registerObject` 作为唯一方案（本版用自有 `KnowledgeRetrieveTool`，便于日后替换底层）

---

## 4. 配置与基础设施

### 4.1 Docker

路径：`demo2/docker/agentscope-postgres/docker-compose.yml`

| 项 | 值 |
|----|-----|
| 镜像 | `pgvector/pgvector:pg16`（替换原 `postgres:16`） |
| 容器名 / 端口 / 库 | 保持 `demo2-agentscope-postgres` / `5432` / `agentscope` |
| 扩展 | 首次连接执行 `CREATE EXTENSION IF NOT EXISTS vector` |

说明：已有 data volume 从纯 Postgres 换到 pgvector 镜像通常可兼容；若启动失败，文档注明 `down -v` 后重建（开发环境可接受）。

### 4.2 应用配置（建议键名）

```properties
app.agentscope.rag.enabled=true
app.agentscope.rag.knowledge-file=agentscope-dev-knowledge.txt
app.agentscope.rag.top-k=3
app.agentscope.rag.similarity-threshold=0.3
app.agentscope.rag.reindex-on-startup=false
app.agentscope.rag.collection-name=agentscope_dev_knowledge
app.agentscope.rag.embedding-dimensions=1024
# Embedding：复用智谱 OpenAI 兼容端点与 ZHIPUAI_API_KEY（具体属性名以实现时对接 OpenAITextEmbedding 为准）
```

### 4.3 入库策略

- `reindex-on-startup=true`：清空该 collection 后重新切分入库  
- `false`（默认）：仅当 collection 为空（或探测无向量）时首次入库  
- 知识文件使用明确分隔符（如 `----`）切块，与现有 Spring AI 基础版风格一致，便于人工维护

### 4.4 降级

| 失败点 | 行为 |
|--------|------|
| PG / pgvector 不可用 | WARN；`AgentscopeRagService` 标记不可用；retrieve 返回空；应用仍启动 |
| Embedding 调用失败 | 该次 retrieve 返回空 + 日志；不中断 ask 主流程 |
| `ragMode=GENERIC` 且检索空 | 仍发送原用户问题（或带「未命中知识库」短提示，二选一：本版选 **仍发送原问题**，避免干扰） |
| `ragMode=AGENTIC` 且检索空 | Tool 返回明确「未检索到相关内容」字符串 |

与现有 agentscope PG 会话降级策略对齐：基础设施失败不拖垮启动。

---

## 5. API 与前端

### 5.1 请求

`DevAgentRequest` 增加可选字段：

```java
public record DevAgentRequest(
        String userId,
        @NotBlank String sessionId,
        @NotBlank String message,
        String ragMode  // 可选：NONE | GENERIC | AGENTIC
) {}
```

- 缺省 / null / blank → `NONE`  
- 大小写不敏感解析  

Controller 路径与 SSE 事件类型 **不变**。

### 5.2 前端

AgentScope Harness Tab：

- 增加 `ragMode` 下拉（关闭 / 自动注入 GENERIC / 工具检索 AGENTIC）  
- 示例问题 2～3 条（能命中新建知识库，例如「Plan Mode 怎么进入」「ragMode GENERIC 和 AGENTIC 有什么区别」）  
- 发送 body 带上当前 `ragMode`  
- AGENTIC 模式下用户应能在现有工具事件 UI 中看到 `retrieve_knowledge`

---

## 6. 提示词与权限

- 在现有 `systemPrompt` 追加一小段（仅文档说明即可，不必按 mode 动态切换整段）：说明存在只读工具 `retrieve_knowledge`，回答「项目约定 / 本演示知识库」类问题时应先检索  
- `retrieve_knowledge` 加入只读默认 ALLOW 列表（与 `read_pom` 等一致），避免每次 HITL 确认  
- 沙箱开启时仍注册该 Tool（不依赖沙箱文件系统）

---

## 7. 测试

| 用例 | 期望 |
|------|------|
| `ragMode` 缺省 | 不调用 retrieve，message 不变 |
| GENERIC + 命中 | 发出的用户消息含知识库片段与原问题 |
| GENERIC + 服务不可用 | message 保持原问题；无异常中断 |
| AGENTIC Tool | 入参 query 到达 Service；返回拼接后的片段文本 |
| mode 非法字符串 | 视为 NONE |

集成/手工：PG+Embedding 可用时，前端切换三种模式各跑一条示例问题。

---

## 8. 技术债与迁移触发条件

### 8.1 已知债

- 依赖 `agentscope-extensions-rag-simple` 及 core 中已 `@Deprecated` 的 `Knowledge` 类型表面  
- 官方立场：新代码不应依赖；2.0.x 期间仍可调用；清理预期在未来大版本（如 2.1）；v2 API「后续 minor」上线，**无公开 ETA**

### 8.2 迁移策略

当官方发布替代 API 时：

1. 只改 `AgentscopeRagService`（及必要时 Tool 委托）  
2. 保持 `DevAgentRequest.ragMode`、前端、GENERIC 拼装契约不变  
3. 删除对本扩展的依赖与本规范中的「临时」标注  

### 8.3 文档义务

README / Tab 文案中注明：本示例为应用层接线 + 临时 Simple RAG 扩展；非 `HarnessAgent.knowledge/ragMode` 官方挂载。

---

## 9. 实现顺序建议

1. Docker 镜像与 `vector` 扩展  
2. 依赖 + `AgentscopeRagService` 入库/检索 + 单测（可 mock Knowledge）  
3. `KnowledgeRetrieveTool` + Config 注册/权限  
4. `DevAgentRequest` / `DevAgentService` mode 分支  
5. 前端下拉与示例问题  
6. 手工验收三种 mode  

---

## 10. 验收标准

1. 默认 `NONE`：行为与改前一致  
2. `GENERIC`：无需工具调用即可基于知识库回答约定类问题  
3. `AGENTIC`：工具事件中出现 `retrieve_knowledge`，回答引用知识库内容  
4. 停掉 PG 或 Embedding 失败时：应用可启动，ask 不崩  
5. 代码中无 `.knowledge(` / `.ragMode(` 调用  
