# AgentScope Toolkit + AgentEvent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 demo2 现有 AgentScope DevAgent 上原位增强：注册 3 个只读项目工具，并把工具/生命周期事件透传到 SSE 与前端轻量工具条。

**Architecture:** 扩展 `DevAgentEvent` 字段；新增 `ProjectInfoTools` 并在 `HarnessAgent` 上 `registerTool` + `PermissionMode.EXPLORE`；`DevAgentService` 将 `streamEvents()` 映射为业务 SSE；AgentScope Tab 按 `toolCallId` 展示工具状态。路径仍为 `POST /agentscope/dev-agent/ask`。

**Tech Stack:** Java 21, Spring Boot 4.1.0, AgentScope Java 2.0.0, Reactor `Flux`, 原生 HTML/CSS/JS

**设计规范:** [docs/superpowers/specs/2026-07-17-agentscope-toolkit-events-design.md](../specs/2026-07-17-agentscope-toolkit-events-design.md)

## Global Constraints

- **AgentScope 版本**：`2.0.0`
- **API**：`POST /agentscope/dev-agent/ask`，`produces=text/event-stream`，端口 `8081`
- **SSE type**：`SESSION` | `AGENT_START` | `MODEL_CALL_START` | `TOOL_CALL_START` | `TOOL_RESULT_END` | `MESSAGE` | `AGENT_RESULT` | `AGENT_END` | `DONE` | `ERROR`
- **工具**：仅 `read_pom` / `list_source_folders` / `find_main_class`；`readOnly=true`；`projectRoot` 不进 Schema
- **权限**：`PermissionMode.EXPLORE`
- **状态**：继续 `InMemoryAgentStateStore`；仍 disable filesystem/shell/memory/subagent 等；仍 `removeTool("wait_async_results")`
- **不修改** Spring AI / Embabel 模块行为
- **中文 Prompt**：继续 UTF-8 YAML（`application-agentscope-prompts.yml`）
- **编译门禁**：`mvn -f demo2/pom.xml -DskipTests compile` 必须 SUCCESS
- **缺 Key**：不阻止启动；`ask` 返回 `SESSION` + `ERROR`

---

## File Structure

| 文件 | 职责 |
|------|------|
| `.../agentscope/model/DevAgentEvent.java` | SSE DTO 扩展为 7 字段 + 工厂 |
| `.../agentscope/tool/ProjectInfoTools.java` | 三个只读项目工具 |
| `.../agentscope/config/DevAgentProperties.java` | 增加 `projectRoot` |
| `.../agentscope/config/AgentScopeConfig.java` | EXPLORE + `registerTool` |
| `application.properties` | `project-root=.` |
| `application-agentscope-prompts.yml` | 双职责 Prompt |
| `.../agentscope/service/DevAgentService.java` | `AgentEvent` → `DevAgentEvent` 映射 |
| `static/js/tabs/agentscope.js` | 工具条 + 新事件处理 |
| `static/css/tabs/agentscope.css` | 工具条样式 |
| `static/index.html` | 欢迎文案 / 示例按钮 |
| `demo2/README.md` + 根 `README.md` | 功能说明更新 |
| 对应 `src/test/...` | 事件工厂、Tools、Service 映射测试 |

**已确认 API（AgentScope 2.0.0 jar）：**

- `@Tool` / `@ToolParam`：`io.agentscope.core.tool`
- `HarnessAgent.Builder.permissionContext(PermissionContextState)`
- `PermissionContextState.builder().mode(PermissionMode.EXPLORE).build()`
- `Toolkit.registerTool(Object)`
- 事件包：`io.agentscope.core.event.*`；`ToolResultEndEvent.getState()` → `ToolResultState`
- `AgentResultEvent.getResult().getTextContent()`

---

