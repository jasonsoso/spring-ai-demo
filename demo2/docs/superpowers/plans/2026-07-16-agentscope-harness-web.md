# AgentScope HarnessAgent Web Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 demo2 接入 AgentScope Java 2.0，用克制的 HarnessAgent 提供 `POST /agentscope/dev-agent/ask` SSE 接口与「AgentScope」测试 Tab，跑通流式清单与会话边界。

**Architecture:** 独立包 `com.jason.demo.demo2.agentscope`；`AgentScopeConfig` 注册 `OpenAIChatModel` + 工具全关的 `HarnessAgent`；`DevAgentService` 用 `RuntimeContext(userId, sessionId)` 调用 `streamEvents`，过滤 `TextBlockDeltaEvent` 并映射为 `SESSION`/`MESSAGE`/`DONE`/`ERROR`；前端 Tab 用 `fetch` + `ReadableStream` 按 JSON `type` 字段消费 SSE。

**Tech Stack:** Java 21, Spring Boot 4.1.0, AgentScope Java 2.0.0 (`agentscope-harness` + `agentscope-extensions-model-openai`), DeepSeek `deepseek-v4-pro`, Reactor `Flux`, 原生 HTML/CSS/JS

**设计规范:** [docs/superpowers/specs/2026-07-16-agentscope-harness-web-design.md](../specs/2026-07-16-agentscope-harness-web-design.md)

## Global Constraints

- **AgentScope 版本**：`2.0.0`（属性 `agentscope.version`）
- **模型**：`deepseek-v4-pro`（独立配置 `app.agentscope.dev-agent.model.*`，复用 `DEEPSEEK_API_KEY`）
- **API**：`POST /agentscope/dev-agent/ask`，`produces=text/event-stream`
- **端口**：`8081`（demo2 现有）
- **SSE 事件 type**：`SESSION` | `MESSAGE` | `DONE` | `ERROR`
- **状态**：`InMemoryAgentStateStore`；工具全部 disable，并 `removeTool("wait_async_results")`
- **不修改** 现有 Spring AI / Embabel 模块行为
- **不降级** Spring Boot 4.1.0
- **中文 Prompt**：放入 UTF-8 YAML（对齐 Embabel），避免 `.properties` Latin-1 乱码
- **编译门禁**：`mvn -f demo2/pom.xml -DskipTests compile` 必须 SUCCESS
- **缺 Key**：不阻止应用启动；首次 `ask` 返回 SSE `ERROR`

---

## File Structure

| 文件 | 职责 |
|------|------|
| `demo2/pom.xml` | `agentscope.version` + 两个 AgentScope 依赖 |
| `demo2/src/main/resources/application.properties` | 模型/name 配置 + `spring.config.import` YAML |
| `demo2/src/main/resources/application-agentscope-prompts.yml` | 中文 system-prompt |
| `.../agentscope/config/DevAgentProperties.java` | `@ConfigurationProperties(prefix="app.agentscope.dev-agent")` |
| `.../agentscope/config/AgentScopeConfig.java` | `Model` + `HarnessAgent` Bean |
| `.../agentscope/model/DevAgentRequest.java` | 请求 DTO |
| `.../agentscope/model/DevAgentEvent.java` | SSE 业务事件 + 工厂方法 |
| `.../agentscope/service/DevAgentService.java` | RuntimeContext + 事件映射 + 缺 Key / onErrorResume |
| `.../agentscope/controller/DevAgentController.java` | REST SSE 入口 |
| `.../test/.../agentscope/service/DevAgentServiceTest.java` | 事件映射单元测试 |
| `.../test/.../agentscope/model/DevAgentEventTest.java` | 工厂方法测试 |
| `static/css/tabs/agentscope.css` | Tab 样式 |
| `static/js/tabs/agentscope.js` | SSE 聊天气泡 + 换会话 |
| `static/index.html` | Tab 按钮、面板、link/script |
| `demo2/README.md` + 根 `README.md` | 功能表一行 + curl |

---

### Task 1: Maven 依赖与编译门禁

**Files:**
- Modify: `demo2/pom.xml`

**Interfaces:**
- Produces: 后续任务可 `import io.agentscope.*`；属性 `${agentscope.version}` = `2.0.0`

