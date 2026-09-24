# demo2 依赖升级 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按已定稿设计，把 demo2 约定范围内的依赖升到目标版本，并让 `mvn test` 与全量页面点检通过。

**Architecture:** 只改 `demo2/pom.xml` 的版本属性或显式版本，按四层顺序升级。每一层先编译、再 `mvn test`、再点该层页面。编译或测试仍失败时，只退回导致失败的坐标，并撤回为它写的适配代码。脱离 Boot BOM 的四项逐个升、逐个测。

**Tech Stack:** Java 21、Spring Boot 4.1.1、Spring AI 2.0.1、MCP Java SDK 2.0.1、AgentScope 2.0.3、Maven

**Spec:** [2026-09-24-demo2-dependency-upgrade-design.md](../specs/2026-09-24-demo2-dependency-upgrade-design.md)

## Global Constraints

- 工作目录是 `demo2`。不改 `demo/pom.xml`、`demo2/workspace/project/pom.xml`、历史 spec / plan
- 父 POM 目标是 `spring-boot-starter-parent` **4.1.1**，不是 4.2.0-M1
- `spring-ai.version` = **2.0.1**
- MCP 四个坐标 `mcp` / `mcp-core` / `mcp-json` / `mcp-json-jackson3` 一起为 **2.0.1**，且声明在 `agentscope-bom` 之后
- `agentscope.version` = **2.0.3**
- `rocketmq-client.version` = **5.5.1**
- `shardingsphere.version` = **5.5.3**
- `spring-retry` = **2.0.13**
- `jsoup` = **1.23.2**
- `rewrite-maven-plugin` = **6.46.1**，`rewrite-spring` = **6.37.1**
- `hutool.version` = **5.8.47**
- `swagger-annotations.version` = **2.2.55**，与 `springdoc-openapi-starter-webmvc-scalar` **3.1.1** 一起进退
- `spring-ai-agent-utils-bom` = **0.12.0**
- `spring-ai-session-bom` = **0.8.0**
- `redisson-spring-boot-starter` = **4.7.0**；lock4j 保持 **2.2.7**，JetCache 保持 **2.8.0.RC**
- 属性覆盖：`lombok.version` **1.18.48**、`caffeine.version` **3.3.0**、`mysql.version` **26.7.0**、`micrometer.version` **1.18.0-M2**、`micrometer-tracing.version` **1.8.0-M2**
- 保持不动：Embabel `2.0.0-SNAPSHOT`、lock4j `2.2.7`、JetCache `2.8.0.RC`、MyBatis-Plus `3.5.17`、MapStruct `1.6.3`、TTL `2.14.5`、Kryo5 `5.6.2`、Guava `33.7.1-jre`、COLA `5.0.0`、`spring-ai-a2a-server-autoconfigure` `0.3.0`、`lombok-mapstruct-binding` `0.2.0`、Java 21
- 只改「不改就编译不过、测试失败或页面出现 500 / `NoSuchMethodError`」的代码。不重构，不改接口路径，不改业务规则
- 升级前已失败的测试不修。`@EnabledIf` 与 `@EnabledIfSystemProperty` 的跳过规则保持原样
- 外部密钥或中间件没配好记为环境阻塞，不因此退版本
- 应用端口 `8081`

---

## File Structure

| 文件 | 职责 |
|------|------|
| `demo2/pom.xml` | 唯一的版本变更入口。每层只改本层坐标 |
| `demo2/src/main/java/...` 与 `demo2/src/test/java/...` | 仅当编译错误或测试失败点名该文件时才改 |
| `demo2/src/main/resources/static/index.html` 与 `static/js/tabs/*.js` | 不改。页面点检只操作现有按钮 |
| 本计划文末「执行结果」 | 记录基线、退回坐标、页面点检 |

编译失败时允许改、且只允许改 spec 第 4 节点名的这些文件（路径均在 `demo2/src/main/java/com/jason/demo/demo2/` 下，测试在 `src/test/java` 的同名测试）：

