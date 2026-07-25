# AgentScope Harness Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 AgentScope Dev Agent 上打开 Harness Memory，使同一 `userId` 换 `sessionId` 后仍能读到项目约定，并用配置开关控制总启用与 `memory_save` HITL。

**Architecture:** `DevAgentProperties.memory` 描述开关、HITL、Flush/Consolidation；`AgentScopeConfig` 在 `enabled=true` 时装配 `io.agentscope.harness.agent.memory.MemoryConfig` 并去掉 `disableMemoryTools/Hooks`，按 `saveRequiresConfirm` 决定是否为 `memory_save` 加 ALLOW；前端复用已有「换会话」逻辑（保留 userId），补充 Memory 示例与文案。Controller / SSE / Service 不改。

**Tech Stack:** Java 21、Spring Boot 4.1、AgentScope Java 2.0（`MemoryConfig` / Memory 工具与 Hooks）、JUnit 6、AssertJ。

## Global Constraints

- 设计规范：`demo2/docs/superpowers/specs/2026-07-25-agentscope-harness-memory-design.md`。
- AgentScope 保持 `2.0.0`，**不新增** Maven 依赖。
- **不改** `DevAgentController` / `DevAgentService` 主流程 / SSE 事件模型。
- 导入时使用 `io.agentscope.harness.agent.memory.MemoryConfig`，避免与 `com.jason.demo.demo2.config.MemoryConfig`（Spring AI 聊天记忆）混淆。
- `consolidation-prompt` 必须含两个 `%d`。
- 测试默认 `app.agentscope.dev-agent.memory.enabled=false`。
- 本地默认 `memory.enabled=true`、`save-requires-confirm=true`。
- 与 Spring AI AutoMemory / Session Memory Tab **无关**。

## File Map

**Create**

- （无新 Java 类；能力由 Harness 内置提供）

**Modify**

- `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/DevAgentProperties.java`：增加 `Memory` record。
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`：条件启用 Memory + 权限规则。
- `demo2/src/main/resources/application.properties`：memory 开关与间隔。
- `demo2/src/main/resources/application-agentscope-prompts.yml`：flush / consolidation / system-prompt 增量。
- `demo2/src/test/resources/application-test.properties`：`memory.enabled=false`。
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/DevAgentPropertiesBindingTest.java`：绑定测试。
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java`：补 `memory` 参数；断言工具有无。
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java`：所有 `new DevAgentProperties(...)` 补 `memory`。
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/mcp/AgentscopeMcpClientRegistryTest.java`：`props(...)` 补 `memory`。
- `demo2/src/main/resources/static/index.html`：按钮文案 + Memory 示例按钮。
- `demo2/src/main/resources/static/js/tabs/agentscope.js`：示例 9/10、欢迎文案。
- `demo2/README.md`：AgentScope Memory 小节。

---

### Task 1: DevAgentProperties Memory 模型与绑定测试

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/DevAgentProperties.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/DevAgentPropertiesBindingTest.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/mcp/AgentscopeMcpClientRegistryTest.java`
- Modify: `demo2/src/test/resources/application-test.properties`

**Interfaces:**
- Produces: `DevAgentProperties.Memory(boolean enabled, boolean saveRequiresConfirm, Duration flushMinGap, Duration consolidationMinGap, int consolidationMaxTokens, String flushPrompt, String consolidationPrompt)`
- Produces: 缺 `memory` 时默认 `enabled=false`、`saveRequiresConfirm=true`，gap/prompt 填文章默认值
- Consumes: 绑定前缀 `app.agentscope.dev-agent.memory.*`

- [ ] **Step 1: 写失败的绑定测试**

在 `DevAgentPropertiesBindingTest` 增加：