- [ ] **Step 1: 在 `<properties>` 增加版本属性**

在现有 `<java.version>` / `<spring-ai.version>` 附近追加：

```xml
<agentscope.version>2.0.0</agentscope.version>
```

- [ ] **Step 2: 在 `<dependencies>` 中追加（建议放在 Embabel 相关依赖附近）**

```xml
<!-- AgentScope Java 2.0：HarnessAgent Web 上手 -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-harness</artifactId>
    <version>${agentscope.version}</version>
</dependency>
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-openai</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

- [ ] **Step 3: 编译验证**

Run:

```bash
mvn -f demo2/pom.xml -DskipTests compile
```

Expected: `BUILD SUCCESS`（若传递依赖冲突，先记录冲突树 `mvn -f demo2/pom.xml dependency:tree -Dincludes=io.agentscope`，再按最小排除修复，不改 Spring AI 主依赖版本）

- [ ] **Step 4: Commit**

```bash
git add demo2/pom.xml
git commit -m "build(demo2): add AgentScope Java 2.0 harness dependencies"
```

---

### Task 2: 请求/事件模型与配置属性

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentRequest.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentEvent.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/DevAgentProperties.java`
- Create: `demo2/src/main/resources/application-agentscope-prompts.yml`
- Modify: `demo2/src/main/resources/application.properties`
- Test: `demo2/src/test/java/com/jason/demo/demo2/agentscope/model/DevAgentEventTest.java`

**Interfaces:**
- Produces:
  - `DevAgentRequest(String userId, String sessionId, String message)` — `sessionId`/`message` `@NotBlank`
  - `DevAgentEvent(String type, String sessionId, String content)` + `session` / `message` / `done` / `error` 静态工厂
  - `DevAgentProperties(String name, String systemPrompt, Model model)`；`Model(String apiKey, String baseUrl, String name)`；prefix `app.agentscope.dev-agent`
- Notes: `Demo2Application` 已有 `@ConfigurationPropertiesScan`，无需改启动类

- [ ] **Step 1: 写失败测试（事件工厂）**

```java
package com.jason.demo.demo2.agentscope.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DevAgentEventTest {

    @Test
    void factories_setExpectedTypes() {
        assertThat(DevAgentEvent.session("s1").type()).isEqualTo("SESSION");
        assertThat(DevAgentEvent.message("s1", "hi").content()).isEqualTo("hi");
        assertThat(DevAgentEvent.done("s1").type()).isEqualTo("DONE");
        assertThat(DevAgentEvent.error("s1", "boom").type()).isEqualTo("ERROR");
        assertThat(DevAgentEvent.error("s1", "boom").content()).isEqualTo("boom");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=DevAgentEventTest test
```

Expected: FAIL（类不存在）

- [ ] **Step 3: 实现模型与属性**

`DevAgentRequest.java`:

```java
package com.jason.demo.demo2.agentscope.model;

import jakarta.validation.constraints.NotBlank;

public record DevAgentRequest(
        String userId,
        @NotBlank String sessionId,
        @NotBlank String message) {
}
```

`DevAgentEvent.java`:

```java
package com.jason.demo.demo2.agentscope.model;

public record DevAgentEvent(String type, String sessionId, String content) {

    public static DevAgentEvent session(String sessionId) {
        return new DevAgentEvent("SESSION", sessionId, "");
    }

    public static DevAgentEvent message(String sessionId, String content) {
        return new DevAgentEvent("MESSAGE", sessionId, content == null ? "" : content);
    }

    public static DevAgentEvent done(String sessionId) {
        return new DevAgentEvent("DONE", sessionId, "");
    }

    public static DevAgentEvent error(String sessionId, String content) {
        return new DevAgentEvent("ERROR", sessionId, content == null ? "" : content);
    }
}
```

`DevAgentProperties.java`:

```java
package com.jason.demo.demo2.agentscope.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.agentscope.dev-agent")
public record DevAgentProperties(
        @NotBlank String name,
        @NotBlank String systemPrompt,
        @Valid Model model) {

    public record Model(
            @NotBlank String apiKey,
            @NotBlank String baseUrl,
            @NotBlank String name) {
    }
}
```