- Boot / Jackson：`framework/jackson/JacksonJsonCustomizer.java`
- Spring AI：`config/MemoryConfig.java`、`config/MysqlMemoryConfig.java`、`config/LkCoffeeAgentConfig.java`、`config/ToolReasoningAgentConfig.java`、`service/ToolReasoningAgentService.java`、`service/AutoMemoryTripAgentService.java`、`controller/VoiceApiController.java`、`mcp/client/controller/McpChatController.java`
- MCP：`agentscope/mcp/AgentscopeMcpClientRegistry.java`、`agentscope/mcp/McpFilesystemTools.java`、`mcp/client/config/McpClientLifecycle.java`、`mcp/client/config/LkCoffeeMcpTransportConfig.java`、`mcp/client/LkCoffeeMcpToolCallbacksProvider.java`、`mcp/server/config/McpServerConfig.java`
- AgentScope：`agentscope/config/AgentScopeConfig.java`、`agentscope/config/AgentscopeDevAgentRegistry.java`、`config/LoggingAgentscopeModel.java`、`agentscope/tool/FileChangeTool.java`、`agentscope/tool/ProjectInfoTools.java`、`agentscope/sandbox/NewlineFlatteningSandbox.java`、`agentscope/plan/LiveSandboxPlanReader.java`、`agentscopea2a/server/RiskReviewA2aController.java`、`agentscopea2a/server/RiskReviewAgentCardController.java`
- RocketMQ：`framework/rocketmq/RocketMessageConcurrentlyListener.java`、`mq/listener/OrderConcurrentListener.java`、`framework/rocketmq/producer/` 下发送类、`framework/rocketmq/RocketMqTracePropagator.java`
- ShardingSphere：`order/service/infrastructure/shard/OrderComplexShardingAlgorithm.java`、`order/service/infrastructure/shard/OrderShardGene.java`、`src/main/resources/shardingsphere.yaml`
- jsoup：`embabel/service/ArticleFetchService.java`
- Hutool：`framework/id/SnowflakeIdGenerator.java`
- springdoc：`config/OpenApiConfig.java`；`@Tag` / `@Operation` / `@Schema` 仅在编译错误点名该文件时改
- agent-utils：`config/SkillsAgentConfig.java`、`service/SkillsAgentService.java`、`config/SubagentAgentConfig.java`、`service/A2aOrchestratorService.java`、`config/A2aWeatherAgentConfig.java`、`config/TodoAgentConfig.java`、`service/TodoAgentService.java`、`sse/TodoSseBridge.java`、`config/AskUserAgentConfig.java`、`service/AskUserAgentService.java`、`service/WebQuestionHandler.java`、`service/AutoMemoryTripAgentService.java`
- session：`config/SessionMemoryAgentConfig.java`、`service/SessionMemoryTripAgentService.java`、`src/test/java/com/jason/demo/demo2/service/SessionMemoryTripAgentServiceTest.java`
- Redisson：`framework/delay/backend/RedissonDelayBackend.java`、`src/test/java/com/jason/demo/demo2/product/ProductDomainServiceCacheTest.java`
- Micrometer：`framework/trace/TraceSupport.java`、`config/TraceIdFilter.java`、`framework/rocketmq/RocketMqTracePropagator.java`、`agentscope/observability/AgentExecutionContext.java`、`framework/rocketmq/producer/BaseEventPublisher.java`、`config/MilvusLazyConfiguration.java`，以及编译错误点名的 `SimpleTracer` 测试

## 编译失败与回退（每一层都用这套）

1. 在 `demo2` 执行 `mvn -q -DskipTests compile`。
2. `BUILD SUCCESS`：不改 Java。
3. `BUILD FAILURE`：只改错误信息点名、且属于上面清单的文件。保持原 public 方法的路径、参数含义和返回含义。改完再执行同一条 compile，直到 `BUILD SUCCESS` 或确认该坐标无法适配。
4. 然后执行 `mvn test`。本层新出现的 Failures/Errors 才修；基线里已有的失败原样留下。
5. 修完仍失败：把本层坐标按下表从可疑到次要逐个退回，每退一个就重新 `mvn -q -DskipTests compile` 和 `mvn test`。第一个让命令恢复到「相对基线没有新增失败」的坐标就是元凶，它留在旧版本，其余已证明无害的目标版本加回去。
6. 退回时在该版本旁加注释，并 `git checkout --` 掉只为这个坐标写的 Java 改动。

第 2 层回退顺序：`agentscope.version` → 四个 MCP 坐标 → `spring-ai.version` → `shardingsphere.version` → `rocketmq-client.version` → `jsoup` → `spring-retry`。rewrite 插件只在 `mvn test` 因插件报错失败时退回。

第 3 层回退顺序：`spring-ai-session-bom` → `spring-ai-agent-utils-bom` → `redisson-spring-boot-starter` → `swagger-annotations.version` 与 springdoc 一起 → `hutool.version`。

退回注释格式（把版本和现象换成实际值）：

```xml
<!-- 目标 4.7.0 未采纳：RDelayedQueue 方法签名变化，适配后 DelayTaskExecutorTest 仍失败 -->
<version>4.1.0</version>
```

属性覆盖退回时删掉该属性，让 Boot BOM 接管，并在 properties 里留一行注释：

```xml
<!-- mysql.version 目标 26.7.0 未采纳：ShardingSphere 无法加载该驱动 -->
```

---

### Task 1: 记录测试基线

**Files:**
- Modify: `demo2/docs/superpowers/plans/2026-09-24-demo2-dependency-upgrade.md`（文末「执行结果」）
- Test: 现有 `demo2/src/test/java` 全部测试，不新增测试

**Interfaces:**
- Consumes: 当前 `demo2/pom.xml`（Boot 4.1.0，未改版本）
- Produces: 基线摘要 `Tests run: N, Failures: F, Errors: E, Skipped: S`，以及失败测试的类名与方法名列表