```java
@Test
void bindsMemorySettings() {
    runner.withPropertyValues(
            "app.agentscope.dev-agent.memory.enabled=true",
            "app.agentscope.dev-agent.memory.save-requires-confirm=false",
            "app.agentscope.dev-agent.memory.flush-min-gap=5m",
            "app.agentscope.dev-agent.memory.consolidation-min-gap=15m",
            "app.agentscope.dev-agent.memory.consolidation-max-tokens=2000",
            "app.agentscope.dev-agent.memory.flush-prompt=flush-me",
            "app.agentscope.dev-agent.memory.consolidation-prompt=consol %d %d"
    ).run(ctx -> {
        DevAgentProperties.Memory memory = ctx.getBean(DevAgentProperties.class).memory();
        assertThat(memory.enabled()).isTrue();
        assertThat(memory.saveRequiresConfirm()).isFalse();
        assertThat(memory.flushMinGap()).isEqualTo(java.time.Duration.ofMinutes(5));
        assertThat(memory.consolidationMinGap()).isEqualTo(java.time.Duration.ofMinutes(15));
        assertThat(memory.consolidationMaxTokens()).isEqualTo(2000);
        assertThat(memory.flushPrompt()).isEqualTo("flush-me");
        assertThat(memory.consolidationPrompt()).isEqualTo("consol %d %d");
    });
}

@Test
void memoryDefaultsToDisabledWhenAbsent() {
    runner.run(ctx -> {
        DevAgentProperties.Memory memory = ctx.getBean(DevAgentProperties.class).memory();
        assertThat(memory.enabled()).isFalse();
        assertThat(memory.saveRequiresConfirm()).isTrue();
        assertThat(memory.flushMinGap()).isEqualTo(java.time.Duration.ofMinutes(10));
        assertThat(memory.consolidationMinGap()).isEqualTo(java.time.Duration.ofMinutes(30));
        assertThat(memory.consolidationMaxTokens()).isEqualTo(4000);
        assertThat(memory.consolidationPrompt()).contains("%d");
    });
}
```

- [ ] **Step 2: 运行测试确认失败**

Run（在 `demo2` 目录）:

```bash
mvn -q -Dtest=DevAgentPropertiesBindingTest#bindsMemorySettings,DevAgentPropertiesBindingTest#memoryDefaultsToDisabledWhenAbsent test
```

Expected: 编译失败或绑定失败（尚无 `memory()` 字段）。

- [ ] **Step 3: 实现 Properties**

将 `DevAgentProperties` 改为（保留现有 `Compaction` / `Model` / `McpSettings` / `McpClientConfig` 不变，仅增加 `memory`）：

```java
package com.jason.demo.demo2.agentscope.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "app.agentscope.dev-agent")
public record DevAgentProperties(
        @NotBlank String name,
        @NotBlank String systemPrompt,
        @NotBlank String projectRoot,
        @NotBlank String workspaceRoot,
        @Valid Compaction compaction,
        @Valid Model model,
        @Valid McpSettings mcp,
        @Valid Memory memory) {

    private static final String DEFAULT_FLUSH_PROMPT = """
            从对话中提取以后仍然有用的项目约定、用户偏好、技术决定和待办事项。
            忽略寒暄、临时状态、工具调用细节以及已经存在的重复信息。
            不记录密码、令牌、密钥、手机号、邮箱等敏感信息。
            只输出 Markdown 列表；没有值得保存的信息时，只输出 NO_REPLY。
            """;

    private static final String DEFAULT_CONSOLIDATION_PROMPT = """
            把现有 MEMORY.md 和新增的每日记忆整理成一份完整的长期记忆。
            合并重复信息；新决定覆盖已经失效的旧决定；删除寒暄、临时状态和敏感信息。
            最终内容不超过 %d tokens，约 %d 个字符。
            只输出整理后的完整 MEMORY.md，不要解释整理过程。
            """;

    public DevAgentProperties {
        if (mcp == null) {
            mcp = new McpSettings(false, List.of());
        }
        if (memory == null) {
            memory = new Memory(
                    false,
                    true,
                    Duration.ofMinutes(10),
                    Duration.ofMinutes(30),
                    4000,
                    DEFAULT_FLUSH_PROMPT,
                    DEFAULT_CONSOLIDATION_PROMPT);
        }
    }

    // ... existing Compaction, Model, McpSettings, McpClientConfig unchanged ...

    public record Memory(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("true") boolean saveRequiresConfirm,
            Duration flushMinGap,
            Duration consolidationMinGap,
            Integer consolidationMaxTokens,
            String flushPrompt,
            String consolidationPrompt) {

        public Memory {
            if (flushMinGap == null) {
                flushMinGap = Duration.ofMinutes(10);
            }
            if (consolidationMinGap == null) {
                consolidationMinGap = Duration.ofMinutes(30);
            }
            if (consolidationMaxTokens == null || consolidationMaxTokens < 1) {
                consolidationMaxTokens = 4000;
            }
            if (flushPrompt == null || flushPrompt.isBlank()) {
                flushPrompt = DEFAULT_FLUSH_PROMPT;
            }
            if (consolidationPrompt == null || consolidationPrompt.isBlank()) {
                consolidationPrompt = DEFAULT_CONSOLIDATION_PROMPT;
            }
        }
    }
}
```