### Task 1: 扩展 DevAgentEvent

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentEvent.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/model/DevAgentEventTest.java`

**Interfaces:**
- Produces:
  ```java
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record DevAgentEvent(
      String type, String sessionId, String content,
      String eventId, String toolCallId, String name, String state)
  ```
  - 工厂：`session` / `message` / `done` / `error`（后四字段为 `null`）
  - 工厂：`lifecycle(type, sessionId, eventId, content)`
  - 工厂：`toolCallStart(sessionId, eventId, toolCallId, name, content)`
  - 工厂：`toolResultEnd(sessionId, eventId, toolCallId, name, state)`
  - 工厂：`agentResult(sessionId, eventId, content)`

- [ ] **Step 1: 写失败测试（新工厂）**

替换/扩展 `DevAgentEventTest.java`：

```java
package com.jason.demo.demo2.agentscope.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DevAgentEventTest {

    @Test
    void legacyFactories_keepNullOptionalFields() {
        assertThat(DevAgentEvent.session("s1"))
                .isEqualTo(new DevAgentEvent("SESSION", "s1", "", null, null, null, null));
        assertThat(DevAgentEvent.message("s1", "hi").content()).isEqualTo("hi");
        assertThat(DevAgentEvent.done("s1").type()).isEqualTo("DONE");
        assertThat(DevAgentEvent.error("s1", "boom").content()).isEqualTo("boom");
        assertThat(DevAgentEvent.message("s1", null).content()).isEqualTo("");
    }

    @Test
    void toolFactories_fillToolFields() {
        DevAgentEvent start = DevAgentEvent.toolCallStart(
                "s1", "e1", "call-1", "read_pom", "准备调用工具：read_pom");
        assertThat(start.type()).isEqualTo("TOOL_CALL_START");
        assertThat(start.toolCallId()).isEqualTo("call-1");
        assertThat(start.name()).isEqualTo("read_pom");
        assertThat(start.state()).isNull();

        DevAgentEvent end = DevAgentEvent.toolResultEnd(
                "s1", "e2", "call-1", "read_pom", "SUCCESS");
        assertThat(end.type()).isEqualTo("TOOL_RESULT_END");
        assertThat(end.state()).isEqualTo("SUCCESS");
        assertThat(end.content()).isEqualTo("");
    }

    @Test
    void agentResult_and_lifecycle() {
        assertThat(DevAgentEvent.agentResult("s1", "e3", "完整回答").type())
                .isEqualTo("AGENT_RESULT");
        assertThat(DevAgentEvent.lifecycle("AGENT_START", "s1", "e0", "Agent 开始").type())
                .isEqualTo("AGENT_START");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run:

```bash
mvn -f demo2/pom.xml -Dtest=DevAgentEventTest test
```

Expected: FAIL（构造器参数数量或工厂方法不存在）

- [ ] **Step 3: 实现扩展后的 DevAgentEvent**

```java
package com.jason.demo.demo2.agentscope.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DevAgentEvent(
        String type,
        String sessionId,
        String content,
        String eventId,
        String toolCallId,
        String name,
        String state) {

    public static DevAgentEvent session(String sessionId) {
        return new DevAgentEvent("SESSION", sessionId, "", null, null, null, null);
    }

    public static DevAgentEvent message(String sessionId, String content) {
        return new DevAgentEvent(
                "MESSAGE", sessionId, content == null ? "" : content, null, null, null, null);
    }

    public static DevAgentEvent done(String sessionId) {
        return new DevAgentEvent("DONE", sessionId, "", null, null, null, null);
    }

    public static DevAgentEvent error(String sessionId, String content) {
        return new DevAgentEvent(
                "ERROR", sessionId, content == null ? "" : content, null, null, null, null);
    }

    public static DevAgentEvent lifecycle(
            String type, String sessionId, String eventId, String content) {
        return new DevAgentEvent(
                type, sessionId, content == null ? "" : content, eventId, null, null, null);
    }

    public static DevAgentEvent toolCallStart(
            String sessionId,
            String eventId,
            String toolCallId,
            String name,
            String content) {
        return new DevAgentEvent(
                "TOOL_CALL_START",
                sessionId,
                content == null ? "" : content,
                eventId,
                toolCallId,
                name,
                null);
    }

    public static DevAgentEvent toolResultEnd(
            String sessionId,
            String eventId,
            String toolCallId,
            String name,
            String state) {
        return new DevAgentEvent(
                "TOOL_RESULT_END", sessionId, "", eventId, toolCallId, name, state);
    }

    public static DevAgentEvent agentResult(String sessionId, String eventId, String content) {
        return new DevAgentEvent(
                "AGENT_RESULT",
                sessionId,
                content == null ? "" : content,
                eventId,
                null,
                null,
                null);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -f demo2/pom.xml -Dtest=DevAgentEventTest test
```

Expected: `BUILD SUCCESS`，测试 PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentEvent.java \
        demo2/src/test/java/com/jason/demo/demo2/agentscope/model/DevAgentEventTest.java
git commit -m "feat(demo2): extend DevAgentEvent for tool and lifecycle SSE fields"
```

---

### Task 2: ProjectInfoTools（只读项目工具）

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/tool/ProjectInfoTools.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/agentscope/tool/ProjectInfoToolsTest.java`

**Interfaces:**
- Produces: `ProjectInfoTools(Path projectRoot)` + `@Tool` 方法：
  - `String readPom(Integer maxChars)` → tool name `read_pom`
  - `String listSourceFolders()` → `list_source_folders`
  - `String findMainClass()` → `find_main_class`
- Consumes: 无（纯文件 IO）；后续 Task 3 注入为 Spring Bean

- [ ] **Step 1: 写失败测试**

```java
package com.jason.demo.demo2.agentscope.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectInfoToolsTest {

    @TempDir
    Path root;

    @Test
    void readPom_returnsContentAndRespectsMaxChars() throws Exception {
        Files.writeString(
                root.resolve("pom.xml"),
                "<project><java.version>17</java.version></project>",
                StandardCharsets.UTF_8);
        ProjectInfoTools tools = new ProjectInfoTools(root);

        assertThat(tools.readPom(null)).contains("<java.version>17</java.version>");
        assertThat(tools.readPom(10)).hasSize(10);
    }

    @Test
    void listSourceFolders_listsExistingSrcDirs() throws Exception {
        Files.createDirectories(root.resolve("src/main/java"));
        Files.createDirectories(root.resolve("src/test/java"));
        ProjectInfoTools tools = new ProjectInfoTools(root);

        String out = tools.listSourceFolders();
        assertThat(out).contains("src/main/java");
        assertThat(out).contains("src/test/java");
    }

    @Test
    void findMainClass_findsSpringBootApplication() throws Exception {
        Path pkg = root.resolve("src/main/java/com/example");
        Files.createDirectories(pkg);
        Files.writeString(
                pkg.resolve("DemoApp.java"),
                """
                package com.example;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                @SpringBootApplication
                public class DemoApp {}
                """,
                StandardCharsets.UTF_8);
        ProjectInfoTools tools = new ProjectInfoTools(root);

        assertThat(tools.findMainClass()).contains("DemoApp.java");
    }

    @Test
    void readPom_missingFile_returnsClearMessage() {
        ProjectInfoTools tools = new ProjectInfoTools(root);
        assertThat(tools.readPom(null)).containsIgnoringCase("not found");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -f demo2/pom.xml -Dtest=ProjectInfoToolsTest test
```

Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 ProjectInfoTools**

```java
package com.jason.demo.demo2.agentscope.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class ProjectInfoTools {

    private static final int DEFAULT_MAX_CHARS = 4000;

    private final Path projectRoot;

    public ProjectInfoTools(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    @Tool(
            name = "read_pom",
            description = "Read the current Maven project's pom.xml. Use it when the user asks about dependencies, Java version, Spring Boot version, or project coordinates.",
            readOnly = true)
    public String readPom(
            @ToolParam(
                    name = "max_chars",
                    description = "Maximum characters to return. Default is 4000.",
                    required = false)
            Integer maxChars) {
        Path pom = projectRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return "pom.xml not found under " + projectRoot;
        }
        try {
            String text = Files.readString(pom, StandardCharsets.UTF_8);
            int limit = maxChars == null || maxChars <= 0 ? DEFAULT_MAX_CHARS : maxChars;
            return text.length() <= limit ? text : text.substring(0, limit);
        } catch (IOException e) {
            return "Failed to read pom.xml: " + e.getMessage();
        }
    }

    @Tool(
            name = "list_source_folders",
            description = "List existing source folders in the current project. Use it before answering questions about project layout.",
            readOnly = true)
    public String listSourceFolders() {
        Path src = projectRoot.resolve("src");
        if (!Files.isDirectory(src)) {
            return "src directory not found under " + projectRoot;
        }
        List<String> folders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(src, 3)) {
            walk.filter(Files::isDirectory)
                    .filter(p -> !p.equals(src))
                    .forEach(p -> folders.add(projectRoot.relativize(p).toString().replace('\\', '/')));
        } catch (IOException e) {
            return "Failed to list source folders: " + e.getMessage();
        }
        if (folders.isEmpty()) {
            return "No subfolders under src";
        }
        return String.join("\n", folders);
    }

    @Tool(
            name = "find_main_class",
            description = "Find Java classes annotated with @SpringBootApplication in the current project.",
            readOnly = true)
    public String findMainClass() {
        Path javaRoot = projectRoot.resolve("src/main/java");
        if (!Files.isDirectory(javaRoot)) {
            return "src/main/java not found under " + projectRoot;
        }
        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(javaRoot)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            String text = Files.readString(p, StandardCharsets.UTF_8);
                            if (text.contains("@SpringBootApplication")) {
                                hits.add(projectRoot.relativize(p).toString().replace('\\', '/'));
                            }
                        } catch (IOException ignored) {
                            // skip unreadable file
                        }
                    });
        } catch (IOException e) {
            return "Failed to scan for main class: " + e.getMessage();
        }
        if (hits.isEmpty()) {
            return "No @SpringBootApplication class found";
        }
        return String.join("\n", hits);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -f demo2/pom.xml -Dtest=ProjectInfoToolsTest test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/tool/ProjectInfoTools.java \
        demo2/src/test/java/com/jason/demo/demo2/agentscope/tool/ProjectInfoToolsTest.java
git commit -m "feat(demo2): add ProjectInfoTools read-only Maven project tools"
```

---

### Task 3: 配置、Prompt、HarnessAgent 注册工具

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/DevAgentProperties.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`
- Modify: `demo2/src/main/resources/application.properties`
- Modify: `demo2/src/main/resources/application-agentscope-prompts.yml`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java`（仅补 `projectRoot` 构造参数，逻辑测试留 Task 4）

**Interfaces:**
- Produces: `DevAgentProperties(String name, String systemPrompt, String projectRoot, Model model)`
- Produces: `ProjectInfoTools` Bean；`HarnessAgent` 带 `EXPLORE` + `registerTool(projectInfoTools)`
- Consumes: Task 2 的 `ProjectInfoTools`

- [ ] **Step 1: 扩展 DevAgentProperties**

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
        @NotBlank String projectRoot,
        @Valid Model model) {

    public record Model(
            String apiKey,
            @NotBlank String baseUrl,
            @NotBlank String name) {
    }
}
```

- [ ] **Step 2: 配置文件**

在 `application.properties` 增加：

```properties
app.agentscope.dev-agent.project-root=.
```

将 `application-agentscope-prompts.yml` 的 `system-prompt` 改为双职责：

```yaml
# AgentScope DevAgent 中文 Prompt（YAML 保证 UTF-8）
app:
  agentscope:
    dev-agent:
      system-prompt: |
        你是研发助手，同时支持两类任务：
        1) 排查清单：把用户问题整理成简洁、可执行的检查清单，控制在 6 条以内，每条一个动作，不写开场白和总结。
        2) 项目问答：当用户询问项目依赖、Java 版本、Spring Boot 版本、源码结构或启动类时，先调用对应工具（read_pom / list_source_folders / find_main_class），再基于工具结果回答。
        当前没有接入日志、数据库、写文件或 Shell 工具，不要声称已经完成查询日志、修改代码、保存文件或执行命令。
        信息不足时指出需要补充的关键内容，不要编造排查结果。
        使用中文回答。