- [ ] **Step 1: 在 demo2 跑全量测试并留下摘要**

在 `demo2` 目录执行：

```powershell
mvn test
```

预期：命令结束。退出码可以不是 0。从结尾复制 Surefire 摘要行，以及每个 `FAILURE` / `ERROR` 的测试类与方法名。

- [ ] **Step 2: 把基线写进本计划文末**

在「执行结果」的「基线」下粘贴摘要。不要为了让基线变绿而改代码。

- [ ] **Step 3: 提交基线记录**

```powershell
git add -- demo2/docs/superpowers/plans/2026-09-24-demo2-dependency-upgrade.md
git commit -m "docs(demo2): record dependency upgrade test baseline"
```

### Task 2: 升级 Spring Boot 到 4.1.1

**Files:**
- Modify: `demo2/pom.xml`（parent `version`，约第 8 行）
- Modify（仅编译失败时）: `demo2/src/main/java/com/jason/demo/demo2/framework/jackson/JacksonJsonCustomizer.java`

**Interfaces:**
- Consumes: Task 1 的基线摘要
- Produces: parent `4.1.1`。Boot 管理的 Spring Framework、Tomcat、Netty、Logback、Jackson、PostgreSQL、Hibernate、Reactor 跟随 4.1.1，不在子 POM 单独写版本

- [ ] **Step 1: 只改 parent 版本**

把：

```xml
        <version>4.1.0</version>
```

改成：

```xml
        <version>4.1.1</version>
```

这是 `<parent>` 里 `spring-boot-starter-parent` 的版本，不是 Redisson 的 `4.1.0`。

- [ ] **Step 2: 编译**

```powershell
mvn -q -DskipTests compile
```

预期：`BUILD SUCCESS`。若失败且错误点名 `JacksonJsonCustomizer.java`，只改该文件里失效的 Jackson 调用，使原序列化行为保持不变，然后重跑本命令。

若修完仍失败：把 parent 改回 `4.1.0`，注释写 `目标 4.1.1 未采纳：` 加上失败现象，撤回 Java 改动。后续任务仍继续，传递依赖保持 4.1.0 带上的版本。

- [ ] **Step 3: 跑测试**

```powershell
mvn test
```

预期：相对基线没有新增 Failures/Errors。有新增则只修本层引入的失败；修不好则按 Step 2 退回 parent。

- [ ] **Step 4: 启动并切换全部 Tab**

```powershell
mvn spring-boot:run
```

浏览器打开 `http://localhost:8081/`。预期：HTTP 200，页面含 `data-tab="chat"`。

依次点击下面 24 个 `button.tab-btn`，每次确认对应的 `#tab-*` 出现且控制台没有未捕获异常：

`chat`、`voice-chat`、`embedding`、`rag`、`rag-opt`、`ecommerce`、`agent`、`agent-memory`、`agent-mysql-memory`、`agent-auto-memory`、`agent-session-memory`、`agent-tools`、`tool-reasoning`、`mcp`、`lkcoffee`、`multi-agent`、`ask-user`、`todo-write`、`subagent`、`a2a`、`embabel`、`agentscope`、`rocketmq`、`member`

启动失败或任一 Tab 白屏：按 Step 2 退回 parent，再重测。点完后停掉 `spring-boot:run`。

- [ ] **Step 5: 提交**

```powershell
git add -- demo2/pom.xml
git commit -m "chore(demo2): upgrade Spring Boot parent to 4.1.1"
```

若退回了 parent，提交信息改为 `chore(demo2): keep Spring Boot parent at 4.1.0`，正文写明未采纳原因。有 Java 适配时把对应源文件一并 `git add`。

### Task 3: 升级同线补丁坐标

**Files:**
- Modify: `demo2/pom.xml` 的 properties、MCP 四段 `dependencyManagement`、jsoup、spring-retry、rewrite 插件
- Modify（仅编译失败时）: 本计划 File Structure 里 Spring AI、MCP、AgentScope、RocketMQ、ShardingSphere、jsoup 点名的文件

**Interfaces:**
- Consumes: Task 2 结束后的 parent 版本（4.1.1，或已退回的 4.1.0）
- Produces: 第 2 层目标版本；`mvn dependency:tree` 中 `io.modelcontextprotocol.sdk:mcp` 解析为 `2.0.1`

- [ ] **Step 1: 改 properties**

```xml
        <spring-ai.version>2.0.1</spring-ai.version>
        <agentscope.version>2.0.3</agentscope.version>
        <rocketmq-client.version>5.5.1</rocketmq-client.version>
        <shardingsphere.version>5.5.3</shardingsphere.version>
```

`swagger-annotations.version`、`hutool.version`、`embabel-agent.version` 本任务不改。

- [ ] **Step 2: 把 MCP 覆盖改为 2.0.1，并改注释**

`agentscope-bom` 之后的注释和四个依赖改成：

