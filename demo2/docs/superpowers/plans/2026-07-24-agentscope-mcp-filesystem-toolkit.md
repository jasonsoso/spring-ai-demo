# AgentScope MCP Filesystem Toolkit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 demo2 的 AgentScope Dev Agent Toolkit 中接入可开关、可扩展的多 Client MCP（本次落地 stdio filesystem 只读三工具），用 `mcp-files/project-profile.md` 演示列目录再读档案。

**Architecture:** `DevAgentProperties.mcp.clients[]` 描述多个 stdio MCP Client；`AgentscopeMcpClientRegistry` 按总开关/单开关创建并托管 `McpClientWrapper` 生命周期；`AgentScopeConfig` 将 Client 白名单工具 `registration().enableTools().apply()` 进现有 `HarnessAgent` Toolkit，并用 `{mcpRoot}` 注入 system prompt。测试环境 `mcp.enabled=false`，不启动 `npx`。

**Tech Stack:** Java 21、Spring Boot 4.1、AgentScope Java 2.0（`McpClientBuilder` / `Toolkit.ToolRegistration`）、官方 `@modelcontextprotocol/server-filesystem@2026.7.10`（经 `npx`）、JUnit 6、AssertJ。

## Global Constraints

- 设计规范：`demo2/docs/superpowers/specs/2026-07-24-agentscope-mcp-filesystem-toolkit-design.md`。
- AgentScope 保持 `2.0.0`，**不新增** Maven 依赖。
- 不改 `DevAgentController` / `DevAgentService` / 前端 `agentscope.js`；不改 Spring AI `mcp/client/**`。
- 继续 `.disableFilesystemTools()`、`.disableToolsConfig()`；不在 `permissionContext` 为 MCP 工具逐条加 ALLOW。
- 本次仅 stdio；不实现 SSE / Streamable HTTP。
- 工具白名单固定为 `list_allowed_directories`、`list_directory`、`read_text_file`。
- 档案 Java 版本写 **21**（对齐 demo2）。
- Windows 先用 `command=npx`；若手工验证失败再另开修复，不在本计划默认改 `cmd /c`。

## File Map

**Create**

- `demo2/mcp-files/project-profile.md`：供 MCP 读取的项目档案。
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/mcp/AgentscopeMcpClientRegistry.java`：解析 root、创建/关闭多 Client、暴露注册条目。
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/mcp/AgentscopeMcpClientRegistryTest.java`：disabled / resolveRoot / primaryMcpRoot。

**Modify**

- `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/DevAgentProperties.java`：`McpSettings` + `McpClientConfig`。
- `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`：Registry Bean、prompt 替换、Toolkit 注册。
- `demo2/src/main/resources/application.properties`：启用 filesystem Client 列表。
- `demo2/src/main/resources/application-agentscope-prompts.yml`：MCP 使用说明与 `{mcpRoot}`。
- `demo2/src/test/resources/application-test.properties`：`mcp.enabled=false`。
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/DevAgentPropertiesBindingTest.java`：绑定 MCP 列表。
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java`：构造函数补 `mcp` + 注入空 Registry。
- `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java`：所有 `new DevAgentProperties(...)` 补 `mcp`。
- `demo2/README.md`：AgentScope MCP 一行说明（与 Spring AI MCP 区分）。

---