```

- [ ] **Step 3: 更新 AgentScopeConfig**

在现有 Bean 方法上注入 `ProjectInfoTools`，并增加工具 Bean：

```java
@Bean
ProjectInfoTools projectInfoTools(DevAgentProperties properties) {
    return new ProjectInfoTools(Path.of(properties.projectRoot()));
}

@Bean
HarnessAgent agentscopeDevAgent(
        @Qualifier("agentscopeDeepSeekModel") Model agentscopeDeepSeekModel,
        DevAgentProperties properties,
        ProjectInfoTools projectInfoTools) throws IOException {
    HarnessAgent agent = HarnessAgent.builder()
            .name(properties.name())
            .sysPrompt(properties.systemPrompt())
            .model(agentscopeDeepSeekModel)
            .stateStore(new InMemoryAgentStateStore())
            .permissionContext(PermissionContextState.builder()
                    .mode(PermissionMode.EXPLORE)
                    .build())
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
    agent.getToolkit().registerTool(projectInfoTools);
    return agent;
}
```

补全 import：`Path`、`ProjectInfoTools`、`PermissionContextState`、`PermissionMode`。

- [ ] **Step 4: 修复 DevAgentServiceTest 构造，保证编译**

所有 `new DevAgentProperties(...)` 在 `systemPrompt` 后插入 `" ." ` 或 `"."`：

```java
properties = new DevAgentProperties(
        "dev-task-agent",
        "prompt",
        ".",
        new DevAgentProperties.Model("sk-test", "https://api.deepseek.com", "deepseek-v4-pro"));
