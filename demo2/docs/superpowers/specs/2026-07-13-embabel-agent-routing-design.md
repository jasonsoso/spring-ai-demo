# Embabel Agent 自动选路 Demo 设计规范

**日期**: 2026-07-13  
**项目**: spring-ai-demo / demo2  
**状态**: 已实现

---

## 1. 背景与目标

### 1.1 需求

在 `demo2` 中集成 [Embabel Agent 0.5.0](https://github.com/embabel/embabel-agent)，复现微信文章《2. Embabel 上手实战：让 Java Agent 自己选择执行链路》的核心示例：同一 Web 入口接收两类请求，由 Embabel `Autonomy` 自动选择 Agent，再在选中 Agent 内执行 Action 链路。

参考文章：https://mp.weixin.qq.com/s/DghJktDEvPMfzm4f70acOA

### 1.2 已确认决策

| 维度 | 选择 |
|------|------|
| 集成方式 | 直接并入 `demo2` 主应用（方案 A） |
| Embabel 版本 | **0.5.0** |
| 模型 | **deepseek-v4-pro**（文章为 deepseek-v4-flash，按用户要求覆盖） |
| API 路径 | **`POST /embabel/agent/ask/stream`**（SSE 流式，主入口） |
| 同步 API | **`POST /embabel/agent/ask`**（可选，curl/Scalar 调试） |
| 端口 | **8081**（demo2 现有） |
| 前端 | 新增「Embabel 自动选路」Tab，**聊天气泡 + SSE 流式** |
| 执行模式 | **Closed 模式**：`Autonomy.chooseAndRunAgent()` 先选 Agent，再在 Agent 内跑 Action |
| DeepSeek 接入 | `embabel-agent-starter-openai-custom` + 已有 `DEEPSEEK_API_KEY` |

### 1.3 依赖管理（`dependencyManagement`）

Embabel 0.5.0 **无官方 BOM**，在 `demo2/pom.xml` 用 `dependencyManagement` 集中锁定版本（对齐现有 `spring-ai-bom` 做法）：

```xml
<properties>
    <embabel-agent.version>0.5.0</embabel-agent.version>
</properties>

<dependencyManagement>
    <dependencies>
        <!-- 现有 spring-ai-bom / agent-utils-bom ... -->

        <dependency>
            <groupId>com.embabel.agent</groupId>
            <artifactId>embabel-agent-starter</artifactId>
            <version>${embabel-agent.version}</version>
        </dependency>
        <dependency>
            <groupId>com.embabel.agent</groupId>
            <artifactId>embabel-agent-starter-webmvc</artifactId>
            <version>${embabel-agent.version}</version>
        </dependency>
        <dependency>
            <groupId>com.embabel.agent</groupId>
            <artifactId>embabel-agent-starter-openai-custom</artifactId>
            <version>${embabel-agent.version}</version>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>com.embabel.agent</groupId>
        <artifactId>embabel-agent-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>com.embabel.agent</groupId>
        <artifactId>embabel-agent-starter-webmvc</artifactId>
    </dependency>
    <dependency>
        <groupId>com.embabel.agent</groupId>
        <artifactId>embabel-agent-starter-openai-custom</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
</dependencies>
```

版本号只出现在 `<properties>` 与 `dependencyManagement`，`dependencies` 块不写 version。

### 1.4 成功标准

1. `mvn -DskipTests compile` 在 `demo2` 目录 **BUILD SUCCESS**
2. 配置 `DEEPSEEK_API_KEY` 后，两条用例分别路由到 `StarNewsAgent` / `PolicyAgent`
3. SSE 流式响应包含过程事件（选路、Action 进度）与最终 `RESULT`（含 `processId`、`agentName`、`outputType`、`output`）
4. `index.html` 新 Tab 以聊天气泡实时展示流式进度与最终结果
5. Swagger（Scalar）可查看 `POST /embabel/agent/ask/stream` 与同步 `POST /embabel/agent/ask`

### 1.5 不在范围

- Open 模式 / GOAP 跨 Agent 拼链路
- Embabel Shell CLI
- 改造 Action 内 `createObject` 为 `StreamingPromptRunnerBuilder`（保持文章结构化输出）
- 降级 demo2 的 Spring Boot 4.1.0
- 独立 Maven 子模块

---

## 2. 架构设计

### 2.1 包结构

```
com.jason.demo.demo2.embabel
├── agent/
│   ├── StarNewsAgent.java
│   └── PolicyAgent.java
├── config/
│   ├── StarNewsAgentProperties.java
│   └── PolicyAgentProperties.java
├── controller/
│   └── EmbabelAgentController.java
├── model/
│   ├── AgentRequest.java
│   ├── AgentResponse.java
│   └── EmbabelSseEvent.java
├── sse/
│   └── EmbabelSseBridge.java
└── service/
    ├── EmbabelAgentService.java
    ├── HoroscopeService.java
    └── PolicyKnowledgeService.java
```

### 2.2 与现有 demo 的关系

| 现有 | Embabel 新增 | 冲突处理 |
|------|-------------|----------|
| `AgentController` @ `/agent/trip` | `EmbabelAgentController` @ `/embabel/agent/*` | 路径不冲突，类名区分 |
| Embabel 内置 `GET /events/process/{id}` | 自定义 SSE 桥接 | demo2 Tab 走统一 `/ask/stream`，内部可复用 Embabel `AgenticEventListener` |
| Spring AI ChatClient 各 Tab | Embabel `Ai` + `Autonomy` | 独立 bean，不共用 ChatClient |
| `spring.ai.deepseek.*` | `embabel.agent.platform.models.openai.custom.*` | 复用同一 `DEEPSEEK_API_KEY` |

### 2.3 执行流程（Closed 模式）

```
用户 message
  → EmbabelAgentService.ask()
  → Autonomy.chooseAndRunAgent(message, ProcessOptions)
  → LlmRanker：读取所有 @Agent，按 description 排名
  → 过滤低于置信度阈值的候选
  → 创建 AgentProcess，在选中 Agent 内执行 Action 链
  → validateOutput()
  → AgentResponse
```

**星座链路**：`UserInput → StarPerson → Horoscope → Writeup`

**制度链路**：`UserInput → PolicyQuestion → PolicyMaterial → PolicyAnswer`

### 2.4 Spring Boot 4.1 兼容策略

demo2 使用 Spring Boot **4.1.0**，文章基于 **3.5.14**。实现时按序处理：

1. 直接添加 Embabel 依赖并 `mvn compile`
2. 若有传递依赖冲突，用 `dependencyManagement` 对齐 demo2 已有 BOM
3. 若 autoconfig 冲突，局部 `@SpringBootApplication(exclude=...)` 或条件排除
4. **不降级** Spring Boot 版本

---

## 3. 后端设计

### 3.1 EmbabelAgentController

**主入口（流式）**：

- `POST /embabel/agent/ask/stream`，`produces = TEXT_EVENT_STREAM`
- 请求体：`AgentRequest(@NotBlank String message)`
- 返回 `SseEmitter`（超时 5min，虚拟线程执行，对齐 `SessionMemoryAgentController`）

**调试入口（同步，可选）**：

- `POST /embabel/agent/ask`
- 响应体：`AgentResponse(processId, agentName, outputType, output)`

Swagger `@Tag(name = "Embabel", ...)`

### 3.2 EmbabelAgentService

**同步 `ask(message)`**（与文章一致）：

```java
execution = autonomy.chooseAndRunAgent(message.strip(), new ProcessOptions());
validateOutput(execution.getOutput());
return new AgentResponse(...);
```

**流式 `streamAsk(message, emitter, jsonMapper)`**：

1. 注册 `EmbabelSseBridge`（实现/包装 `AgenticEventListener`），绑定当前 `SseEmitter`
2. 虚拟线程中执行 `chooseAndRunAgent()`
3. 执行过程中推送 SSE 事件（见 §3.2.1）
4. 完成后 `validateOutput()`，推送 `RESULT` 事件并 `complete()`
5. 异常时推送 `ERROR` 事件并 `completeWithError()`

#### 3.2.1 SSE 事件协议

| event | 含义 | data 示例 |
|-------|------|-----------|
| `AGENT_SELECTED` | Ranker 选中 Agent | `{"agentName":"StarNewsAgent"}` |
| `ACTION_START` | Action 开始 | `{"action":"extractStarPerson"}` |
| `ACTION_COMPLETE` | Action 完成 | `{"action":"extractStarPerson","outputType":"StarPerson"}` |
| `PROGRESS` | 可读进度文案（供聊天气泡追加） | `{"text":"正在查询制度资料…"}` |
| `RESULT` | 最终结果 | 完整 `AgentResponse` JSON |
| `ERROR` | 失败 | `{"message":"..."}` |

事件来源：优先通过 `AgenticEventListener.onProcessEvent(AgentProcessEvent)` 映射；若 0.5.0 事件粒度不足，在 Service 层对 `chooseAndRunAgent` 前后补发 `AGENT_SELECTED` / `PROGRESS`，保证 UI 有流式反馈。

> Embabel 内置 `GET /events/process/{processId}`（`embabel-agent-starter-webmvc`）保持默认开启，可作为备用订阅通道；demo2 Tab **不直接依赖**该端点，统一走 `/ask/stream` 以降低前端复杂度。

**validateOutput 规则**：

| 类型 | 必填字段 | 句子完整性 |
|------|----------|------------|
| `Writeup` | title, summary, advice | summary、advice 以 `。！？.!?` 结尾 |
| `PolicyAnswer` | title, answer, source | answer 以 `。！？.!?` 结尾 |

校验失败 → `502 BAD_GATEWAY`。

### 3.3 StarNewsAgent

- `@Agent(description = "根据人物和星座生成一段当天运势文案")`
- `extractStarPerson(UserInput, Ai)` — LLM 结构化提取
- `retrieveHoroscope(StarPerson)` — 调用 `HoroscopeService`（无 LLM）
- `writeup(StarPerson, Horoscope, Ai)` — `@AchievesGoal`，生成 `Writeup`

内部 record：`StarPerson`、`Horoscope`、`Writeup`。

### 3.4 PolicyAgent

- `@Agent(description = "回答员工制度、差旅、报销、请假等公司政策问题")`
- `extractPolicyQuestion(UserInput, Ai)` — LLM 提取类别与问题
- `retrievePolicy(PolicyQuestion)` — 调用 `PolicyKnowledgeService`
- `answer(PolicyQuestion, PolicyMaterial, Ai)` — `@AchievesGoal`，生成 `PolicyAnswer`

内部 record：`PolicyQuestion`、`PolicyMaterial`、`PolicyAnswer`。

### 3.5 HoroscopeService

- `@Service`，静态 Map 存储 **12 个星座**运势文案
- `dailyHoroscope(String sign)` → `Horoscope(sign, summary)`
- 未知星座返回默认文案

### 3.6 PolicyKnowledgeService

- `@Service`，本地规则匹配（文章同款）：
  - 含「请假」→ 员工请假制度
  - 含「报销」「差旅」「出差」→ 差旅与报销制度
  - 其他 → 通用制度说明

### 3.7 配置属性类

- `StarNewsAgentProperties`：`@ConfigurationProperties("demo.star-news-agent")`
  - `prompts.extractStarPerson`
  - `prompts.writeup`
- `PolicyAgentProperties`：`@ConfigurationProperties("demo.policy-agent")`
  - `prompts.extractPolicyQuestion`
  - `prompts.answer`

### 3.8 启动类变更

`Demo2Application` 增加 `@ConfigurationPropertiesScan`。

---

## 4. 配置

写入 `application.properties`：

```properties
# ===== Embabel Agent（文章示例 · 自动选路）=====
embabel.models.default-llm=deepseek-v4-pro
embabel.agent.platform.models.openai.custom.api-key=${DEEPSEEK_API_KEY:}
embabel.agent.platform.models.openai.custom.base-url=https://api.deepseek.com
embabel.agent.platform.models.openai.custom.models=deepseek-v4-pro

# Embabel 内置 REST/SSE（保留默认 true，便于调试）
embabel.agent.platform.rest.process-events-enabled=true

demo.star-news-agent.prompts.extract-star-person=...
demo.star-news-agent.prompts.writeup=...
demo.policy-agent.prompts.extract-policy-question=...
demo.policy-agent.prompts.answer=...
```

提示词内容与文章 `application.yml` 一致（中文 prompt，含 `{userInput}`、`{name}` 等占位符）。

---

## 5. 前端 Tab 设计

参照 `session-memory` / `tool-reasoning` **SSE 聊天气泡**模式。

### 5.1 新增文件

| 文件 | 说明 |
|------|------|
| `static/css/tabs/embabel.css` | Tab 样式（聊天气泡 + 进度区） |
| `static/js/tabs/embabel.js` | `fetch` + `ReadableStream` 解析 SSE |

### 5.2 index.html 变更

- Tab 导航：「🔀 Embabel 自动选路」
- 内容区 `tab-embabel`：
  - 说明文字（两层动作：先选 Agent，再跑 Action）
  - 两个示例按钮（文章 curl 用例）
  - **聊天气泡区** `#embabelMessages`
  - 底部输入框 + 发送按钮
  - 侧栏或气泡内展示：`agentName`、Action 进度、`outputType`、最终 JSON
- 引入 css/js

### 5.3 交互（SSE 流式）

```
用户发送 message
  → POST /embabel/agent/ask/stream
  → 用户气泡立即显示
  → assistant 气泡创建，随事件更新：
      AGENT_SELECTED  → 「已选择 StarNewsAgent」
      ACTION_*        → 追加 Action 进度行
      PROGRESS        → 追加可读文案
      RESULT          → 渲染结构化 output（title/summary/advice 或 answer）
      ERROR           → 红色错误提示
```

- 使用 `fetch` + `response.body.getReader()` 解析 `event:` / `data:` 行（与 `session-memory.js` 一致）
- 发送中禁用输入框，完成后恢复

---

## 6. 错误处理

| 场景 | HTTP | 说明 |
|------|------|------|
| message 为空 | 400 | Bean Validation |
| ProcessExecutionException | 502 | Agent 执行失败 |
| NoAgentFound | 502 | 无 Agent 超过置信度阈值 |
| validateOutput 失败 | 502 | 字段空或句子不完整 |
| 不支持的 output 类型 | 502 | 非 Writeup / PolicyAnswer |
| 缺 DEEPSEEK_API_KEY | 502 | 运行时 LLM 调用失败 |

---

## 7. 测试与验证

### 7.1 编译（硬性要求）

```bash
cd demo2
mvn -DskipTests compile
```

### 7.2 运行时验证（需 DEEPSEEK_API_KEY）

**SSE 流式（主路径）**：

```bash
curl -N -X POST http://localhost:8081/embabel/agent/ask/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"message":"给李白写一段白羊座今日运势文案"}'
```

期望事件序列含 `AGENT_SELECTED` → 若干 `ACTION_*` → `RESULT`（`agentName=StarNewsAgent`，`outputType=Writeup`）。

**同步（调试）**：

```bash
curl -X POST http://localhost:8081/embabel/agent/ask \
  -H "Content-Type: application/json" \
  -d '{"message":"出差回来后报销需要哪些材料"}'
```

期望：`agentName=PolicyAgent`，`outputType=PolicyAnswer`。

### 7.3 UI 验证

在「Embabel 自动选路」Tab 点击两个示例按钮，聊天气泡应流式显示选路与 Action 进度，最终展示对应结构化结果。

---

## 8. 实现文件清单

| 操作 | 路径 |
|------|------|
| 修改 | `demo2/pom.xml` |
| 修改 | `demo2/src/main/java/.../Demo2Application.java` |
| 修改 | `demo2/src/main/resources/application.properties` |
| 修改 | `demo2/src/main/resources/static/index.html` |
| 新增 | `demo2/src/main/java/.../embabel/**`（约 12 个 Java 文件，含 SSE bridge） |
| 新增 | `demo2/src/main/resources/static/css/tabs/embabel.css` |
| 新增 | `demo2/src/main/resources/static/js/tabs/embabel.js` |

---

## 9. 文章对照差异汇总

| 项 | 文章 | 本设计 |
|----|------|--------|
| Spring Boot | 3.5.14 | 4.1.0（保持 demo2） |
| 模型 | deepseek-v4-flash | **deepseek-v4-pro** |
| API | `POST /agent/ask`（同步） | **`POST /embabel/agent/ask/stream`（主）+ `/ask`（调试）** |
| 依赖版本 | 各 dependency 写 version | **`dependencyManagement` 集中管理** |
| Controller | `AgentController` | **`EmbabelAgentController`** |
| 端口 | 8080 | 8081 |
| 前端 | curl only | **SSE 聊天气泡 Tab + curl** |