注意：缺 `DEEPSEEK_API_KEY` 时 `apiKey` 可能为空字符串。若 `@NotBlank` 导致上下文启动失败，将 `Model.apiKey` 改为无 `@NotBlank` 的 `String apiKey`（允许空，由 Service 在 `ask` 时报错），并在本任务同步调整。

- [ ] **Step 4: 配置文件**

`application-agentscope-prompts.yml`:

```yaml
# AgentScope DevAgent 中文 Prompt（YAML 保证 UTF-8）
app:
  agentscope:
    dev-agent:
      system-prompt: |
        你是一个研发任务整理助手。
        用户给出一个待处理的问题后，把它整理成简洁、可执行的检查清单。
        当前没有接入项目文件、日志、数据库或外部工具，不要声称已经完成查询、修改或保存。
        信息不足时指出需要补充的关键内容，不要编造排查结果。
        回答控制在 6 条以内，每条只写一个检查动作，不写开场白和总结。
        使用中文回答。
```

在 `application.properties` 末尾追加（`spring.config.import` 若已有 embabel 行，改为逗号追加或再写一行 import——Spring Boot 允许多次 import；推荐与 embabel 同一行逗号分隔）：

```properties
# AgentScope HarnessAgent（研发任务清单）
app.agentscope.dev-agent.name=dev-task-agent
app.agentscope.dev-agent.model.api-key=${DEEPSEEK_API_KEY:}
app.agentscope.dev-agent.model.base-url=https://api.deepseek.com
app.agentscope.dev-agent.model.name=deepseek-v4-pro
```

并把现有：

```properties
spring.config.import=optional:classpath:application-embabel-prompts.yml
```

改为：

```properties
spring.config.import=optional:classpath:application-embabel-prompts.yml,optional:classpath:application-agentscope-prompts.yml
```

- [ ] **Step 5: 运行测试确认通过**

```bash
mvn -f demo2/pom.xml -Dtest=DevAgentEventTest test
```

Expected: `BUILD SUCCESS`，测试 PASS

- [ ] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope \
  demo2/src/test/java/com/jason/demo/demo2/agentscope \
  demo2/src/main/resources/application.properties \
  demo2/src/main/resources/application-agentscope-prompts.yml
git commit -m "feat(demo2): add AgentScope request/event models and properties"
```

---

### Task 3: DevAgentService（TDD）

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java`

**Interfaces:**
- Consumes: `HarnessAgent.streamEvents(String, RuntimeContext)` → `Flux<? extends AgentEvent>`；`TextBlockDeltaEvent#getDelta()`；`DevAgentRequest`；`DevAgentProperties.model().apiKey()`
- Produces: `Flux<DevAgentEvent> ask(DevAgentRequest request)`

- [ ] **Step 1: 写失败测试**