注意：`Memory` 的默认 prompt 常量写在外层 record，内层 compact constructor 引用 `DevAgentProperties.DEFAULT_*`；若编译器不允许内层引用私有常量，改为 `public static final` 或把默认字符串直接写在 `Memory` compact 内。

测试辅助：所有 `new DevAgentProperties(..., mcp)` 在末尾追加 `null`（走默认 disabled Memory），或显式传入：

```java
new DevAgentProperties.Memory(
        false, true,
        java.time.Duration.ofMinutes(10),
        java.time.Duration.ofMinutes(30),
        4000,
        "flush",
        "consol %d %d")
```

优先传 `null`，减少样板。

在 `application-test.properties` 追加：

```properties
# AgentScope Harness Memory：测试不启长期记忆 Hooks/工具
app.agentscope.dev-agent.memory.enabled=false
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -q -Dtest=DevAgentPropertiesBindingTest,DevAgentServiceTest,AgentScopeMiddlewareConfigTest,AgentscopeMcpClientRegistryTest test
```

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/DevAgentProperties.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/config/DevAgentPropertiesBindingTest.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/mcp/AgentscopeMcpClientRegistryTest.java \
  demo2/src/test/resources/application-test.properties
git commit -m "feat(demo2): add DevAgentProperties memory settings binding"
```

---

### Task 2: AgentScopeConfig 装配 Memory + 权限 + 配置文件

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`
- Modify: `demo2/src/main/resources/application.properties`
- Modify: `demo2/src/main/resources/application-agentscope-prompts.yml`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java`

**Interfaces:**
- Consumes: `DevAgentProperties.Memory`
- Produces: `@Bean MemoryConfig agentscopeMemoryConfig(DevAgentProperties)`（FQN：`io.agentscope.harness.agent.memory.MemoryConfig`）
- Produces: `enabled=true` 时 Toolkit 含 `memory_save` / `memory_search` / `memory_get`；`false` 时不含
- Produces: `saveRequiresConfirm=true` 时不对 `memory_save` 加 ALLOW

- [ ] **Step 1: 写失败/扩展的装配测试**

在 `AgentScopeMiddlewareConfigTest` 增加（保留原测试；原 `new DevAgentProperties` 已在 Task 1 补 `memory`）：

```java
@Test
void agentscopeDevAgent_memoryDisabled_omitsMemoryTools() throws Exception {
    // properties.memory.enabled=false（默认）
    // 构建 agent 后：
    assertThat(agent.getToolkit().getToolNames())
            .doesNotContain("memory_save", "memory_search", "memory_get");
}

