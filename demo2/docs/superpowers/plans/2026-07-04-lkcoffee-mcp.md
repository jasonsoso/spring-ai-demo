# 瑞幸 MCP 点单 · SSE 对话 Implementation Plan

> **Status:** ✅ 已完成（2026-07-06）— Task 1–8 完成；`LkCoffeeSkillLoaderTest` / `LkCoffeeAgentServiceTest` 通过；完整点单联调需配置 `LKCOFFEE_TOKEN` + `AMAP_API_KEY`。归档见 [archive/2026-07-06-lkcoffee-mcp.md](../archive/2026-07-06-lkcoffee-mcp.md)。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 demo2 新增「☕ 瑞幸 MCP 点单」Tab：双远程 MCP（瑞幸 + 高德 geocode）+ 官方 My Coffee Skill 编排 + SSE 多轮对话，演示完整点单链路。

**Architecture:** `LkCoffeeSkillLoader` 启动时读入官方 `SKILL.md` 并追加项目覆盖规则作为 System Prompt；`LkCoffeeMcpConfig` 通过 `McpClientCustomizer<HttpClientStreamableHttpTransport.Builder>` 注入 Bearer Token（ThreadLocal）；`LkCoffeeAgentService` 挂载**白名单过滤后**的 MCP `ToolCallback`，对齐 `ToolReasoningAgentService` 的 SSE 流式模式；前端对齐 `tool-reasoning.js`（`fetch` + `ReadableStream`）。

**实施说明（与初稿差异）：**
- Subagent `task-brief` 脚本在 Windows/WSL 下失败，改由主 Agent inline 完成全部 Task。
- Bearer 注入改用 `McpClientCustomizer`（Spring AI 2.0 不再应用 `McpSyncHttpClientRequestCustomizer` Bean）。
- `LkCoffeeAgentService` 双构造器需 `@Autowired` 标注正式构造器。
- Token ThreadLocal 在 SSE `onError`/`onComplete` 中清除，不可在 `subscribe()` 后立即 `finally clear`。
- 测试环境增加 `agent.lkcoffee.enabled=false`，避免启动时连接远程 MCP 超时。
- `LkCoffeeMcpToolCallbacksProvider` 延迟加载 MCP 工具（须在 `McpClientInitializer` 之后），修复应用无法启动问题。
- `geocodeAddress` 当前返回 `{ raw: content }`，结构化 `{ longitude, latitude }` 解析为可选后续改进。

**Tech Stack:** Java 21, Spring Boot 4.1, Spring AI 2.0.0 MCP Client, DeepSeek `deepseek-v4-pro`, 原生 HTML/CSS/JS

**设计规范:** [docs/superpowers/specs/2026-07-04-lkcoffee-mcp-design.md](../specs/2026-07-04-lkcoffee-mcp-design.md)

## Global Constraints

- **不修改** `McpChatController`、本地 MCP Server、`WeatherTool`、`AttractionTool`（注：新增远程 MCP 连接后，现有 MCP Tab 工具列表可能变长，可接受）
- **不引入 SkillsTool**；Skill 内容内嵌 System Prompt
- **模型**：`agent.lkcoffee.chat.model=deepseek-v4-pro`（联调失败可降级 `deepseek-chat`）
- **记忆**：独立 `lkCoffeeChatMemory` Bean（窗口 20 条，与 `@Primary` chatMemory 隔离）
- **sessionId 校验**：`^[a-zA-Z0-9_-]+$`，非法 HTTP 400
- **API 前缀**：`/agent/lkcoffee`；SSE 超时 **5 分钟**
- **Token 优先级**：请求体 `token` > 环境变量 `LKCOFFEE_TOKEN`；禁止读写 `~/.my-coffee/`
- **下单确认**：`previewOrder` 后必须用户明确确认才 `createOrder`（覆盖 Skill 自动下单规则）
- **SSE 事件**：`RUNNING` / `TOOL_CALL` / `TOKEN` / `ORDER_PREVIEW` / `PAYMENT_QR` / `COMPLETED` / `FAILED`
- **McpToolFilter**：**不注册全局 Bean**（Spring AI 仅允许一个，会影响现有 MCP Tab）；改在 `LkCoffeeAgentConfig` 内按工具名白名单过滤
- **编译门禁**：`mvn -pl demo2 -DskipTests compile`；单元测试 `mvn -pl demo2 test`

---

## File Structure