```java
package com.jason.demo.demo2.agentscope.service;

import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import com.jason.demo.demo2.agentscope.model.DevAgentEvent;
import com.jason.demo.demo2.agentscope.model.DevAgentRequest;
import io.agentscope.core.agent.Event; // 若包名不同，以 IDE/jar 实际 import 为准
import io.agentscope.core.agent.event.TextBlockDeltaEvent; // 以实际包名为准
import io.agentscope.harness.HarnessAgent; // 以实际包名为准
import io.agentscope.core.RuntimeContext; // 以实际包名为准
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DevAgentServiceTest {

    @Mock
    HarnessAgent harnessAgent;

    DevAgentProperties properties;
    DevAgentService service;

    @BeforeEach
    void setUp() {
        properties = new DevAgentProperties(
                "dev-task-agent",
                "prompt",
                new DevAgentProperties.Model("sk-test", "https://api.deepseek.com", "deepseek-v4-pro"));
        service = new DevAgentService(harnessAgent, properties);
    }

    @Test
    void ask_emitsSessionMessagesAndDone() {
        TextBlockDeltaEvent d1 = mock(TextBlockDeltaEvent.class);
        TextBlockDeltaEvent d2 = mock(TextBlockDeltaEvent.class);
        when(d1.getDelta()).thenReturn("1.");
        when(d2.getDelta()).thenReturn("确认超时时间段");
        when(harnessAgent.streamEvents(eq("帮我整理"), any(RuntimeContext.class)))
                .thenReturn(Flux.just(d1, d2));

        StepVerifier.create(service.ask(new DevAgentRequest("u1", "s1", "帮我整理")))
                .expectNext(DevAgentEvent.session("s1"))
                .expectNext(DevAgentEvent.message("s1", "1."))
                .expectNext(DevAgentEvent.message("s1", "确认超时时间段"))
                .expectNext(DevAgentEvent.done("s1"))
                .verifyComplete();

        ArgumentCaptor<RuntimeContext> ctx = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(harnessAgent).streamEvents(eq("帮我整理"), ctx.capture());
        // 断言 sessionId / userId 已写入；getter 名以 RuntimeContext 实际 API 为准
        assertThat(ctx.getValue().getSessionId()).isEqualTo("s1");
        assertThat(ctx.getValue().getUserId()).isEqualTo("u1");
    }

    @Test
    void ask_blankApiKey_emitsErrorWithoutCallingAgent() {
        service = new DevAgentService(
                harnessAgent,
                new DevAgentProperties(
                        "dev-task-agent",
                        "prompt",
                        new DevAgentProperties.Model("  ", "https://api.deepseek.com", "deepseek-v4-pro")));

        StepVerifier.create(service.ask(new DevAgentRequest(null, "s1", "hi")))
                .expectNext(DevAgentEvent.session("s1"))
                .expectNextMatches(e -> "ERROR".equals(e.type()) && e.content().contains("DEEPSEEK_API_KEY"))
                .verifyComplete();
    }

    @Test
    void ask_streamFailure_emitsError() {
        when(harnessAgent.streamEvents(any(), any()))
                .thenReturn(Flux.error(new RuntimeException("upstream down")));

        StepVerifier.create(service.ask(new DevAgentRequest("u1", "s1", "hi")))
                .expectNext(DevAgentEvent.session("s1"))
                .expectNextMatches(e -> "ERROR".equals(e.type()) && e.content().contains("upstream down"))
                .verifyComplete();
    }
}
```

**Import 校正说明（实现时必须做）：** Task 1 编译通过后，用 IDE 或 `jar tf` 确认 `HarnessAgent`、`RuntimeContext`、`TextBlockDeltaEvent`、`streamEvents` 的真实包名与方法签名，再改测试/实现中的 import 与 getter；不得臆造 API。

若项目测试 classpath 无 `reactor-test`，在 `demo2/pom.xml` 的 test 依赖中增加：

```xml
<dependency>
    <groupId>io.projectreactor</groupId>
    <artifactId>reactor-test</artifactId>
    <scope>test</scope>
</dependency>
```

（Boot parent 通常已管理版本。）

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -f demo2/pom.xml -Dtest=DevAgentServiceTest test
```

Expected: FAIL（`DevAgentService` 不存在）

- [ ] **Step 3: 最小实现**

```java
package com.jason.demo.demo2.agentscope.service;

import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import com.jason.demo.demo2.agentscope.model.DevAgentEvent;
import com.jason.demo.demo2.agentscope.model.DevAgentRequest;
import io.agentscope.core.RuntimeContext; // 校正包名
import io.agentscope.core.agent.event.TextBlockDeltaEvent; // 校正包名
import io.agentscope.harness.HarnessAgent; // 校正包名
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class DevAgentService {

    private final HarnessAgent devAgent;
    private final DevAgentProperties properties;

    public DevAgentService(HarnessAgent devAgent, DevAgentProperties properties) {
        this.devAgent = devAgent;
        this.properties = properties;
    }

    public Flux<DevAgentEvent> ask(DevAgentRequest request) {
        String sessionId = request.sessionId();
        String apiKey = properties.model().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return Flux.just(
                    DevAgentEvent.session(sessionId),
                    DevAgentEvent.error(sessionId, "DEEPSEEK_API_KEY is not configured"));
        }

        RuntimeContext.Builder contextBuilder = RuntimeContext.builder()
                .sessionId(sessionId);
        if (request.userId() != null && !request.userId().isBlank()) {
            contextBuilder.userId(request.userId().strip());
        }
        RuntimeContext context = contextBuilder.build();

        Flux<DevAgentEvent> messages = devAgent
                .streamEvents(request.message(), context)
                .ofType(TextBlockDeltaEvent.class)
                .map(event -> DevAgentEvent.message(sessionId, event.getDelta()));

        return Flux.concat(
                        Mono.just(DevAgentEvent.session(sessionId)),
                        messages,
                        Mono.just(DevAgentEvent.done(sessionId)))
                .onErrorResume(ex -> Flux.just(
                        DevAgentEvent.error(sessionId, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage())));
    }
}
```

注意：`onErrorResume` 挂在 `Flux.concat(...)` 上时，若 `SESSION` 已发出后 `messages` 失败，订阅方会先收到 SESSION，再收到 ERROR（符合设计）。若 concat 在 SESSION 之后失败，`DONE` 不会发出。

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -f demo2/pom.xml -Dtest=DevAgentServiceTest,DevAgentEventTest test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/service \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/service \
  demo2/pom.xml
git commit -m "feat(demo2): map HarnessAgent streamEvents to DevAgent SSE events"
```