@Test
void agentscopeDevAgent_memoryEnabled_registersMemoryTools() throws Exception {
    DevAgentProperties properties = new DevAgentProperties(
            "dev-task-agent",
            "prompt",
            tempDir.toString(),
            tempDir.toString(),
            new DevAgentProperties.Compaction(6, 2, "请整理：{messages}"),
            new DevAgentProperties.Model("sk-test", "https://api.deepseek.com", "deepseek-v4-pro"),
            new DevAgentProperties.McpSettings(false, java.util.List.of()),
            new DevAgentProperties.Memory(
                    true,
                    true,
                    java.time.Duration.ofMinutes(10),
                    java.time.Duration.ofMinutes(30),
                    4000,
                    "flush",
                    "consol %d %d"));
    // config.agentscopeDevAgent(..., config.agentscopeMemoryConfig(properties), ...)
    assertThat(agent.getToolkit().getToolNames())
            .contains("memory_save", "memory_search", "memory_get");
}
```

若 `agentscopeDevAgent` 方法签名增加 `MemoryConfig` 参数，同步改本类所有调用。

另增权限单元测试（同一测试类或新建 `AgentScopeMemoryPermissionTest`）：

```java
@Test
void applyMemoryAllowRules_saveRequiresConfirm_skipsMemorySave() {
    PermissionContextState.Builder builder =
            PermissionContextState.builder().mode(PermissionMode.DEFAULT);
    AgentScopeConfig.applyMemoryAllowRules(
            builder,
            new DevAgentProperties.Memory(
                    true, true,
                    Duration.ofMinutes(10), Duration.ofMinutes(30), 4000,
                    "f", "c %d %d"));
    PermissionContextState state = builder.build();
    // 用现有 Permission API 断言：memory_search / memory_get 为 ALLOW；
    // memory_save 无 ALLOW（具体断言方式以 PermissionContextState 公开 API 为准，
    // 例如检查 allow 规则列表或对假 ToolUse 求值）。
}
```

若 `PermissionContextState` 不便直接断言规则列表，可改为：在 `saveRequiresConfirm=true` 时仅验证 Toolkit 含 `memory_save`（HITL 由 DEFAULT 模式默认覆盖），并在 README 手工验证路径说明；**不要**为断言而引入脆弱反射。最低要求：enabled 开关下工具有无断言必须有。

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -q -Dtest=AgentScopeMiddlewareConfigTest#agentscopeDevAgent_memoryEnabled_registersMemoryTools test
```

Expected: FAIL（仍 disable Memory 或签名未接 MemoryConfig）。

- [ ] **Step 3: 实现 AgentScopeConfig**

增加 import：

```java
import io.agentscope.harness.agent.memory.MemoryConfig;
```

增加 Bean 与转换：

```java
@Bean
MemoryConfig agentscopeMemoryConfig(DevAgentProperties properties) {
    return toMemoryConfig(properties.memory());
}

static MemoryConfig toMemoryConfig(DevAgentProperties.Memory config) {
    return MemoryConfig.builder()
            .flushTrigger(MemoryConfig.FlushTrigger.throttled(config.flushMinGap()))
            .consolidationMinGap(config.consolidationMinGap())
            .consolidationMaxTokens(config.consolidationMaxTokens())
            .flushPrompt(config.flushPrompt())
            .consolidationPrompt(config.consolidationPrompt())
            .build();
}
```

修改 `agentscopeDevAgent`：增加参数 `MemoryConfig agentscopeMemoryConfig`；将 builder 中：

```java
.disableMemoryTools()
.disableMemoryHooks()
.compaction(agentscopeCompactionConfig)
```

改为：

```java
.compaction(agentscopeCompactionConfig)
```

并在构建前分支：

```java
HarnessAgent.Builder builder = HarnessAgent.builder()
        .name(properties.name())
        // ... 现有公共配置 ...
        .compaction(agentscopeCompactionConfig)
        .disableSubagents()
        // ... 其余 disable* ...
        ;

if (properties.memory().enabled()) {
    builder.memory(agentscopeMemoryConfig);
} else {
    builder.disableMemoryTools().disableMemoryHooks();
}

HarnessAgent agent = builder.build();
```

（若 `HarnessAgent.builder()` 返回类型名不同，以源码/IDE 为准；保持链式等价。）

`permissionContext` 改为接收 `DevAgentProperties`（或 `Memory`）：

```java
.permissionContext(permissionContext(properties, agentscopeMcpClientRegistry))
```