| 文件 | 职责 |
|------|------|
| `resources/.claude/skills/my-coffee/SKILL.md` | 官方 Skill v0.8.2（原样 vendoring） |
| `resources/.claude/skills/my-coffee/manifest.json` | 版本标注 |
| `model/LkCoffeeChatRequest.java` | 请求 DTO |
| `model/LkCoffeeSseEvent.java` | SSE 事件工厂 |
| `mcp/client/LkCoffeeTokenContext.java` | ThreadLocal Bearer Token |
| `service/LkCoffeeSkillLoader.java` | 读 SKILL.md + 覆盖规则 |
| `mcp/client/config/LkCoffeeMcpConfig.java` | HTTP Customizer、过滤工具 Bean |
| `config/LkCoffeeAgentConfig.java` | ChatMemory、Advisor、ChatClient 依赖 |
| `mcp/client/LkCoffeeToolCallbackWrapper.java` | 包装 MCP ToolCallback，推送 TOOL_CALL / ORDER_PREVIEW / PAYMENT_QR |
| `service/LkCoffeeAgentService.java` | `streamChat`、`clearSession`、`geocode` |
| `controller/LkCoffeeAgentController.java` | REST + SSE 端点 |
| `test/.../LkCoffeeSkillLoaderTest.java` | Skill 加载与覆盖规则 |
| `test/.../LkCoffeeAgentServiceTest.java` | sessionId 校验 |
| `static/css/tabs/lkcoffee.css` | Tab 样式 |
| `static/js/tabs/lkcoffee.js` | SSE 对话 + 定位/Token |
| `static/index.html` | Tab 入口 |
| `application.properties` | MCP 连接 + 模型 + Skill 路径 |
| `application-test.properties` | 测试跳过远程 MCP 初始化（`agent.lkcoffee.enabled=false`） |
| `sse/LkCoffeeStreamContext.java` | SSE 桥接（TOOL_CALL / ORDER_PREVIEW / PAYMENT_QR） |

---

### Task 1: Vendoring 官方 Skill + Model DTO

**Files:**
- Create: `demo2/src/main/resources/.claude/skills/my-coffee/SKILL.md`
- Create: `demo2/src/main/resources/.claude/skills/my-coffee/manifest.json`
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/LkCoffeeChatRequest.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/model/LkCoffeeSseEvent.java`

**Interfaces:**
- Produces: `LkCoffeeChatRequest`（`sessionId`, `message`, `token`, `longitude`, `latitude`, `address`）；`LkCoffeeSseEvent` 静态工厂

- [x] **Step 1: 下载并复制官方 Skill**

Run（PowerShell）:

```powershell
cd demo2
Invoke-WebRequest -Uri "https://unpkg.luckincoffeecdn.com/@luckin/my-coffee-skill@latest/dist/my-coffee-skill.zip" -OutFile target/my-coffee-skill.zip
Expand-Archive -Path target/my-coffee-skill.zip -DestinationPath target/my-coffee-skill -Force
Copy-Item target/my-coffee-skill/my-coffee/SKILL.md src/main/resources/.claude/skills/my-coffee/SKILL.md
Copy-Item target/my-coffee-skill/my-coffee/manifest.json src/main/resources/.claude/skills/my-coffee/manifest.json
```

Expected: `SKILL.md` 约 19KB，`manifest.json` 中 `version` 为 `0.8.2`

- [x] **Step 2: 创建 `LkCoffeeChatRequest.java`**

```java
package com.jason.demo.demo2.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "瑞幸 MCP 点单对话请求")
public class LkCoffeeChatRequest {

    @Schema(description = "会话 ID", example = "lk-session-001")
    private String sessionId;

    @Schema(description = "用户消息", example = "帮我来一杯冰美式")
    private String message;

    @Schema(description = "瑞幸 Bearer Token（可选，覆盖环境变量）")
    private String token;

    @Schema(description = "经度（可选，来自浏览器定位或地址解析）")
    private Double longitude;

    @Schema(description = "纬度（可选）")
    private Double latitude;

    @Schema(description = "地址（可选，无经纬度时供 Agent geocode）")
    private String address;
}
```

- [x] **Step 3: 创建 `LkCoffeeSseEvent.java`**

```java
package com.jason.demo.demo2.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LkCoffeeSseEvent {

    private String type;
    private String content;
    private String error;
    private String toolName;
    private Integer callIndex;
    private Object payload;
    private String qrUrl;

    public static LkCoffeeSseEvent running() {
        return new LkCoffeeSseEvent("RUNNING", null, null, null, null, null, null);
    }

    public static LkCoffeeSseEvent toolCall(String toolName, int callIndex) {
        return new LkCoffeeSseEvent("TOOL_CALL", null, null, toolName, callIndex, null, null);
    }

    public static LkCoffeeSseEvent token(String content) {
        return new LkCoffeeSseEvent("TOKEN", content, null, null, null, null, null);
    }

    public static LkCoffeeSseEvent orderPreview(Object payload) {
        return new LkCoffeeSseEvent("ORDER_PREVIEW", null, null, null, null, payload, null);
    }

    public static LkCoffeeSseEvent paymentQr(String qrUrl) {
        return new LkCoffeeSseEvent("PAYMENT_QR", null, null, null, null, null, qrUrl);
    }

    public static LkCoffeeSseEvent completed() {
        return new LkCoffeeSseEvent("COMPLETED", null, null, null, null, null, null);
    }

    public static LkCoffeeSseEvent failed(String error) {
        return new LkCoffeeSseEvent("FAILED", null, error, null, null, null, null);
    }
}
```

- [x] **Step 4: 编译验证**

Run: `cd demo2 && mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/resources/.claude/skills/my-coffee/ \
        demo2/src/main/java/com/jason/demo/demo2/model/LkCoffeeChatRequest.java \
        demo2/src/main/java/com/jason/demo/demo2/model/LkCoffeeSseEvent.java
