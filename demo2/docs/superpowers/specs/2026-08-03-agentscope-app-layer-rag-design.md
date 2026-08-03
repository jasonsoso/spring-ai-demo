# AgentScope Harness 应用层 RAG 设计规范

**日期**: 2026-08-03  
**项目**: spring-ai-demo / demo2  
**状态**: 已确认，待实现  
**修订**: 2026-08-03 — 临时改用废弃的 `ReActAgent` builder RAG API（`.knowledge()` / `.ragMode()`），官方 v2 API 落地后再迁回  
**前置**: [2026-07-16-agentscope-harness-web-design.md](./2026-07-16-agentscope-harness-web-design.md)；[2026-07-22-agentscope-postgres-session-design.md](./2026-07-22-agentscope-postgres-session-design.md)  
**官方参考**:
- [RAG Knowledge Base overview](https://java.agentscope.io/v2/en/integration/rag/overview.html)
- [Simple Knowledge](https://java.agentscope.io/v2/en/integration/rag/simple.html)
- [V1 迁移指南 · B.5 RAG 模块](https://java.agentscope.io/v2/zh/docs/change-log.html)

---

## 1. 背景与目标

### 1.1 问题

demo2 已有完整 **Spring AI** RAG（基础版 / Milvus / 电商客服），但 **AgentScope Harness Tab** 没有向量检索增强问答示例。  
AgentScope Java 2.0 的 `Knowledge` / `RAGMode` / `KnowledgeRetrievalTools` / `GenericRAGHook` 已标记 `@Deprecated(forRemoval = true)`；`HarnessAgent.Builder` **不暴露** `.knowledge()` / `.ragMode()`。v2 知识库 API 重写进行中，**无公开 ETA**；中文迁移指南写明废弃面在 **2.0.x 期间保留**，清理更可能在未来大版本（如 2.1）。

### 1.2 目标

1. 在现有 **AgentScope Harness Tab** 上提供可切换的 RAG 演示（`NONE` / `GENERIC` / `AGENTIC`）
2. 检索引擎用 **`agentscope-extensions-rag-simple`**（`SimpleKnowledge` + `PgVectorStore`）
3. Agent 接线 **临时调用废弃的 builder RAG API**（在 `ReActAgent.builder()` 上 `.knowledge()` / `.ragMode()` / `.retrieveConfig()`），再经 `HarnessAgent.Builder.fromAgent(...)` 进入 Harness；官方新 API 后再改
4. 向量库复用现有 **`demo2-agentscope-postgres`**（升级为带 pgvector 的镜像），不另起 Postgres
5. 新建一份与研发助手人设匹配的短知识库文档
6. 将 Knowledge 装配收口到单一配置/服务，便于日后替换

### 1.3 已确认决策

| 维度 | 选择 |
|------|------|
| 入口 | 挂进现有 AgentScope Harness Tab（非独立 Tab） |
| 知识库内容 | 新建研发/AgentScope 约定文档（非户外安全指南） |
| 模式 | `NONE` / `GENERIC` / `AGENTIC`，前端可切换；默认 `NONE` |
| 向量存储 | PgVector，复用 `app.agentscope.datasource` 对应实例 |
| 检索引擎 | `agentscope-extensions-rag-simple`（临时） |
| Agent 接线 | **临时使用**废弃 `ReActAgent.Builder.knowledge/ragMode/retrieveConfig`；经 `fromAgent` 进入 Harness |
| Embedding | OpenAI 兼容接口指向智谱（与现有 `ZHIPUAI_API_KEY` / embedding-2 对齐） |
| PG 容器 | 同一 compose service；镜像改为 `pgvector/pgvector:pg16` + `CREATE EXTENSION vector` |

### 1.4 非目标

- 不改 Spring AI 三套 RAG Tab / 接口
- 不引入 Bailian / Dify / RAGFlow 托管知识库
- 不把 RAG 绑进沙箱文件系统或 Skills 渐进加载（本版不做）
- 不等待官方 v2 RAG 再交付例子
- 不改变现有 SSE 事件模型与确认/权限主链路语义（仅请求体多一个可选字段）

---

## 2. 架构

### 2.1 为何不能直接写 `HarnessAgent.builder().knowledge(...)`

`HarnessAgent.Builder` 公开 API **没有** `knowledge` / `ragMode` / `retrieveConfig`（RAG 在 2.0 废弃后未再转发）。  
`HarnessAgent.Builder.fromAgent(ReActAgent)` 也不会拷贝 RAG 字段本身，但会拷贝：

- `toolkit.copy()` — AGENTIC 下 `configureRAG` 注册的 `retrieve_knowledge` 工具会随之带上  
- `hooks` — GENERIC 下挂上的 `GenericRAGHook` 会随之带上  

因此本版接线为：

```
SimpleKnowledge + PgVectorStore + Embedding
        │
        ▼
ReActAgent.builder()
  .knowledge(knowledge)
  .ragMode(GENERIC | AGENTIC)   // 废弃 API，临时使用
  .retrieveConfig(...)
  .toolkit / model / sysPrompt / ...
  .build()                      // 内部 configureRAG → toolkit 或 hooks
        │
        ▼
HarnessAgent.Builder.fromAgent(seed)
  .workspace / filesystem / permission / memory / plan...
  .build()
```

`NONE`：不走 seed RAG，保持现有 `HarnessAgent.builder()` 装配路径（零检索）。

### 2.2 请求流

```
AgentScope Tab
  ragMode = NONE | GENERIC | AGENTIC
        │
        ▼
POST /agentscope/dev-agent/ask  (DevAgentRequest + ragMode)
        │
        ▼
DevAgentService.ask
  → 按 ragMode 选择对应 HarnessAgent 实例
        │
        ▼
AgentscopeDevAgentRegistry / 缓存的 3 套 Agent
  NONE     → 现有无 Knowledge 的 HarnessAgent
  GENERIC  → fromAgent(seed with RAGMode.GENERIC)
  AGENTIC  → fromAgent(seed with RAGMode.AGENTIC)
        │
        ▼
共享 AgentscopeRagKnowledge（SimpleKnowledge Bean）
```

### 2.3 模式语义

| `ragMode` | 行为 |
|-----------|------|
| `NONE`（默认） | 现网路径；无 Knowledge、无检索工具/Hook |
| `GENERIC` | 官方 Generic 模式：每轮推理前 Hook 自动检索注入（`RAGMode.GENERIC`） |
| `AGENTIC` | 官方 Agentic 模式：模型按需调用 `retrieve_knowledge`（`RAGMode.AGENTIC`） |

说明：官方枚举名为 **`GENERIC`**（不是 STATIC）。前端文案可写「自动注入」，请求/枚举用 `GENERIC`。

### 2.4 多实例与沙箱

- `ragMode` 在 builder 期固定，**不能**在单例 Agent 上按请求改 mode → 需 **最多 3 个 HarnessAgent**（可懒加载缓存）  
- 共享同一 `Knowledge`、同一 `ActiveSandboxRegistry` / DataSource  
- 现有「单 HarnessAgent 沙箱勿并发」约束扩展为：**同一 agent 实例内**仍串行；不同 mode 的实例尽量避免并行沙箱 ask（Service 层可继续用全局锁或按 session 串行，实现时与现 `DevAgentService` 对齐）

---

## 3. 组件与文件改动

| 动作 | 路径 | 说明 |
|------|------|------|
| 依赖 | `demo2/pom.xml` | `agentscope-extensions-rag-simple`（BOM 对齐） |
| 改 | `docker/agentscope-postgres/docker-compose.yml` | `image: pgvector/pgvector:pg16` |
| 增 | init SQL 或启动探测 | `CREATE EXTENSION IF NOT EXISTS vector` |
| 增 | `resources/agentscope-dev-knowledge.txt` | 研发短知识库，`----` 切块 |
| 增 | `agentscope/rag/AgentscopeRagProperties.java` | top-K、阈值、文件、reindex、collection、维度等 |
| 增 | `agentscope/rag/AgentscopeRagMode.java` | `NONE` / `GENERIC` / `AGENTIC` |
| 增 | `agentscope/rag/AgentscopeRagKnowledgeConfig.java`（名可调） | 装配 `SimpleKnowledge` + 入库；降级时 Optional 空 |
| 改 | `AgentScopeConfig` | 抽出可按 `RAGMode` 构建的方法；`NONE` 保持现状；GENERIC/AGENTIC 走 `ReActAgent` seed + `fromAgent` |
| 增 | `AgentscopeDevAgentRegistry`（或等价） | 按 mode 返回/缓存 HarnessAgent |
| 改 | `DevAgentRequest` | 可选 `ragMode` |
| 改 | `DevAgentService` | 按 mode 选 Agent；不再在 Service 里手工拼 GENERIC 上下文 |
| 改 | `application.properties` | `app.agentscope.rag.*` |
| 改 | 前端 Tab | ragMode 下拉 + 示例问题 |
| 增 | 单测 | mode 解析；registry 选择；Knowledge 不可用时 GENERIC/AGENTIC 仍可 ask |

**本版采用（临时）**：

- `ReActAgent.builder().knowledge(...).ragMode(...).retrieveConfig(...)`  
- 依赖框架 `configureRAG` 产生的 `KnowledgeRetrievalTools` / `GenericRAGHook`  
- `@SuppressWarnings("deprecation")` 集中在装配类，并注释指向本规范 §8  

**本版不采用**：

- Service 层手工拼装 GENERIC 上下文（改由官方 Hook）  
- 自研 `KnowledgeRetrieveTool`（改由官方工具名 `retrieve_knowledge`；权限列表放行该名）

---

## 4. 配置与基础设施

### 4.1 Docker

| 项 | 值 |
|----|-----|
| 镜像 | `pgvector/pgvector:pg16`（替换 `postgres:16`） |
| 容器 / 端口 / 库 | 不变：`demo2-agentscope-postgres` / `5432` / `agentscope` |
| 扩展 | `CREATE EXTENSION IF NOT EXISTS vector` |

已有 volume 异常时开发环境允许 `down -v` 重建（文档注明）。

### 4.2 应用配置（建议键名）

```properties
app.agentscope.rag.enabled=true
app.agentscope.rag.knowledge-file=agentscope-dev-knowledge.txt
app.agentscope.rag.top-k=3
app.agentscope.rag.similarity-threshold=0.3
app.agentscope.rag.reindex-on-startup=false
app.agentscope.rag.collection-name=agentscope_dev_knowledge
app.agentscope.rag.embedding-dimensions=1024
```

Embedding 对接 `OpenAITextEmbedding` + 智谱兼容 base URL / API Key（与现有环境变量对齐，实现时写死属性绑定）。

### 4.3 入库策略

- `reindex-on-startup=true`：清空 collection 后重建  
- `false`：仅空库时首次入库  
- 知识文件 `----` 切块

### 4.4 降级

| 失败点 | 行为 |
|--------|------|
| PG / pgvector / Knowledge 装配失败 | WARN；`rag.enabled` 等效不可用；`GENERIC`/`AGENTIC` 请求 **回退 NONE agent** 并打 WARN |
| Embedding 单次失败 | 框架/检索返回空；不拖垮进程 |
| `rag.enabled=false` | 仅注册 NONE agent |

---

## 5. API 与前端

### 5.1 请求

```java
public record DevAgentRequest(
        String userId,
        @NotBlank String sessionId,
        @NotBlank String message,
        String ragMode  // 可选：NONE | GENERIC | AGENTIC
) {}
```

缺省 / 非法 → `NONE`。Controller 与 SSE **不变**。

### 5.2 前端

- 下拉：关闭（NONE）/ 自动注入（GENERIC）/ 工具检索（AGENTIC）  
- 2～3 条命中知识库的示例问题  
- body 带 `ragMode`  
- AGENTIC 下工具事件应出现 `retrieve_knowledge`

---

## 6. 提示词与权限

- sysPrompt 追加：存在知识库时，约定类问题应使用检索（AGENTIC 下体现为调用 `retrieve_knowledge`）  
- 将 `retrieve_knowledge` 加入只读默认 ALLOW（与 `read_pom` 等一致）  
- GENERIC/AGENTIC 实例在沙箱开关两种形态下都应能构建（检索不依赖沙箱 FS）

---

## 7. 测试

| 用例 | 期望 |
|------|------|
| 缺省 ragMode | 走 NONE agent |
| GENERIC | 无用户显式调工具也能答约定问题（Hook 注入） |
| AGENTIC | 工具事件含 `retrieve_knowledge` |
| Knowledge 不可用 | 回退 NONE，ask 不崩 |
| 非法 ragMode | NONE |

---

## 8. 技术债与迁移触发条件

### 8.1 已知债

- 使用 `@Deprecated(forRemoval = true)` 的 `Knowledge` / `RAGMode` / builder 方法 / Hook / RetrievalTools  
- `HarnessAgent` 无一等 RAG API，依赖 `fromAgent` 间接带走 toolkit/hooks 副作用  
- 多 Agent 实例缓存增加装配复杂度  

官方：2.0.x 可调用；清理预期大版本（如 2.1）；v2 API「后续 minor」，**无 ETA**。

### 8.2 迁移策略（官方新 API 后）

1. 删除 `ReActAgent` seed 上的 `.knowledge/.ragMode`，改为新一等 API（若 Harness 已转发则直接挂 `HarnessAgent.Builder`）  
2. 尽量保留 `DevAgentRequest.ragMode`、前端、PgVector 数据与知识文件  
3. 若新 API 支持运行时切 mode，可收敛为单 Agent 实例  

### 8.3 文档义务

Tab/README 标明：本示例临时使用 2.0 废弃 RAG builder API + `fromAgent`；待官方 v2 知识库 API 替换。

---

## 9. 实现顺序建议

1. Docker 镜像与 `vector` 扩展  
2. 依赖 + Knowledge 装配/入库 + 降级  
3. 按 mode 构建 HarnessAgent（NONE / GENERIC / AGENTIC）+ Registry  
4. `DevAgentRequest` / `DevAgentService` 选 Agent  
5. 权限放行 `retrieve_knowledge` + 提示词  
6. 前端下拉与示例  
7. 手工验收三种 mode  

---

## 10. 验收标准

1. 默认 `NONE`：行为与改前一致  
2. `GENERIC`：自动检索增强，无需用户侧工具调用即可引用知识库  
3. `AGENTIC`：出现 `retrieve_knowledge` 工具事件，回答引用知识库  
4. Knowledge/PG 失败时：回退可聊，应用可启动  
5. 装配代码中**显式调用** `.knowledge(` / `.ragMode(`（集中、带 deprecation 抑制与迁移注释）  
6. **无**对已删除计划的「禁止 builder RAG」约束  
