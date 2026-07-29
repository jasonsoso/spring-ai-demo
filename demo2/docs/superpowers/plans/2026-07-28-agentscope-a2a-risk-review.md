# AgentScope A2A 风险审查实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在同一个 `demo2` Spring 容器中增加独立的 AgentScope A2A 风险审查 Server/Client，并通过现有 `/agentscope/dev-agent/ask` 的自然语言请求触发。

**Architecture:** 保留现有 `agentscope` 开发 Agent。新增同级 `agentscopea2a` 包：`server` 提供独立风险审查 Agent 和 AgentCard，`client` 负责 Server 就绪后的 AgentCard 发现、A2A 调用和 `risk_review` 桥接工具。主 Agent 通过自然语言决定调用该工具，响应继续走现有 SSE。

**Tech Stack:** Spring Boot 4.1、Java 21、AgentScope 2.0.0、AgentScope A2A Server/Client、Jackson、Reactor、JUnit 5、AssertJ、现有 AgentScope HarnessAgent。

## Global Constraints

- Client 和 Server 必须位于同一个 `demo2` Spring 容器和进程。
- A2A 代码必须位于 `com.jason.demo.demo2.agentscopea2a`，不得放入 `com.jason.demo.demo2.agentscope`。
- 现有 `/agentscope/dev-agent/ask` 是唯一入口，不新增 `/risk-review` 接口。
- `DevAgentRequest` 不增加 `mode` 字段，旧请求 JSON 必须保持兼容。
- 风险审查通过自然语言触发 `risk_review` 工具。
- 风险审查 Agent 只接收改动说明，不读取 Workspace、Sandbox、Diff、文件或会话状态。
- `risk_review` 工具在 Sandbox 开关开启和关闭时都必须注册，但不属于 Sandbox 工具集合。
- Server 完成 AgentCard 就绪后，Client 才能初始化。
- 风险审查继续使用现有 `Flux<DevAgentEvent>` SSE，不增加前端事件类型。
- 不修改现有 `/confirm`、`/apply-diff` 语义。
- 不替换当前 Spring AI 天气 A2A 示例。
- 不提交密钥、认证信息或真实模型凭据。

---

## 文件地图

### 新建文件

- `demo2/src/main/java/com/jason/demo/demo2/agentscopea2a/server/RiskReviewAgentProperties.java`：绑定 Agent 名称、描述、系统提示和 AgentCard 配置。
- `demo2/src/main/java/com/jason/demo/demo2/agentscopea2a/server/RiskReviewAgentConfiguration.java`：创建复用现有 DeepSeek 配置的风险审查模型、Agent Builder 和 A2A Server 配置。
- `demo2/src/main/java/com/jason/demo/demo2/agentscopea2a/server/RiskReviewAgentCardController.java`：在 `/agentscope-a2a/.well-known/agent-card.json` 暴露 AgentCard。
- `demo2/src/main/java/com/jason/demo/demo2/agentscopea2a/server/RiskReviewA2aController.java`：在 `/agentscope-a2a` 转发 JSON-RPC，避免与 Spring AI A2A 根路径冲突。
- `demo2/src/main/java/com/jason/demo/demo2/agentscopea2a/server/RiskReviewServerReadiness.java`：记录 AgentCard 可用状态，提供 Client 等待的就绪信号。
- `demo2/src/main/java/com/jason/demo/demo2/agentscopea2a/client/RiskReviewClientProperties.java`：绑定远端 Agent 名称、地址、超时和最大请求长度。
- `demo2/src/main/java/com/jason/demo/demo2/agentscopea2a/client/RemoteRiskReviewService.java`：Server 就绪后创建 A2A Client，提交改动说明并返回远端文本。
- `demo2/src/main/java/com/jason/demo/demo2/agentscopea2a/client/RiskReviewTool.java`：暴露给主 Agent 的 `risk_review` 工具，只调用远端服务。
- `demo2/src/test/java/com/jason/demo/demo2/agentscopea2a/server/RiskReviewAgentConfigurationTest.java`：验证 Server Agent 和 AgentCard 配置。
- `demo2/src/test/java/com/jason/demo/demo2/agentscopea2a/client/RemoteRiskReviewServiceTest.java`：验证 Client 生命周期、请求内容和错误映射。
- `demo2/src/test/java/com/jason/demo/demo2/agentscopea2a/client/RiskReviewToolTest.java`：验证桥接工具不访问文件并透传结果。