git commit -m "feat(demo2): add lkcoffee skill vendoring and request/SSE models"
```

---

### Task 2: SkillLoader + TokenContext

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/service/LkCoffeeSkillLoader.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/mcp/client/LkCoffeeTokenContext.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/service/LkCoffeeSkillLoaderTest.java`

**Interfaces:**
- Consumes: `agent.lkcoffee.skill` Resource 路径
- Produces: `LkCoffeeSkillLoader.buildSystemPrompt()` → `String`；`LkCoffeeTokenContext.set/get/clear`

- [x] **Step 1: 写失败测试 `LkCoffeeSkillLoaderTest.java`**

```java
package com.jason.demo.demo2.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "agent.lkcoffee.skill=classpath:/.claude/skills/my-coffee/SKILL.md",
        "agent.lkcoffee.enabled=false",
        "app.mcp.client.init-on-startup=false"
})
class LkCoffeeSkillLoaderTest {

    @Autowired
    LkCoffeeSkillLoader skillLoader;

    @Test
    void buildSystemPrompt_containsSkillAndOverrideRules() {
        String prompt = skillLoader.buildSystemPrompt();
        assertThat(prompt).contains("My Coffee");
        assertThat(prompt).contains("demo2 项目覆盖规则");
        assertThat(prompt).contains("禁止读写 ~/.my-coffee/");
        assertThat(prompt).contains("previewOrder 完成后");
    }
}
```

- [x] **Step 2: 运行测试确认失败**

Run: `cd demo2 && mvn -q test -Dtest=LkCoffeeSkillLoaderTest`
Expected: FAIL（类不存在）

- [x] **Step 3: 创建 `LkCoffeeTokenContext.java`**

```java
package com.jason.demo.demo2.mcp.client;

public final class LkCoffeeTokenContext {

    private static final ThreadLocal<String> TOKEN = new ThreadLocal<>();

    private LkCoffeeTokenContext() {}

    public static void set(String token) {
        TOKEN.set(token);
    }

    public static String get() {
        return TOKEN.get();
    }

    public static void clear() {
        TOKEN.remove();
    }
}
```

- [x] **Step 4: 创建 `LkCoffeeSkillLoader.java`**

```java
package com.jason.demo.demo2.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class LkCoffeeSkillLoader {

    private static final String OVERRIDE_RULES = """

            【demo2 项目覆盖规则 — 优先级高于 Skill 默认行为】

            1. Token 管理：
               - 使用 Tab 设置区 / 请求体 token / 环境变量 LKCOFFEE_TOKEN。
               - 禁止读写 ~/.my-coffee/LUCKIN_MCP_TOKEN 本地文件。
               - 禁止询问用户是否保存 token 到本地文件。

            2. MCP 调用方式：
               - 仅通过 Spring AI 已挂载的 MCP 工具调用，禁止 curl 直连 MCP HTTP。
               - 忽略 Skill 中「curl 调用 MCP」相关章节。

            3. 下单确认（强制）：
               - previewOrder 完成后，必须展示价格明细并等待用户明确回复「确认下单」等肯定语，
                 才允许调用 createOrder。
               - 覆盖 Skill 中「价格不涨则不再询问、直接 createOrder」的规则。

            4. 定位：
               - 优先使用前端请求附带的 longitude/latitude（见用户消息中的坐标上下文）。
               - 用户给地址时，调用高德 MCP 地理编码工具（非 IP 定位）。

            5. 门店选择：
               - queryShopList 后必须让用户从返回列表中确认门店，禁止自动选最近一家。
            """;

    private final String skillContent;

    public LkCoffeeSkillLoader(@Value("${agent.lkcoffee.skill}") Resource skillResource) throws IOException {
        this.skillContent = StreamUtils.copyToString(skillResource.getInputStream(), StandardCharsets.UTF_8);
        log.info("[LkCoffee] 已加载 My Coffee Skill，长度={} 字符", skillContent.length());
    }

    public String buildSystemPrompt() {
        return skillContent + "\n\n" + OVERRIDE_RULES;
    }
}
```

- [x] **Step 5: 运行测试确认通过**

Run: `cd demo2 && mvn -q test -Dtest=LkCoffeeSkillLoaderTest`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/service/LkCoffeeSkillLoader.java \
        demo2/src/main/java/com/jason/demo/demo2/mcp/client/LkCoffeeTokenContext.java \
        demo2/src/test/java/com/jason/demo/demo2/service/LkCoffeeSkillLoaderTest.java