```xml
            <!--
              统一 MCP Java SDK 2.0.1（与 Spring AI 2.0.1 一致）。
              覆盖放在 agentscope-bom 之后，避免 BOM 把 MCP 钉回旧版。
              AgentScope Toolkit MCP 走自建 SDK stdio Client + AgentTool，不用 McpClientBuilder。
            -->
            <dependency>
                <groupId>io.modelcontextprotocol.sdk</groupId>
                <artifactId>mcp</artifactId>
                <version>2.0.1</version>
            </dependency>
            <dependency>
                <groupId>io.modelcontextprotocol.sdk</groupId>
                <artifactId>mcp-core</artifactId>
                <version>2.0.1</version>
            </dependency>
            <dependency>
                <groupId>io.modelcontextprotocol.sdk</groupId>
                <artifactId>mcp-json</artifactId>
                <version>2.0.1</version>
            </dependency>
            <dependency>
                <groupId>io.modelcontextprotocol.sdk</groupId>
                <artifactId>mcp-json-jackson3</artifactId>
                <version>2.0.1</version>
            </dependency>
```

四个 artifact 必须同进同退。不要只改其中一个。

- [ ] **Step 3: 改 jsoup、spring-retry、rewrite**

```xml
            <artifactId>jsoup</artifactId>
            <version>1.23.2</version>
```

```xml
            <artifactId>spring-retry</artifactId>
            <version>2.0.13</version>
```

```xml
                <artifactId>rewrite-maven-plugin</artifactId>
                <version>6.46.1</version>
```

```xml
                        <artifactId>rewrite-spring</artifactId>
                        <version>6.37.1</version>
```

- [ ] **Step 4: 确认 MCP 解析版本**

```powershell
mvn -q dependency:tree "-Dincludes=io.modelcontextprotocol.sdk:mcp"
```

预期：树中出现 `io.modelcontextprotocol.sdk:mcp:jar:2.0.1`。若是 `0.17` 或其他版本，检查覆盖是否仍在 `agentscope-bom` 之后，修正顺序后重跑。

- [ ] **Step 5: 编译并按「编译失败与回退」处理**

```powershell
mvn -q -DskipTests compile
```

预期：`BUILD SUCCESS`。失败则只改错误点名的清单文件，保持原行为。仍失败则按第 2 层回退顺序定位，退回的坐标加注释。

- [ ] **Step 6: 跑测试**

```powershell
mvn test
```

预期：相对基线没有新增 Failures/Errors。新增失败按同一回退顺序处理。

- [ ] **Step 7: 点第 2 层页面**

停掉占用 8081 的进程后：

```powershell
mvn spring-boot:run
```

打开 `http://localhost:8081/`。对每一行：先点对应 Tab，再点主按钮。业务错误可以；白屏、脚本未捕获异常、HTTP 500、`NoSuchMethodError` 算失败。密钥或中间件缺失记为环境阻塞，不退版本。

| Tab | 主操作 |
|-----|--------|
| `chat` | 在输入框填 `你好`，点 `#sendButton` |
| `embedding` | 点 `#embeddingBtn` |
| `rag` | 点 `#ragAskBtn` |
| `rag-opt` | 点 `#ragOptAskBtn` |
| `mcp` | 点 `#mcpAskBtn` |
| `lkcoffee` | 点 `#lkCoffeeSendBtn` |
| `agentscope` | 在 `#agentscopeMessageInput` 填 `看一下当前项目结构`，点 `#agentscopeSendBtn` |
| `rocketmq` | 点「同步发送」（`rocketmqSend('sync')`），看 `#mqSendResult` |
| `member` | 在 `#memberShardMemberId` 填 `1001`，点「计算路由」，看 `#memberShardResult` 不再是初始占位句 |

失败且属于启动失败、类缺失或方法签名：退回该页面对应的坐标（聊天/Embedding/RAG → Spring AI；MCP/瑞幸 → MCP；AgentScope → agentscope；RocketMQ → rocketmq-client；分片 → shardingsphere），重跑 `mvn test` 并重测该页。点完停掉应用。

- [ ] **Step 8: 提交**

```powershell
git add -- demo2/pom.xml
git commit -m "chore(demo2): upgrade Spring AI, MCP, AgentScope, and patch libraries"
```

有适配源文件时一并加入。退回的坐标留在这次提交里，注释写清原因。

### Task 4: 升级行为可能变化的库

**Files:**
- Modify: `demo2/pom.xml` 的 hutool、swagger、springdoc、agent-utils BOM、session BOM、Redisson
- Modify（仅编译失败时）: File Structure 里 Hutool、springdoc、agent-utils、session、Redisson 点名的文件

**Interfaces:**
- Consumes: Task 3 结束后的第 2 层版本
- Produces: 第 3 层目标版本。lock4j 仍是 `2.2.7`，JetCache 仍是 `2.8.0.RC`

- [ ] **Step 1: 改第 3 层版本**