### 修改文件

- `demo2/pom.xml`：加入并锁定 AgentScope 2.0.0 A2A Server/Client 依赖；先验证实际 artifact 名称。
- `demo2/src/main/resources/application.properties`：增加 `agentscopea2a.server` 和 `agentscopea2a.client` 配置。
- `demo2/src/main/java/com/jason/demo/demo2/Demo2Application.java`：排除 AgentScope A2A Starter 自动注册的根路径 Controller。
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`：在现有 Toolkit 组装中注册 `risk_review`，不把它放入 Sandbox 专用分支。
- `demo2/src/main/resources/application-agentscope-prompts.yml`：补充主 Agent 何时调用 `risk_review`、只传递用户提供事实的规则。
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java`：仅在确有必要时调整事件/source 映射；默认不改主执行分支。
- `demo2/src/main/resources/static/js/tabs/agentscope.js`：增加自然语言风险审查示例，复用现有 `/ask` 和 SSE。
- `demo2/README.md`：记录同容器 A2A Server/Client、自然语言示例和验证命令。
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java`：覆盖 Sandbox 开关下 `risk_review` 工具存在。
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java`：覆盖普通请求兼容性与风险审查工具事件链。

---

### Task 1: 验证 AgentScope 2.0.0 A2A API 与依赖

**Files:**
- Modify: `demo2/pom.xml`
- Test fixture: `demo2/src/test/java/com/jason/demo/demo2/agentscopea2a/DependencySmokeTest.java`

**Interfaces:**
- Produces: 当前 BOM 可解析的 A2A Server/Client artifact、AgentCard 类型和 AgentScope Tool 注册方式，供后续任务使用。

- [ ] **Step 1: 检查当前依赖树和本地 Maven 缓存**

Run:

```powershell
cd D:\ai\spring-ai-demo\demo2
.\mvnw.cmd -q dependency:tree "-Dincludes=io.agentscope"
```

Expected: 输出当前 AgentScope 2.0.0 BOM 解析出的 artifact；不得凭文章名称直接假设依赖存在。

- [ ] **Step 2: 验证文章中的 Server/Client artifact**

Run:

```powershell
.\mvnw.cmd -q dependency:get "-Dartifact=io.agentscope:agentscope-a2a-spring-boot-starter:2.0.0"
.\mvnw.cmd -q dependency:get "-Dartifact=io.agentscope:agentscope-extensions-a2a-client:2.0.0"
```

Expected: 两个 artifact 都能解析；如果 artifact 名称不成立，依据同一 BOM 的实际 artifact 列表修正 `pom.xml`，并在计划执行记录中保留实际坐标。

- [ ] **Step 3: 加入最小依赖验证测试**

测试必须只验证类可加载，不启动真实模型或 HTTP 服务：

```java
@Test
void agentscopeA2aTypesAreAvailable() {
    assertThat(ReActAgent.class).isNotNull();
    assertThat(A2aAgent.class).isNotNull();
}
```

- [ ] **Step 4: 运行依赖验证**

Run:

```powershell
.\mvnw.cmd -q -Dtest=DependencySmokeTest test
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`。

- [ ] **Step 5: Commit**

```powershell
git add demo2/pom.xml demo2/src/test/java/com/jason/demo/demo2/agentscopea2a/DependencySmokeTest.java
git commit -m "build(demo2): verify AgentScope A2A dependencies"
```