```

空白 Key 那个用例同样补 `"."`。

- [ ] **Step 5: 编译验证**

```bash
mvn -f demo2/pom.xml -DskipTests compile
mvn -f demo2/pom.xml -Dtest=DevAgentEventTest,ProjectInfoToolsTest,DevAgentServiceTest test
```

Expected: compile SUCCESS；既有 Service 测试仍 PASS（行为尚未改映射）

- [ ] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/DevAgentProperties.java \
        demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java \
        demo2/src/main/resources/application.properties \
        demo2/src/main/resources/application-agentscope-prompts.yml \
        demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java
git commit -m "feat(demo2): register ProjectInfoTools on HarnessAgent with EXPLORE mode"
```

---

### Task 4: DevAgentService 事件映射

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java`

**Interfaces:**
- Consumes: `HarnessAgent.streamEvents(String, RuntimeContext)` → `Flux<? extends AgentEvent>`；Task 1 工厂
- Produces: `Flux<DevAgentEvent>` 含工具/生命周期/MESSAGE/AGENT_RESULT；未知类型丢弃

- [ ] **Step 1: 写/更新失败测试（工具事件映射）**

在 `DevAgentServiceTest` 增加：

```java
@Test
void ask_mapsToolAndLifecycleEvents() {
    AgentStartEvent agentStart = mock(AgentStartEvent.class);
    when(agentStart.getType()).thenReturn(AgentEventType.AGENT_START);
    when(agentStart.getId()).thenReturn("e-start");

    ToolCallStartEvent toolStart = mock(ToolCallStartEvent.class);
    when(toolStart.getType()).thenReturn(AgentEventType.TOOL_CALL_START);
    when(toolStart.getId()).thenReturn("e-ts");
    when(toolStart.getToolCallId()).thenReturn("call-1");
    when(toolStart.getToolCallName()).thenReturn("read_pom");

    ToolResultEndEvent toolEnd = mock(ToolResultEndEvent.class);
    when(toolEnd.getType()).thenReturn(AgentEventType.TOOL_RESULT_END);
    when(toolEnd.getId()).thenReturn("e-te");
    when(toolEnd.getToolCallId()).thenReturn("call-1");
    when(toolEnd.getToolCallName()).thenReturn("read_pom");
    when(toolEnd.getState()).thenReturn(ToolResultState.SUCCESS);

    TextBlockDeltaEvent delta = mock(TextBlockDeltaEvent.class);
    when(delta.getType()).thenReturn(AgentEventType.TEXT_BLOCK_DELTA);
    when(delta.getDelta()).thenReturn("Java 17");

    AgentResultEvent result = mock(AgentResultEvent.class);
    when(result.getType()).thenReturn(AgentEventType.AGENT_RESULT);
    when(result.getId()).thenReturn("e-res");
    Msg msg = mock(Msg.class);
    when(msg.getTextContent()).thenReturn("Java 17");
    when(result.getResult()).thenReturn(msg);

    when(harnessAgent.streamEvents(eq("问版本"), any(RuntimeContext.class)))
            .thenReturn(Flux.just(agentStart, toolStart, toolEnd, delta, result));

    StepVerifier.create(service.ask(new DevAgentRequest("u1", "s1", "问版本")))
            .expectNext(DevAgentEvent.session("s1"))
            .expectNextMatches(e -> "AGENT_START".equals(e.type()))
            .expectNext(DevAgentEvent.toolCallStart(
                    "s1", "e-ts", "call-1", "read_pom", "准备调用工具：read_pom"))
            .expectNext(DevAgentEvent.toolResultEnd(
                    "s1", "e-te", "call-1", "read_pom", "SUCCESS"))
            .expectNext(DevAgentEvent.message("s1", "Java 17"))
            .expectNext(DevAgentEvent.agentResult("s1", "e-res", "Java 17"))
            .expectNext(DevAgentEvent.done("s1"))
            .verifyComplete();
}
```

并改写 `ask_emitsSessionMessagesAndDone`：mock 的 `TextBlockDeltaEvent` 需 `when(d1.getType()).thenReturn(AgentEventType.TEXT_BLOCK_DELTA)`（若实现用 `getType()` switch）。

补 import：`AgentEventType`、`AgentStartEvent`、`ToolCallStartEvent`、`ToolResultEndEvent`、`AgentResultEvent`、`ToolResultState`、`Msg`。

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -f demo2/pom.xml -Dtest=DevAgentServiceTest#ask_mapsToolAndLifecycleEvents test
```