```xml
        <swagger-annotations.version>2.2.55</swagger-annotations.version>
        <hutool.version>5.8.47</hutool.version>
```

```xml
                <artifactId>spring-ai-agent-utils-bom</artifactId>
                <version>0.12.0</version>
```

```xml
                <artifactId>spring-ai-session-bom</artifactId>
                <version>0.8.0</version>
```

```xml
            <artifactId>springdoc-openapi-starter-webmvc-scalar</artifactId>
            <version>3.1.1</version>
```

```xml
            <artifactId>redisson-spring-boot-starter</artifactId>
            <version>4.7.0</version>
```

不要改 `lock4j.version` 和 `jetcache.version`。swagger 与 springdoc 必须同进同退。

- [ ] **Step 2: 编译**

```powershell
mvn -q -DskipTests compile
```

预期：`BUILD SUCCESS`。session 与 agent-utils 最可能改签名。只改错误点名的清单文件，控制器路径保持不变。仍失败则按第 3 层回退顺序处理。

Redisson 若与 lock4j 或 JetCache 在编译或测试中冲突，Redisson 退回：

```xml
            <!-- 目标 4.7.0 未采纳：与 lock4j 2.2.7 或 JetCache 2.8.0.RC 不兼容 -->
            <artifactId>redisson-spring-boot-starter</artifactId>
            <version>4.1.0</version>
```

注释里的现象换成实际错误的第一行。

- [ ] **Step 3: 跑测试**

```powershell
mvn test
```

预期：相对基线没有新增 Failures/Errors。`SessionMemoryTripAgentServiceTest`、`ProductDomainServiceCacheTest`、`SnowflakeIdConfigurationTest` 若新失败，先按清单改测试构造；改完仍失败则退回对应坐标。

- [ ] **Step 4: 点第 3 层页面**

```powershell
mvn spring-boot:run
```

打开 `http://localhost:8081/`。

| Tab | 主操作 |
|-----|--------|
| `agent-session-memory` | `#sessionMemoryMessageInput` 填 `我想周末去杭州`，点 `#sessionMemorySendBtn` |
| `agent-tools` | 点 `#agentToolsPlanBtn` |
| `ask-user` | 点 `#askUserStartBtn` |
| `todo-write` | 点 `#todoStartBtn` |
| `subagent` | 点 `#subagentStartBtn` |
| `a2a` | 点 `#a2aStartBtn` |
| `agent-auto-memory` | 点 `#autoMemorySendBtn` |
| `embabel` | `#embabelMessageInput` 填 `https://example.com`，点 `#embabelSendBtn` |
| `member` | 打开会员 Tab。首页 `#memberPhonePage` 有内容后，若出现商品则点开详情再点立即购买，确认预览区出现；没有商品则记录环境阻塞 |

判定规则与 Task 3 Step 7 相同。Session 页失败退 `spring-ai-session-bom`；Tools / AskUser / Todo / Subagent / A2A / 自主记忆失败退 `spring-ai-agent-utils-bom`；Embabel 抓取失败且堆栈在 jsoup 则退 jsoup（jsoup 已在 Task 3 升级，这里只在新错误来自 Hutool 或 Redisson 时退第 3 层坐标）；会员下单雪花 ID 异常退 hutool；延时关单或 Redisson 类错误退 Redisson。点完停掉应用。

- [ ] **Step 5: 打开文档页**

应用仍在运行时访问：

- `http://localhost:8081/scalar` 预期：页面打开，不是 500
- `http://localhost:8081/v3/api-docs` 预期：HTTP 200，正文是 JSON

打不开且堆栈指向 springdoc 或 swagger：swagger 与 springdoc 一起退回 `2.2.47` 和 `3.0.3`，重跑 `mvn test`。

- [ ] **Step 6: 提交**

```powershell
git add -- demo2/pom.xml
git commit -m "chore(demo2): upgrade Hutool, springdoc, agent-utils, session, and Redisson"
```

有适配源文件时一并加入。

### Task 5: 覆盖 Lombok 1.18.48

**Files:**
- Modify: `demo2/pom.xml` 的 `<properties>`

**Interfaces:**
- Consumes: Task 4 结束后的 POM
- Produces: 属性 `lombok.version` = `1.18.48`。编译插件里已有的 `${lombok.version}` 跟着变，不要再给 lombok 依赖写死版本

- [ ] **Step 1: 在 properties 增加覆盖**

放在 `<java.version>` 旁边：

```xml
        <lombok.version>1.18.48</lombok.version>
```

- [ ] **Step 2: 编译并测试**

```powershell
mvn -q -DskipTests compile
mvn test
```

预期：compile `BUILD SUCCESS`；测试相对基线没有新增失败。失败则删除该属性，注释写 `lombok.version 目标 1.18.48 未采纳：` 加上现象，再重跑两条命令。不改业务代码。

- [ ] **Step 3: 提交**