### Task 2: 建立独立风险审查 Server

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscopea2a/server/RiskReviewAgentProperties.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscopea2a/server/RiskReviewAgentConfiguration.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscopea2a/server/RiskReviewServerReadiness.java`
- Modify: `demo2/src/main/resources/application.properties`
- Test: `demo2/src/test/java/com/jason/demo/demo2/agentscopea2a/server/RiskReviewAgentConfigurationTest.java`

**Interfaces:**
- `RiskReviewAgentProperties`：`@ConfigurationProperties(prefix = "app.agentscopea2a.server")`，提供 `name`、`description`、`version`、`systemPrompt`。
- `RiskReviewAgentConfiguration.riskReviewAgentBuilder(...)`：返回独立 `ReActAgent.Builder`，使用空 `Toolkit` 和复用的模型 Bean；A2A Starter 使用 Builder Runner 暴露 Server。
- `RiskReviewServerReadiness.awaitReady(Duration timeout)`：应用就绪事件完成后返回，否则抛出明确的未就绪异常。

- [ ] **Step 1: 写配置绑定失败测试**

覆盖空名称、空 Prompt 和默认 AgentCard 字段；测试使用 `ApplicationContextRunner`，不需要真实 API Key。

- [ ] **Step 2: 写 Agent Builder 配置测试**

验证：

```java
ReActAgent agent = builder.build();
assertThat(agent.getName()).isEqualTo("risk-review-agent");
assertThat(agent.getDescription()).contains("Java");
agent.close();
```

验证 Toolkit 为空，不能注册 `read_file`、`execute` 或任何项目文件工具。

- [ ] **Step 3: 运行测试确认先失败**

```powershell
.\mvnw.cmd -q -Dtest=RiskReviewAgentConfigurationTest test
```

Expected: 新类型或 Bean 尚不存在导致失败。

- [ ] **Step 4: 实现 Server 配置**

Server 配置必须：

- 复用现有 `@Qualifier("agentscopeDeepSeekModel") Model`；
- 创建独立 Agent 名称和系统提示；
- 使用空 Toolkit；
- 配置 AgentCard 的 name、description、version、text 输入和 text 输出；
- 使用 `AgentScopeA2aServer.builder(...)` 创建 Server Bean，并手动注册前缀 Controller；不能让 Starter 自动注册根路径 Controller；
- 不修改现有 `agentscope` 包中的 Agent。

- [ ] **Step 5: 实现 Server readiness**

使用 Spring `ApplicationReadyEvent` 标记 Server readiness；自定义 Controller Bean 已完成注册，不执行模型调用。

- [ ] **Step 6: 运行测试确认通过**

```powershell
.\mvnw.cmd -q -Dtest=RiskReviewAgentConfigurationTest test
```

Expected: PASS。

- [ ] **Step 7: Commit**

```powershell
git add demo2/src/main/java/com/jason/demo/demo2/agentscopea2a/server demo2/src/main/resources/application.properties demo2/src/test/java/com/jason/demo/demo2/agentscopea2a/server
git commit -m "feat(demo2): add AgentScope risk review server"
```

### Task 3: 实现 Server 就绪后的 A2A Client

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscopea2a/client/RiskReviewClientProperties.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscopea2a/client/RemoteRiskReviewService.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/agentscopea2a/client/RemoteRiskReviewServiceTest.java`

**Interfaces:**
- `RemoteRiskReviewService.review(String message)`：返回 `Mono<String>`，只提交改动说明。
- `RemoteRiskReviewService.isReady()`：返回 Client 是否已读取 AgentCard 并可调用。
- `RemoteRiskReviewService.start()`：只在 Server readiness 完成后执行一次 Client 初始化。

- [ ] **Step 1: 写未就绪和成功调用测试**

使用 stubbed AgentCard resolver/client，不启动真实 HTTP Server。覆盖：

```java
StepVerifier.create(service.review("审查 delayMillis"))
    .expectNext("## 结论\n...\n## 风险\n...\n## 建议\n...")
    .verifyComplete();
```

并验证传出的任务只包含 `message`，不包含 userId、sessionId、路径或文件内容。

- [ ] **Step 2: 写初始化顺序测试**

先触发 Client start，断言 Server readiness 未完成时不创建远程 Agent；完成 readiness 后再次触发，断言只创建一次。

- [ ] **Step 3: 运行测试确认先失败**

```powershell
.\mvnw.cmd -q -Dtest=RemoteRiskReviewServiceTest test
```

- [ ] **Step 4: 实现配置与 Client**

Client 必须：

- 使用 `SmartLifecycle` 或等价 Spring 就绪机制；
- 等待 `RiskReviewServerReadiness`；
- 通过 `WellKnownAgentCardResolver` 读取 `/.well-known/agent-card.json`；
- 创建非流式 A2A Client；
- 设置明确连接和调用超时；
- 将 AgentCard、连接、超时和远端执行异常映射为可读业务异常；
- 不在构造函数中发起网络访问。

- [ ] **Step 5: 运行测试确认通过**

```powershell
.\mvnw.cmd -q -Dtest=RemoteRiskReviewServiceTest test
```

Expected: PASS。

- [ ] **Step 6: Commit**