Expected: FAIL（仍只转发 MESSAGE 或不匹配）

- [ ] **Step 3: 实现 DevAgentService 映射**

```java
package com.jason.demo.demo2.agentscope.service;

import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import com.jason.demo.demo2.agentscope.model.DevAgentEvent;
import com.jason.demo.demo2.agentscope.model.DevAgentRequest;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class DevAgentService {

    private final HarnessAgent agentscopeDevAgent;
    private final DevAgentProperties properties;

    public DevAgentService(HarnessAgent agentscopeDevAgent, DevAgentProperties properties) {
        this.agentscopeDevAgent = agentscopeDevAgent;
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

        RuntimeContext.Builder contextBuilder = RuntimeContext.builder().sessionId(sessionId);
        if (request.userId() != null && !request.userId().isBlank()) {
            contextBuilder.userId(request.userId().strip());
        }
        RuntimeContext context = contextBuilder.build();

        Flux<DevAgentEvent> events = agentscopeDevAgent
                .streamEvents(request.message(), context)
                .handle((event, sink) -> {
                    DevAgentEvent mapped = mapEvent(sessionId, event);
                    if (mapped != null) {
                        sink.next(mapped);
                    }
                });

        return Flux.concat(
                        Mono.just(DevAgentEvent.session(sessionId)),
                        events,
                        Mono.just(DevAgentEvent.done(sessionId)))
                .onErrorResume(ex -> Flux.just(DevAgentEvent.error(
                        sessionId,
                        ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage())));
    }

    private DevAgentEvent mapEvent(String sessionId, AgentEvent event) {
        return switch (event.getType()) {
            case AGENT_START -> DevAgentEvent.lifecycle(
                    "AGENT_START",
                    sessionId,
                    event.getId(),
                    "Agent 开始");
            case MODEL_CALL_START -> DevAgentEvent.lifecycle(
                    "MODEL_CALL_START",
                    sessionId,
                    event.getId(),
                    "模型调用开始");
            case AGENT_END -> DevAgentEvent.lifecycle(
                    "AGENT_END",
                    sessionId,
                    event.getId(),
                    "Agent 结束");
            case TEXT_BLOCK_DELTA -> DevAgentEvent.message(
                    sessionId, ((TextBlockDeltaEvent) event).getDelta());
            case TOOL_CALL_START -> {
                ToolCallStartEvent e = (ToolCallStartEvent) event;
                yield DevAgentEvent.toolCallStart(
                        sessionId,
                        e.getId(),
                        e.getToolCallId(),
                        e.getToolCallName(),
                        "准备调用工具：" + e.getToolCallName());
            }
            case TOOL_RESULT_END -> {
                ToolResultEndEvent e = (ToolResultEndEvent) event;
                yield DevAgentEvent.toolResultEnd(
                        sessionId,
                        e.getId(),
                        e.getToolCallId(),
                        e.getToolCallName(),
                        e.getState() == null ? null : e.getState().name());
            }
            case AGENT_RESULT -> {
                AgentResultEvent e = (AgentResultEvent) event;
                String text = e.getResult() == null ? "" : e.getResult().getTextContent();
                yield DevAgentEvent.agentResult(sessionId, e.getId(), text);
            }
            default -> null;
        };
    }
}
```