```powershell
git add -- demo2/pom.xml
git commit -m "chore(demo2): override Lombok to 1.18.48"
```

### Task 6: 覆盖 Caffeine 3.3.0

**Files:**
- Modify: `demo2/pom.xml` 的 `<properties>`

**Interfaces:**
- Consumes: Task 5 的 POM
- Produces: 属性 `caffeine.version` = `3.3.0`。不改 `jetcache.version`

- [ ] **Step 1: 增加属性**

```xml
        <caffeine.version>3.3.0</caffeine.version>
```

- [ ] **Step 2: 确认解析版本并测试**

```powershell
mvn -q dependency:tree "-Dincludes=com.github.ben-manes.caffeine:caffeine"
mvn -q -DskipTests compile
mvn test
```

预期：树中是 `caffeine:jar:3.3.0`；compile 成功；测试无新增失败。失败则删除该属性并写未采纳注释，再重跑 compile 与 test。不改业务代码。

- [ ] **Step 3: 提交**

```powershell
git add -- demo2/pom.xml
git commit -m "chore(demo2): override Caffeine to 3.3.0"
```

### Task 7: 覆盖 MySQL Connector/J 26.7.0

**Files:**
- Modify: `demo2/pom.xml` 的 `<properties>`
- 不改 `shardingsphere.yaml`，除非 compile 或会员下单页面证明驱动类名变了

**Interfaces:**
- Consumes: Task 6 的 POM。数据源仍是 `jdbc:shardingsphere:classpath:shardingsphere.yaml`
- Produces: 属性 `mysql.version` = `26.7.0`，或删除该属性以回到 Boot 管理的 9.7.0

- [ ] **Step 1: 增加属性**

```xml
        <mysql.version>26.7.0</mysql.version>
```

- [ ] **Step 2: 确认驱动版本并测试**

```powershell
mvn -q dependency:tree "-Dincludes=com.mysql:mysql-connector-j"
mvn -q -DskipTests compile
mvn test
```

预期：树中是 `mysql-connector-j:jar:26.7.0`；compile 成功；测试无新增失败。失败则删除 `mysql.version`，注释写 `mysql.version 目标 26.7.0 未采纳：` 加上现象（ShardingSphere 或 MyBatis-Plus 加载失败的第一行），再重跑 compile 与 test。

- [ ] **Step 3: 点 DB 记忆和会员下单**

```powershell
mvn spring-boot:run
```

打开 `http://localhost:8081/`。

- Tab `agent-mysql-memory`：点 `#mysqlMemoryPlanBtn`。连接失败但错误是账号/库不存在，记环境阻塞。`ClassNotFoundException`、`NoClassDefFoundError`、SQLException 驱动类不匹配，则退回 `mysql.version`。
- Tab `member`：走首页 → 商品详情 → 立即购买。同样规则。

点完停掉应用。

- [ ] **Step 4: 提交**

```powershell
git add -- demo2/pom.xml
git commit -m "chore(demo2): override MySQL Connector/J to 26.7.0"
```

### Task 8: 覆盖 Micrometer 1.18.0-M2 与 tracing 1.8.0-M2

**Files:**
- Modify: `demo2/pom.xml` 的 `<properties>`
- Modify（仅编译失败时）: `TraceSupport.java`、`TraceIdFilter.java`、`RocketMqTracePropagator.java`、`AgentExecutionContext.java`、`BaseEventPublisher.java`、`MilvusLazyConfiguration.java`，以及错误点名的 `SimpleTracer` 测试

**Interfaces:**
- Consumes: Task 7 的 POM
- Produces: `micrometer.version` 与 `micrometer-tracing.version` 成对出现，或成对删除

- [ ] **Step 1: 两个属性一起加**

```xml
        <micrometer.version>1.18.0-M2</micrometer.version>
        <micrometer-tracing.version>1.8.0-M2</micrometer-tracing.version>
```

不要只加其中一个。

- [ ] **Step 2: 编译并测试**

```powershell
mvn -q dependency:tree "-Dincludes=io.micrometer:micrometer-core,io.micrometer:micrometer-tracing"
mvn -q -DskipTests compile
mvn test
```

预期：`micrometer-core` 为 `1.18.0-M2`，`micrometer-tracing` 为 `1.8.0-M2`；compile 成功；测试无新增失败。签名变化只改 Step 的 Files 列表里被点名的文件。仍失败则两个属性一起删除，注释写 `micrometer 目标 1.18.0-M2 / tracing 1.8.0-M2 未采纳：` 加上现象，并撤回 Java 改动，再重跑 compile 与 test。

- [ ] **Step 3: 点带 trace 的页面**

```powershell
mvn spring-boot:run
```

- Tab `chat`：输入 `你好`，点 `#sendButton`。响应可以是业务错误，不能是 trace 相关 500。
- Tab `rocketmq`：点「同步发送」，`#mqSendResult` 不能出现 `NoSuchMethodError`。