### Task 1: DevAgentProperties MCP 模型与绑定测试

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/DevAgentProperties.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/DevAgentPropertiesBindingTest.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java`
- Modify: `demo2/src/test/resources/application-test.properties`

**Interfaces:**
- Produces: `DevAgentProperties.McpSettings(boolean enabled, List<McpClientConfig> clients)`
- Produces: `DevAgentProperties.McpClientConfig(String name, boolean enabled, String command, List<String> arguments, String root, List<String> enabledTools)`
- Produces: 安全默认 — 缺 `mcp` 时 `enabled=false`、`clients=[]`；单 Client 缺 `enabled` 时为 `true`
- Consumes: 现有 `app.agentscope.dev-agent.*` 绑定前缀

- [ ] **Step 1: 写失败的绑定测试**

在 `DevAgentPropertiesBindingTest` 增加：

```java
@Test
void bindsMcpClientsList() {
    runner.withPropertyValues(
            "app.agentscope.dev-agent.mcp.enabled=true",
            "app.agentscope.dev-agent.mcp.clients[0].name=project-files",
            "app.agentscope.dev-agent.mcp.clients[0].command=npx",
            "app.agentscope.dev-agent.mcp.clients[0].arguments[0]=-y",
            "app.agentscope.dev-agent.mcp.clients[0].arguments[1]=@modelcontextprotocol/server-filesystem@2026.7.10",
            "app.agentscope.dev-agent.mcp.clients[0].root=mcp-files",
            "app.agentscope.dev-agent.mcp.clients[0].enabled-tools[0]=list_directory",
            "app.agentscope.dev-agent.mcp.clients[0].enabled-tools[1]=read_text_file"
    ).run(ctx -> {
        DevAgentProperties.McpSettings mcp = ctx.getBean(DevAgentProperties.class).mcp();
        assertThat(mcp.enabled()).isTrue();
        assertThat(mcp.clients()).hasSize(1);
        DevAgentProperties.McpClientConfig c0 = mcp.clients().getFirst();
        assertThat(c0.name()).isEqualTo("project-files");
        assertThat(c0.enabled()).isTrue();
        assertThat(c0.command()).isEqualTo("npx");
        assertThat(c0.arguments()).containsExactly(
                "-y", "@modelcontextprotocol/server-filesystem@2026.7.10");
        assertThat(c0.root()).isEqualTo("mcp-files");
        assertThat(c0.enabledTools()).containsExactly("list_directory", "read_text_file");
    });
}

@Test
void mcpDefaultsToDisabledWhenAbsent() {
    runner.run(ctx -> {
        DevAgentProperties.McpSettings mcp = ctx.getBean(DevAgentProperties.class).mcp();
        assertThat(mcp.enabled()).isFalse();
        assertThat(mcp.clients()).isEmpty();
    });
}
```

- [ ] **Step 2: 运行测试确认失败**

Run（在 `demo2` 目录）:

```bash
mvn -q -Dtest=DevAgentPropertiesBindingTest#bindsMcpClientsList,DevAgentPropertiesBindingTest#mcpDefaultsToDisabledWhenAbsent test
```

Expected: 编译失败或绑定失败（尚无 `mcp()` 字段）。

- [ ] **Step 3: 实现 Properties**

将 `DevAgentProperties` 改为：

```java
package com.jason.demo.demo2.agentscope.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

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
        @Valid McpSettings mcp) {

    public DevAgentProperties {
        if (mcp == null) {
            mcp = new McpSettings(false, List.of());
        }
    }

    public record Compaction(
            @Min(2) int triggerMessages,
            @Min(1) int keepMessages,
            @NotBlank String summaryPrompt) {
    }

    public record Model(
            String apiKey,
            @NotBlank String baseUrl,
            @NotBlank String name) {
    }

    public record McpSettings(
            @DefaultValue("false") boolean enabled,
            @DefaultValue List<@Valid McpClientConfig> clients) {

        public McpSettings {
            if (clients == null) {
                clients = List.of();
            }
        }
    }

    public record McpClientConfig(
            @NotBlank String name,
            @DefaultValue("true") boolean enabled,
            @NotBlank String command,
            @NotEmpty List<@NotBlank String> arguments,
            String root,
            @NotEmpty List<@NotBlank String> enabledTools) {
    }
}
```

- [ ] **Step 4: 同步所有手写构造**

凡 `new DevAgentProperties(...)` 在 `model` 后追加禁用 MCP：

```java
new DevAgentProperties.McpSettings(false, List.of())
```

至少修改：

- `DevAgentServiceTest.java`（两处）
- `AgentScopeMiddlewareConfigTest.java`（一处；本 Task 只改 Properties 构造；Registry 注入留 Task 4）

- [ ] **Step 5: 测试配置关 MCP**

在 `application-test.properties` 追加：

```properties
# AgentScope Toolkit MCP：测试不启 npx 子进程
app.agentscope.dev-agent.mcp.enabled=false
```

- [ ] **Step 6: 跑绑定测试通过**

```bash
mvn -q -Dtest=DevAgentPropertiesBindingTest test
```

Expected: PASS。

- [ ] **Step 7: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/DevAgentProperties.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/config/DevAgentPropertiesBindingTest.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/service/DevAgentServiceTest.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java \
  demo2/src/test/resources/application-test.properties
git commit -m "feat(demo2): add multi-client MCP settings to DevAgentProperties"
```

---