git commit -m "feat(demo2): add lkcoffee skill loader and token context"
```

---

### Task 3: application.properties + MCP 连接配置

**Files:**
- Modify: `demo2/src/main/resources/application.properties`
- Modify: `demo2/src/test/resources/application-test.properties`
- Create: `demo2/src/main/java/com/jason/demo/demo2/config/LkCoffeeAgentConfig.java`（部分：properties 绑定）

**Interfaces:**
- Produces: `agent.lkcoffee.chat.model`、`lkcoffee.token` 配置项可读

- [x] **Step 1: 追加 `application.properties`**

在 MCP Client 配置块后追加：

```properties
# ===== 瑞幸 MCP 点单 Agent =====
spring.ai.mcp.client.streamable-http.connections.lkcoffee.url=https://gwmcp.lkcoffee.com/order/user
spring.ai.mcp.client.streamable-http.connections.lkcoffee.endpoint=/mcp
lkcoffee.token=${LKCOFFEE_TOKEN:}

spring.ai.mcp.client.streamable-http.connections.amap.url=https://mcp.amap.com/mcp?key=${AMAP_API_KEY:}

agent.lkcoffee.chat.model=deepseek-v4-pro
agent.lkcoffee.skill=classpath:/.claude/skills/my-coffee/SKILL.md
```

- [x] **Step 2: 追加 `application-test.properties`**

```properties
# 瑞幸 Tab 测试：跳过远程 MCP 手动初始化（与现有 local-server 一致）
app.mcp.client.init-on-startup=false
agent.lkcoffee.enabled=false
lkcoffee.token=
spring.ai.mcp.client.streamable-http.connections.amap.url=https://mcp.amap.com/mcp?key=
agent.lkcoffee.skill=classpath:/.claude/skills/my-coffee/SKILL.md
```

- [x] **Step 3: 创建 `LkCoffeeAgentConfig.java`（ChatMemory 部分）**

```java
package com.jason.demo.demo2.config;

import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LkCoffeeAgentConfig {

    @Value("${agent.lkcoffee.chat.model:deepseek-v4-pro}")
    private String lkCoffeeChatModel;

    @Value("${lkcoffee.token:}")
    private String defaultToken;

    public String getLkCoffeeChatModel() {
        return lkCoffeeChatModel;
    }

    public String getDefaultToken() {
        return defaultToken;
    }

    @Bean("lkCoffeeChatMemory")
    public ChatMemory lkCoffeeChatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
    }

    @Bean("lkCoffeeMessageChatMemoryAdvisor")
    public MessageChatMemoryAdvisor lkCoffeeMessageChatMemoryAdvisor(
            @org.springframework.beans.factory.annotation.Qualifier("lkCoffeeChatMemory") ChatMemory lkCoffeeChatMemory) {
        return MessageChatMemoryAdvisor.builder(lkCoffeeChatMemory).build();
    }
}
```

- [x] **Step 4: 编译验证**

Run: `cd demo2 && mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/resources/application.properties \
        demo2/src/test/resources/application-test.properties \
        demo2/src/main/java/com/jason/demo/demo2/config/LkCoffeeAgentConfig.java
git commit -m "feat(demo2): add lkcoffee MCP connections and agent config beans"
```

---

### Task 4: LkCoffeeMcpConfig（Token Customizer + 工具白名单）

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/mcp/client/config/LkCoffeeMcpConfig.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/mcp/client/LkCoffeeToolCallbackWrapper.java`

**Interfaces:**
- Consumes: `SyncMcpToolCallbackProvider`、`LkCoffeeTokenContext`
- Produces: `@Bean("lkCoffeeMcpToolCallbacks") ToolCallback[]` — 仅瑞幸 8 工具 + 高德 geocode 2 工具

- [x] **Step 1: 创建 `LkCoffeeMcpConfig.java`**

```java
package com.jason.demo.demo2.mcp.client.config;

import com.jason.demo.demo2.mcp.client.LkCoffeeTokenContext;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpRequest;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
public class LkCoffeeMcpConfig {

    private static final Set<String> LKCOFFEE_TOOL_SUFFIXES = Set.of(
            "queryShopList", "searchProductForMcp", "queryProductDetailInfo", "switchProduct",
            "previewOrder", "createOrder", "queryOrderDetailInfo", "cancelOrder");

    /** 启动后从 McpClientInitializer 日志确认高德 geocode 实际 tool name，补充到此集合 */
    private static final Set<String> AMAP_GEO_TOOL_SUFFIXES = Set.of(
            "geocode", "reverse_geocode", "maps_geo", "maps_regeocode");

    @Bean
    public McpSyncHttpClientRequestCustomizer lkCoffeeBearerTokenCustomizer() {
        return (connectionName, requestBuilder) -> {
            if (!"lkcoffee".equals(connectionName)) {
                return;
            }
            String token = LkCoffeeTokenContext.get();
            if (token != null && !token.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + token);
            }
        };
    }

    @Bean("lkCoffeeMcpToolCallbacks")
    public ToolCallback[] lkCoffeeMcpToolCallbacks(SyncMcpToolCallbackProvider mcpToolCallbackProvider) {
        return Arrays.stream(mcpToolCallbackProvider.getToolCallbacks())
                .filter(tc -> isAllowedTool(tc.getToolDefinition().name()))
                .map(LkCoffeeToolCallbackWrapper::new)
                .toArray(ToolCallback[]::new);
    }

    static boolean isAllowedTool(String prefixedName) {
        String lower = prefixedName.toLowerCase();
        return LKCOFFEE_TOOL_SUFFIXES.stream().anyMatch(lower::contains)
                || AMAP_GEO_TOOL_SUFFIXES.stream().anyMatch(lower::contains);
    }
}
```