失败且堆栈在 `io.micrometer`：两个属性一起退回。点完停掉应用。

- [ ] **Step 4: 提交**

```powershell
git add -- demo2/pom.xml
git commit -m "chore(demo2): override Micrometer to 1.18.0-M2 and tracing to 1.8.0-M2"
```

有适配源文件时一并加入。

### Task 9: 全量页面点检并写执行结果

**Files:**
- Modify: `demo2/docs/superpowers/specs/2026-09-24-demo2-dependency-upgrade-design.md`（状态行）
- Modify: 本计划文末「执行结果」

**Interfaces:**
- Consumes: Task 2 至 Task 8 的最终 POM 与基线摘要
- Produces: 一份结果，含已升级坐标、退回坐标、基线失败、页面点检三态

- [x] **Step 1: 再跑一次全量测试**

```powershell
mvn test
```

预期：相对基线没有新增 Failures/Errors。有新增则回到引入它的那一层任务，按该层回退规则处理，然后从该任务重新往下做。不要在本任务里同时改多个层的版本。

- [x] **Step 2: 启动应用**

```powershell
mvn spring-boot:run
```

确认 `http://localhost:8081/` 返回 200。

- [x] **Step 3: 点完全部主操作**

对下表每一行点一次。结果只许写成三种：`通过`、`失败`、`环境阻塞`。

| 页面 | 操作 |
|------|------|
| `chat` | `#sendButton`，输入 `你好` |
| `voice-chat` | `#voiceSendButton` |
| `embedding` | `#embeddingBtn` |
| `rag` | `#ragAskBtn` |
| `rag-opt` | `#ragOptAskBtn` |
| `ecommerce` | `#ecommerceAskBtn` |
| `agent` | `#agentPlanBtn` |
| `agent-memory` | `#memoryPlanBtn` |
| `agent-mysql-memory` | `#mysqlMemoryPlanBtn` |
| `agent-auto-memory` | `#autoMemorySendBtn` |
| `agent-session-memory` | `#sessionMemorySendBtn`，输入 `我想周末去杭州` |
| `agent-tools` | `#agentToolsPlanBtn` |
| `tool-reasoning` | `#toolReasoningSendBtn` |
| `mcp` | `#mcpAskBtn` |
| `lkcoffee` | `#lkCoffeeSendBtn` |
| `multi-agent` | `#multiAgentPlanBtn` |
| `ask-user` | `#askUserStartBtn` |
| `todo-write` | `#todoStartBtn` |
| `subagent` | `#subagentStartBtn` |
| `a2a` | `#a2aStartBtn` |
| `embabel` | `#embabelSendBtn` |
| `agentscope` | `#agentscopeSendBtn` |
| `rocketmq` | `rocketmqSend('sync')` |
| `member` 首页 | `#memberNavHome`，`#memberPhonePage` 有内容 |
| `member` 商品详情 | 点一个商品卡片 |
| `member` 下单预览 | 详情里点立即购买 |
| `member` 订单 | `#memberNavOrders` |
| `member` 我的 | `#memberNavMe` |
| `member` 登录态 | 看 `#memberSessionBox` |
| `member` 分片 | `#memberShardMemberId` = `1001`，点「计算路由」 |
| `member` 台账 | 点「刷新订单+台账」，看 `#memberOrderResult` |
| `/scalar` | 打开 `http://localhost:8081/scalar` |
| `/v3/api-docs` | 打开 `http://localhost:8081/v3/api-docs`，HTTP 200 |

`失败` 且属于类缺失或方法签名：退回该功能对应的坐标，重跑 `mvn test`，并重测该行。`环境阻塞` 不退版本。

- [x] **Step 4: 写执行结果并改 spec 状态**

把 spec 开头的 `**状态**: 已定稿，待实现` 改成 `**状态**: 已实现`。

在本计划「执行结果」填完四段：实际升上去的坐标（含最终版本号）、退回的坐标（目标版本 + 失败现象）、基线里就有的测试失败、上表每一行的三态。

- [x] **Step 5: 停应用并提交**

停掉 `spring-boot:run`。

```powershell
git add -- demo2/docs/superpowers/specs/2026-09-24-demo2-dependency-upgrade-design.md demo2/docs/superpowers/plans/2026-09-24-demo2-dependency-upgrade.md demo2/pom.xml
git commit -m "docs(demo2): record dependency upgrade results"
```

若 Step 3 又改了源文件，把那些文件一并加入。

---

## 执行结果

### 基线

命令：在 `demo2` 目录执行 `mvn test`（未改 `pom.xml`，未启动应用）。

```
Tests run: 402, Failures: 0, Errors: 0, Skipped: 1
```

BUILD SUCCESS，退出码 0。

FAILURE / ERROR：无

Skipped（非失败，仅记录）：
- `com.jason.demo.demo2.order.OrderIdGeneratorConcurrencyStressTest#concurrentNextOrderId_noDuplicatesWithinDuration`（`@EnabledIfSystemProperty(named = "orderId.stress", matches = "true")`，默认跳过）