### Task 2: AgentscopeMcpClientRegistry（root 解析 + 关闭时不启进程）

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/agentscope/mcp/AgentscopeMcpClientRegistry.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/agentscope/mcp/AgentscopeMcpClientRegistryTest.java`

**Interfaces:**
- Produces: `AgentscopeMcpClientRegistry.create(DevAgentProperties) → AgentscopeMcpClientRegistry`
- Produces: `record Entry(McpClientWrapper client, List<String> enabledTools)`
- Produces: `List<Entry> entries()`
- Produces: `static Path resolveRoot(String projectRoot, String configuredRoot)`
- Produces: `static String primaryMcpRootDisplay(DevAgentProperties properties)` — 无可用 root 时返回 `(MCP 未启用)`
- Produces: `void close()` — 依次 `client.close()`
- Consumes: `McpClientBuilder.create(name).stdioTransport(command, args...).buildAsync().block()`
- Consumes: `DevAgentProperties.mcp()`

- [ ] **Step 1: 写失败的 Registry 测试**

```java
package com.jason.demo.demo2.agentscope.mcp;

import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentscopeMcpClientRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void create_whenDisabled_returnsEmptyWithoutClients() {
        DevAgentProperties props = props(false, List.of(
                new DevAgentProperties.McpClientConfig(
                        "project-files",
                        true,
                        "npx",
                        List.of("-y", "@modelcontextprotocol/server-filesystem@2026.7.10"),
                        "mcp-files",
                        List.of("list_directory"))));

        try (AgentscopeMcpClientRegistry registry = AgentscopeMcpClientRegistry.create(props)) {
            assertThat(registry.entries()).isEmpty();
        }
    }

    @Test
    void resolveRoot_relativeUsesProjectRoot() {
        Path root = AgentscopeMcpClientRegistry.resolveRoot(tempDir.toString(), "mcp-files");
        assertThat(root).isEqualTo(tempDir.resolve("mcp-files").toAbsolutePath().normalize());
    }

    @Test
    void resolveRoot_absoluteKeepsNormalized() {
        Path abs = tempDir.resolve("abs-root").toAbsolutePath();
        Path root = AgentscopeMcpClientRegistry.resolveRoot(tempDir.toString(), abs.toString());
        assertThat(root).isEqualTo(abs.normalize());
    }

    @Test
    void primaryMcpRootDisplay_whenDisabled_returnsPlaceholder() {
        DevAgentProperties props = props(false, List.of());
        assertThat(AgentscopeMcpClientRegistry.primaryMcpRootDisplay(props))
                .isEqualTo("(MCP 未启用)");
    }

    @Test
    void primaryMcpRootDisplay_usesFirstEnabledClientWithRoot() {
        DevAgentProperties props = props(true, List.of(
                new DevAgentProperties.McpClientConfig(
                        "other", true, "echo", List.of("x"), null, List.of("t")),
                new DevAgentProperties.McpClientConfig(
                        "project-files",
                        true,
                        "npx",
                        List.of("-y"),
                        "mcp-files",
                        List.of("list_directory"))));

        String display = AgentscopeMcpClientRegistry.primaryMcpRootDisplay(props);
        assertThat(display).isEqualTo(
                tempDir.resolve("mcp-files").toAbsolutePath().normalize().toString());
    }

    private DevAgentProperties props(boolean enabled, List<DevAgentProperties.McpClientConfig> clients) {
        return new DevAgentProperties(
                "dev-task-agent",
                "prompt {mcpRoot}",
                tempDir.toString(),
                tempDir.toString(),
                new DevAgentProperties.Compaction(6, 2, "请整理：{messages}"),
                new DevAgentProperties.Model("sk", "https://api.deepseek.com", "deepseek-v4-pro"),
                new DevAgentProperties.McpSettings(enabled, clients));
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q -Dtest=AgentscopeMcpClientRegistryTest test
```

Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现 Registry**

```java
package com.jason.demo.demo2.agentscope.mcp;

import com.jason.demo.demo2.agentscope.config.DevAgentProperties;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class AgentscopeMcpClientRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentscopeMcpClientRegistry.class);
    public static final String MCP_DISABLED_ROOT_PLACEHOLDER = "(MCP 未启用)";

    public record Entry(McpClientWrapper client, List<String> enabledTools) {
    }

    private final List<Entry> entries;

    private AgentscopeMcpClientRegistry(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<Entry> entries() {
        return entries;
    }

    public static AgentscopeMcpClientRegistry create(DevAgentProperties properties) {
        DevAgentProperties.McpSettings mcp = properties.mcp();
        if (!mcp.enabled()) {
            return new AgentscopeMcpClientRegistry(List.of());
        }

        List<Entry> created = new ArrayList<>();
        try {
            for (DevAgentProperties.McpClientConfig config : mcp.clients()) {
                if (!config.enabled()) {
                    continue;
                }
                List<String> arguments = new ArrayList<>(config.arguments());
                if (config.root() != null && !config.root().isBlank()) {
                    arguments.add(resolveRoot(properties.projectRoot(), config.root()).toString());
                }
                McpClientWrapper client = McpClientBuilder.create(config.name())
                        .stdioTransport(config.command(), arguments.toArray(String[]::new))
                        .buildAsync()
                        .block();
                created.add(new Entry(client, List.copyOf(config.enabledTools())));
                log.info("AgentScope MCP client ready: name={}, tools={}",
                        config.name(), config.enabledTools());
            }
        } catch (RuntimeException ex) {
            created.forEach(entry -> safeClose(entry.client()));
            throw ex;
        }
        return new AgentscopeMcpClientRegistry(created);
    }

    public static Path resolveRoot(String projectRoot, String configuredRoot) {
        Path configured = Path.of(configuredRoot);
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        return Path.of(projectRoot)
                .toAbsolutePath()
                .normalize()
                .resolve(configured)
                .normalize();
    }

    public static String primaryMcpRootDisplay(DevAgentProperties properties) {
        if (!properties.mcp().enabled()) {
            return MCP_DISABLED_ROOT_PLACEHOLDER;
        }
        for (DevAgentProperties.McpClientConfig config : properties.mcp().clients()) {
            if (config.enabled() && config.root() != null && !config.root().isBlank()) {
                return resolveRoot(properties.projectRoot(), config.root()).toString();
            }
        }
        return MCP_DISABLED_ROOT_PLACEHOLDER;
    }

    @Override
    public void close() {
        for (Entry entry : entries) {
            safeClose(entry.client());
        }
    }

    private static void safeClose(McpClientWrapper client) {
        try {
            client.close();
        } catch (RuntimeException ex) {
            log.warn("Failed to close MCP client {}", client.getName(), ex);
        }
    }
}
```

- [ ] **Step 4: 跑 Registry 测试**

```bash
mvn -q -Dtest=AgentscopeMcpClientRegistryTest test
```

Expected: PASS（不启动真实 `npx`，因 `enabled=false` 与纯静态路径测试）。

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/mcp/AgentscopeMcpClientRegistry.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/mcp/AgentscopeMcpClientRegistryTest.java
git commit -m "feat(demo2): add AgentscopeMcpClientRegistry for multi stdio MCP clients"
```