删除未使用的具体类型 import（`AgentStartEvent` 等若只做 cast 可按编译器提示清理）；`switch` 用 `event.getType()` 即可。

- [ ] **Step 4: 跑全部 agentscope 测试**

```bash
mvn -f demo2/pom.xml -Dtest=com.jason.demo.demo2.agentscope.** test
```

Expected: PASS（含缺 Key、流失败、工具映射、旧 MESSAGE 用例）

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java \
        demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java
git commit -m "feat(demo2): map AgentScope tool and lifecycle events to SSE"
```

---

### Task 5: 前端轻量工具条

**Files:**
- Modify: `demo2/src/main/resources/static/js/tabs/agentscope.js`
- Modify: `demo2/src/main/resources/static/css/tabs/agentscope.css`
- Modify: `demo2/src/main/resources/static/index.html`（欢迎文案、示例按钮）

**Interfaces:**
- Consumes: SSE JSON 的 `type` / `toolCallId` / `name` / `state` / `content`
- Produces: 每轮助手气泡上方工具条；`AGENT_RESULT` 不追加文字

- [ ] **Step 1: CSS 增加工具条样式**

追加到 `agentscope.css`：

```css
.agentscope-tool-strip {
    display: flex;
    flex-direction: column;
    gap: 4px;
    margin: 0 0 6px 0;
    width: 100%;
    max-width: 720px;
}
.agentscope-tool-item {
    font-size: 12px;
    line-height: 1.4;
    padding: 4px 8px;
    border-left: 3px solid #17864f;
    background: #f3faf6;
    color: #2f3b40;
}
.agentscope-tool-item.is-done {
    border-left-color: #4a90d9;
}
.agentscope-tool-item.is-error {
    border-left-color: #c0392b;
}
.agentscope-samples {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin: 8px 0 12px;
}
.agentscope-samples button {
    font-size: 12px;
    padding: 4px 10px;
    cursor: pointer;
}
```

- [ ] **Step 2: 更新 index.html 欢迎与示例**

在 `#tab-agentscope` 内：

