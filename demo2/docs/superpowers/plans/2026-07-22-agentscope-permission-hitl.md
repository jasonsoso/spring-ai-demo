# AgentScope Permission HITL Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 AgentScope DevAgent 上落地写文件权限 HITL：`notes/` 写入 ASK、删除/越界 DENY、只读 ALLOW，SSE 确认 + Tab 批准/拒绝。

**Architecture:** 原位增强 `agentscope` 包。`PermissionMode.DEFAULT` + 只读 ALLOW 规则；新增 `FileChangeTool`（`ToolBase`）；`DevAgentService` 缓存 pending 并用 `ConfirmResult` 恢复；AgentScope Tab 渲染确认卡片。

**Tech Stack:** Java 21, Spring Boot 4.1, AgentScope Java 2.0.0, Reactor `Flux`/`Mono`, 原生 HTML/CSS/JS

**设计规范:** [docs/superpowers/specs/2026-07-22-agentscope-permission-hitl-design.md](../specs/2026-07-22-agentscope-permission-hitl-design.md)

## Global Constraints

- **AgentScope 版本**：`2.0.0`
- **API**：`POST /agentscope/dev-agent/ask` + `POST /agentscope/dev-agent/confirm`，`produces=text/event-stream`，端口 `8081`
- **SSE 新增 type**：`REQUIRE_USER_CONFIRM` | `REQUEST_STOP`（旧 type 全部保留）
- **权限**：`PermissionMode.DEFAULT`；只读三工具显式 ALLOW；`request_file_change` 由工具 `checkPermissions` 决定 ASK/DENY
- **写入边界**：仅 `{projectRoot}/notes/`；删除 DENY；符号链接拒绝；批准 ≠ 放开路径限制
- **确认语义**：整批 `approved`；key = `normalizeUserId(userId) + "|" + sessionId`；空 userId → `_anonymous`
- **状态**：`InMemoryAgentStateStore` + 内存 `ConcurrentHashMap` pending（重启丢失）
- **仍 disable**：filesystem / shell / memory / subagent / workspace / dynamic skills 等；仍 `removeTool("wait_async_results")`
- **不修改** Spring AI / Embabel 行为
- **编译门禁**：在 `demo2` 目录执行 `mvn -DskipTests compile`（或仓库根 `mvn -f demo2/pom.xml`，确保能读到 `demo2/.mvn/settings.xml`）
- **缺 Key**：ask / confirm 均 `SESSION` + `ERROR`，不调 Agent
- **YAGNI**：不做按 toolCallId 审批、过期、持久化、幂等状态机

---

## File Structure

| 文件 | 职责 |
|------|------|
| `.../model/PendingToolCall.java` | 待确认工具调用 DTO |
| `.../model/DevAgentConfirmRequest.java` | confirm 请求体 |
| `.../model/DevAgentEventType.java` | + `REQUIRE_USER_CONFIRM` / `REQUEST_STOP` |
| `.../model/DevAgentEvent.java` | + `pendingToolCalls` + 工厂 |
| `.../tool/FileChangeTool.java` | `request_file_change` + 权限/路径/写盘 |
| `.../config/AgentScopeConfig.java` | DEFAULT + ALLOW + 注册工具 |
| `application-agentscope-prompts.yml` | 写文件约束 Prompt |
| `demo2/notes/.gitkeep` + `.gitignore` | 演示目录，忽略写入产物 |
| `.../service/DevAgentService.java` | pending 缓存、确认事件映射、`confirm()` |
| `.../controller/DevAgentController.java` | `POST /confirm` |
| `static/js/tabs/agentscope.js` + `agentscope.css` + `index.html` | 确认卡片 UI |
| `demo2/README.md` | 文档更新 |
| 对应 `src/test/...` | 单测 |

**已确认 API（AgentScope 2.0.0 jar）：**

- `ToolBase`：`protected ToolBase(Builder)`；override `checkPermissions` / `callAsync`
- `Toolkit.registerAgentTool(AgentTool)`（注解工具继续 `registerTool`）
- `PermissionContextState.Builder.addAllowRule(String, PermissionRule)`
- `PermissionRule(String toolName, String ruleContent, PermissionBehavior behavior, String source)`
- `PermissionDecision.builder().behavior(...).message(...).decisionReason(...).build()`
- `ConfirmResult(boolean confirmed, ToolUseBlock toolCall)`
- `Msg.METADATA_CONFIRM_RESULTS`；`Msg.builder()...metadata(...)`
- `HarnessAgent.streamEvents(Msg, RuntimeContext)` / `streamEvents(String, RuntimeContext)`
- 事件：`RequireUserConfirmEvent`、`RequestStopEvent`；`AgentEventType.REQUIRE_USER_CONFIRM` / `REQUEST_STOP`
- `GenerateReason.PERMISSION_ASKING`

---