### 已升级

| 坐标 | 最终版本 |
|------|----------|
| `spring-boot-starter-parent` | 4.1.1 |
| `spring-ai.version` | 2.0.1 |
| MCP `mcp` / `mcp-core` / `mcp-json` / `mcp-json-jackson3` | 2.0.1 |
| `agentscope.version` | 2.0.3 |
| `rocketmq-client.version` | 5.5.1 |
| `spring-retry` | 2.0.13 |
| `jsoup` | 1.23.2 |
| `rewrite-maven-plugin` | 6.46.1 |
| `rewrite-spring` | 6.37.1 |
| `hutool.version` | 5.8.47 |
| `swagger-annotations.version` | 2.2.55 |
| `springdoc-openapi-starter-webmvc-scalar` | 3.1.1 |
| `spring-ai-agent-utils-bom` | 0.12.0 |
| `redisson-spring-boot-starter` | 4.7.0 |
| `lombok.version` | 1.18.48 |
| `caffeine.version` | 3.3.0 |
| `mysql.version` | 26.7.0 |
| `micrometer.version` | 1.18.0-M2 |
| `micrometer-tracing.version` | 1.8.0-M2 |

### 已退回

| 坐标 | 目标版本 | 保留版本 | 失败现象 |
|------|----------|----------|----------|
| `shardingsphere.version` | 5.5.3 | 5.5.2 | `shardingsphere-jdbc` 5.5.3 将 `shardingsphere-sharding-core` 降为 test scope，`ComplexKeysShardingAlgorithm` 不在编译/运行 classpath |
| `spring-ai-session-bom` | 0.8.0 | 0.2.0 | Session 查询需要列 `e.archived`（`AI_SESSION_EVENT`），当前表无该列：`Unknown column 'e.archived' in 'field list'` |

### 页面点检

Task 9 全量复测（`mvn test`：Tests run: 402, Failures: 0, Errors: 0, Skipped: 1；应用 `http://localhost:8081/` HTTP 200）。

| 页面 | 操作 | 结果 |
|------|------|------|
| `chat` | `#sendButton`，输入 `你好` | 通过 |
| `voice-chat` | `#voiceSendButton` | 通过 |
| `embedding` | `#embeddingBtn` | 通过 |
| `rag` | `#ragAskBtn` | 通过 |
| `rag-opt` | `#ragOptAskBtn` | 环境阻塞 |
| `ecommerce` | `#ecommerceAskBtn` | 环境阻塞 |
| `agent` | `#agentPlanBtn` | 通过 |
| `agent-memory` | `#memoryPlanBtn` | 通过 |
| `agent-mysql-memory` | `#mysqlMemoryPlanBtn` | 通过 |
| `agent-auto-memory` | `#autoMemorySendBtn` | 通过 |
| `agent-session-memory` | `#sessionMemorySendBtn`，输入 `我想周末去杭州` | 通过 |
| `agent-tools` | `#agentToolsPlanBtn` | 通过 |
| `tool-reasoning` | `#toolReasoningSendBtn` | 通过 |
| `mcp` | `#mcpAskBtn` | 通过 |
| `lkcoffee` | `#lkCoffeeSendBtn` | 通过 |
| `multi-agent` | `#multiAgentPlanBtn` | 通过 |
| `ask-user` | `#askUserStartBtn` | 通过 |
| `todo-write` | `#todoStartBtn` | 通过 |
| `subagent` | `#subagentStartBtn` | 通过 |
| `a2a` | `#a2aStartBtn` | 通过 |
| `embabel` | `#embabelSendBtn` | 通过 |
| `agentscope` | `#agentscopeSendBtn` | 通过 |
| `rocketmq` | `rocketmqSend('sync')` | 通过 |
| `member` 首页 | `#memberNavHome`，`#memberPhonePage` 有内容 | 通过 |
| `member` 商品详情 | 点一个商品卡片 | 通过 |
| `member` 下单预览 | 详情里点立即购买 | 通过 |
| `member` 订单 | `#memberNavOrders` | 通过 |
| `member` 我的 | `#memberNavMe` | 通过 |
| `member` 登录态 | 看 `#memberSessionBox` | 通过 |
| `member` 分片 | `#memberShardMemberId` = `1001`，点「计算路由」 | 通过 |
| `member` 台账 | 点「刷新订单+台账」，看 `#memberOrderResult` | 通过 |
| `/scalar` | 打开 `http://localhost:8081/scalar` | 通过 |
| `/v3/api-docs` | 打开 `http://localhost:8081/v3/api-docs`，HTTP 200 | 通过 |

说明：`rag-opt` / `ecommerce` 返回 HTTP 200，正文提示 Milvus 未运行（`localhost:19530` Connection refused / 「系统繁忙」），按中间件环境问题记为环境阻塞，未退版本。无新增坐标退回。
