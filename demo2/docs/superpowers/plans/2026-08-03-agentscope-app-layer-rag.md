# AgentScope Harness RAG（废弃 builder API）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 AgentScope Harness Tab 上提供可切换的 `NONE` / `GENERIC` / `AGENTIC` 向量 RAG 演示：用 `agentscope-extensions-rag-simple` + PgVector，经废弃的 `ReActAgent.knowledge/ragMode` + `HarnessAgent.fromAgent` 接线。

**Architecture:** 共享 `SimpleKnowledge`（智谱 Embedding + 同一 agentscope PG 的 `PgVectorStore`）。按 mode 缓存最多 3 个 `HarnessAgent`：`NONE` 走现有 builder；`GENERIC`/`AGENTIC` 先 `ReActAgent.builder().knowledge().ragMode().build()`，再 `HarnessAgent.Builder.fromAgent(seed)` 叠加 Harness 配置。`DevAgentService` 按请求选 Agent；Knowledge 不可用时回退 `NONE`。

**Tech Stack:** Java 21, Spring Boot 4.1, AgentScope 2.0.0（`agentscope-harness` + `agentscope-extensions-rag-simple`）, PostgreSQL + pgvector, Reactor, 原生 HTML/JS

**设计规范:** [docs/superpowers/specs/2026-08-03-agentscope-app-layer-rag-design.md](../specs/2026-08-03-agentscope-app-layer-rag-design.md)

## Global Constraints

- **AgentScope**：`2.0.0`；RAG 类型 `@Deprecated`，装配处集中 `@SuppressWarnings("deprecation")` + 指向规范 §8
- **禁止**在 `HarnessAgent.Builder` 上调用不存在的 `.knowledge()` / `.ragMode()`；必须经 `ReActAgent` seed + `fromAgent`
- **官方枚举**：`RAGMode.GENERIC`（不是 STATIC）；应用枚举 `AgentscopeRagMode.NONE|GENERIC|AGENTIC`
- **PG**：复用 `demo2-agentscope-postgres`；镜像 `pgvector/pgvector:pg16`；不另起容器
- **API**：`POST /agentscope/dev-agent/ask` 可选 `ragMode`；SSE 事件模型不变；confirm 路径不变（Service 记 session 上次 mode）
- **默认**：缺省 / 非法 `ragMode` → `NONE`；行为与改前一致
- **降级**：Knowledge 装配失败 → WARN，GENERIC/AGENTIC 回退 NONE，应用可启动
- **不改** Spring AI 三套 RAG Tab
- **编译门禁**：`cd demo2 && .\mvnw.cmd -DskipTests compile`（使用 `demo2/.mvn/settings.xml`，本地仓 `D:\repository`）
- **YAGNI**：不做 Bailian/Dify、不做 Skills 替代、不做运行时单实例切 mode

---

## File Structure

| 文件 | 职责 |
|------|------|
| `demo2/pom.xml` | 加 `agentscope-extensions-rag-simple` |
| `demo2/docker/agentscope-postgres/docker-compose.yml` | 镜像改 pgvector |
| `demo2/docker/agentscope-postgres/init/01-pgvector.sql` | `CREATE EXTENSION vector` |
| `demo2/src/main/resources/agentscope-dev-knowledge.txt` | 研发短知识库 |
| `.../agentscope/rag/AgentscopeRagMode.java` | 模式枚举 + 解析 |
| `.../agentscope/rag/AgentscopeRagProperties.java` | `app.agentscope.rag.*` |
| `.../agentscope/rag/AgentscopeRagKnowledgeHolder.java` | 可选 Knowledge + RetrieveConfig；入库 |
| `.../agentscope/rag/AgentscopeRagConfiguration.java` | 装配 Holder；失败降级 |
| `.../agentscope/config/AgentscopeDevAgentRegistry.java` | 按 mode 取/缓存 HarnessAgent |
| `.../agentscope/config/AgentScopeConfig.java` | 抽出按 mode 构建；权限放行 `retrieve_knowledge` |
| `.../agentscope/model/DevAgentRequest.java` | + `ragMode` |
| `.../agentscope/service/DevAgentService.java` | 选 Agent；session mode 记忆供 confirm |
| `application.properties` | rag 配置项 |
| `static/index.html` + `js/tabs/agentscope.js` | 下拉 + 示例 + body |
| `demo2/README.md` | 简短说明 + 技术债 |
| 对应 `src/test/...` | 单测 |