---

### Task 3: 资料文件 + 应用配置 + Prompt

**Files:**
- Create: `demo2/mcp-files/project-profile.md`
- Modify: `demo2/src/main/resources/application.properties`
- Modify: `demo2/src/main/resources/application-agentscope-prompts.yml`

**Interfaces:**
- Produces: 运行时配置 `app.agentscope.dev-agent.mcp.*`（本地默认启用 filesystem）
- Produces: system prompt 含 `{mcpRoot}` 与档案查询 / 越界验证指令
- Consumes: Task 1 的 Properties 形状

- [ ] **Step 1: 创建档案文件**

`demo2/mcp-files/project-profile.md`：

```markdown
# 项目档案

- 项目编号：AJS-MCP-011
- Java 版本：21
- Spring Boot 版本：4.1.0
- 维护团队：平台工程组
- 发布窗口：每周四 20:00
```

- [ ] **Step 2: 写入 application.properties**

在 AgentScope 配置块后追加：

```properties
# AgentScope Toolkit MCP（stdio filesystem；与 Spring AI MCP Tab 无关）
# 需本机 Node/npx；测试见 application-test.properties 关闭
app.agentscope.dev-agent.mcp.enabled=true
app.agentscope.dev-agent.mcp.clients[0].name=project-files
app.agentscope.dev-agent.mcp.clients[0].enabled=true
app.agentscope.dev-agent.mcp.clients[0].command=npx
app.agentscope.dev-agent.mcp.clients[0].arguments[0]=-y
app.agentscope.dev-agent.mcp.clients[0].arguments[1]=@modelcontextprotocol/server-filesystem@2026.7.10
app.agentscope.dev-agent.mcp.clients[0].root=mcp-files
app.agentscope.dev-agent.mcp.clients[0].enabled-tools[0]=list_allowed_directories
app.agentscope.dev-agent.mcp.clients[0].enabled-tools[1]=list_directory
app.agentscope.dev-agent.mcp.clients[0].enabled-tools[2]=read_text_file
```