---

### Task 4: AgentScopeConfig + Controller

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/controller/DevAgentController.java`

**Interfaces:**
- Consumes: `DevAgentProperties`；`DevAgentService.ask`
- Produces: `@Bean Model`（AgentScope）、`@Bean HarnessAgent`（供 `DevAgentService` 注入）；`POST /agentscope/dev-agent/ask` → `Flux<DevAgentEvent>`

- [ ] **Step 1: 实现 AgentScopeConfig**

包名以 Task 1 后的实际 API 为准（下方为文章等价结构）：

```java
package com.jason.demo.demo2.agentscope.config;

import io.agentscope.core.model.Model; // 校正
import io.agentscope.extensions.model.openai.DeepSeekFormatter; // 校正
import io.agentscope.extensions.model.openai.OpenAIChatModel; // 校正
import io.agentscope.harness.HarnessAgent; // 校正
import io.agentscope.harness.state.InMemoryAgentStateStore; // 校正
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class AgentScopeConfig {

    @Bean
    Model agentscopeDeepSeekModel(DevAgentProperties properties) {
        DevAgentProperties.Model model = properties.model();
        return OpenAIChatModel.builder()
                .apiKey(model.apiKey() == null ? "" : model.apiKey())
                .baseUrl(model.baseUrl())
                .modelName(model.name())
                .formatter(new DeepSeekFormatter())
                .stream(true)
                .build();
    }

    @Bean
    HarnessAgent agentscopeDevAgent(Model agentscopeDeepSeekModel, DevAgentProperties properties)
            throws IOException {
        HarnessAgent agent = HarnessAgent.builder()
                .name(properties.name())
                .sysPrompt(properties.systemPrompt())
                .model(agentscopeDeepSeekModel)
                .stateStore(new InMemoryAgentStateStore())
                .enableAgentTracingLog(false)
                .disableFilesystemTools()
                .disableShellTool()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableCompaction()
                .disableSubagents()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableToolsConfig()
                .build();
        agent.getToolkit().removeTool("wait_async_results");
        return agent;
    }
}
```

Bean 方法名避免与 Spring AI 的 `ChatModel` 混淆；若存在多个 `Model` 类型冲突，给 AgentScope `Model` Bean 使用 `@Qualifier("agentscopeDeepSeekModel")`，并在 `HarnessAgent` 参数上同样加 `@Qualifier`。

- [ ] **Step 2: 实现 Controller**

```java
package com.jason.demo.demo2.agentscope.controller;

import com.jason.demo.demo2.agentscope.model.DevAgentEvent;
import com.jason.demo.demo2.agentscope.model.DevAgentRequest;
import com.jason.demo.demo2.agentscope.service.DevAgentService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/agentscope/dev-agent")
public class DevAgentController {

    private final DevAgentService devAgentService;

    public DevAgentController(DevAgentService devAgentService) {
        this.devAgentService = devAgentService;
    }