**已确认 API（jar）：**

```java
PgVectorStore.builder()
  .jdbcUrl(url).username(u).password(p)
  .tableName(name).dimensions(1024).build();

OpenAITextEmbedding.builder()
  .apiKey(key).baseUrl(base).modelName("embedding-2").dimensions(1024).build();

SimpleKnowledge.builder().embeddingModel(em).embeddingStore(store).build();

RetrieveConfig.builder().limit(3).scoreThreshold(0.3).build();

DocumentMetadata.builder()
  .content(TextBlock.builder().text(chunk).build())
  .docId("agentscope-dev").chunkId("c-" + i).build();
new Document(metadata);

ReActAgent.builder().knowledge(k).ragMode(RAGMode.GENERIC|AGENTIC).retrieveConfig(cfg)...build();
HarnessAgent.Builder.fromAgent(seed).workspace(...).build();
```

---

### Task 1: Docker pgvector + Maven 依赖 + 知识库文件 + 配置键

**Files:**
- Modify: `demo2/pom.xml`
- Modify: `demo2/docker/agentscope-postgres/docker-compose.yml`
- Create: `demo2/docker/agentscope-postgres/init/01-pgvector.sql`
- Create: `demo2/src/main/resources/agentscope-dev-knowledge.txt`
- Modify: `demo2/src/main/resources/application.properties`

**Interfaces:**
- Produces: classpath 资源 `agentscope-dev-knowledge.txt`（`----` 分隔）；配置前缀 `app.agentscope.rag.*`

- [ ] **Step 1: 在 `pom.xml` 的 AgentScope 依赖区加入**

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-rag-simple</artifactId>
</dependency>
```

（版本由已有 `agentscope-bom` 管理，勿写死版本号。）

- [ ] **Step 2: 改 docker-compose**

将 `image: postgres:16` 改为 `image: pgvector/pgvector:pg16`。  
在 service 下增加 init 挂载（保留原有 data volume）：

```yaml
    volumes:
      - agentscope_pg_data:/var/lib/postgresql/data
      - ./init:/docker-entrypoint-initdb.d:ro
```

顶部注释追加：若旧 volume 无 vector 扩展，可执行 `CREATE EXTENSION IF NOT EXISTS vector;`；不行再 `down -v` 重建。

- [ ] **Step 3: 创建 `init/01-pgvector.sql`**

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

- [ ] **Step 4: 创建知识库文件**（至少 4 块，`----` 分隔），内容覆盖：会话 userId/sessionId、Plan Mode 进入步骤、ragMode NONE/GENERIC/AGENTIC 区别、沙箱 `project` 工作目录约束。

- [ ] **Step 5: `application.properties` 追加**

```properties
# AgentScope RAG（临时：extensions-rag-simple + 废弃 ReActAgent.knowledge/ragMode）
app.agentscope.rag.enabled=true
app.agentscope.rag.knowledge-file=agentscope-dev-knowledge.txt
app.agentscope.rag.top-k=3
app.agentscope.rag.similarity-threshold=0.3
app.agentscope.rag.reindex-on-startup=false
app.agentscope.rag.table-name=agentscope_dev_knowledge
app.agentscope.rag.embedding-dimensions=1024
app.agentscope.rag.embedding-api-key=${ZHIPUAI_API_KEY:}
app.agentscope.rag.embedding-base-url=https://open.bigmodel.cn/api/paas/v4
app.agentscope.rag.embedding-model=embedding-2
```

- [ ] **Step 6: 编译验证依赖可解析**

Run: `cd demo2 && .\mvnw.cmd -DskipTests compile`  
Expected: BUILD SUCCESS（或至少 dependency 可解析）

- [ ] **Step 7: Commit**

```bash
git add demo2/pom.xml demo2/docker/agentscope-postgres demo2/src/main/resources/agentscope-dev-knowledge.txt demo2/src/main/resources/application.properties
git commit -m "chore(demo2): add rag-simple dep, pgvector image, and knowledge file"
```

---

### Task 2: `AgentscopeRagMode` + `AgentscopeRagProperties`（TDD）

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/rag/AgentscopeRagMode.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/rag/AgentscopeRagProperties.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/agentscope/rag/AgentscopeRagModeTest.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/agentscope/rag/AgentscopeRagPropertiesBindingTest.java`