> **注意：** 若 `McpSyncHttpClientRequestCustomizer` 包名或方法签名与 Spring AI 2.0.0 不一致，以 IDE 自动导入为准；核心是仅对 `connectionName == "lkcoffee"` 注入 Bearer Header。

- [x] **Step 2: 创建 `LkCoffeeToolCallbackWrapper.java`**

```java
package com.jason.demo.demo2.mcp.client;

import com.jason.demo.demo2.model.LkCoffeeSseEvent;
import com.jason.demo.demo2.sse.LkCoffeeStreamContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LkCoffeeToolCallbackWrapper implements ToolCallback {

    private static final Pattern QR_URL = Pattern.compile(
            "\"payOrderQrCodeUrl\"\\s*:\\s*\"([^\"]+)\"");

    private final ToolCallback delegate;

    public LkCoffeeToolCallbackWrapper(ToolCallback delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        AtomicInteger idx = LkCoffeeStreamContext.callIndex();
        int callIndex = idx != null ? idx.incrementAndGet() : 0;
        String toolName = getToolDefinition().name();
        LkCoffeeStreamContext.emit(LkCoffeeSseEvent.toolCall(toolName, callIndex));

        String result = delegate.call(toolInput);

        if (toolName.toLowerCase().contains("previeworder")) {
            LkCoffeeStreamContext.emit(LkCoffeeSseEvent.orderPreview(result));
        } else if (toolName.toLowerCase().contains("createorder")) {
            Matcher m = QR_URL.matcher(result);
            if (m.find()) {
                LkCoffeeStreamContext.emit(LkCoffeeSseEvent.paymentQr(m.group(1)));
            }
        }
        return result;
    }
}
```

- [x] **Step 3: 创建 `LkCoffeeStreamContext.java`（SSE 桥接，参照 ToolReasoningStreamContext）**

路径: `demo2/src/main/java/com/jason/demo/demo2/sse/LkCoffeeStreamContext.java`

```java
package com.jason.demo.demo2.sse;

import com.jason.demo.demo2.model.LkCoffeeSseEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public final class LkCoffeeStreamContext {

    private static final ThreadLocal<Context> CTX = new ThreadLocal<>();

    private record Context(SseEmitter emitter, JsonMapper jsonMapper, AtomicInteger callIndex) {}

    private LkCoffeeStreamContext() {}

    public static void bind(SseEmitter emitter, JsonMapper jsonMapper) {
        CTX.set(new Context(emitter, jsonMapper, new AtomicInteger(0)));
    }

    public static AtomicInteger callIndex() {
        Context c = CTX.get();
        return c != null ? c.callIndex : null;
    }

    public static void emit(LkCoffeeSseEvent event) {
        Context c = CTX.get();
        if (c == null) {
            return;
        }
        try {
            c.emitter.send(SseEmitter.event()
                    .data(c.jsonMapper.writeValueAsString(event))
                    .build());
        } catch (IOException e) {
            c.emitter.completeWithError(e);
        }
    }

    public static void clear() {
        CTX.remove();
    }
}
```

- [x] **Step 4: 编译验证**

Run: `cd demo2 && mvn -q compile`
Expected: BUILD SUCCESS（若 Customizer API 不匹配，按 Spring AI 2.0 文档调整）

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/mcp/client/config/LkCoffeeMcpConfig.java \
        demo2/src/main/java/com/jason/demo/demo2/mcp/client/LkCoffeeToolCallbackWrapper.java \
        demo2/src/main/java/com/jason/demo/demo2/sse/LkCoffeeStreamContext.java
git commit -m "feat(demo2): add lkcoffee MCP token customizer and filtered tool callbacks"
```

---

### Task 5: LkCoffeeAgentService（SSE 流式对话）

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/service/LkCoffeeAgentService.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/service/LkCoffeeAgentServiceTest.java`