- 欢迎文案改为同时提示清单整理与项目问答  
- 示例按钮（若尚无容器则加）：

```html
<div class="agentscope-samples">
  <button type="button" onclick="fillAgentscopeSample(1)">示例：排查清单</button>
  <button type="button" onclick="fillAgentscopeSample(2)">示例：支付回调清单</button>
  <button type="button" onclick="fillAgentscopeSample(3)">示例：项目版本与启动类</button>
</div>
```

- [ ] **Step 3: 更新 agentscope.js**

关键改动要点（完整替换处理循环逻辑）：

1. `resetAgentscopeConversation` 欢迎文案同步更新  
2. `fillAgentscopeSample` 增加 sample 3：  
   `'帮我看一下这个项目用了哪个 Java 版本、Spring Boot 版本，以及启动类在哪里'`  
3. `sendAgentscopeMessage` 中：
   - 创建助手气泡前，先插入 `toolStrip`（`div.agentscope-tool-strip`）到同一轮容器，或气泡顶部  
   - 维护 `Map`：`toolCallId → HTMLElement`  
   - `TOOL_CALL_START`：新增 `.agentscope-tool-item`，文案 `准备调用：{name}`  
   - `TOOL_RESULT_END`：更新同行文案为 `{name} · {state}`，SUCCESS → `is-done`，ERROR/DENIED → `is-error`  
   - `MESSAGE`：追加到助手文字节点  
   - `AGENT_RESULT`：**忽略**聊天追加（可仅 `setAgentscopeStatus('AGENT_RESULT')`）  
   - `AGENT_START` / `MODEL_CALL_START` / `AGENT_END`：更新状态栏  
   - `DONE` / `ERROR`：保持现有行为  

推荐结构：每次发送时创建 wrapper：