**Interfaces:**
- Produces:
  ```java
  public enum AgentscopeRagMode { NONE, GENERIC, AGENTIC;
    public static AgentscopeRagMode from(String raw); // null/blank/illegal → NONE
  }

  @ConfigurationProperties(prefix = "app.agentscope.rag")
  public record AgentscopeRagProperties(
      boolean enabled,
      String knowledgeFile,
      int topK,
      double similarityThreshold,
      boolean reindexOnStartup,
      String tableName,
      int embeddingDimensions,
      String embeddingApiKey,
      String embeddingBaseUrl,
      String embeddingModel) {}
  ```

- [ ] **Step 1: 写失败测试 `AgentscopeRagModeTest`**

```java
@Test
void nullOrBlank_isNone() {
    assertThat(AgentscopeRagMode.from(null)).isEqualTo(AgentscopeRagMode.NONE);
    assertThat(AgentscopeRagMode.from("  ")).isEqualTo(AgentscopeRagMode.NONE);
}

@Test
void parsesCaseInsensitive() {
    assertThat(AgentscopeRagMode.from("generic")).isEqualTo(AgentscopeRagMode.GENERIC);
    assertThat(AgentscopeRagMode.from("AGENTIC")).isEqualTo(AgentscopeRagMode.AGENTIC);
}

@Test
void illegal_isNone() {
    assertThat(AgentscopeRagMode.from("STATIC")).isEqualTo(AgentscopeRagMode.NONE);
}
```

- [ ] **Step 2: Run 确认失败**

Run: `cd demo2 && .\mvnw.cmd "-Dtest=AgentscopeRagModeTest" test`  
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现枚举与 Properties**（compact constructor 给默认值：enabled=true, topK=3, threshold=0.3, dimensions=1024, tableName=agentscope_dev_knowledge, knowledgeFile=agentscope-dev-knowledge.txt）

- [ ] **Step 4: Binding 测试**（仿 `DevAgentPropertiesBindingTest`，`ApplicationContextRunner` + `@EnableConfigurationProperties(AgentscopeRagProperties.class)`）

- [ ] **Step 5: 跑通测试并 Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/rag demo2/src/test/java/com/jason/demo/demo2/agentscope/rag
git commit -m "feat(demo2): add AgentscopeRagMode and rag properties"
```

---

### Task 3: `AgentscopeRagKnowledgeHolder` 装配与入库（可降级）

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/rag/AgentscopeRagKnowledgeHolder.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/rag/AgentscopeRagConfiguration.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/agentscope/rag/AgentscopeRagKnowledgeHolderTest.java`

**Interfaces:**
- Consumes: `AgentscopeRagProperties`, `AgentScopeDataSourceProperties`
- Produces:
  ```java
  public final class AgentscopeRagKnowledgeHolder {
    public boolean available();
    public Knowledge knowledgeOrNull();
    public RetrieveConfig retrieveConfig();
    static List<String> splitChunks(String content);
    static AgentscopeRagKnowledgeHolder unavailable(AgentscopeRagProperties rag);
    static AgentscopeRagKnowledgeHolder available(Knowledge k, RetrieveConfig cfg);
    static AgentscopeRagKnowledgeHolder forTests(Knowledge k, RetrieveConfig cfg); // available=true
  }
  ```
- Bean：`AgentscopeRagKnowledgeHolder`（永远非 null；`available()==false` 表示降级）

- [ ] **Step 1: 单测 `splitChunks`**

```java
@Test
void splitChunks_trimsAndDropsEmpty() {
    List<String> chunks = AgentscopeRagKnowledgeHolder.splitChunks("a\n----\n\n----\nb");
    assertThat(chunks).containsExactly("a", "b");
}
```

- [ ] **Step 2: 实现 `splitChunks` + Holder 工厂使切分 / unavailable 测试通过**

```java
@Test
void unavailable_hasNullKnowledge() {
    AgentscopeRagKnowledgeHolder h = AgentscopeRagKnowledgeHolder.unavailable(defaults());
    assertThat(h.available()).isFalse();
    assertThat(h.knowledgeOrNull()).isNull();
}
```