```powershell
git add demo2/src/main/java/com/jason/demo/demo2/agentscopea2a/client demo2/src/test/java/com/jason/demo/demo2/agentscopea2a/client demo2/src/main/resources/application.properties
git commit -m "feat(demo2): add AgentScope risk review client"
```

### Task 4: 注册自然语言桥接工具

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscopea2a/client/RiskReviewTool.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`
- Modify: `demo2/src/main/resources/application-agentscope-prompts.yml`
- Test: `demo2/src/test/java/com/jason/demo/demo2/agentscopea2a/client/RiskReviewToolTest.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java`

**Interfaces:**
- `RiskReviewTool.review(String changeDescription)`：返回远端完整审查文本。
- 工具名称固定为 `risk_review`。

- [ ] **Step 1: 写桥接工具测试**

注入 mock `RemoteRiskReviewService`，调用工具后断言：

- 改动说明原样传递；
- 结果原样返回；
- service 失败时抛出可识别异常；
- 工具没有任何 Path、File、Process 或 Sandbox 依赖。

- [ ] **Step 2: 写 Sandbox 配置测试**

分别使用 `sandbox.enabled=false` 和 `true` 构建 Agent，断言两种情况下 `risk_review` 都存在；同时断言它不依赖 `execute`、`read_file` 或 `edit_file`。

- [ ] **Step 3: 运行测试确认先失败**

```powershell
.\mvnw.cmd -q -Dtest=RiskReviewToolTest,AgentScopeMiddlewareConfigTest test
```

- [ ] **Step 4: 实现工具和注册**

在 `AgentScopeConfig` 中把 `RiskReviewTool` 注入 `agentscopeDevAgent(...)`，在 Sandbox 分支之前注册；仅给 `risk_review` 增加允许规则。不要把它放进现有 Sandbox 工具移除或专用 Toolkit 分支。

在 `application-agentscope-prompts.yml` 增加明确规则：

```text
当用户要求审查代码改动、风险、合并安全性或回归影响时，调用 risk_review。
只把用户明确提供的改动说明传给 risk_review。
不要声称 risk_review 读取过项目文件。
```

- [ ] **Step 5: 运行测试确认通过**

```powershell
.\mvnw.cmd -q -Dtest=RiskReviewToolTest,AgentScopeMiddlewareConfigTest test
```

Expected: PASS。

- [ ] **Step 6: Commit**

```powershell
git add demo2/src/main/java/com/jason/demo/demo2/agentscopea2a/client/RiskReviewTool.java demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java demo2/src/main/resources/application-agentscope-prompts.yml demo2/src/test/java/com/jason/demo/demo2/agentscopea2a/client/RiskReviewToolTest.java demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java
git commit -m "feat(demo2): expose risk review through natural language"
```

### Task 5: 保持 `/ask` SSE 并接入前端示例

**Files:**
- Modify: `demo2/src/main/resources/static/js/tabs/agentscope.js`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java`
- Test: `demo2/src/test/resources/static/agentscope-risk-review-test.md`

**Interfaces:**
- `/agentscope/dev-agent/ask` 请求 JSON 不变。
- 风险审查通过现有 `consumeAgentscopeSse(...)` 接收工具事件和最终消息。

- [ ] **Step 1: 写 SSE 兼容测试**

验证普通请求仍能产生现有事件；风险审查请求能观察到 `TOOL_CALL_START(risk_review)`、`TOOL_RESULT_END(risk_review)` 和最终 `MESSAGE`，但不触发 `/confirm` 或 Diff 回写。

- [ ] **Step 2: 运行后端测试确认当前基线**

```powershell
.\mvnw.cmd -q -Dtest=DevAgentServiceTest test
```

Expected: 修改前基线通过；若已有失败，记录并与本功能失败区分。

- [ ] **Step 3: 增加前端风险审查示例**

在现有 `fillAgentscopeSample(...)` 增加明确自然语言样例，例如：

```text
请审查 RetryPolicy.delayMillis 的改动：原实现第一次重试使用第二档延迟，修改后第一次应为 1000ms，第二次 2000ms，第三次 4000ms。请给出结论、风险和建议。
```

继续使用已有 `/ask` 请求体和 SSE 解析，不新增模式字段或新接口。

- [ ] **Step 4: 验证 HTML/JS 行为**

确认风险审查结果通过现有消息气泡显示，工具状态显示 `risk_review`，不出现 Diff 回写按钮。

