# demo2 依赖升级

**日期**: 2026-09-24  
**项目**: spring-ai-demo / demo2  
**状态**: 已实现  
**范围**: 只改 `demo2/pom.xml`，以及为了让这些版本编译、测试、页面点检通过而必须改的 `demo2/src` 代码。

---

## 1. 背景与目标

demo2 钉在 Spring Boot 4.1.0。一批手写版本和 Boot BOM 管理的版本落后于 Maven Central 稳定版或约定目标。本次把约定范围内的坐标升到目标版本，修掉因此出现的编译错误、测试失败和页面故障。

已确认决策：

| 维度 | 选择 |
|------|------|
| 范围 | 方案 B：补丁线、行为可能变化的库、rewrite 插件，以及脱离 Boot BOM 的 Lombok、Caffeine、MySQL Connector/J、Micrometer |
| 做法 | 分四层升级。上一层 `mvn test` 和该层页面点检通过后，才改下一层 |
| 失败 | 改完代码仍失败的坐标退回原版本，写明原因。其余坐标照升 |
| 验收 | `mvn test` 退出码 0，再加上全量页面点检 |

## 2. 版本目标

能用属性覆盖的，覆盖 Spring Boot 属性，不在每个依赖上再写 `<version>`。包括 `lombok.version`、`caffeine.version`、`mysql.version`、`micrometer.version`。Micrometer 升到 1.18.0-M2 时，`micrometer-tracing.version` 一并设为 **1.8.0-M2**。

| 层 | 坐标 | 现在 | 目标 |
|----|------|------|------|
| 1 平台 | `spring-boot-starter-parent` | 4.1.0 | 4.1.1 |
| 2 补丁 | `spring-ai.version` | 2.0.0 | 2.0.1 |
| 2 | MCP `mcp` / `mcp-core` / `mcp-json` / `mcp-json-jackson3` | 2.0.0 | 2.0.1 |
| 2 | `agentscope.version` | 2.0.0 | 2.0.3 |
| 2 | `rocketmq-client.version` | 5.5.0 | 5.5.1 |
| 2 | `shardingsphere.version` | 5.5.2 | 5.5.3 |
| 2 | `spring-retry` | 2.0.12 | 2.0.13 |
| 2 | `jsoup` | 1.22.2 | 1.23.2 |
| 2 | `rewrite-maven-plugin` | 6.32.0 | 6.46.1 |
| 2 | `rewrite-spring` | 6.30.4 | 6.37.1 |
| 3 行为可能变 | `hutool.version` | 5.8.35 | 5.8.47 |
| 3 | `swagger-annotations.version` | 2.2.47 | 2.2.55 |
| 3 | `springdoc-openapi-starter-webmvc-scalar` | 3.0.3 | 3.1.1 |
| 3 | `spring-ai-agent-utils-bom` | 0.10.0 | 0.12.0 |
| 3 | `spring-ai-session-bom` | 0.2.0 | 0.8.0 |
| 3 | `redisson-spring-boot-starter` | 4.1.0 | 4.7.0 |
| 4 脱离 BOM | `lombok.version` | 1.18.46（Boot 管） | 1.18.48 |
| 4 | `caffeine.version` | 3.2.4 | 3.3.0 |
| 4 | `mysql.version` | 9.7.0 | 26.7.0 |
| 4 | `micrometer.version` + tracing | 1.17.1 / 1.7.1 | 1.18.0-M2 / 1.8.0-M2 |

AgentScope 升到 2.0.3 之后，MCP 的显式覆盖仍放在 `agentscope-bom` 后面，版本改为 2.0.1。若 2.0.3 的 BOM 已经依赖 MCP 2.0.1，覆盖保留，用来锁住版本。

以下保持不动：`embabel-agent` 2.0.0-SNAPSHOT、JetCache 2.8.0.RC、lock4j 2.2.7、MyBatis-Plus 3.5.17、MapStruct 1.6.3、TTL 2.14.5、Kryo5 5.6.2、Guava 33.7.1-jre、COLA 5.0.0、`spring-ai-a2a-server-autoconfigure` 0.3.0、`lombok-mapstruct-binding` 0.2.0。Java 仍是 21。

`demo/pom.xml`、`demo2/workspace/project/pom.xml`、历史 spec / plan 不改。`pom.xml` 里因版本变化而说错的注释要改。

## 3. 执行顺序与回退