- [ ] **Step 3: 在 `AgentscopeRagConfiguration` 中装配**

```java
@Bean
AgentscopeRagKnowledgeHolder agentscopeRagKnowledgeHolder(
        AgentscopeRagProperties rag,
        AgentScopeDataSourceProperties ds) {
    if (!rag.enabled() || rag.embeddingApiKey() == null || rag.embeddingApiKey().isBlank()) {
        log.warn("AgentScope RAG disabled or missing ZHIPU embedding key");
        return AgentscopeRagKnowledgeHolder.unavailable(rag);
    }
    try {
        ensureVectorExtension(ds); // JDBC: CREATE EXTENSION IF NOT EXISTS vector
        PgVectorStore store = PgVectorStore.builder()
                .jdbcUrl(ds.url())
                .username(ds.username())
                .password(ds.password())
                .tableName(rag.tableName())
                .dimensions(rag.embeddingDimensions())
                .connectionTimeoutMs(ds.connectionTimeoutMs())
                .build();
        OpenAITextEmbedding embedding = OpenAITextEmbedding.builder()
                .apiKey(rag.embeddingApiKey())
                .baseUrl(rag.embeddingBaseUrl())
                .modelName(rag.embeddingModel())
                .dimensions(rag.embeddingDimensions())
                .build();
        SimpleKnowledge knowledge = SimpleKnowledge.builder()
                .embeddingModel(embedding)
                .embeddingStore(store)
                .build();
        ingestIfNeeded(knowledge, store, rag);
        return AgentscopeRagKnowledgeHolder.available(knowledge, RetrieveConfig.builder()
                .limit(rag.topK())
                .scoreThreshold(rag.similarityThreshold())
                .build());
    } catch (Exception ex) {
        log.warn("AgentScope RAG init failed, degrade: {}", ex.toString());
        return AgentscopeRagKnowledgeHolder.unavailable(rag);
    }
}
```

入库细节：
- `ClassPathResource(rag.knowledgeFile())` 读 UTF-8 → `splitChunks`
- 每块：`new Document(DocumentMetadata.builder().content(TextBlock.builder().text(chunk).build()).docId("agentscope-dev").chunkId("c-"+i).build())`
- `reindexOnStartup=true`：`store.getConnection()` 上 `TRUNCATE TABLE <tableName>`（表名仅来自配置）后 `addDocuments().block()`
- `false`：`SELECT COUNT(*)` 为 0 才入库

类上注解：

```java
@SuppressWarnings("deprecation")
// Temporary: agentscope-extensions-rag-simple + deprecated Knowledge until AgentScope v2 RAG APIs.
// See docs/superpowers/specs/2026-08-03-agentscope-app-layer-rag-design.md §8
```

- [ ] **Step 4: 跑测**

Run: `cd demo2 && .\mvnw.cmd "-Dtest=AgentscopeRagKnowledgeHolderTest,AgentscopeRagModeTest,AgentscopeRagPropertiesBindingTest" test`  
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(demo2): assemble SimpleKnowledge with PgVector and degrade safely"
```

---

### Task 4: `AgentscopeDevAgentRegistry` + 按 mode 构建 HarnessAgent

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentscopeDevAgentRegistry.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentscopeDevAgentRegistryTest.java`

**Interfaces:**
- Consumes: `AgentscopeRagKnowledgeHolder`
- Produces:
  ```java
  public final class AgentscopeDevAgentRegistry {
    public HarnessAgent get(AgentscopeRagMode mode);
    // GENERIC/AGENTIC 且 !holder.available() → 返回 NONE agent
  }
  ```

- [ ] **Step 1: 重构 `buildAgentscopeDevAgent`，增加 RAG 参数**

```java
@SuppressWarnings("deprecation")
private HarnessAgent buildAgentscopeDevAgent(
        ...,
        AgentscopeRagKnowledgeHolder ragHolder,
        AgentscopeRagMode ragMode)
```

分支：

1. **`NONE`，或请求 RAG 但 `!ragHolder.available()`**：现网 `HarnessAgent.builder()...build()`（无 knowledge）。
2. **`GENERIC` / `AGENTIC` 且 available**：组好 Toolkit 与 systemPrompt（追加：约定类问题请用知识库 / `retrieve_knowledge`），然后：