**Interfaces:**
- Consumes: `LkCoffeeSkillLoader.buildSystemPrompt()`、`@Qualifier("lkCoffeeMcpToolCallbacks") ToolCallback[]`、`@Qualifier("lkCoffeeMessageChatMemoryAdvisor")`、`LkCoffeeAgentConfig.getDefaultToken()`
- Produces: `streamChat(LkCoffeeChatRequest, SseEmitter, JsonMapper)`、`clearSession(String)`、`validateSessionId(String)`、`resolveToken(String requestToken)`

- [x] **Step 1: 写失败测试 `LkCoffeeAgentServiceTest.java`**

```java
package com.jason.demo.demo2.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LkCoffeeAgentServiceTest {

    private final LkCoffeeAgentService service = new LkCoffeeAgentService(
            MessageWindowChatMemory.builder()
                    .chatMemoryRepository(new InMemoryChatMemoryRepository())
                    .maxMessages(20)
                    .build());

    @Test
    void validateSessionId_rejectsInvalid() {
        assertThatThrownBy(() -> service.validateSessionId("bad id!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveToken_prefersRequestToken() {
        var svc = new LkCoffeeAgentService(
                MessageWindowChatMemory.builder()
                        .chatMemoryRepository(new InMemoryChatMemoryRepository())
                        .build());
        String resolved = svc.resolveToken("from-request", "from-env");
        org.assertj.core.api.Assertions.assertThat(resolved).isEqualTo("from-request");
    }
}
```

- [x] **Step 2: 运行测试确认失败**

Run: `cd demo2 && mvn -q test -Dtest=LkCoffeeAgentServiceTest`
Expected: FAIL

- [x] **Step 3: 实现 `LkCoffeeAgentService.java`**

核心结构（完整文件实现时参照 `ToolReasoningAgentService`）：

```java
@Slf4j
@Service
public class LkCoffeeAgentService {

    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final String defaultToken;

    public LkCoffeeAgentService(
            ChatClient.Builder chatClientBuilder,
            LkCoffeeSkillLoader skillLoader,
            LkCoffeeAgentConfig config,
            @Qualifier("lkCoffeeMcpToolCallbacks") ToolCallback[] lkCoffeeMcpToolCallbacks,
            @Qualifier("lkCoffeeMessageChatMemoryAdvisor") MessageChatMemoryAdvisor memoryAdvisor,
            @Qualifier("lkCoffeeChatMemory") ChatMemory lkCoffeeChatMemory) {
        this.chatMemory = lkCoffeeChatMemory;
        this.defaultToken = config.getDefaultToken();
        this.chatClient = chatClientBuilder.clone()
                .defaultSystem(skillLoader.buildSystemPrompt())
                .defaultOptions(DeepSeekChatOptions.builder().model(config.getLkCoffeeChatModel()))
                .defaultTools(lkCoffeeMcpToolCallbacks)
                .defaultAdvisors(memoryAdvisor, ToolCallingAdvisor.builder().build())
                .build();
    }

    /** 包级可见构造，供单元测试 validateSessionId / resolveToken */
    LkCoffeeAgentService(ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
        this.defaultToken = "";
        this.chatClient = null;
    }

    public String resolveToken(String requestToken, String envToken) {
        if (requestToken != null && !requestToken.isBlank()) {
            return requestToken.trim();
        }
        return envToken != null ? envToken.trim() : "";
    }

    public void streamChat(LkCoffeeChatRequest request, SseEmitter emitter, JsonMapper jsonMapper) {
        validateSessionId(request.getSessionId());
        String token = resolveToken(request.getToken(), defaultToken);
        if (token.isBlank()) {
            sendSse(emitter, jsonMapper, LkCoffeeSseEvent.failed(
                    "缺少瑞幸 Token，请在 Tab 设置或配置 LKCOFFEE_TOKEN，前往 https://open.lkcoffee.com/mcp 获取"));
            emitter.complete();
            return;
        }

        LkCoffeeTokenContext.set(token);
        LkCoffeeStreamContext.bind(emitter, jsonMapper);
        try {
            sendSse(emitter, jsonMapper, LkCoffeeSseEvent.running());
            String userMessage = buildUserMessage(request);
            chatClient.prompt()
                    .user(userMessage)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, request.getSessionId()))
                    .stream()
                    .content()
                    .subscribe(
                            chunk -> sendSse(emitter, jsonMapper, LkCoffeeSseEvent.token(chunk)),
                            err -> { /* FAILED + completeWithError */ },
                            () -> { /* COMPLETED + complete */ });
        } finally {
            LkCoffeeTokenContext.clear();
            LkCoffeeStreamContext.clear();
        }
    }

    private String buildUserMessage(LkCoffeeChatRequest request) {
        StringBuilder sb = new StringBuilder();
        if (request.getLongitude() != null && request.getLatitude() != null) {
            sb.append("[当前坐标: longitude=").append(request.getLongitude())
              .append(", latitude=").append(request.getLatitude()).append("]\n");
        }
        if (request.getAddress() != null && !request.getAddress().isBlank()) {
            sb.append("[用户地址: ").append(request.getAddress()).append("]\n");
        }
        sb.append(request.getMessage());
        return sb.toString();
    }
    // validateSessionId, clearSession, sendSse 同 ToolReasoningAgentService
}
```