```javascript
function beginAgentscopeAssistantTurn() {
    const box = document.getElementById('agentscopeMessages');
    const welcome = document.getElementById('agentscopeWelcome');
    if (welcome) welcome.remove();

    const wrap = document.createElement('div');
    wrap.className = 'message assistant';
    const strip = document.createElement('div');
    strip.className = 'agentscope-tool-strip';
    const content = document.createElement('div');
    content.className = 'message-content';
    wrap.appendChild(strip);
    wrap.appendChild(content);
    box.appendChild(wrap);
    scrollAgentscopeMessages();
    return { strip, content, tools: new Map() };
}
```

在 SSE 分支中调用该结构；用户气泡仍用 `appendAgentscopeBubble(message, true)`。

- [ ] **Step 4: 手工冒烟（有 Key 时）**

启动（在 `demo2` 工作目录，保证 `project-root=.` 指向模块根）：

```bash
# 确保 DEEPSEEK_API_KEY 已设置
mvn -f demo2/pom.xml spring-boot:run
```

浏览器打开 AgentScope Tab：点「示例：项目版本与启动类」，应看到工具条状态变化与回答。  
curl 对照：

```bash
curl -N -X POST "http://localhost:8081/agentscope/dev-agent/ask" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"dev-user-001\",\"sessionId\":\"toolkit-session-001\",\"message\":\"帮我看一下这个项目用了哪个 Java 版本、Spring Boot 版本，以及启动类在哪里\"}"
```

Expected: 流中含 `TOOL_CALL_START` / `TOOL_RESULT_END`，再有回答相关事件。

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/resources/static/js/tabs/agentscope.js \
        demo2/src/main/resources/static/css/tabs/agentscope.css \
        demo2/src/main/resources/static/index.html
git commit -m "feat(demo2): show AgentScope tool-call status in Tab UI"
```

---

### Task 6: README 与总验收

**Files:**
- Modify: `demo2/README.md`（AgentScope 小节）
- Modify: `README.md`（根目录功能表一行）

**Interfaces:**
- Produces: 文档写明 Toolkit + 工具事件 SSE；curl 含项目问答示例

- [ ] **Step 1: 更新 demo2/README.md**

功能表中 `DevAgentController` 说明改为类似：

`HarnessAgent SSE：清单整理 + 项目只读工具 + 工具事件透传`

`/agentscope/dev-agent` 小节改为：SSE 含 `SESSION` →（生命周期/工具事件）→ `MESSAGE*` → `AGENT_RESULT` → `DONE`；并补项目问答 curl（与规范 §7.2 一致）。

- [ ] **Step 2: 更新根 README.md**

将 AgentScope 行改为体现「项目只读工具 + 工具过程可见」。

- [ ] **Step 3: 全量 agentscope 测试 + 编译**

```bash
mvn -f demo2/pom.xml -Dtest=com.jason.demo.demo2.agentscope.** test
mvn -f demo2/pom.xml -DskipTests compile
```

Expected: 全部 SUCCESS

- [ ] **Step 4: Commit**

```bash
git add demo2/README.md README.md
git commit -m "docs: document AgentScope Toolkit and tool-event SSE"
```

---

## Spec Coverage Checklist

| Spec 要求 | Task |
|-----------|------|
| 3 个只读工具 + projectRoot 不进 Schema | Task 2, 3 |
| `project-root` 可配置默认 `.` | Task 3 |
| 双职责 Prompt | Task 3 |
| `PermissionMode.EXPLORE` + registerTool | Task 3 |
| 扩展 DevAgentEvent 七字段 | Task 1 |
| 映射 TOOL_* / lifecycle / MESSAGE / AGENT_RESULT | Task 4 |
| 前端轻量工具条，AGENT_RESULT 不重复渲染 | Task 5 |
| 单元测试 Tools + Service | Task 2, 4 |
| README 更新 | Task 6 |
| 非目标（写工具/Tool Group/持久化等） | 全计划未引入 |

---

## Self-Review Notes

- 无 TBD；API 包名已对 AgentScope 2.0.0 jar 核实  
- `DevAgentProperties` 增参后 Task 3 同步修测试构造，避免编译断裂  
- `ToolResultState.name()` 与枚举常量名一致（SUCCESS/ERROR/…）  
- 前端与后端对 `AGENT_RESULT` 约定一致：可下发、不追加气泡