```java
ReActAgent seed = ReActAgent.builder()
    .name(properties.name())
    .sysPrompt(systemPrompt)
    .model(agentscopeDeepSeekModel)
    .toolkit(toolkit)
    .knowledge(ragHolder.knowledgeOrNull())
    .ragMode(ragMode == AgentscopeRagMode.GENERIC
            ? io.agentscope.core.rag.RAGMode.GENERIC
            : io.agentscope.core.rag.RAGMode.AGENTIC)
    .retrieveConfig(ragHolder.retrieveConfig())
    .build();

HarnessAgent.Builder builder = HarnessAgent.Builder.fromAgent(seed)
    .maxRetries(...)
    .workspace(...);
// 继续套用现有 sandbox / compaction / memory / permission / middleware / plan 分支
```

**硬约束：** `fromAgent` 已拷贝 toolkit/hooks；后续不要再 `.toolkit(新空Toolkit)` 覆盖掉 `retrieve_knowledge` / GenericRAGHook。

3. **`permissionContext`**：`READ_ONLY_TOOL_NAMES` 增加 `"retrieve_knowledge"`。

4. **Bean：**

```java
@Bean
AgentscopeDevAgentRegistry agentscopeDevAgentRegistry(..., AgentscopeRagKnowledgeHolder holder) {
    HarnessAgent none = build...(AgentscopeRagMode.NONE);
    return new AgentscopeDevAgentRegistry(
            none,
            mode -> build...(mode),
            holder);
}
```

Registry：`ConcurrentHashMap` 懒缓存 GENERIC/AGENTIC；`get(NONE)` 恒为 none。

可选保留 `@Bean HarnessAgent agentscopeDevAgent` 返回 `registry.get(NONE)` 以免其它注入点断裂。

- [ ] **Step 2: Registry 单测**

```java
@Test
void unavailableRag_fallsBackToNone() {
    HarnessAgent none = mock(HarnessAgent.class);
    AgentscopeRagKnowledgeHolder holder = AgentscopeRagKnowledgeHolder.unavailable(defaults());
    AtomicInteger builds = new AtomicInteger();
    var reg = new AgentscopeDevAgentRegistry(
            none, m -> { builds.incrementAndGet(); return mock(HarnessAgent.class); }, holder);
    assertThat(reg.get(AgentscopeRagMode.GENERIC)).isSameAs(none);
    assertThat(builds.get()).isZero();
}

@Test
void cachesAgentic() {
    // holder.forTests(...)；两次 get(AGENTIC) 同一实例，build 一次
}
```

- [ ] **Step 3: 跑测**

Run: `cd demo2 && .\mvnw.cmd "-Dtest=AgentscopeDevAgentRegistryTest" test`

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(demo2): build per-ragMode HarnessAgents via ReActAgent fromAgent"
```

---

### Task 5: `DevAgentRequest` + `DevAgentService` 选 Agent

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentRequest.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java`
- Modify or Create: `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceRagRoutingTest.java`

**Interfaces:**
- Consumes: `AgentscopeDevAgentRegistry`
- Produces: ask/confirm 使用 `registry.get(mode)`；`ConcurrentHashMap<String, AgentscopeRagMode> lastRagModeBySession`

- [ ] **Step 1: 扩展请求体**

```java
public record DevAgentRequest(
        String userId,
        @NotBlank String sessionId,
        @NotBlank String message,
        String ragMode) {}
```

- [ ] **Step 2: Service 注入 Registry**

`askAfterContext`：

```java
AgentscopeRagMode mode = AgentscopeRagMode.from(request.ragMode());
lastRagModeBySession.put(sessionKey(userId, sessionId), mode);
HarnessAgent agent = agentscopeDevAgentRegistry.get(mode);
Flux<DevAgentEvent> events = mapAgentEvents(
        userId, sessionId,
        agent.streamEvents(request.message(), invocation.runtimeContext()));
```

`confirmAfterContext`：

```java
AgentscopeRagMode mode = lastRagModeBySession.getOrDefault(
        sessionKey(userId, sessionId), AgentscopeRagMode.NONE);
HarnessAgent agent = agentscopeDevAgentRegistry.get(mode);
```

沙箱 `sandboxRequestLock`：**所有 mode 共用一把锁**。