- [x] **Step 4: 运行测试确认通过**

Run: `cd demo2 && mvn -q test -Dtest=LkCoffeeAgentServiceTest`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/service/LkCoffeeAgentService.java \
        demo2/src/test/java/com/jason/demo/demo2/service/LkCoffeeAgentServiceTest.java
git commit -m "feat(demo2): add lkcoffee agent SSE streaming service"
```

---

### Task 6: LkCoffeeAgentController

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/controller/LkCoffeeAgentController.java`

**Interfaces:**
- Consumes: `LkCoffeeAgentService`
- Produces: `POST /agent/lkcoffee/chat/stream`、`DELETE /clear`、`GET /tools`、`GET /geocode`

- [x] **Step 1: 创建 `LkCoffeeAgentController.java`**

参照 `ToolReasoningAgentController`，追加：

```java
@Tag(name = "LkCoffee", description = "瑞幸 MCP + My Coffee Skill SSE 点单 Demo")
@RestController
@RequestMapping("/agent/lkcoffee")
@RequiredArgsConstructor
public class LkCoffeeAgentController {

    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final LkCoffeeAgentService lkCoffeeAgentService;
    private final JsonMapper jsonMapper;
    @Qualifier("lkCoffeeMcpToolCallbacks")
    private final ToolCallback[] lkCoffeeMcpToolCallbacks;

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody LkCoffeeChatRequest request) { /* 同 tool-reasoning */ }

    @DeleteMapping("/clear")
    public Map<String, String> clear(@RequestParam("sessionId") String sessionId) { /* ... */ }

    @GetMapping("/tools")
    public List<String> listTools() {
        return Arrays.stream(lkCoffeeMcpToolCallbacks)
                .map(t -> t.getToolDefinition().name() + " - " + t.getToolDefinition().description())
                .toList();
    }

    @GetMapping("/geocode")
    public Map<String, Object> geocode(
            @RequestParam("address") String address,
            @RequestParam(value = "city", required = false) String city) {
        return lkCoffeeAgentService.geocodeAddress(address, city);
    }
}
```

`geocodeAddress` 实现：在 Service 中临时构建仅含高德 geocode 工具的 ChatClient 调用，或直接通过 `McpSyncClient` 调工具（实现时选更简单路径；返回 `{ "longitude": ..., "latitude": ..., "formattedAddress": "..." }`）。

- [x] **Step 2: 编译验证**

Run: `cd demo2 && mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/controller/LkCoffeeAgentController.java
git commit -m "feat(demo2): add lkcoffee REST and SSE controller"
```

---

### Task 7: 前端 Tab（index.html + lkcoffee.js + lkcoffee.css）

**Files:**
- Create: `demo2/src/main/resources/static/css/tabs/lkcoffee.css`
- Create: `demo2/src/main/resources/static/js/tabs/lkcoffee.js`
- Modify: `demo2/src/main/resources/static/index.html`

**Interfaces:**
- Consumes: `POST /agent/lkcoffee/chat/stream`、`GET /agent/lkcoffee/geocode`、`DELETE /agent/lkcoffee/clear`

- [x] **Step 1: 创建 `lkcoffee.css`**

主色 `#0022AB`；聊天气泡复用 `.message.user` / `.message.assistant`；设置区 `.lkcoffee-settings`；价格卡片 `.order-preview-card`；二维码 `.payment-qr img { max-width: 200px; }`

- [x] **Step 2: 创建 `lkcoffee.js`**

参照 `tool-reasoning.js` 结构，关键变量：

```javascript
let lkCoffeeSessionId = crypto.randomUUID();
const LK_TOKEN_KEY = 'lkcoffee_token';

function initLkCoffeeLocation() {
    if (!navigator.geolocation) return;
    navigator.geolocation.getCurrentPosition(
        pos => { /* 填入 #lkCoffeeLongitude #lkCoffeeLatitude */ },
        () => { /* 显示手动/地址输入提示 */ }
    );
}

async function geocodeLkCoffeeAddress() { /* GET /agent/lkcoffee/geocode?address= */ }

async function sendLkCoffeeMessage() {
    const body = {
        sessionId: lkCoffeeSessionId,
        message: document.getElementById('lkCoffeeMessageInput').value.trim(),
        token: sessionStorage.getItem(LK_TOKEN_KEY) || undefined,
        longitude: parseFloat(document.getElementById('lkCoffeeLongitude').value) || undefined,
        latitude: parseFloat(document.getElementById('lkCoffeeLatitude').value) || undefined
    };
    // fetch POST /agent/lkcoffee/chat/stream
    // 解析 RUNNING / TOOL_CALL / TOKEN / ORDER_PREVIEW / PAYMENT_QR / COMPLETED / FAILED
}

document.addEventListener('DOMContentLoaded', () => {
    initLkCoffeeLocation();
    document.getElementById('lkCoffeeSessionIdDisplay').textContent = lkCoffeeSessionId;
});
```