```java
private static PermissionContextState permissionContext(
        DevAgentProperties properties,
        AgentscopeMcpClientRegistry agentscopeMcpClientRegistry) {
    PermissionContextState.Builder builder =
            PermissionContextState.builder().mode(PermissionMode.DEFAULT);
    READ_ONLY_TOOL_NAMES.forEach(
            toolName -> builder.addAllowRule(toolName, allowRule(toolName)));
    for (AgentscopeMcpClientRegistry.Entry entry : agentscopeMcpClientRegistry.entries()) {
        for (String toolName : entry.enabledTools()) {
            builder.addAllowRule(toolName, allowRule(toolName));
        }
    }
    applyMemoryAllowRules(builder, properties.memory());
    return builder.build();
}

/** package-visible for tests */
static void applyMemoryAllowRules(
        PermissionContextState.Builder builder, DevAgentProperties.Memory memory) {
    if (!memory.enabled()) {
        return;
    }
    builder.addAllowRule("memory_search", allowRule("memory_search"));
    builder.addAllowRule("memory_get", allowRule("memory_get"));
    if (!memory.saveRequiresConfirm()) {
        builder.addAllowRule("memory_save", allowRule("memory_save"));
    }
}
```

- [ ] **Step 4: 写入配置与 prompts**

`application.properties` 在 MCP 段落后增加：

```properties
# AgentScope Harness Memory（跨会话长期记忆；与 Spring AI AutoMemory Tab 无关）
app.agentscope.dev-agent.memory.enabled=true
app.agentscope.dev-agent.memory.save-requires-confirm=true
app.agentscope.dev-agent.memory.flush-min-gap=10m
app.agentscope.dev-agent.memory.consolidation-min-gap=30m
app.agentscope.dev-agent.memory.consolidation-max-tokens=4000
```

`application-agentscope-prompts.yml`：在现有 `system-prompt` **末尾追加** Memory 指引；并增加 `memory.flush-prompt` / `memory.consolidation-prompt`（全文与设计规范 §4.2 一致，含两个 `%d`）。

示例 system-prompt 追加段落：

```yaml
        用户明确要求记住项目约定、个人偏好或长期决定时，
        调用 memory_save 保存；查询过去的决定时，优先使用长期记忆。
```

- [ ] **Step 5: 运行测试确认通过**

```bash
mvn -q -Dtest=AgentScopeMiddlewareConfigTest,DevAgentPropertiesBindingTest test
```

Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java \
  demo2/src/main/resources/application.properties \
  demo2/src/main/resources/application-agentscope-prompts.yml \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java
git commit -m "feat(demo2): enable AgentScope Harness Memory with HITL switch"
```

---

### Task 3: 前端 Memory 示例与「新开会话」文案

**Files:**
- Modify: `demo2/src/main/resources/static/index.html`
- Modify: `demo2/src/main/resources/static/js/tabs/agentscope.js`

**Interfaces:**
- Consumes: 现有 `resetAgentscopeConversation()`（已生成新 `sessionId` 且**保留** `userId`）
- Produces: 按钮文案「新开会话（保留 userId）」；示例 9/10 预填 Memory 演示

说明：现有 `#agentscopeNewSessionBtn`（文案「换会话」）已满足「保留 userId + 清空对话区」；本任务**不新建重复按钮**，只改文案与示例。

- [ ] **Step 1: 改 HTML**

将：

```html
<button type="button" id="agentscopeNewSessionBtn">换会话</button>
```

改为：

```html
<button type="button" id="agentscopeNewSessionBtn">新开会话（保留 userId）</button>
```

在 samples 区追加：

```html
<button type="button" onclick="fillAgentscopeSample(9)">示例：Memory 记住约定</button>
<button type="button" onclick="fillAgentscopeSample(10)">示例：Memory 跨会话提问</button>
```

欢迎语中补充一句：可用「Memory 记住约定」后点「新开会话（保留 userId）」再用「跨会话提问」验证长期记忆。

- [ ] **Step 2: 改 JS**

在 `fillAgentscopeSample` 的 `samples` 增加：