    @PostMapping(path = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<DevAgentEvent> ask(@Valid @RequestBody DevAgentRequest request) {
        return devAgentService.ask(request);
    }
}
```

- [ ] **Step 3: 编译 + 单元测试**

```bash
mvn -f demo2/pom.xml -DskipTests compile
mvn -f demo2/pom.xml -Dtest=DevAgentServiceTest,DevAgentEventTest test
```

Expected: SUCCESS / PASS

- [ ] **Step 4: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope
git commit -m "feat(demo2): wire HarnessAgent beans and SSE controller"
```

---

### Task 5: 前端 AgentScope Tab

**Files:**
- Create: `demo2/src/main/resources/static/css/tabs/agentscope.css`
- Create: `demo2/src/main/resources/static/js/tabs/agentscope.js`
- Modify: `demo2/src/main/resources/static/index.html`（在 Embabel Tab 按钮后追加按钮；在 `tab-embabel` 面板后追加面板；增加 css/js 引用）

**Interfaces:**
- Consumes: `POST /agentscope/dev-agent/ask`，SSE `data:` JSON = `DevAgentEvent`（看 `type` 字段，不是 SSE `event:` 名）
- Produces: 可换会话的流式对话 UI

- [ ] **Step 1: 追加 CSS（可复用通用 `.message`）**

`agentscope.css` 最小集：

```css
.agentscope-header { margin-bottom: 12px; }
.agentscope-meta { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 8px; align-items: center; }
.agentscope-meta label { font-size: 13px; display: flex; gap: 4px; align-items: center; }
.agentscope-meta input { min-width: 160px; padding: 4px 8px; }
.agentscope-status { font-size: 12px; color: #64748b; margin-bottom: 8px; }
.agentscope-messages { min-height: 280px; max-height: 480px; overflow-y: auto; margin-bottom: 12px; }
.agentscope-input-bar { display: flex; gap: 8px; }
.agentscope-input-bar textarea { flex: 1; }
```

- [ ] **Step 2: 实现 `agentscope.js`**

```javascript
// ========== AgentScope HarnessAgent ==========
function newAgentscopeSessionId() {
    if (crypto.randomUUID) return crypto.randomUUID();
    return 'sess-' + Date.now() + '-' + Math.random().toString(16).slice(2);
}

function ensureAgentscopeSessionId() {
    const el = document.getElementById('agentscopeSessionId');
    if (el && !el.value.trim()) el.value = newAgentscopeSessionId();
}

function resetAgentscopeConversation() {
    const box = document.getElementById('agentscopeMessages');
    if (!box) return;
    box.innerHTML = '<div id="agentscopeWelcome" class="message assistant"><div class="message-content">'
        + '输入研发任务，获取可执行检查清单。可点「换会话」验证 session 隔离。'
        + '</div></div>';
    document.getElementById('agentscopeSessionId').value = newAgentscopeSessionId();
    setAgentscopeStatus('就绪');
}

function setAgentscopeStatus(text) {
    const el = document.getElementById('agentscopeStatus');
    if (el) el.textContent = text;
}

function setAgentscopeInputEnabled(enabled) {
    const input = document.getElementById('agentscopeMessageInput');
    const btn = document.getElementById('agentscopeSendBtn');
    if (input) input.disabled = !enabled;
    if (btn) btn.disabled = !enabled;
}

function scrollAgentscopeMessages() {
    const box = document.getElementById('agentscopeMessages');
    if (box) box.scrollTop = box.scrollHeight;
}

function appendAgentscopeBubble(text, isUser) {
    const box = document.getElementById('agentscopeMessages');
    const welcome = document.getElementById('agentscopeWelcome');
    if (welcome) welcome.remove();
    const div = document.createElement('div');
    div.className = 'message ' + (isUser ? 'user' : 'assistant');
    const content = document.createElement('div');
    content.className = 'message-content';
    content.textContent = text || '';
    div.appendChild(content);
    box.appendChild(div);
    scrollAgentscopeMessages();
    return content;
}

function fillAgentscopeSample(n) {
    const samples = {
        1: '帮我整理一份今天排查订单接口超时的执行清单',
        2: '支付回调偶发 500，给我一份不超过 6 步的排查顺序'
    };
    const input = document.getElementById('agentscopeMessageInput');
    if (input) {
        input.value = samples[n] || '';
        input.focus();
    }
}

async function sendAgentscopeMessage() {
    ensureAgentscopeSessionId();
    const message = document.getElementById('agentscopeMessageInput').value.trim();
    const sessionId = document.getElementById('agentscopeSessionId').value.trim();
    const userId = document.getElementById('agentscopeUserId').value.trim();
    if (!message || !sessionId) return;

    appendAgentscopeBubble(message, true);
    document.getElementById('agentscopeMessageInput').value = '';
    const assistant = appendAgentscopeBubble('', false);
    setAgentscopeInputEnabled(false);
    setAgentscopeStatus('连接中…');

    try {
        const body = { sessionId, message };
        if (userId) body.userId = userId;
        const res = await fetch('/agentscope/dev-agent/ask', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Accept': 'text/event-stream'
            },
            body: JSON.stringify(body)
        });
        if (!res.ok) {
            throw new Error(await res.text() || ('HTTP ' + res.status));
        }

        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            const parts = buffer.split('\n\n');
            buffer = parts.pop();
            for (const part of parts) {
                let data = '';
                part.split('\n').forEach(function (line) {
                    if (line.startsWith('data:')) data += line.slice(5).trim();
                });
                if (!data || data === '[DONE]') continue;
                const payload = JSON.parse(data);
                if (payload.type === 'SESSION') {
                    setAgentscopeStatus('SESSION ' + (payload.sessionId || sessionId));
                } else if (payload.type === 'MESSAGE') {
                    setAgentscopeStatus('流式中…');
                    assistant.textContent += (payload.content || '');
                    scrollAgentscopeMessages();
                } else if (payload.type === 'DONE') {
                    setAgentscopeStatus('DONE');
                } else if (payload.type === 'ERROR') {
                    setAgentscopeStatus('ERROR');
                    assistant.textContent += (assistant.textContent ? '\n' : '') + '[ERROR] ' + (payload.content || '出错');
                }
            }
        }
    } catch (e) {
        setAgentscopeStatus('失败');
        assistant.textContent += (assistant.textContent ? '\n' : '') + '[ERROR] ' + (e.message || e);
    } finally {
        setAgentscopeInputEnabled(true);
    }
}

document.getElementById('agentscopeForm')?.addEventListener('submit', function (e) {
    e.preventDefault();
    sendAgentscopeMessage();
});
document.getElementById('agentscopeNewSessionBtn')?.addEventListener('click', function () {
    resetAgentscopeConversation();
});
ensureAgentscopeSessionId();
```

- [ ] **Step 3: 修改 `index.html`**

1. `<head>` 增加：`<link rel="stylesheet" href="/css/tabs/agentscope.css">`（紧挨 embabel.css）
2. Tab 导航 Embabel 按钮后增加：

```html
<button class="tab-btn" data-tab="agentscope" onclick="switchTab('agentscope')">🧭 AgentScope Harness</button>
```

3. 在 `tab-embabel` 面板后增加面板：

```html
<div id="tab-agentscope" class="tab-content">
    <div class="agentscope-header">
        <h2>AgentScope HarnessAgent</h2>
        <p>克制版研发任务清单：无工具，进程内会话状态，SSE：SESSION → MESSAGE* → DONE</p>
    </div>
    <div class="agentscope-meta">
        <label>userId <input id="agentscopeUserId" type="text" placeholder="可选，如 dev-user-001" value="dev-user-001"></label>
        <label>sessionId <input id="agentscopeSessionId" type="text" placeholder="会话 ID"></label>
        <button type="button" id="agentscopeNewSessionBtn">换会话</button>
        <button type="button" onclick="fillAgentscopeSample(1)">示例：订单超时</button>
        <button type="button" onclick="fillAgentscopeSample(2)">示例：支付 500</button>
    </div>
    <div id="agentscopeStatus" class="agentscope-status">就绪</div>
    <div id="agentscopeMessages" class="agentscope-messages">
        <div id="agentscopeWelcome" class="message assistant">
            <div class="message-content">输入研发任务，获取可执行检查清单。可点「换会话」验证 session 隔离。</div>
        </div>
    </div>
    <form id="agentscopeForm" class="agentscope-input-bar">
        <textarea id="agentscopeMessageInput" rows="3" placeholder="描述待排查的研发问题…" required></textarea>
        <button type="submit" id="agentscopeSendBtn">发送</button>
    </form>
</div>
```

4. 脚本区末尾增加：`<script src="/js/tabs/agentscope.js"></script>`

- [ ] **Step 4: Commit**

```bash
git add demo2/src/main/resources/static
git commit -m "feat(demo2): add AgentScope HarnessAgent test tab"
```

---

### Task 6: README 与手工验收

**Files:**
- Modify: `demo2/README.md`（Controller 一览表 + 功能模块一行）
- Modify: `README.md`（根目录功能表一行）

**Interfaces:**
- Produces: 文档可发现 `/agentscope/dev-agent/ask`；手工验收步骤可执行

- [ ] **Step 1: 更新 README**

根 `README.md` 功能模块表追加一行：

| AgentScope Harness | `/agentscope/dev-agent/*` | 克制 HarnessAgent SSE 任务清单（仅 demo2） |

`demo2/README.md` Controller 一览追加：

| `DevAgentController` | `agentscope.controller` | `/agentscope/dev-agent` | HarnessAgent SSE 任务清单 |

并在合适位置加 curl 三步（端口 **8081**）：首轮清单 / 同 session 追问 / 换 session。

- [ ] **Step 2: 手工验收（本机有 `DEEPSEEK_API_KEY` 时）**

```bash
# 启动 demo2 后：
curl -N -X POST "http://localhost:8081/agentscope/dev-agent/ask" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"dev-user-001\",\"sessionId\":\"dev-session-001\",\"message\":\"帮我整理一份今天排查订单接口超时的执行清单\"}"
```

验收：

1. 先 `SESSION`，再若干 `MESSAGE`，最后 `DONE`
2. 同 session 追问「我刚才让你整理的是什么任务？」能关联
3. 换 `sessionId` 问同一句应不串话
4. 浏览器打开 `http://localhost:8081/` → AgentScope Tab 流式正常
5. （可选）断点/日志确认 Toolkit 无可用工具

- [ ] **Step 3: 全量相关测试**

```bash
mvn -f demo2/pom.xml -Dtest=DevAgentServiceTest,DevAgentEventTest test
```

Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add README.md demo2/README.md
git commit -m "docs(demo2): document AgentScope HarnessAgent module"
```

- [ ] **Step 5: 将设计规范状态改为已实现（实现全部通过后）**

修改 `demo2/docs/superpowers/specs/2026-07-16-agentscope-harness-web-design.md` 头部 `**状态**: 待实现` → `**状态**: 已实现`，并 commit：

```bash
git add demo2/docs/superpowers/specs/2026-07-16-agentscope-harness-web-design.md
git commit -m "docs(demo2): mark AgentScope HarnessAgent design as implemented"
```

---

## Spec Coverage Self-Review

| Spec 项 | 对应 Task |
|---------|-----------|
| 并入 demo2 + 独立 agentscope 包 | Task 2–4 |
| agentscope-harness + openai 扩展 2.0.0 | Task 1 |
| deepseek-v4-pro + DEEPSEEK_API_KEY | Task 2 配置 |
| POST `/agentscope/dev-agent/ask` SSE | Task 4 |
| SESSION/MESSAGE/DONE + ERROR | Task 2–3 |
| InMemory + disable* + remove wait_async_results | Task 4 |
| 前端 Tab | Task 5 |
| 缺 Key 不挡启动、ask 报错 | Task 3 |
| 单元测试事件映射 | Task 3 |
| README + curl 会话验证 | Task 6 |
| 非目标（工具/持久化/WebFlux 全切） | 各 Task 均未引入 |

**Placeholder scan:** 测试中的 AgentScope import 标注了「以实际 jar 为准」——实现 Task 3 前必须用编译产物校正，不得留下错误包名。

**Type consistency:** `DevAgentRequest` / `DevAgentEvent` / `DevAgentProperties` / `ask(DevAgentRequest): Flux<DevAgentEvent>` 在 Task 2–5 一致；Bean 名 `agentscopeDeepSeekModel` / `agentscopeDevAgent` 避免与 Spring AI 冲突。