工作目录是 `demo2`。严格按第 1 层到第 4 层做。

每一层：

1. 只改这一层的版本，以及因此说错的 `pom.xml` 注释。
2. 修这一层引起的编译错误。
3. 跑 `mvn test`，修这一层引起的测试失败。
4. 启动应用，点这一层对应的页面。
5. 改完代码仍然失败，就在本层里定位是哪一个坐标。只把那个坐标退回原版本，并在版本旁边写一行注释：目标版本、失败现象。为它写的适配代码一并撤回。
6. 退回之后再跑 `mvn test` 并重测相关页面，通过了才进入下一层。

层内规则：

- 第 1 层只有 Spring Boot parent。它过不了就整层退回 4.1.0，后面三层继续升。
- 第 2 层、第 3 层先整层升。失败后再在层内定位，只退那一个坐标。
- 必须一起进退：MCP 的四个 artifact；Micrometer 1.18.0-M2 和 tracing 1.8.0-M2；swagger-annotations 和 springdoc。
- 第 4 层四个属性互不依赖，按 Lombok、Caffeine、MySQL、Micrometer 的顺序逐个升、逐个测。失败只退当前这一个。
- Redisson 升到 4.7.0 时，lock4j 2.2.7 和 JetCache 2.8.0.RC 保持不动。若这两者跟 Redisson 4.7 对不上，Redisson 退回 4.1.0。
- MySQL Connector/J 26.7.0 若和现有的 ShardingSphere 或 MyBatis-Plus 对不上，MySQL 驱动退回 Boot 管理的 9.7.0。

开始改第 1 层之前，先跑一次 `mvn test`，记下基线：哪些失败、哪些被跳过。之后每一层只对本层新出现的失败负责。升级前就已经失败的测试不在这次范围内，最后说明里单独列出。

## 4. 要核对的代码

这些是直接调用了待升级库的代码。某一层升完后先编译。编译失败或行为和升级前不一致，就改清单里的文件。编译通过且行为不变的文件不改。`@Schema`、`@Operation`、`@Tag` 只有在注解方法被删掉、编译失败时才改。

不顺手重构，不改接口路径，不改业务规则。

| 升级项 | 要核对的代码 | 预期 |
|--------|----------------|------|
| Spring Boot 4.1.1（含 Jackson 3.1.5） | `framework/jackson/JacksonJsonCustomizer` | 补丁，多半不用改 |
| Spring AI 2.0.1 | `MemoryConfig`、`MysqlMemoryConfig`、`LkCoffeeAgentConfig`、`ToolReasoningAgentConfig`、`ToolReasoningAgentService`、`AutoMemoryTripAgentService`、`VoiceApiController`、`McpChatController` | 补丁，核对 `ChatMemory`、TTS、MCP ToolCallback |
| MCP 2.0.1 | `AgentscopeMcpClientRegistry`、`McpFilesystemTools`、`McpClientLifecycle`、`LkCoffeeMcpTransportConfig`、`LkCoffeeMcpToolCallbacksProvider`、`McpServerConfig` | 核对 `McpClient.sync`、`McpSchema`、transport |
| AgentScope 2.0.3 | `AgentScopeConfig`、`AgentscopeDevAgentRegistry`、`LoggingAgentscopeModel`、`FileChangeTool`、`ProjectInfoTools`、`NewlineFlatteningSandbox`、`LiveSandboxPlanReader`、`RiskReviewA2aController`、`RiskReviewAgentCardController` | 核对 Harness、权限、沙箱、A2A |
| RocketMQ 5.5.1 | `RocketMessageConcurrentlyListener`、`OrderConcurrentListener`、`framework/rocketmq` 下的 producer / `RocketMqTracePropagator` | 补丁，核对 `MessageExt`、发送 API |
| ShardingSphere 5.5.3 | `OrderComplexShardingAlgorithm`、`OrderShardGene`、`shardingsphere.yaml` | 核对 `ComplexKeysShardingAlgorithm` |
| jsoup 1.23.2 | `ArticleFetchService` | 核对 `Jsoup.connect`、DOM 取值 |
| Hutool 5.8.47 | `SnowflakeIdGenerator` | 核对 `IdUtil.getSnowflake` |
| springdoc 3.1.1 + swagger 2.2.55 | `OpenApiConfig`，以及全部 `@Tag` / `@Operation` / `@Schema` | 先看 `OpenApiConfig` 和 `/scalar` 能否打开 |
| agent-utils 0.12.0 | `SkillsAgentConfig`、`SkillsAgentService`、`SubagentAgentConfig`、`A2aOrchestratorService`、`A2aWeatherAgentConfig`、`TodoAgentConfig`、`TodoAgentService`、`TodoSseBridge`、`AskUserAgentConfig`、`AskUserAgentService`、`WebQuestionHandler`、`AutoMemoryTripAgentService` | 工具类、Subagent、Advisor 签名变了就改 |
| session 0.8.0 | `SessionMemoryAgentConfig`、`SessionMemoryTripAgentService` 及其测试 | 优先看 `SessionService`、`SessionEvent`、`SessionMemoryAdvisor`、`SessionEventTools` |
| Redisson 4.7.0 | `RedissonDelayBackend`；测试里的 `RedissonAutoConfigurationV4` | 核对 `RDelayedQueue`。和 lock4j、JetCache 对不上就退回 4.1.0 |
| Micrometer 1.18.0-M2 + tracing 1.8.0-M2 | `TraceSupport`、`TraceIdFilter`、`RocketMqTracePropagator`、`AgentExecutionContext`、`BaseEventPublisher`、`MilvusLazyConfiguration`，以及使用 `SimpleTracer` 的测试 | 核对 `Tracer`、`Span`、`Propagator`、`ContextSnapshot` |
| Lombok 1.18.48、Caffeine 3.3.0、MySQL Connector/J 26.7.0、spring-retry 2.0.13、rewrite 插件 | 无直接业务调用。MySQL 驱动只影响 ShardingSphere 后面的物理连接 | 不改业务代码；MySQL 驱动连不上物理库就退回 9.7.0 |