- [ ] **Step 3: 更新 system-prompt**

将 `application-agentscope-prompts.yml` 中 `system-prompt` 替换为：

```yaml
      system-prompt: |
        你是一个研发任务整理助手。
        根据用户请求整理任务，必要时调用已经注册的工具。
        MCP 资料目录是 {mcpRoot}。
        用户询问其中的项目档案时，先调用 list_directory 查看这个目录，
        再调用 read_text_file 读取指定文件。调用工具时使用完整路径，
        不能根据文件名猜测内容。
        用户明确要求用 read_text_file 验证越界路径时，仍要按原路径调用该工具，
        不能改用 list_allowed_directories，也不要提前判断访问结果。
        使用中文回答，并遵守 Workspace 中的项目规则。
```

- [ ] **Step 4: Commit**

```bash
git add demo2/mcp-files/project-profile.md \
  demo2/src/main/resources/application.properties \
  demo2/src/main/resources/application-agentscope-prompts.yml
git commit -m "feat(demo2): configure filesystem MCP client and project profile"
```

---

### Task 4: 接入 AgentScopeConfig Toolkit

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java`

**Interfaces:**
- Consumes: `AgentscopeMcpClientRegistry.create` / `entries` / `primaryMcpRootDisplay`
- Produces: `@Bean(destroyMethod = "close") AgentscopeMcpClientRegistry agentscopeMcpClientRegistry(...)`
- Produces: `HarnessAgent` 构建时 `sysPrompt` 已替换 `{mcpRoot}`；对每个 Entry 调用 `registration().mcpClient(...).enableTools(...).apply()`

- [ ] **Step 1: 先改 MiddlewareConfigTest 签名预期（会失败）**

将 `agentscopeDevAgent(...)` 调用改为传入空 Registry，并在 Properties 中已含 `mcp`（Task 1）：

```java
try (HarnessAgent agent = config.agentscopeDevAgent(
        model,
        properties,
        AgentScopeConfig.toCompactionConfig(properties.compaction()),
        new ProjectInfoTools(tempDir),
        new FileChangeTool(tempDir),
        store,
        middleware,
        AgentscopeMcpClientRegistry.create(properties))) {
```

增加 import：`com.jason.demo.demo2.agentscope.mcp.AgentscopeMcpClientRegistry`。

另增断言（可选但推荐）：

```java
assertThat(agent.getToolkit().getToolNames())
        .doesNotContain("list_directory", "read_text_file", "list_allowed_directories");
```

（因 `properties.mcp.enabled=false`。）

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q -Dtest=AgentScopeMiddlewareConfigTest test
```

Expected: 编译失败（方法签名尚未增加 Registry 参数）。

- [ ] **Step 3: 修改 AgentScopeConfig**

1. 增加 Bean：

```java
@Bean(destroyMethod = "close")
AgentscopeMcpClientRegistry agentscopeMcpClientRegistry(DevAgentProperties properties) {
    return AgentscopeMcpClientRegistry.create(properties);
}
```

2. `agentscopeDevAgent` 增加参数 `AgentscopeMcpClientRegistry agentscopeMcpClientRegistry`。

3. 构建前：

```java
String systemPrompt = properties.systemPrompt()
        .replace("{mcpRoot}", AgentscopeMcpClientRegistry.primaryMcpRootDisplay(properties));

HarnessAgent agent = HarnessAgent.builder()
        .name(properties.name())
        .sysPrompt(systemPrompt)
        // ... 其余保持不变
        .build();
agent.getToolkit().removeTool("wait_async_results");
agent.getToolkit().registerTool(projectInfoTools);
agent.getToolkit().registerAgentTool(fileChangeTool);
for (AgentscopeMcpClientRegistry.Entry entry : agentscopeMcpClientRegistry.entries()) {
    agent.getToolkit()
            .registration()
            .mcpClient(entry.client())
            .enableTools(entry.enabledTools())
            .apply();
}
return agent;
```

注意：`apply()` 返回 `void`，不要 `.block()`。

- [ ] **Step 4: 跑相关测试**

```bash
mvn -q -Dtest=AgentScopeMiddlewareConfigTest,AgentscopeMcpClientRegistryTest,DevAgentPropertiesBindingTest,DevAgentServiceTest,AgentscopeCompactionConfigTest test
```

Expected: PASS。

- [ ] **Step 5: 全量测试（不启 MCP）**

```bash
mvn -q test
```

Expected: PASS；日志中不应因 AgentScope MCP 拉起 `npx`。

- [ ] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/agentscope/config/AgentScopeConfig.java \
  demo2/src/test/java/com/jason/demo/demo2/agentscope/config/AgentScopeMiddlewareConfigTest.java
git commit -m "feat(demo2): register AgentScope MCP tools into Dev Agent Toolkit"
```

---

### Task 5: README 与手工验收清单

**Files:**
- Modify: `demo2/README.md`

**Interfaces:**
- Produces: 文档说明 AgentScope Toolkit MCP 与 Spring AI MCP 的区别、Node 依赖、开关配置、curl 验收

- [ ] **Step 1: 更新 README**

在 AgentScope Harness 相关章节（功能表与/或详细节）补充要点（保持现有文风，一两段即可）：

- Dev Agent Toolkit 可挂载 **stdio MCP**（默认 filesystem，只读三工具），配置前缀 `app.agentscope.dev-agent.mcp`，支持 `clients[]` 扩展。
- 与「🔌 MCP Client 聊天 / 瑞幸 MCP」**无关**；本能力走 AgentScope `McpClientBuilder`，不是 Spring AI MCP Client。
- 依赖本机 **Node.js / npx**；测试 `app.agentscope.dev-agent.mcp.enabled=false`。
- 资料目录：`demo2/mcp-files/`；档案查询与越界验证 curl 示例指向 `POST /agentscope/dev-agent/ask`（端口 **8081**）。

档案查询 curl：

```bash
curl -sN -X POST "http://localhost:8081/agentscope/dev-agent/ask" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"mcp-user-011\",\"sessionId\":\"mcp-session-011\",\"message\":\"请先列出 MCP 资料目录，再读取 project-profile.md，告诉我项目编号、Java 版本、Spring Boot 版本和维护团队。\"}"
```

越界（Windows 可用 `C:\\Windows\\System32\\drivers\\etc\\hosts` 或任意 `mcp-files` 外路径）：

```bash
curl -sN -X POST "http://localhost:8081/agentscope/dev-agent/ask" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"mcp-user-011\",\"sessionId\":\"mcp-outside-011\",\"message\":\"请必须调用 read_text_file 读取 C:\\\\Windows\\\\System32\\\\drivers\\\\etc\\\\hosts，并告诉我工具返回了什么。不要只根据规则直接回答。\"}"
```

- [ ] **Step 2: 手工验收（实现者本机，需 Postgres + DEEPSEEK_API_KEY + Node）**

1. `mvn spring-boot:run`（工作目录 `demo2`）。
2. 启动日志出现 `AgentScope MCP client ready: name=project-files` 及 tools 列表。
3. 跑档案 curl：事件含 `list_directory` / `read_text_file`；回答含 `AJS-MCP-011`、`21`、`4.1.0`、`平台工程组`；无 `REQUIRE_USER_CONFIRM`。
4. 跑越界 curl：出现 Access denied；无确认事件。
5. 若 Windows 上 `npx` 启动失败：记录错误，开 follow-up（`command=cmd` + `/c` + `npx`...），不阻塞本 Task 文档合并。

- [ ] **Step 3: Commit**

```bash
git add demo2/README.md
git commit -m "docs(demo2): document AgentScope filesystem MCP toolkit usage"
```

---

## Spec coverage checklist

| Spec 要求 | Task |
|-----------|------|
| 多 Client 列表 + 总/单开关 | 1, 2 |
| filesystem stdio + 版本钉死 | 3 |
| 只读三工具白名单 | 3, 4 |
| `{mcpRoot}` prompt | 3, 4 |
| `mcp-files/project-profile.md`（Java 21） | 3 |
| Registry 生命周期 `close` | 2, 4 |
| 测试默认关 MCP | 1, 4 |
| 不改 Controller/SSE/Spring AI MCP | 全篇遵守 |
| README 区分两套 MCP | 5 |
| 手工档案 + 越界 | 5 |
| 远程 transport 扩展 | 明确非本计划 |

## Placeholder / consistency self-check

- 无 TBD；API 使用 `registration().apply()`（void），与 AgentScope 2.0.0 `javap` 一致。
- Properties 构造与所有测试构造均含 `mcp` 参数。
- 端口统一 **8081**；路径前缀 `/agentscope/dev-agent/ask`。