### Task 1: 扩展 SSE 事件模型

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/PendingToolCall.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentEventType.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentEvent.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/model/DevAgentEventTest.java`

**Interfaces:**
- Produces:
  ```java
  public record PendingToolCall(String toolCallId, String name, Map<String, Object> input) {}

  // DevAgentEvent 第 8 字段 pendingToolCalls；旧工厂传 null
  static DevAgentEvent confirmation(String sessionId, String eventId, List<PendingToolCall> pendingToolCalls)
  static DevAgentEvent requestStop(String sessionId, String eventId, String content)
  ```

- [ ] **Step 1: 写失败测试**

扩展 `DevAgentEventTest.java`（同步更新既有断言为 8 字段构造，末位 `null`）：

```java
package com.jason.demo.demo2.agentscope.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DevAgentEventTest {

    @Test
    void legacyFactories_keepNullOptionalFields() {
        assertThat(DevAgentEvent.session("s1"))
                .isEqualTo(new DevAgentEvent(
                        DevAgentEventType.SESSION, "s1", "", null, null, null, null, null));
        assertThat(DevAgentEvent.message("s1", "hi").content()).isEqualTo("hi");
        assertThat(DevAgentEvent.done("s1").type()).isEqualTo(DevAgentEventType.DONE);
        assertThat(DevAgentEvent.error("s1", "boom").content()).isEqualTo("boom");
        assertThat(DevAgentEvent.message("s1", null).content()).isEqualTo("");
        assertThat(DevAgentEvent.session("s1").pendingToolCalls()).isNull();
    }

    @Test
    void toolFactories_fillToolFields() {
        DevAgentEvent start = DevAgentEvent.toolCallStart(
                "s1", "e1", "call-1", "read_pom", "准备调用工具：read_pom");
        assertThat(start.type()).isEqualTo(DevAgentEventType.TOOL_CALL_START);
        assertThat(start.toolCallId()).isEqualTo("call-1");
        assertThat(start.name()).isEqualTo("read_pom");
        assertThat(start.state()).isNull();
        assertThat(start.pendingToolCalls()).isNull();

        DevAgentEvent end = DevAgentEvent.toolResultEnd(
                "s1", "e2", "call-1", "read_pom", "SUCCESS");
        assertThat(end.type()).isEqualTo(DevAgentEventType.TOOL_RESULT_END);
        assertThat(end.state()).isEqualTo("SUCCESS");
        assertThat(end.content()).isEqualTo("");
    }

    @Test
    void agentResult_and_lifecycle() {
        assertThat(DevAgentEvent.agentResult("s1", "e3", "完整回答").type())
                .isEqualTo(DevAgentEventType.AGENT_RESULT);
        assertThat(DevAgentEvent.lifecycle(DevAgentEventType.AGENT_START, "s1", "e0", "Agent 开始").type())
                .isEqualTo(DevAgentEventType.AGENT_START);
    }

    @Test
    void confirmation_carriesPendingToolCalls() {
        PendingToolCall pending = new PendingToolCall(
                "call-9",
                "request_file_change",
                Map.of(
                        "operation", "create",
                        "path", "notes/permission-demo.txt",
                        "content", "hello"));
        DevAgentEvent event = DevAgentEvent.confirmation("s1", "e-c", List.of(pending));
        assertThat(event.type()).isEqualTo(DevAgentEventType.REQUIRE_USER_CONFIRM);
        assertThat(event.content()).isEqualTo("请确认待执行的工具调用。");
        assertThat(event.pendingToolCalls()).containsExactly(pending);
        assertThat(event.eventId()).isEqualTo("e-c");
    }

    @Test
    void requestStop_setsTypeAndContent() {
        DevAgentEvent stop = DevAgentEvent.requestStop("s1", "e-s", "PERMISSION_ASKING");
        assertThat(stop.type()).isEqualTo(DevAgentEventType.REQUEST_STOP);
        assertThat(stop.content()).isEqualTo("PERMISSION_ASKING");
        assertThat(stop.pendingToolCalls()).isNull();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run（在 `demo2` 目录）:

```bash
mvn -Dtest=DevAgentEventTest test
```

Expected: 编译失败或断言失败（缺 `PendingToolCall` / 第 8 字段 / 新工厂 / 新枚举）。

- [ ] **Step 3: 最小实现**

`PendingToolCall.java`:

```java
package com.jason.demo.demo2.agentscope.model;

import java.util.Map;

public record PendingToolCall(String toolCallId, String name, Map<String, Object> input) {
}
```

`DevAgentEventType.java` 末尾追加：

```java
    /** 需要用户确认待执行的工具调用。 */
    REQUIRE_USER_CONFIRM,
    /** 本轮请求停止（如权限询问）。 */
    REQUEST_STOP
```

`DevAgentEvent.java` 完整替换为：

```java
package com.jason.demo.demo2.agentscope.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DevAgentEvent(
        DevAgentEventType type,
        String sessionId,
        String content,
        String eventId,
        String toolCallId,
        String name,
        String state,
        List<PendingToolCall> pendingToolCalls) {

    public static DevAgentEvent session(String sessionId) {
        return new DevAgentEvent(DevAgentEventType.SESSION, sessionId, "", null, null, null, null, null);
    }

    public static DevAgentEvent message(String sessionId, String content) {
        return new DevAgentEvent(
                DevAgentEventType.MESSAGE, sessionId, content == null ? "" : content, null, null, null, null, null);
    }

    public static DevAgentEvent done(String sessionId) {
        return new DevAgentEvent(DevAgentEventType.DONE, sessionId, "", null, null, null, null, null);
    }

    public static DevAgentEvent error(String sessionId, String content) {
        return new DevAgentEvent(
                DevAgentEventType.ERROR, sessionId, content == null ? "" : content, null, null, null, null, null);
    }

    public static DevAgentEvent lifecycle(
            DevAgentEventType type, String sessionId, String eventId, String content) {
        return new DevAgentEvent(
                type, sessionId, content == null ? "" : content, eventId, null, null, null, null);
    }

    public static DevAgentEvent toolCallStart(
            String sessionId,
            String eventId,
            String toolCallId,
            String name,
            String content) {
        return new DevAgentEvent(
                DevAgentEventType.TOOL_CALL_START,
                sessionId,
                content == null ? "" : content,
                eventId,
                toolCallId,
                name,
                null,
                null);
    }

    public static DevAgentEvent toolResultEnd(
            String sessionId,
            String eventId,
            String toolCallId,
            String name,
            String state) {
        return new DevAgentEvent(
                DevAgentEventType.TOOL_RESULT_END, sessionId, "", eventId, toolCallId, name, state, null);
    }

    public static DevAgentEvent agentResult(String sessionId, String eventId, String content) {
        return new DevAgentEvent(
                DevAgentEventType.AGENT_RESULT,
                sessionId,
                content == null ? "" : content,
                eventId,
                null,
                null,
                null,
                null);
    }

    public static DevAgentEvent confirmation(
            String sessionId, String eventId, List<PendingToolCall> pendingToolCalls) {
        return new DevAgentEvent(
                DevAgentEventType.REQUIRE_USER_CONFIRM,
                sessionId,
                "请确认待执行的工具调用。",
                eventId,
                null,
                null,
                null,
                pendingToolCalls);
    }

    public static DevAgentEvent requestStop(String sessionId, String eventId, String content) {
        return new DevAgentEvent(
                DevAgentEventType.REQUEST_STOP,
                sessionId,
                content == null ? "" : content,
                eventId,
                null,
                null,
                null,
                null);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -Dtest=DevAgentEventTest test
```

Expected: `BUILD SUCCESS`，`DevAgentEventTest` 全绿。

- [ ] **Step 5: 修编译连锁（若有）**

若其它测试仍用 7 字段 `new DevAgentEvent(...)`，一并改为末位 `null`。再跑：

```bash
mvn -Dtest=com.jason.demo.demo2.agentscope.** test
```

Expected: 现有 agentscope 测试仍绿（Service 尚未改签名前应编译通过）。

- [ ] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/model/PendingToolCall.java \
  demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentEventType.java \
  demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentEvent.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/model/DevAgentEventTest.java
git commit -m "$(cat <<'EOF'
feat(demo2): extend DevAgentEvent for permission confirmation SSE

EOF
)"
```

---

### Task 2: FileChangeTool（权限 + 路径 + 写盘）

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/tool/FileChangeTool.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/agentscope/tool/FileChangeToolTest.java`

**Interfaces:**
- Produces:
  ```java
  public class FileChangeTool extends ToolBase {
      public FileChangeTool(Path projectRoot);
      @Override public Mono<PermissionDecision> checkPermissions(Map<String,Object> toolInput, PermissionContextState context);
      @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param);
  }
  ```
  - 工具名固定：`request_file_change`
  - WRITE_OPERATIONS：`create` / `write` / `update` / `append`（大小写不敏感）

- [ ] **Step 1: 写失败测试**

```java
package com.jason.demo.demo2.agentscope.tool;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileChangeToolTest {

    @TempDir
    Path tempDir;

    FileChangeTool tool;
    PermissionContextState context;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tempDir.resolve("notes"));
        tool = new FileChangeTool(tempDir);
        context = PermissionContextState.builder().mode(PermissionMode.DEFAULT).build();
    }

    @Test
    void checkPermissions_createUnderNotes_asks() {
        StepVerifier.create(tool.checkPermissions(
                        Map.of("operation", "create", "path", "notes/a.txt", "content", "x"),
                        context))
                .assertNext(d -> {
                    assertThat(d.getBehavior()).isEqualTo(PermissionBehavior.ASK);
                    assertThat(d.getDecisionReason()).contains("safety");
                })
                .verifyComplete();
    }

    @Test
    void checkPermissions_delete_denies() {
        StepVerifier.create(tool.checkPermissions(
                        Map.of("operation", "delete", "path", "notes/a.txt"),
                        context))
                .assertNext(d -> assertThat(d.getBehavior()).isEqualTo(PermissionBehavior.DENY))
                .verifyComplete();
    }

    @Test
    void checkPermissions_pathEscape_denies() {
        StepVerifier.create(tool.checkPermissions(
                        Map.of("operation", "write", "path", "notes/../application.yml", "content", "x"),
                        context))
                .assertNext(d -> assertThat(d.getBehavior()).isEqualTo(PermissionBehavior.DENY))
                .verifyComplete();
    }

    @Test
    void checkPermissions_absolutePath_denies() {
        String abs = tempDir.resolve("notes/a.txt").toAbsolutePath().toString();
        StepVerifier.create(tool.checkPermissions(
                        Map.of("operation", "create", "path", abs, "content", "x"),
                        context))
                .assertNext(d -> assertThat(d.getBehavior()).isEqualTo(PermissionBehavior.DENY))
                .verifyComplete();
    }

    @Test
    void callAsync_writesFileUnderNotes() {
        ToolUseBlock use = ToolUseBlock.builder()
                .id("c1")
                .name("request_file_change")
                .input(Map.of(
                        "operation", "create",
                        "path", "notes/permission-demo.txt",
                        "content", "AgentScope Permission HITL 已通过。"))
                .build();
        ToolCallParam param = ToolCallParam.builder().toolUseBlock(use).input(use.getInput()).build();

        StepVerifier.create(tool.callAsync(param))
                .assertNext(result -> assertThat(result).isInstanceOf(ToolResultBlock.class))
                .verifyComplete();

        Path written = tempDir.resolve("notes/permission-demo.txt");
        assertThat(written).exists();
        assertThat(Files.readString(written, StandardCharsets.UTF_8))
                .isEqualTo("AgentScope Permission HITL 已通过。");
    }
}
```

若 `ToolUseBlock.builder()` / `ToolCallParam.builder()` 字段名与 jar 不一致，用 `javap` 校正后写入实现；原则：能构造带 `input` 的 `ToolCallParam` 即可。

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -Dtest=FileChangeToolTest test
```

Expected: 找不到 `FileChangeTool`。

- [ ] **Step 3: 实现 `FileChangeTool`**

```java
package com.jason.demo.demo2.agentscope.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class FileChangeTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(FileChangeTool.class);

    private static final Set<String> WRITE_OPERATIONS =
            Set.of("create", "write", "update", "append");

    private final Path projectRoot;
    private final Path notesRoot;

    public FileChangeTool(Path projectRoot) {
        super(ToolBase.builder()
                .name("request_file_change")
                .description(
                        "Request a file create/write under the notes/ directory. "
                                + "Only use when the user explicitly asks to write a file. "
                                + "Deletes are not allowed. Paths outside notes/ are rejected.")
                .inputSchema(inputSchema())
                .readOnly(false)
                .concurrencySafe(false));
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.notesRoot = this.projectRoot.resolve("notes").normalize();
    }

    private static Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
                "operation",
                Map.of(
                        "type", "string",
                        "description", "File operation: create, write, update, or append. delete is denied."));
        properties.put(
                "path",
                Map.of(
                        "type", "string",
                        "description", "Relative path under notes/, e.g. notes/plan.txt"));
        properties.put(
                "content",
                Map.of(
                        "type", "string",
                        "description", "Text content to write."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("operation", "path", "content"));
        return schema;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContextState context) {
        log.info("Checking file change permission");
        String operation = value(toolInput, "operation").toLowerCase(Locale.ROOT);
        String path = value(toolInput, "path");

        if ("delete".equals(operation) || "remove".equals(operation)) {
            return Mono.just(PermissionDecision.builder()
                    .behavior(PermissionBehavior.DENY)
                    .message("删除文件不允许由 Agent 自动执行：" + path)
                    .decisionReason("safety: delete operation is denied")
                    .build());
        }

        if (!WRITE_OPERATIONS.contains(operation)) {
            return Mono.just(PermissionDecision.builder()
                    .behavior(PermissionBehavior.DENY)
                    .message("不支持的文件操作：" + operation)
                    .decisionReason("safety: unsupported file operation")
                    .build());
        }

        try {
            resolveTarget(path);
            return Mono.just(PermissionDecision.builder()
                    .behavior(PermissionBehavior.ASK)
                    .message("写入类操作需要人工确认：" + path)
                    .decisionReason("safety: write operation requires approval")
                    .build());
        } catch (IllegalArgumentException ex) {
            return Mono.just(PermissionDecision.builder()
                    .behavior(PermissionBehavior.DENY)
                    .message(ex.getMessage())
                    .decisionReason("safety: path is outside notes directory")
                    .build());
        }
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            Map<String, Object> input = param.getInput();
            String operation = value(input, "operation").toLowerCase(Locale.ROOT);
            String path = value(input, "path");
            String content = value(input, "content");

            log.info("Executing confirmed file change operation={} path={}", operation, path);
            Path target = resolveTarget(path);
            ensureNoSymlinks(target);
            Files.createDirectories(target.getParent());
            if ("append".equals(operation) && Files.isRegularFile(target)) {
                Files.writeString(
                        target,
                        content,
                        StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.APPEND);
            } else {
                Files.writeString(target, content, StandardCharsets.UTF_8);
            }
            log.info("File change completed path={}", path);
            return ToolResultBlock.text("已写入文件：" + path + "（operation=" + operation + "）");
        }).onErrorResume(ex -> Mono.just(ToolResultBlock.error(
                ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage())));
    }

    Path resolveTarget(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        Path relative = Path.of(path);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("不允许绝对路径：" + path);
        }
        Path resolved = projectRoot.resolve(relative).normalize();
        if (!resolved.startsWith(notesRoot)) {
            throw new IllegalArgumentException("路径必须位于 notes/ 目录下：" + path);
        }
        return resolved;
    }

    private void ensureNoSymlinks(Path target) throws java.io.IOException {
        if (Files.exists(notesRoot) && Files.isSymbolicLink(notesRoot)) {
            throw new IllegalArgumentException("notes/ 不能是符号链接");
        }
        Path parent = target.getParent();
        if (parent != null && Files.exists(parent) && Files.isSymbolicLink(parent)) {
            throw new IllegalArgumentException("目标父目录不能是符号链接：" + parent);
        }
        if (Files.exists(target) && Files.isSymbolicLink(target)) {
            throw new IllegalArgumentException("目标文件不能是符号链接：" + target);
        }
    }

    private static String value(Map<String, Object> input, String key) {
        if (input == null || input.get(key) == null) {
            return "";
        }
        return String.valueOf(input.get(key));
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -Dtest=FileChangeToolTest test
```

Expected: `BUILD SUCCESS`。若 builder API 不匹配，按编译错误微调测试构造，不改权限语义。

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/tool/FileChangeTool.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/tool/FileChangeToolTest.java
git commit -m "$(cat <<'EOF'
feat(demo2): add FileChangeTool with ASK/DENY path guards

EOF
)"
```

---

### Task 3: 装配 DEFAULT 权限 + 注册工具 + Prompt + notes 目录

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`
- Modify: `demo2/src/main/resources/application-agentscope-prompts.yml`
- Create: `demo2/notes/.gitkeep`
- Modify: `demo2/.gitignore`（追加 `notes/*` 与 `!notes/.gitkeep`）

**Interfaces:**
- Consumes: `FileChangeTool(Path)`；`ProjectInfoTools`
- Produces: `HarnessAgent` 使用 `PermissionMode.DEFAULT` + 三只读 ALLOW + `registerAgentTool(fileChangeTool)`

- [ ] **Step 1: 更新 `AgentScopeConfig`**

替换权限与注册相关片段：

```java
    private static final List<String> READ_ONLY_TOOL_NAMES =
            List.of("read_pom", "list_source_folders", "find_main_class");

    @Bean
    FileChangeTool fileChangeTool(DevAgentProperties properties) {
        return new FileChangeTool(Path.of(properties.projectRoot()));
    }

    @Bean
    HarnessAgent agentscopeDevAgent(
            @Qualifier("agentscopeDeepSeekModel") Model agentscopeDeepSeekModel,
            DevAgentProperties properties,
            ProjectInfoTools projectInfoTools,
            FileChangeTool fileChangeTool) throws IOException {
        HarnessAgent agent = HarnessAgent.builder()
                .name(properties.name())
                .sysPrompt(properties.systemPrompt())
                .model(agentscopeDeepSeekModel)
                .stateStore(new InMemoryAgentStateStore())
                .permissionContext(permissionContext())
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
        agent.getToolkit().registerAgentTool(fileChangeTool);
        return agent;
    }

    private static PermissionContextState permissionContext() {
        PermissionContextState.Builder builder =
                PermissionContextState.builder().mode(PermissionMode.DEFAULT);
        READ_ONLY_TOOL_NAMES.forEach(
                toolName -> builder.addAllowRule(toolName, allowRule(toolName)));
        return builder.build();
    }

    private static PermissionRule allowRule(String toolName) {
        return new PermissionRule(toolName, null, PermissionBehavior.ALLOW, "app");
    }
```

补全 import：`FileChangeTool`、`PermissionBehavior`、`PermissionRule`、`List`。删除仅使用 `EXPLORE` 的旧写法。

- [ ] **Step 2: 更新 Prompt**

`application-agentscope-prompts.yml`：

```yaml
# AgentScope DevAgent 中文 Prompt（YAML 保证 UTF-8）
app:
  agentscope:
    dev-agent:
      system-prompt: |
        你是研发助手，同时支持三类任务：
        1) 排查清单：把用户问题整理成简洁、可执行的检查清单，控制在 6 条以内，每条一个动作，不写开场白和总结。
        2) 项目问答：当用户询问项目依赖、Java 版本、Spring Boot 版本、源码结构或启动类时，先调用对应工具（read_pom / list_source_folders / find_main_class），再基于工具结果回答。
        3) 写文件：仅当用户明确要求创建或写入文件时，才调用 request_file_change；路径必须是 notes/ 下的相对路径；不要尝试删除文件。
        当前没有接入日志、数据库或 Shell 工具，不要声称已经完成查询日志或执行命令。
        写文件是否真正执行由权限系统与用户确认决定，不要假设已经写入成功。
        信息不足时指出需要补充的关键内容，不要编造排查结果。
        使用中文回答。
```

- [ ] **Step 3: notes 目录与 gitignore**

创建空文件 `demo2/notes/.gitkeep`。

在 `demo2/.gitignore` 追加：

```
### AgentScope permission demo writes ###
notes/*
!notes/.gitkeep
```

- [ ] **Step 4: 编译验证**

```bash
mvn -DskipTests compile
```

Expected: `BUILD SUCCESS`。

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java \
  demo2/src/main/resources/application-agentscope-prompts.yml \
  demo2/notes/.gitkeep \
  demo2/.gitignore
git commit -m "$(cat <<'EOF'
feat(demo2): wire DEFAULT permission mode and FileChangeTool

EOF
)"
```

---

### Task 4: DevAgentService — ASK 事件映射与 pending 缓存

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java`

**Interfaces:**
- Produces（本 Task 先落地 ask 侧；confirm 在 Task 5）:
  ```java
  // 内部
  ConcurrentHashMap<String, List<ToolUseBlock>> pendingConfirmations
  String normalizeUserId(String userId)  // blank → "_anonymous"
  String confirmationKey(String userId, String sessionId)
  DevAgentEvent mapEvent(String userId, String sessionId, AgentEvent event)
  ```
  - `ask` 构建 `RuntimeContext` 时始终 `userId(normalizeUserId(...))`

- [ ] **Step 1: 写失败测试（确认事件映射）**

在 `DevAgentServiceTest` 追加：

```java
    @Test
    void ask_mapsRequireUserConfirmAndStoresPending() {
        RequireUserConfirmEvent confirm = mock(RequireUserConfirmEvent.class);
        when(confirm.getType()).thenReturn(AgentEventType.REQUIRE_USER_CONFIRM);
        when(confirm.getId()).thenReturn("e-c");
        ToolUseBlock toolCall = ToolUseBlock.builder()
                .id("call-9")
                .name("request_file_change")
                .input(Map.of(
                        "operation", "create",
                        "path", "notes/a.txt",
                        "content", "x"))
                .build();
        when(confirm.getToolCalls()).thenReturn(List.of(toolCall));

        RequestStopEvent stop = mock(RequestStopEvent.class);
        when(stop.getType()).thenReturn(AgentEventType.REQUEST_STOP);
        when(stop.getId()).thenReturn("e-s");
        when(stop.getGenerateReason()).thenReturn(GenerateReason.PERMISSION_ASKING);

        when(harnessAgent.streamEvents(eq("写文件"), any(RuntimeContext.class)))
                .thenReturn(Flux.just(confirm, stop));

        StepVerifier.create(service.ask(new DevAgentRequest("u1", "s1", "写文件")))
                .expectNext(DevAgentEvent.session("s1"))
                .expectNextMatches(e ->
                        e.type() == DevAgentEventType.REQUIRE_USER_CONFIRM
                                && e.pendingToolCalls() != null
                                && e.pendingToolCalls().size() == 1
                                && "call-9".equals(e.pendingToolCalls().get(0).toolCallId()))
                .expectNextMatches(e ->
                        e.type() == DevAgentEventType.REQUEST_STOP
                                && e.content().contains("PERMISSION_ASKING"))
                .expectNext(DevAgentEvent.done("s1"))
                .verifyComplete();
    }
```

补 import：`RequireUserConfirmEvent`、`RequestStopEvent`、`ToolUseBlock`、`GenerateReason`、`List`、`Map`。

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -Dtest=DevAgentServiceTest#ask_mapsRequireUserConfirmAndStoresPending test
```

Expected: FAIL（未映射新事件）。

- [ ] **Step 3: 实现 ask 侧 pending + 映射**

在 `DevAgentService` 中：

1. 增加字段：
   ```java
   private final ConcurrentHashMap<String, List<ToolUseBlock>> pendingConfirmations =
           new ConcurrentHashMap<>();
   ```
2. `ask` 内：
   ```java
   String userId = normalizeUserId(request.userId());
   RuntimeContext context = RuntimeContext.builder()
           .sessionId(sessionId)
           .userId(userId)
           .build();
   // mapEvent 改为 mapEvent(userId, sessionId, event)
   ```
3. `mapEvent` 增加分支（可用 `instanceof` 或 `switch` on type）：
   - `RequireUserConfirmEvent`：`pendingConfirmations.put(confirmationKey(userId, sessionId), List.copyOf(e.getToolCalls()))`；返回 `DevAgentEvent.confirmation(...)`，`toPendingToolCall` 映射 `id/name/input`
   - `RequestStopEvent`：content 优先 `getGenerateReason() == null ? getReason() : getGenerateReason().name()`；返回 `requestStop`
4. 辅助方法：
   ```java
   static String normalizeUserId(String userId) {
       return userId == null || userId.isBlank() ? "_anonymous" : userId.strip();
   }

   static String confirmationKey(String userId, String sessionId) {
       return normalizeUserId(userId) + "|" + sessionId;
   }

   private PendingToolCall toPendingToolCall(ToolUseBlock block) {
       return new PendingToolCall(block.getId(), block.getName(), block.getInput());
   }
   ```

同步修正既有测试：若断言 `RuntimeContext.getUserId()` 对 `null` userId，改为期望 `_anonymous`（仅当你改了始终写入 userId 的行为）。当前 `ask_blankApiKey` 用 `null` userId 且不调 Agent，可不动。

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -Dtest=DevAgentServiceTest test
```

Expected: 全绿。

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java
git commit -m "$(cat <<'EOF'
feat(demo2): map permission ASK events and cache pending tool calls

EOF
)"
```

---

### Task 5: confirm API（Request + Controller + Service.confirm）

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentConfirmRequest.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/controller/DevAgentController.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java`

**Interfaces:**
- Produces:
  ```java
  public record DevAgentConfirmRequest(String userId, @NotBlank String sessionId, boolean approved) {}
  public Flux<DevAgentEvent> confirm(DevAgentConfirmRequest request)
  // Controller: POST /confirm → Flux SSE
  ```

- [ ] **Step 1: 写失败测试**

```java
    @Test
    void confirm_withoutPending_emitsError() {
        StepVerifier.create(service.confirm(new DevAgentConfirmRequest("u1", "s-missing", true)))
                .expectNext(DevAgentEvent.session("s-missing"))
                .expectNextMatches(e ->
                        e.type() == DevAgentEventType.ERROR
                                && e.content().contains("待确认"))
                .verifyComplete();
    }

    @Test
    void confirm_approved_resumesWithConfirmResultsMetadata() {
        // 先通过 ask 映射塞入 pending（复用 RequireUserConfirmEvent mock）
        RequireUserConfirmEvent confirmEvt = mock(RequireUserConfirmEvent.class);
        when(confirmEvt.getType()).thenReturn(AgentEventType.REQUIRE_USER_CONFIRM);
        when(confirmEvt.getId()).thenReturn("e-c");
        ToolUseBlock toolCall = ToolUseBlock.builder()
                .id("call-9")
                .name("request_file_change")
                .input(Map.of("operation", "create", "path", "notes/a.txt", "content", "x"))
                .build();
        when(confirmEvt.getToolCalls()).thenReturn(List.of(toolCall));
        when(harnessAgent.streamEvents(eq("写"), any(RuntimeContext.class)))
                .thenReturn(Flux.just(confirmEvt));

        StepVerifier.create(service.ask(new DevAgentRequest("u1", "s1", "写")))
                .expectNextCount(3) // SESSION + REQUIRE_USER_CONFIRM + DONE
                .verifyComplete();

        ToolResultEndEvent toolEnd = mock(ToolResultEndEvent.class);
        when(toolEnd.getType()).thenReturn(AgentEventType.TOOL_RESULT_END);
        when(toolEnd.getId()).thenReturn("e-te");
        when(toolEnd.getToolCallId()).thenReturn("call-9");
        when(toolEnd.getToolCallName()).thenReturn("request_file_change");
        when(toolEnd.getState()).thenReturn(ToolResultState.SUCCESS);

        when(harnessAgent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(Flux.just(toolEnd));

        StepVerifier.create(service.confirm(new DevAgentConfirmRequest("u1", "s1", true)))
                .expectNext(DevAgentEvent.session("s1"))
                .expectNext(DevAgentEvent.toolResultEnd(
                        "s1", "e-te", "call-9", "request_file_change", "SUCCESS"))
                .expectNext(DevAgentEvent.done("s1"))
                .verifyComplete();

        ArgumentCaptor<Msg> msgCaptor = ArgumentCaptor.forClass(Msg.class);
        verify(harnessAgent).streamEvents(msgCaptor.capture(), any(RuntimeContext.class));
        Msg resume = msgCaptor.getValue();
        assertThat(resume.getTextContent()).isEqualTo("approved");
        Object raw = resume.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        assertThat(raw).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<ConfirmResult> results = (List<ConfirmResult>) raw;
        assertThat(results).hasSize(1);
        assertThat(results.get(0).isConfirmed()).isTrue();
        assertThat(results.get(0).getToolCall().getId()).isEqualTo("call-9");
    }
```

补 import：`DevAgentConfirmRequest`、`ConfirmResult`、`Msg`（若未导入）。

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -Dtest=DevAgentServiceTest#confirm_withoutPending_emitsError,DevAgentServiceTest#confirm_approved_resumesWithConfirmResultsMetadata test
```

Expected: 编译失败（缺 `confirm` / `DevAgentConfirmRequest`）。

- [ ] **Step 3: 实现 Request + Service.confirm + Controller**

`DevAgentConfirmRequest.java`:

```java
package com.jason.demo.demo2.agentscope.model;

import jakarta.validation.constraints.NotBlank;

public record DevAgentConfirmRequest(
        String userId,
        @NotBlank String sessionId,
        boolean approved) {
}
```

`DevAgentService.confirm`：

```java
    public Flux<DevAgentEvent> confirm(DevAgentConfirmRequest request) {
        String sessionId = request.sessionId();
        String userId = normalizeUserId(request.userId());
        String apiKey = properties.model().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return Flux.just(
                    DevAgentEvent.session(sessionId),
                    DevAgentEvent.error(sessionId, "DEEPSEEK_API_KEY is not configured"));
        }

        List<ToolUseBlock> pending = pendingConfirmations.remove(confirmationKey(userId, sessionId));
        if (pending == null || pending.isEmpty()) {
            return Flux.just(
                    DevAgentEvent.session(sessionId),
                    DevAgentEvent.error(sessionId, "没有待确认的工具调用"));
        }

        List<ConfirmResult> confirmResults = pending.stream()
                .map(toolCall -> new ConfirmResult(request.approved(), toolCall))
                .toList();

        Msg resumeMessage = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(request.approved() ? "approved" : "denied")
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, confirmResults))
                .build();

        RuntimeContext context = RuntimeContext.builder()
                .sessionId(sessionId)
                .userId(userId)
                .build();

        Flux<DevAgentEvent> events = agentscopeDevAgent
                .streamEvents(resumeMessage, context)
                .handle((event, sink) -> {
                    DevAgentEvent mapped = mapEvent(userId, sessionId, event);
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
```

`DevAgentController` 增加：

```java
    @PostMapping(path = "/confirm", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<DevAgentEvent> confirm(@Valid @RequestBody DevAgentConfirmRequest request) {
        return devAgentService.confirm(request);
    }
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -Dtest=DevAgentServiceTest test
mvn -DskipTests compile
```

Expected: 全绿 + 编译成功。

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/model/DevAgentConfirmRequest.java \
  demo2/src/main/java/com/jason/demo/demo2/agentscope/controller/DevAgentController.java \
  demo2/src/main/java/com/jason/demo/demo2/agentscope/service/DevAgentService.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java
git commit -m "$(cat <<'EOF'
feat(demo2): add /agentscope/dev-agent/confirm HITL resume API

EOF
)"
```

---

### Task 6: AgentScope Tab 确认 UI

**Files:**
- Modify: `demo2/src/main/resources/static/js/tabs/agentscope.js`
- Modify: `demo2/src/main/resources/static/css/tabs/agentscope.css`
- Modify: `demo2/src/main/resources/static/index.html`

**Interfaces:**
- Consumes: SSE `REQUIRE_USER_CONFIRM.pendingToolCalls`；`POST /agentscope/dev-agent/confirm`
- Produces: 确认卡片 + 批准/拒绝；确认后复用 SSE 解析继续更新同一助手轮次

- [ ] **Step 1: CSS**

在 `agentscope.css` 追加：

```css
.agentscope-confirm-card {
    margin-top: 8px;
    padding: 10px 12px;
    border: 1px solid #c5d0d6;
    background: #f7faf8;
    border-radius: 8px;
    font-size: 13px;
    line-height: 1.5;
}
.agentscope-confirm-card pre {
    margin: 6px 0 10px;
    white-space: pre-wrap;
    word-break: break-word;
    max-height: 160px;
    overflow: auto;
    background: #eef2f1;
    padding: 8px;
    border-radius: 6px;
}
.agentscope-confirm-actions {
    display: flex;
    gap: 8px;
}
.agentscope-confirm-actions button {
    padding: 6px 12px;
    border-radius: 6px;
    border: 1px solid #9bb0a5;
    background: #fff;
    cursor: pointer;
}
.agentscope-confirm-actions button.approve {
    background: #17864f;
    color: #fff;
    border-color: #17864f;
}
.agentscope-confirm-actions button:disabled {
    opacity: 0.55;
    cursor: not-allowed;
}
```

- [ ] **Step 2: JS — 确认卡片与 confirm 流**

在 `agentscope.js`：

1. `fillAgentscopeSample` 增加示例 4：
   ```javascript
   4: '请创建 notes/permission-demo.txt，内容是：AgentScope Permission HITL 已通过。'
   ```
2. 抽取 `consumeAgentscopeSse(res, turn)`：把现有 `reader` 循环与 `payload.type` 分支放进去；新增：
   - `REQUIRE_USER_CONFIRM` → `renderAgentscopeConfirmCard(turn, payload)`
   - `REQUEST_STOP` → `setAgentscopeStatus('REQUEST_STOP ' + (payload.content || ''))`
3. `renderAgentscopeConfirmCard(turn, payload)`：
   - 在 `turn.col` 内创建 `.agentscope-confirm-card`
   - 列出 `pendingToolCalls`（name、operation、path、content 截断至 200 字）
   - 按钮「批准」「拒绝」→ `submitAgentscopeConfirm(turn, card, approved)`
4. `submitAgentscopeConfirm`：
   - 禁用输入与按钮
   - `POST /agentscope/dev-agent/confirm`，body `{ userId, sessionId, approved }`
   - `consumeAgentscopeSse` 继续写入同一 `turn`
   - `finally` 恢复输入（按钮可保持 disabled 或移除卡片）

核心确认渲染伪代码（实现时写完整可运行 JS）：

```javascript
function renderAgentscopeConfirmCard(turn, payload) {
    const card = document.createElement('div');
    card.className = 'agentscope-confirm-card';
    const calls = payload.pendingToolCalls || [];
    let body = '需要确认以下工具调用：\n';
    calls.forEach(function (c, i) {
        const input = c.input || {};
        const content = String(input.content || '');
        const clipped = content.length > 200 ? content.slice(0, 200) + '…' : content;
        body += (i + 1) + '. ' + (c.name || '') + '\n'
            + '   operation: ' + (input.operation || '') + '\n'
            + '   path: ' + (input.path || '') + '\n'
            + '   content: ' + clipped + '\n';
    });
    const pre = document.createElement('pre');
    pre.textContent = body;
    card.appendChild(pre);
    const actions = document.createElement('div');
    actions.className = 'agentscope-confirm-actions';
    const approveBtn = document.createElement('button');
    approveBtn.type = 'button';
    approveBtn.className = 'approve';
    approveBtn.textContent = '批准';
    const denyBtn = document.createElement('button');
    denyBtn.type = 'button';
    denyBtn.textContent = '拒绝';
    approveBtn.onclick = function () { submitAgentscopeConfirm(turn, card, true); };
    denyBtn.onclick = function () { submitAgentscopeConfirm(turn, card, false); };
    actions.appendChild(approveBtn);
    actions.appendChild(denyBtn);
    card.appendChild(actions);
    turn.col.appendChild(card);
    scrollAgentscopeMessages();
}
```

- [ ] **Step 3: index.html 文案**

- 副标题改为提到「写文件需确认」
- 欢迎文案补充 `notes/` 写入与批准/拒绝
- 示例按钮增加「示例：写 notes 文件（HITL）」→ `fillAgentscopeSample(4)`

- [ ] **Step 4: 手工冒烟（可选，有 Key 时）**

启动应用后打开 Tab，发送示例 4，应出现确认卡片；批准后 `demo2/notes/permission-demo.txt` 落盘。

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/resources/static/js/tabs/agentscope.js \
  demo2/src/main/resources/static/css/tabs/agentscope.css \
  demo2/src/main/resources/static/index.html
git commit -m "$(cat <<'EOF'
feat(demo2): add AgentScope Tab approve/deny for file writes

EOF
)"
```

---

### Task 7: README + 全量回归

**Files:**
- Modify: `demo2/README.md`（AgentScope 章节）
- 可选：根 `README.md` 若有 AgentScope 一行描述则同步

- [ ] **Step 1: 更新 demo2/README.md**

在 AgentScope 表格中：

- Controller 说明改为：只读工具 + 写文件 HITL
- 增加一行：`POST /agentscope/dev-agent/confirm`，Body：`{"userId?","sessionId","approved"}`
- SSE type 列表追加 `REQUIRE_USER_CONFIRM` / `REQUEST_STOP`
- 补充 curl 示例（ask 写文件 → confirm 批准）
- 注明仅允许 `notes/`，删除 DENY

- [ ] **Step 2: 全量 agentscope 测试 + 编译**

```bash
mvn -Dtest=com.jason.demo.demo2.agentscope.** test
mvn -DskipTests compile
```

Expected: `BUILD SUCCESS`。

- [ ] **Step 3: Commit**

```bash
git add demo2/README.md
git commit -m "$(cat <<'EOF'
docs(demo2): document AgentScope permission HITL confirm flow

EOF
)"
```

---

## Self-Review（对照 spec）

| Spec 要求 | Task |
|-----------|------|
| 原位增强 `/agentscope/dev-agent` | 3–5 |
| `FileChangeTool` + ASK/DENY/路径/符号链接 | 2 |
| `DEFAULT` + 只读 ALLOW | 3 |
| Prompt 写文件约束 | 3 |
| `REQUIRE_USER_CONFIRM` / `REQUEST_STOP` + pending DTO | 1, 4 |
| pending 内存缓存 + userId\|sessionId | 4 |
| `POST /confirm` + `ConfirmResult` 恢复 | 5 |
| Tab 批准/拒绝 | 6 |
| `notes/.gitkeep` + gitignore | 3 |
| 单测 + curl/手工 | 2, 4, 5, 7 |
| 非目标（分 toolCallId / 持久化等） | 未纳入 |

无 TBD；命名与 spec 一致：`request_file_change`、`pendingToolCalls`、`_anonymous`。