## 5. 验证

### 5.1 自动化测试

验收命令：在 `demo2` 目录执行 `mvn test`，退出码为 0。

跳过规则保持原样。带 `@EnabledIf`（Redis、Postgres）或 `@EnabledIfSystemProperty`（订单号压测）的测试，环境不在就继续跳过。不为这次升级去起一套新的依赖环境，除非点页面时应用本身需要它们才能启动。

跑法：

- 第 1 到第 3 层：每层改完并修好本层编译和测试后，跑一次 `mvn test`。
- 第 4 层：Lombok、Caffeine、MySQL、Micrometer 每升一个跑一次。
- 四层都做完，再跑一次 `mvn test` 作为总验收。

### 5.2 页面点检

应用端口是 `8081`。每一层 `mvn test` 通过后启动应用，点这一层影响到的页面。四层都做完，再做一次全量点检。

通过标准：Tab 能打开，主按钮有响应。接口返回业务错误可以。白屏、前端脚本报错、HTTP 500、`NoSuchMethodError` 算失败。外部密钥或中间件没配好时，记成环境阻塞，不因此退版本。启动失败、类缺失、方法签名错误，按第 3 节回退对应坐标，然后重跑 `mvn test` 并重测相关页面。

全量要点的页面：

- 首页 24 个 Tab，每个做一次主操作：AI 聊天、语音对话、Embedding、RAG 基础、RAG 优化、电商客服、行程规划、记忆行程、DB 记忆、自主记忆、Session 记忆、Agent Tools、工具推理、MCP Client、瑞幸点单、多 Agent、AskUser、TodoWrite、Subagent、A2A、Embabel、AgentScope、RocketMQ、会员。
- 会员 Tab 里的子页面：首页商品列表、商品详情、下单预览、订单、我的，以及侧栏的登录态、分片计算、订单台账。
- 文档页：`http://localhost:8081/scalar` 和 `http://localhost:8081/v3/api-docs`。

各层点页面：

| 层 | 要点的页面 |
|----|------------|
| 1 | 应用能起来，24 个 Tab 都能切换 |
| 2 | 聊天、Embedding、RAG、MCP、瑞幸、AgentScope、RocketMQ、会员里的分片 |
| 3 | Session 记忆、Agent Tools、AskUser、TodoWrite、Subagent、A2A、自主记忆、Embabel、会员下单 |
| 4 | DB 记忆、会员下单，以及带 trace 的聊天 / RocketMQ |

## 6. 完成标准

结束时给出一份结果：

- 实际升上去的坐标
- 按规则退回的坐标（目标版本和失败现象）
- 基线里就有的测试失败
- 页面点检结果（通过、失败、环境阻塞）