- [ ] **Step 5: 运行测试**

```powershell
.\mvnw.cmd -q -Dtest=DevAgentServiceTest test
```

Expected: PASS。

- [ ] **Step 6: Commit**

```powershell
git add demo2/src/main/resources/static/js/tabs/agentscope.js demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java demo2/src/test/resources/static/agentscope-risk-review-test.md
git commit -m "feat(demo2): integrate risk review with ask SSE"
```

### Task 6: 完成配置、文档与集成验证

**Files:**
- Modify: `demo2/src/main/resources/application.properties`
- Modify: `demo2/README.md`
- Create: `demo2/src/test/java/com/jason/demo/demo2/agentscopea2a/RiskReviewA2aIntegrationTest.java`

**Interfaces:**
- 配置默认使用同一 DeepSeek API Key、Base URL 和模型名。
- Server 默认 Agent 名称为 `risk-review-agent`。
- Client 默认使用本机同容器 AgentCard 地址和显式超时。

- [ ] **Step 1: 写上下文集成测试**

使用 Spring 测试上下文和 stub 模型，验证：

- Server Bean、AgentCard 和 Client Bean 都能创建；
- Client readiness 在 Server readiness 之后；
- `risk_review` 工具存在；
- 普通 AgentScope `/ask` 路径的 Bean 仍可创建。

- [ ] **Step 2: 运行完整自动化测试**

```powershell
.\mvnw.cmd -q test
```

Expected: 全部测试通过，且没有新增依赖冲突、Bean 循环依赖或配置绑定错误。

- [ ] **Step 3: 启动应用验证 AgentCard**

```powershell
.\mvnw.cmd spring-boot:run
```

另开 PowerShell：

```powershell
Invoke-WebRequest http://localhost:8081/.well-known/agent-card.json
```

Expected: 返回风险审查 AgentCard，包含 `risk-review-agent` 和文本输入输出能力。

- [ ] **Step 4: 验证自然语言 `/ask`**

使用现有页面或请求：

```powershell
Invoke-WebRequest `
  -Method Post `
  -Uri http://localhost:8081/agentscope/dev-agent/ask `
  -ContentType "application/json" `
  -Body '{"userId":"risk-user-001","sessionId":"risk-session-001","message":"请审查 RetryPolicy.delayMillis 的改动，并给出结论、风险和建议。"}'
```

Expected: SSE 中能看到 `risk_review` 工具调用，最终内容包含“结论、风险、建议”。

- [ ] **Step 5: 验证 Sandbox 解耦**

在 Sandbox 开启和关闭配置下分别启动测试上下文，确认：

- `risk_review` 工具均存在；
- 风险审查不访问 Sandbox；
- 普通 Sandbox `/ask`、`/confirm`、`/apply-diff` 行为没有改变。

- [ ] **Step 6: 更新 README**

记录：

- 同容器 Server 先初始化、Client 后初始化；
- `/ask` 自然语言触发方式；
- AgentCard 地址；
- AgentScope A2A 依赖和启动前提；
- 风险审查不读取项目文件和 Sandbox；
- 常见错误：Server 未就绪、AgentCard 失败、超时、模型失败。

- [ ] **Step 7: Commit**

```powershell
git add demo2/src/main/resources/application.properties demo2/README.md demo2/src/test/java/com/jason/demo/demo2/agentscopea2a/RiskReviewA2aIntegrationTest.java
git commit -m "docs(demo2): document AgentScope A2A risk review"
```

---

## Verification Checklist

- [ ] AgentScope 2.0.0 A2A artifact 和 API 已通过 Maven 与编译测试验证。
- [ ] A2A Server 和 Client 位于 `agentscopea2a`，没有混入 `agentscope`。
- [ ] Server AgentCard 在 Client 初始化前可用。
- [ ] `DevAgentRequest` 未增加字段，旧 `/ask` 请求兼容。
- [ ] 自然语言可触发 `risk_review`。
- [ ] 风险审查不读取 Sandbox、Workspace、Diff 或文件。
- [ ] `/ask` 仍返回现有 SSE 事件。
- [ ] `/confirm`、`/apply-diff` 和普通开发 Agent 流程未回归。
- [ ] 前端有风险审查自然语言示例。
- [ ] 全量 Maven 测试和手工 AgentCard/ask 验证通过。