`ORDER_PREVIEW`：解析 `payload` JSON 渲染价格卡片；`PAYMENT_QR`：插入 `<img src="qrUrl">`

- [x] **Step 3: 修改 `index.html`**

在 `<link>` 区追加 `lkcoffee.css`；在 MCP Tab 按钮后追加：

```html
<button class="tab-btn" data-tab="lkcoffee" onclick="switchTab('lkcoffee')">☕ 瑞幸 MCP 点单</button>
```

追加 `#tab-lkcoffee` 面板（Header + 设置区 + 消息区 + 快捷按钮 + 输入栏）；`<script src="/js/tabs/lkcoffee.js">` 于 body 末尾。

- [x] **Step 4: 手工冒烟（需启动服务）**

Run: `cd demo2 && mvn spring-boot:run`
打开 `http://localhost:8081`，切换「☕ 瑞幸 MCP 点单」Tab，确认 UI 渲染、定位/Token 输入框可见。

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/resources/static/css/tabs/lkcoffee.css \
        demo2/src/main/resources/static/js/tabs/lkcoffee.js \
        demo2/src/main/resources/static/index.html
git commit -m "feat(demo2): add lkcoffee MCP ordering frontend tab"
```

---

### Task 8: 全量编译、测试与联调清单

**Files:**
- Modify（若 Task 4 启动日志发现高德 tool name 不匹配）: `LkCoffeeMcpConfig.AMAP_GEO_TOOL_SUFFIXES`

- [x] **Step 1: 全量编译**

Run: `cd demo2 && mvn -q compile`
Expected: BUILD SUCCESS

- [x] **Step 2: 全量单元测试**

Run: `cd demo2 && mvn -q test`
Expected: BUILD SUCCESS

- [x] **Step 3: 启动并确认 MCP 初始化日志**

Run: `cd demo2 && mvn spring-boot:run`（需 `DEEPSEEK_API_KEY`；可选 `LKCOFFEE_TOKEN`、`AMAP_API_KEY`）

Expected 日志包含：
- `[LkCoffee] 已加载 My Coffee Skill`
- `[MCP Client] 初始化成功`（lkcoffee / amap / local-server）
- 记录 amap geocode 实际 tool name，必要时更新 `AMAP_GEO_TOOL_SUFFIXES`

- [ ] **Step 4: 联调清单（手工，需有效 Token）**

| # | 操作 | 预期 |
|---|------|------|
| 1 | Tab 保存 Token | sessionStorage 持久化 |
| 2 | 浏览器定位 | 经纬度填入 |
| 3 | 地址解析 | geocode 返回坐标 |
| 4 | 「查附近门店」 | Agent 调 queryShopList |
| 5 | 完整点单 + 确认 | preview → 用户确认 → 二维码 |
| 6 | 清除会话 | 多轮上下文清空 |

- [ ] **Step 5: Commit（若有 Task 4 工具名修正）**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/mcp/client/config/LkCoffeeMcpConfig.java
git commit -m "fix(demo2): align amap geocode tool names from MCP listTools"
```

---

## Spec Coverage Checklist

| Spec 要求 | Task |
|-----------|------|
| 瑞幸 Streamable HTTP MCP | Task 3 |
| 高德 MCP geocode | Task 3, 4 |
| Bearer Token ThreadLocal | Task 2, 4 |
| 官方 SKILL.md 内嵌 | Task 1, 2 |
| 项目覆盖规则 | Task 2 |
| 工具白名单（不用全局 McpToolFilter） | Task 4 |
| SSE 多轮 ChatMemory | Task 3, 5 |
| preview 后强制确认 | Task 2（覆盖规则） |
| 前端定位/Token/地址 | Task 7 |
| ORDER_PREVIEW / PAYMENT_QR | Task 4, 7 |
| deepseek-v4-pro | Task 3 |
| 不修改 McpChatController | Global Constraints |

---

## 修订记录

| 日期 | 变更 |
|------|------|
| 2026-07-04 | 初稿：8 Task 实现计划 |
| 2026-07-04 | **实施完成**：Task 1–7 代码落地；联调修复 lazy MCP 工具加载；`mvn compile` / `mvn test` 通过；应用可启动，远程 MCP 需 Token/Key |
| 2026-07-05 | `77c5f18`：Token 改为 `LkCoffeeTokenResolver`（移除 ThreadLocal / 前端 Token 输入）；`McpClientLifecycle` 延迟初始化远程 MCP；地理编码解析增强 |
| 2026-07-05 | `16acbdf`：`LkCoffeeMcpLoggingHttpClient` 响应体调试日志 |
| 2026-07-06 | 归档文档：`archive/2026-07-06-lkcoffee-mcp.md` |