- [ ] **Step 3: 单测** mock registry，verify ask 带 `ragMode=GENERIC` 时 `get(GENERIC)` 被调用

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(demo2): route DevAgent ask/confirm by ragMode"
```

---

### Task 6: 前端 Tab（下拉 + 示例 + 请求体）

**Files:**
- Modify: `demo2/src/main/resources/static/index.html`
- Modify: `demo2/src/main/resources/static/js/tabs/agentscope.js`

- [ ] **Step 1: 在 `agentscope-meta` 增加**

```html
<label class="agentscope-rag-mode">RAG
  <select id="agentscopeRagMode">
    <option value="NONE" selected>关闭（NONE）</option>
    <option value="GENERIC">自动注入（GENERIC）</option>
    <option value="AGENTIC">工具检索（AGENTIC）</option>
  </select>
</label>
```

欢迎文案补一句：可选 RAG 模式验证知识库（临时废弃 API）。

示例按钮（接现有 15）：

```html
<button type="button" onclick="fillAgentscopeSample(16)">示例：RAG·Plan Mode 约定</button>
<button type="button" onclick="fillAgentscopeSample(17)">示例：RAG·三种 ragMode</button>
<button type="button" onclick="fillAgentscopeSample(18)">示例：RAG·沙箱 project 路径</button>
```

- [ ] **Step 2: `fillAgentscopeSample` 增加 16/17/18**；选中时可把 `#agentscopeRagMode` 设为 `AGENTIC` 或 `GENERIC`

- [ ] **Step 3: DevAgent 发送 body 增加**

```javascript
const ragMode = (document.getElementById('agentscopeRagMode')?.value || 'NONE').trim();
body.ragMode = ragMode;
```

AG-UI 路径本版可不传 ragMode。

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(demo2): add AgentScope Tab ragMode switch and samples"
```

---

### Task 7: README + 验收

**Files:**
- Modify: `demo2/README.md`

- [ ] **Step 1: AgentScope 章节补充**

  - `agentscope-extensions-rag-simple` + 废弃 `ReActAgent.knowledge/ragMode` + `fromAgent`  
  - PG 需 pgvector；compose 路径  
  - Tab RAG 下拉；默认 NONE  
  - 官方 v2 后按规范 §8 迁移  

- [ ] **Step 2: 手工验收（有 Key + PG）**

  1. 重启 compose 与应用  
  2. NONE：老示例仍可用  
  3. GENERIC：示例 16，能引用知识库  
  4. AGENTIC：示例 17，工具事件含 `retrieve_knowledge`  
  5. 无 Embedding Key 或 PG 挂：应用可起，GENERIC 不崩  

- [ ] **Step 3: 跑相关单测**

Run: `cd demo2 && .\mvnw.cmd "-Dtest=AgentscopeRagModeTest,AgentscopeRagPropertiesBindingTest,AgentscopeRagKnowledgeHolderTest,AgentscopeDevAgentRegistryTest,DevAgentServiceRagRoutingTest" test`

- [ ] **Step 4: Commit**

```bash
git commit -m "docs(demo2): document AgentScope temporary RAG demo"
```

---

## Spec Coverage Checklist

| 规范条目 | Task |
|----------|------|
| rag-simple + PgVector 复用 PG | 1, 3 |
| 镜像 pgvector + extension | 1 |
| 新建知识库文件 | 1 |
| 废弃 builder API + fromAgent | 4 |
| NONE/GENERIC/AGENTIC 可切换 | 4, 5, 6 |
| 默认 NONE | 2, 5, 6 |
| Knowledge 失败回退 | 3, 4 |
| retrieve_knowledge ALLOW | 4 |
| DevAgentRequest.ragMode | 5 |
| confirm 不改 API（session 记 mode） | 5 |
| 前端下拉与示例 | 6 |
| 文档 / 技术债 | 7 |
| 不改 Spring AI RAG / SSE 模型 | 全局约束 |

## Self-Review Notes

- 无 TBD；Confirm 用 `lastRagModeBySession`（规范要求 API 不变）  
- API 名与 jar 一致：`PgVectorStore`、`OpenAITextEmbedding`、`RAGMode.GENERIC`  
- Task 4 明确禁止 `fromAgent` 后再覆盖 toolkit  