```javascript
9: '请记住下面三条项目约定：构建统一使用 Maven Wrapper；测试命令是 ./mvnw test；发布窗口是每周四 20:00。保存后简短确认。',
10: '我们项目使用什么构建方式？测试命令是什么？发布窗口安排在什么时候？不要调用项目文件工具。'
```

并在函数末尾：

```javascript
if (n === 9 || n === 10) {
    const userId = document.getElementById('agentscopeUserId');
    const sessionId = document.getElementById('agentscopeSessionId');
    if (userId) userId.value = 'memory-user-012';
    if (sessionId) {
        sessionId.value = n === 9 ? 'memory-session-a-012' : 'memory-session-b-012';
    }
}
```

更新 `resetAgentscopeConversation` / 欢迎 HTML 中「换会话」措辞为「新开会话（保留 userId）」，并提及 Memory 示例。

- [ ] **Step 3: 手工快速检查（无自动化）**

打开 AgentScope Tab：确认按钮文案、示例 9/10 会写入对应 userId/sessionId。

- [ ] **Step 4: Commit**

```bash
git add demo2/src/main/resources/static/index.html \
  demo2/src/main/resources/static/js/tabs/agentscope.js
git commit -m "feat(demo2): add AgentScope Memory demo samples and session button label"
```

---

### Task 4: README 文档

**Files:**
- Modify: `demo2/README.md`

- [ ] **Step 1: 更新能力表与 API 节**

在 AgentScope Harness 能力描述中追加 **Harness Memory（跨会话）**；在 `/agentscope/dev-agent` 小节增加 Memory 配置项与三条 curl（对齐文章：会话 A 保存 → 会话 B 同 userId → 换 userId）。

必写要点：

1. `memory.enabled` / `save-requires-confirm` / 间隔配置键。
2. 落盘路径：`workspace/{userId}/MEMORY.md`。
3. 与 Spring AI AutoMemory / Session Memory Tab 无关。
4. demo 信任客户端 `userId`（生产应来自登录态）。
5. Spec 链接：`docs/superpowers/specs/2026-07-25-agentscope-harness-memory-design.md`。

curl 示例（端口以 README 现有 AgentScope 端口为准，当前文档若写 `8081` 则保持一致）：

```bash
# 会话 A：保存约定（若 save-requires-confirm=true，需前端/confirm 批准 memory_save）
curl -sN -X POST "http://localhost:8081/agentscope/dev-agent/ask" \
  -H "Content-Type: application/json" \
  -d '{"userId":"memory-user-012","sessionId":"memory-session-a-012","message":"请记住下面三条项目约定：构建统一使用 Maven Wrapper；测试命令是 ./mvnw test；发布窗口是每周四 20:00。保存后简短确认。"}'
```

- [ ] **Step 2: Commit**

```bash
git add demo2/README.md
git commit -m "docs(demo2): document AgentScope Harness Memory cross-session recall"
```

---

## Manual verification（实现完成后）

1. 启动 PostgreSQL（可选）+ 配置 `DEEPSEEK_API_KEY`，`mvn spring-boot:run`（`demo2`）。
2. Tab：示例 9 → 批准 `memory_save` HITL → 确认 `workspace/memory-user-012/MEMORY.md` 生成。
3. 点「新开会话（保留 userId）」或示例 10 → 能答出三条约定。
4. 将 userId 改为 `memory-user-other-012` 再问 → 不知道。
5. 临时设 `memory.enabled=false` 重启 → Toolkit 无 memory 工具。

---

## Self-review (plan vs spec)

| Spec 要求 | Task |
|-----------|------|
| 现有 Dev Agent 打开 Memory | Task 2 |
| `memory.enabled` + test 默认 false | Task 1 + 2 |
| `save-requires-confirm` 默认 true | Task 1 + 2 |
| flush/consolidation 配置与 prompt | Task 2 |
| system-prompt 增量 | Task 2 |
| 前端新开会话保留 userId | Task 3（复用现有按钮） |
| Memory 示例 | Task 3 |
| README / curl | Task 4 |
| 不改 Controller/SSE/Service | Global Constraints |
| 不做 MEMORY.md 面板 / 独立 Tab | 无对应任务（YAGNI） |
