# Embabel Agent 自动选路 Implementation Plan

> **Status:** ✅ 已完成（2026-07-14）— `mvn compile` / 单元测试通过；SSE Tab 已集成。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 在 demo2 集成 Embabel Agent 0.5.0，复现文章双 Agent（星座文案 + 制度问答）自动选路示例，主入口为 SSE 流式对话，同步 API 供 curl 调试。

**Architecture:** `dependencyManagement` 锁定 Embabel 三件套；`StarNewsAgent` / `PolicyAgent` 用 `@Action` + `@AchievesGoal`；`EmbabelAgentService` 通过 `Autonomy.chooseAndRunAgent()` 做 Closed 模式选路；`EmbabelSseBridge` 监听 `AgenticEventListener` 将过程事件桥接到 `SseEmitter`；前端 Tab 用 `fetch` + `ReadableStream` 解析 SSE。

**Tech Stack:** Java 21, Spring Boot 4.1.0, Embabel Agent 0.5.0, DeepSeek `deepseek-v4-pro`（OpenAI 兼容）, 原生 HTML/CSS/JS

**设计规范:** [docs/superpowers/specs/2026-07-13-embabel-agent-routing-design.md](../specs/2026-07-13-embabel-agent-routing-design.md)

## Global Constraints

- **Embabel 版本**：`0.5.0`（`embabel-agent.version` 属性 + `dependencyManagement`，`dependencies` 不写 version）
- **模型**：`deepseek-v4-pro`（`embabel.models.default-llm` 与 `embabel.agent.platform.models.openai.custom.models`）
- **API 前缀**：`/embabel/agent`；SSE 主入口 `POST /ask/stream`；同步调试 `POST /ask`
- **端口**：`8081`（demo2 现有）
- **不降级** Spring Boot 4.1.0
- **不修改** 现有 `/agent/trip` 的 `AgentController` 及其他 Tab 后端
- **Action 保持** `ai.withDefaultLlm().createObject()`（不改为 StreamingPromptRunnerBuilder）
- **编译门禁**：`mvn -DskipTests compile` 必须 SUCCESS
- **环境变量**：复用 `DEEPSEEK_API_KEY`

---

## File Structure

| 文件 | 职责 |
|------|------|
| `pom.xml` | `embabel-agent.version` + `dependencyManagement` + 四个新依赖 |
| `Demo2Application.java` | 增加 `@ConfigurationPropertiesScan` |
| `application.properties` | Embabel LLM 配置 + 四个中文 prompt |
| `embabel/config/StarNewsAgentProperties.java` | `demo.star-news-agent.prompts.*` |
| `embabel/config/PolicyAgentProperties.java` | `demo.policy-agent.prompts.*` |
| `embabel/service/HoroscopeService.java` | 12 星座本地运势 |
| `embabel/service/PolicyKnowledgeService.java` | 制度资料规则匹配 |
| `embabel/agent/StarNewsAgent.java` | 星座文案 Agent |
| `embabel/agent/PolicyAgent.java` | 制度问答 Agent |
| `embabel/model/AgentRequest.java` | `{ message }` |
| `embabel/model/AgentResponse.java` | `{ processId, agentName, outputType, output }` |
| `embabel/model/EmbabelSseEvent.java` | SSE event 名 + JSON data 工厂 |
| `embabel/sse/EmbabelSseBridge.java` | `AgenticEventListener` → SseEmitter |
| `embabel/service/EmbabelAgentService.java` | `ask()` + `streamAsk()` + `validateOutput()` |
| `embabel/controller/EmbabelAgentController.java` | REST + SSE 端点 |
| `test/.../EmbabelAgentServiceTest.java` | validateOutput 单元测试 |
| `test/.../PolicyKnowledgeServiceTest.java` | 制度匹配单元测试 |
| `static/css/tabs/embabel.css` | Tab 样式 |
| `static/js/tabs/embabel.js` | SSE 聊天气泡 |
| `static/index.html` | Tab 按钮、面板、link/script |

---

### Task 1: Maven 依赖与编译门禁

**Files:**
- Modify: `demo2/pom.xml`

**Interfaces:**
- Produces: Embabel 0.5.0 三件套 + validation 可在后续任务 `import com.embabel.*`

- [x] **Step 1: 在 `<properties>` 增加版本属性**

```xml
<embabel-agent.version>0.5.0</embabel-agent.version>
```

- [x] **Step 2: 在现有 `<dependencyManagement><dependencies>` 末尾追加**

```xml
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
```

- [x] **Step 3: 在 `<dependencies>` 末尾追加（无 version）**

```xml
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
```

- [x] **Step 4: 编译验证**

Run: `cd demo2 && mvn -DskipTests compile`

Expected: `BUILD SUCCESS`。若失败：
- 传递依赖冲突 → 在 `dependencyManagement` 对齐 Spring 7 / Boot 4 已有 BOM
- autoconfig 冲突 → 记录错误，Task 8 在 `Demo2Application` 加 `exclude`

- [x] **Step 5: Commit**

```bash
git add demo2/pom.xml
git commit -m "build(demo2): add Embabel Agent 0.5.0 via dependencyManagement"
```

---

### Task 2: 配置属性与 Prompt

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/Demo2Application.java`
- Modify: `demo2/src/main/resources/application.properties`
- Create: `demo2/src/main/java/com/jason/demo/demo2/embabel/config/StarNewsAgentProperties.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/embabel/config/PolicyAgentProperties.java`

**Interfaces:**
- Consumes: Task 1 编译通过
- Produces: `StarNewsAgentProperties`、`PolicyAgentProperties` 可被 Agent 注入

- [x] **Step 1: Demo2Application 增加扫描**

```java
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(exclude = MilvusVectorStoreAutoConfiguration.class)
@ConfigurationPropertiesScan
public class Demo2Application {
```

- [x] **Step 2: 创建 StarNewsAgentProperties**

```java
package com.jason.demo.demo2.embabel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demo.star-news-agent")
public record StarNewsAgentProperties(Prompts prompts) {
    public record Prompts(String extractStarPerson, String writeup) {}
}
```

- [x] **Step 3: 创建 PolicyAgentProperties**

```java
package com.jason.demo.demo2.embabel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demo.policy-agent")
public record PolicyAgentProperties(Prompts prompts) {
    public record Prompts(String extractPolicyQuestion, String answer) {}
}
```

- [x] **Step 4: application.properties 追加 Embabel 与 prompt 配置**

```properties
# ===== Embabel Agent（文章示例 · 自动选路）=====
embabel.models.default-llm=deepseek-v4-pro
embabel.agent.platform.models.openai.custom.api-key=${DEEPSEEK_API_KEY:}
embabel.agent.platform.models.openai.custom.base-url=https://api.deepseek.com
embabel.agent.platform.models.openai.custom.models=deepseek-v4-pro
embabel.agent.platform.rest.process-events-enabled=true

demo.star-news-agent.prompts.extract-star-person=请从用户输入中提取人物姓名和星座。\n只返回结构化对象，不要解释。\n\n用户输入：\n{userInput}
demo.star-news-agent.prompts.writeup=请基于人物和当天运势，写一段轻松但不过度玄学的中文文案。\n返回结构化对象，必须包含 title、summary、advice，三个字段都不能为空。\nsummary 和 advice 都要是完整中文句子，以中文句号、问号或感叹号结尾。\n\n人物：{name}\n星座：{sign}\n运势：{horoscope}

demo.policy-agent.prompts.extract-policy-question=请从用户输入中提取制度问题类别和原始问题。\ncategory 可以是：差旅报销、请假、通用制度。\n只返回结构化对象，不要解释。\n\n用户输入：\n{userInput}
demo.policy-agent.prompts.answer=请基于给定制度资料回答员工问题。\n不要编造资料里没有的规则。资料不覆盖时，提醒员工联系人事或财务确认。\n返回结构化对象，包含 title、answer、source。\nanswer 必须是完整中文句子，以中文句号、问号或感叹号结尾。\n\n问题：{question}\n类别：{category}\n制度标题：{title}\n制度资料：{content}
```

（properties 中用 `\n` 表示换行；若 IDE 支持多行值也可用 `|` 块，与 spec 一致即可。）

- [x] **Step 5: 编译**

Run: `cd demo2 && mvn -DskipTests compile`
Expected: BUILD SUCCESS

- [x] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/Demo2Application.java \
  demo2/src/main/resources/application.properties \
  demo2/src/main/java/com/jason/demo/demo2/embabel/config/
git commit -m "feat(demo2): add Embabel config properties and prompts"
```

---

### Task 3: 本地业务服务

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/embabel/service/HoroscopeService.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/embabel/service/PolicyKnowledgeService.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/embabel/service/PolicyKnowledgeServiceTest.java`

**Interfaces:**
- Consumes: `StarNewsAgent.Horoscope`、`PolicyAgent.PolicyMaterial`（Task 4 定义；本 Task 可先写返回类型为内部 record，Task 4 再对齐）
- Produces: `HoroscopeService.dailyHoroscope(String sign)`、`PolicyKnowledgeService.findPolicy(String category, String question)`

- [x] **Step 1: 写失败测试 PolicyKnowledgeServiceTest**

```java
package com.jason.demo.demo2.embabel.service;

import com.jason.demo.demo2.embabel.agent.PolicyAgent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyKnowledgeServiceTest {

    private final PolicyKnowledgeService service = new PolicyKnowledgeService();

    @Test
    void findPolicy_leave() {
        PolicyAgent.PolicyMaterial m = service.findPolicy("请假", "年假怎么请");
        assertThat(m.title()).contains("请假");
    }

    @Test
    void findPolicy_travel() {
        PolicyAgent.PolicyMaterial m = service.findPolicy("差旅报销", "出差回来报销材料");
        assertThat(m.title()).contains("差旅");
    }
}
```

- [x] **Step 2: 运行测试确认失败**

Run: `cd demo2 && mvn -Dtest=PolicyKnowledgeServiceTest test`
Expected: FAIL（类不存在）

- [x] **Step 3: 实现 PolicyKnowledgeService（文章同款）**

```java
package com.jason.demo.demo2.embabel.service;

import com.jason.demo.demo2.embabel.agent.PolicyAgent;
import org.springframework.stereotype.Service;

@Service
public class PolicyKnowledgeService {

    public PolicyAgent.PolicyMaterial findPolicy(String category, String question) {
        String normalized = ((category == null ? "" : category) + " " + (question == null ? "" : question));
        if (normalized.contains("请假")) {
            return new PolicyAgent.PolicyMaterial("员工请假制度",
                    "年假、病假、事假都需要在系统里提交申请。病假需要补充医院证明或就诊记录。"
                            + "连续请假超过 3 天，需要直属负责人和部门负责人审批。紧急情况可以先口头同步，回到岗位后补提申请。");
        }
        if (normalized.contains("报销") || normalized.contains("差旅") || normalized.contains("出差")) {
            return new PolicyAgent.PolicyMaterial("差旅与报销制度",
                    "出差回来后，需要提交出差审批单、交通票据、住宿发票、行程说明和费用明细。"
                            + "住宿、交通和餐补按公司差旅标准核销。报销应在返程后 7 个工作日内提交。"
                            + "如果票据缺失，需要补充情况说明并由直属负责人确认。");
        }
        return new PolicyAgent.PolicyMaterial("通用制度说明",
                "制度问题需要先确认所属类别、适用人员范围和生效时间。资料不明确时，应提示员工联系人事或财务确认。");
    }
}
```

- [x] **Step 4: 实现 HoroscopeService（12 星座）**

```java
package com.jason.demo.demo2.embabel.service;

import com.jason.demo.demo2.embabel.agent.StarNewsAgent;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class HoroscopeService {

    private static final Map<String, String> HOROSCOPES = Map.ofEntries(
            Map.entry("白羊座", "适合把手头任务拆小，先完成最关键的一步。沟通时少绕弯，直接说结论会更顺。"),
            Map.entry("金牛座", "今天适合处理预算、排期和资源确认。别急着拍板，先把边界条件问清楚。"),
            Map.entry("双子座", "信息量大的一天，先筛选再行动。多确认一次细节，能避免返工。"),
            Map.entry("巨蟹座", "适合整理情绪和优先级。把重要关系里的期待说清楚，会更轻松。"),
            Map.entry("狮子座", "表达欲强，但先听后说效果更好。把亮点落在具体成果上。"),
            Map.entry("处女座", "细节控上线，适合查漏补缺。别追求完美，完成比完美更重要。"),
            Map.entry("天秤座", "适合做选择和取舍。权衡时写下三条标准，决策会更快。"),
            Map.entry("天蝎座", "专注力强，适合攻坚难题。注意别把情绪带进协作讨论。"),
            Map.entry("射手座", "适合学习新东西或拓展视野。计划留一点弹性，惊喜可能来自变化。"),
            Map.entry("摩羯座", "务实推进的一天。把大目标拆成可交付的小里程碑。"),
            Map.entry("水瓶座", "灵感活跃，适合头脑风暴。落地时找一个可执行的下一步。"),
            Map.entry("双鱼座", "直觉敏锐，适合创意表达。重要事项仍建议书面确认。")
    );

    public StarNewsAgent.Horoscope dailyHoroscope(String sign) {
        String summary = HOROSCOPES.getOrDefault(sign, "今天适合先把目标说清楚，再决定下一步动作。");
        return new StarNewsAgent.Horoscope(sign, summary);
    }
}
```

- [x] **Step 5: 运行测试**

Run: `cd demo2 && mvn -Dtest=PolicyKnowledgeServiceTest test`
Expected: PASS（PolicyAgent 尚未存在时会编译失败——先完成 Task 4 Step 1 再跑，或 Task 3+4 合并提交）

- [x] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/embabel/service/ \
  demo2/src/test/java/com/jason/demo/demo2/embabel/service/
git commit -m "feat(demo2): add HoroscopeService and PolicyKnowledgeService"
```

---

### Task 4: Embabel Agents（文章核心）

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/embabel/agent/StarNewsAgent.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/embabel/agent/PolicyAgent.java`

**Interfaces:**
- Consumes: `StarNewsAgentProperties`, `PolicyAgentProperties`, `HoroscopeService`, `PolicyKnowledgeService`, Embabel `@Agent`/`@Action`/`Ai`
- Produces: Spring 管理的 `StarNewsAgent`、`PolicyAgent` bean，供 `Autonomy` 发现

- [x] **Step 1: 创建 StarNewsAgent（文章代码，包名调整）**

```java
package com.jason.demo.demo2.embabel.agent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.domain.io.UserInput;
import com.jason.demo.demo2.embabel.config.StarNewsAgentProperties;
import com.jason.demo.demo2.embabel.service.HoroscopeService;
import org.springframework.stereotype.Component;

@Agent(description = "根据人物和星座生成一段当天运势文案")
@Component
public class StarNewsAgent {

    private final HoroscopeService horoscopeService;
    private final StarNewsAgentProperties properties;

    public StarNewsAgent(HoroscopeService horoscopeService, StarNewsAgentProperties properties) {
        this.horoscopeService = horoscopeService;
        this.properties = properties;
    }

    @Action
    public StarPerson extractStarPerson(UserInput userInput, Ai ai) {
        String prompt = properties.prompts().extractStarPerson()
                .replace("{userInput}", userInput.getContent());
        return ai.withDefaultLlm().createObject(prompt, StarPerson.class);
    }

    @Action
    public Horoscope retrieveHoroscope(StarPerson starPerson) {
        return horoscopeService.dailyHoroscope(starPerson.sign());
    }

    @AchievesGoal(description = "生成一段结合人物和星座运势的文案")
    @Action
    public Writeup writeup(StarPerson starPerson, Horoscope horoscope, Ai ai) {
        String prompt = properties.prompts().writeup()
                .replace("{name}", starPerson.name())
                .replace("{sign}", starPerson.sign())
                .replace("{horoscope}", horoscope.summary());
        return ai.withDefaultLlm().createObject(prompt, Writeup.class);
    }

    public record StarPerson(String name, String sign) {}
    public record Horoscope(String sign, String summary) {}
    public record Writeup(String title, String summary, String advice) {}
}
```

- [x] **Step 2: 创建 PolicyAgent**

```java
package com.jason.demo.demo2.embabel.agent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.domain.io.UserInput;
import com.jason.demo.demo2.embabel.config.PolicyAgentProperties;
import com.jason.demo.demo2.embabel.service.PolicyKnowledgeService;
import org.springframework.stereotype.Component;

@Agent(description = "回答员工制度、差旅、报销、请假等公司政策问题")
@Component
public class PolicyAgent {

    private final PolicyKnowledgeService policyKnowledgeService;
    private final PolicyAgentProperties properties;

    public PolicyAgent(PolicyKnowledgeService policyKnowledgeService, PolicyAgentProperties properties) {
        this.policyKnowledgeService = policyKnowledgeService;
        this.properties = properties;
    }

    @Action
    public PolicyQuestion extractPolicyQuestion(UserInput userInput, Ai ai) {
        String prompt = properties.prompts().extractPolicyQuestion()
                .replace("{userInput}", userInput.getContent());
        return ai.withDefaultLlm().createObject(prompt, PolicyQuestion.class);
    }

    @Action
    public PolicyMaterial retrievePolicy(PolicyQuestion question) {
        return policyKnowledgeService.findPolicy(question.category(), question.question());
    }

    @AchievesGoal(description = "基于公司制度资料回答员工问题")
    @Action
    public PolicyAnswer answer(PolicyQuestion question, PolicyMaterial material, Ai ai) {
        String prompt = properties.prompts().answer()
                .replace("{question}", question.question())
                .replace("{category}", question.category())
                .replace("{title}", material.title())
                .replace("{content}", material.content());
        return ai.withDefaultLlm().createObject(prompt, PolicyAnswer.class);
    }

    public record PolicyQuestion(String category, String question) {}
    public record PolicyMaterial(String title, String content) {}
    public record PolicyAnswer(String title, String answer, String source) {}
}
```

- [x] **Step 3: 编译并修正 import**

Run: `cd demo2 && mvn -DskipTests compile`

若 `@Agent` 扫描不生效，确认 `embabel-agent-starter` autoconfig 已加载；若 `UserInput` 包路径错误，以编译器提示为准修正（常见：`com.embabel.agent.domain.io.UserInput`）。

- [x] **Step 4: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/embabel/agent/
git commit -m "feat(demo2): add StarNewsAgent and PolicyAgent for Embabel routing"
```

---

### Task 5: REST 模型与 SSE 协议

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/embabel/model/AgentRequest.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/embabel/model/AgentResponse.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/embabel/model/EmbabelSseEvent.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/embabel/sse/EmbabelSseBridge.java`

**Interfaces:**
- Consumes: `AgentResponse` 字段类型
- Produces:
  - `AgentRequest(String message)`
  - `AgentResponse(String processId, String agentName, String outputType, Object output)`
  - `EmbabelSseEvent.agentSelected(String agentName)` 等工厂方法
  - `EmbabelSseBridge.bind(SseEmitter, JsonMapper)` / `unbind()` / `onProcessEvent(...)`

- [x] **Step 1: AgentRequest**

```java
package com.jason.demo.demo2.embabel.model;

import jakarta.validation.constraints.NotBlank;

public record AgentRequest(@NotBlank String message) {}
```

- [x] **Step 2: AgentResponse**

```java
package com.jason.demo.demo2.embabel.model;

public record AgentResponse(
        String processId,
        String agentName,
        String outputType,
        Object output) {}
```

- [x] **Step 3: EmbabelSseEvent**

```java
package com.jason.demo.demo2.embabel.model;

import java.util.Map;

public record EmbabelSseEvent(String event, Object data) {

    public static EmbabelSseEvent agentSelected(String agentName) {
        return new EmbabelSseEvent("AGENT_SELECTED", Map.of("agentName", agentName));
    }

    public static EmbabelSseEvent actionStart(String action) {
        return new EmbabelSseEvent("ACTION_START", Map.of("action", action));
    }

    public static EmbabelSseEvent actionComplete(String action, String outputType) {
        return new EmbabelSseEvent("ACTION_COMPLETE", Map.of("action", action, "outputType", outputType));
    }

    public static EmbabelSseEvent progress(String text) {
        return new EmbabelSseEvent("PROGRESS", Map.of("text", text));
    }

    public static EmbabelSseEvent result(AgentResponse response) {
        return new EmbabelSseEvent("RESULT", response);
    }

    public static EmbabelSseEvent error(String message) {
        return new EmbabelSseEvent("ERROR", Map.of("message", message));
    }
}
```

- [x] **Step 4: EmbabelSseBridge（ThreadLocal + 事件映射）**

```java
package com.jason.demo.demo2.embabel.sse;

import com.embabel.agent.spi.event.AgentProcessEvent;
import com.embabel.agent.spi.event.AgenticEventListener;
import com.jason.demo.demo2.embabel.model.EmbabelSseEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

@Component
public class EmbabelSseBridge implements AgenticEventListener {

    private static final ThreadLocal<Context> CTX = new ThreadLocal<>();

    public void bind(SseEmitter emitter, JsonMapper jsonMapper) {
        CTX.set(new Context(emitter, jsonMapper));
    }

    public void unbind() {
        CTX.remove();
    }

    public void send(EmbabelSseEvent event) {
        Context ctx = CTX.get();
        if (ctx == null) return;
        try {
            ctx.emitter().send(SseEmitter.event()
                    .name(event.event())
                    .data(ctx.jsonMapper().writeValueAsString(event.data())));
        } catch (IOException ignored) {
            // client disconnected
        }
    }

    @Override
    public void onProcessEvent(AgentProcessEvent event) {
        // 按 Embabel 0.5.0 实际事件类型映射；编译后根据 event 类名补全 switch
        // 最小实现：从 event 提取 action 名并 send actionStart/actionComplete
        String typeName = event.getClass().getSimpleName();
        if (typeName.contains("Action") && typeName.contains("Start")) {
            send(EmbabelSseEvent.progress("执行中: " + typeName));
        }
    }

    private record Context(SseEmitter emitter, JsonMapper jsonMapper) {}
}
```

> **实现注记：** `AgenticEventListener` / `AgentProcessEvent` 包名以 `mvn compile` 为准；若 0.5.0 需显式注册 listener，在 `EmbabelAgentService` 构造器注入 `AgentPlatform` 或 Embabel 提供的 `EventListenerRegistry` 注册本 bean。

- [x] **Step 5: 编译**

Run: `cd demo2 && mvn -DskipTests compile`

- [x] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/embabel/model/ \
  demo2/src/main/java/com/jason/demo/demo2/embabel/sse/
git commit -m "feat(demo2): add Embabel SSE models and event bridge"
```

---

### Task 6: EmbabelAgentService（同步 + 流式 + 校验）

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/embabel/service/EmbabelAgentService.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/embabel/service/EmbabelAgentServiceTest.java`

**Interfaces:**
- Consumes: `Autonomy`, `EmbabelSseBridge`, `StarNewsAgent.Writeup`, `PolicyAgent.PolicyAnswer`
- Produces:
  - `AgentResponse ask(String message)`
  - `void streamAsk(String message, SseEmitter emitter, JsonMapper jsonMapper)`

- [x] **Step 1: 写 validateOutput 失败测试**

```java
package com.jason.demo.demo2.embabel.service;

import com.jason.demo.demo2.embabel.agent.PolicyAgent;
import com.jason.demo.demo2.embabel.agent.StarNewsAgent;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbabelAgentServiceTest {

    private final EmbabelAgentService service = new EmbabelAgentService(null, null);

    @Test
    void validateOutput_rejectsBlankWriteupTitle() {
        var bad = new StarNewsAgent.Writeup("", "完整句子。", "建议完整。");
        assertThatThrownBy(() -> service.validateOutput(bad))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void validateOutput_acceptsValidPolicyAnswer() {
        var ok = new PolicyAgent.PolicyAnswer("标题", "这是完整回答。", "来源");
        assertThatCode(() -> service.validateOutput(ok)).doesNotThrowAnyException();
    }
}
```

为测试 `validateOutput`，将方法设为 `void validateOutput(Object output)` 且 package-private 或 public（与 demo2 其他 Service 测试风格一致）。

- [x] **Step 2: 运行测试确认失败**

Run: `cd demo2 && mvn -Dtest=EmbabelAgentServiceTest test`
Expected: FAIL

- [x] **Step 3: 实现 EmbabelAgentService**

```java
package com.jason.demo.demo2.embabel.service;

import com.embabel.agent.api.common.Autonomy;
import com.embabel.agent.api.exception.NoAgentFound;
import com.embabel.agent.core.AgentProcessExecution;
import com.embabel.agent.core.ProcessExecutionException;
import com.embabel.agent.core.ProcessOptions;
import com.jason.demo.demo2.embabel.agent.PolicyAgent;
import com.jason.demo.demo2.embabel.agent.StarNewsAgent;
import com.jason.demo.demo2.embabel.model.AgentResponse;
import com.jason.demo.demo2.embabel.model.EmbabelSseEvent;
import com.jason.demo.demo2.embabel.sse.EmbabelSseBridge;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

@Service
public class EmbabelAgentService {

    private final Autonomy autonomy;
    private final EmbabelSseBridge sseBridge;

    public EmbabelAgentService(Autonomy autonomy, EmbabelSseBridge sseBridge) {
        this.autonomy = autonomy;
        this.sseBridge = sseBridge;
    }

    public AgentResponse ask(String message) {
        AgentProcessExecution execution = run(message);
        Object output = execution.getOutput();
        validateOutput(output);
        return toResponse(execution, output);
    }

    public void streamAsk(String message, SseEmitter emitter, JsonMapper jsonMapper) {
        sseBridge.bind(emitter, jsonMapper);
        try {
            sseBridge.send(EmbabelSseEvent.progress("正在分析请求并选择 Agent…"));
            AgentProcessExecution execution = run(message);
            String agentName = execution.getAgentProcess().getAgent().getName();
            sseBridge.send(EmbabelSseEvent.agentSelected(agentName));
            Object output = execution.getOutput();
            validateOutput(output);
            AgentResponse response = toResponse(execution, output);
            sseBridge.send(EmbabelSseEvent.result(response));
            emitter.complete();
        } catch (ResponseStatusException ex) {
            sseBridge.send(EmbabelSseEvent.error(ex.getReason()));
            emitter.completeWithError(ex);
        } catch (Exception ex) {
            sseBridge.send(EmbabelSseEvent.error(ex.getMessage()));
            emitter.completeWithError(ex);
        } finally {
            sseBridge.unbind();
        }
    }

    private AgentProcessExecution run(String message) {
        try {
            return autonomy.chooseAndRunAgent(message.strip(), new ProcessOptions());
        } catch (NoAgentFound ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No matching agent", ex);
        } catch (ProcessExecutionException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Agent execution failed", ex);
        }
    }

    private AgentResponse toResponse(AgentProcessExecution execution, Object output) {
        return new AgentResponse(
                execution.getAgentProcess().getId(),
                execution.getAgentProcess().getAgent().getName(),
                output.getClass().getSimpleName(),
                output);
    }

    public void validateOutput(Object output) {
        if (output instanceof StarNewsAgent.Writeup writeup) {
            requireText(writeup.title(), "Writeup.title");
            requireCompleteSentence(writeup.summary(), "Writeup.summary");
            requireCompleteSentence(writeup.advice(), "Writeup.advice");
            return;
        }
        if (output instanceof PolicyAgent.PolicyAnswer answer) {
            requireText(answer.title(), "PolicyAnswer.title");
            requireCompleteSentence(answer.answer(), "PolicyAnswer.answer");
            requireText(answer.source(), "PolicyAnswer.source");
            return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unsupported agent output type");
    }

    private void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Agent returned blank field: " + fieldName);
        }
    }

    private void requireCompleteSentence(String value, String fieldName) {
        requireText(value, fieldName);
        String text = value.strip();
        if (!text.matches(".*[。！？.!?]$")) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Agent returned incomplete field: " + fieldName);
        }
    }
}
```

> **实现注记：** `AgentProcessExecution`、`ProcessOptions`、`NoAgentFound` 包名以编译器为准调整。`streamAsk` 在 `run()` 完成后补发 `AGENT_SELECTED`；若 listener 已映射 `ACTION_*`，保留双通道不冲突。

- [x] **Step 4: 运行单元测试**

Run: `cd demo2 && mvn -Dtest=EmbabelAgentServiceTest,PolicyKnowledgeServiceTest test`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/embabel/service/EmbabelAgentService.java \
  demo2/src/test/java/com/jason/demo/demo2/embabel/service/
git commit -m "feat(demo2): add EmbabelAgentService with sync, SSE stream, and output validation"
```

---

### Task 7: REST Controller

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/embabel/controller/EmbabelAgentController.java`

**Interfaces:**
- Consumes: `EmbabelAgentService.ask()`, `EmbabelAgentService.streamAsk()`
- Produces: HTTP 端点 `/embabel/agent/ask` 与 `/embabel/agent/ask/stream`

- [x] **Step 1: 实现 Controller（对齐 SessionMemoryAgentController）**

```java
package com.jason.demo.demo2.embabel.controller;

import com.jason.demo.demo2.embabel.model.AgentRequest;
import com.jason.demo.demo2.embabel.model.AgentResponse;
import com.jason.demo.demo2.embabel.service.EmbabelAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Tag(name = "Embabel", description = "Embabel Agent 自动选路（Closed 模式）")
@RestController
@RequestMapping("/embabel/agent")
@RequiredArgsConstructor
public class EmbabelAgentController {

    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final EmbabelAgentService embabelAgentService;
    private final JsonMapper jsonMapper;
    private final ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor();

    @Operation(summary = "SSE 流式问答", description = "Autonomy 选路 + Action 进度事件 + 最终结果")
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@Valid @RequestBody AgentRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        virtualThreads.execute(() -> embabelAgentService.streamAsk(request.message(), emitter, jsonMapper));
        return emitter;
    }

    @Operation(summary = "同步问答（调试）", description = "curl / Scalar 调试用")
    @PostMapping("/ask")
    public AgentResponse ask(@Valid @RequestBody AgentRequest request) {
        return embabelAgentService.ask(request.message());
    }
}
```

- [x] **Step 2: 全量编译**

Run: `cd demo2 && mvn -DskipTests compile`
Expected: BUILD SUCCESS

- [x] **Step 3: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/embabel/controller/
git commit -m "feat(demo2): add EmbabelAgentController with SSE and sync endpoints"
```

---

### Task 8: 前端 Tab（SSE 聊天气泡）

**Files:**
- Create: `demo2/src/main/resources/static/css/tabs/embabel.css`
- Create: `demo2/src/main/resources/static/js/tabs/embabel.js`
- Modify: `demo2/src/main/resources/static/index.html`

**Interfaces:**
- Consumes: `POST /embabel/agent/ask/stream` SSE 事件协议（Task 5）
- Produces: 浏览器 Tab「🔀 Embabel 自动选路」

- [x] **Step 1: embabel.css** — 复用 `agent-session-memory.css` 聊天气泡布局，增加 `.embabel-progress-line`、`.embabel-result-json`

- [x] **Step 2: embabel.js 核心逻辑**

```javascript
const EMBABEL_SAMPLES = {
    1: '给李白写一段白羊座今日运势文案',
    2: '出差回来后报销需要哪些材料'
};

function fillEmbabelSample(n) {
    document.getElementById('embabelMessageInput').value = EMBABEL_SAMPLES[n] || '';
}

function setEmbabelInputEnabled(enabled) {
    document.getElementById('embabelMessageInput').disabled = !enabled;
    document.getElementById('embabelSendBtn').disabled = !enabled;
}

function appendEmbabelBubble(text, isUser) {
    const box = document.getElementById('embabelMessages');
    const welcome = document.getElementById('embabelWelcome');
    if (welcome) welcome.remove();
    const div = document.createElement('div');
    div.className = 'message ' + (isUser ? 'user' : 'assistant');
    const content = document.createElement('div');
    content.className = 'message-content';
    if (isUser) content.textContent = text;
    div.appendChild(content);
    box.appendChild(div);
    box.scrollTop = box.scrollHeight;
    return content;
}

async function sendEmbabelMessage() {
    const message = document.getElementById('embabelMessageInput').value.trim();
    if (!message) return;
    appendEmbabelBubble(message, true);
    document.getElementById('embabelMessageInput').value = '';
    const assistant = appendEmbabelBubble('', false);
    const progressEl = document.createElement('div');
    progressEl.className = 'embabel-progress';
    assistant.appendChild(progressEl);
    const resultEl = document.createElement('pre');
    resultEl.className = 'embabel-result-json';
    assistant.appendChild(resultEl);

    setEmbabelInputEnabled(false);
    try {
        const res = await fetch('/embabel/agent/ask/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
            body: JSON.stringify({ message })
        });
        if (!res.ok) throw new Error(await res.text() || 'HTTP ' + res.status);
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
                let event = 'message', data = '';
                part.split('\n').forEach(line => {
                    if (line.startsWith('event:')) event = line.slice(6).trim();
                    if (line.startsWith('data:')) data += line.slice(5).trim();
                });
                if (!data) continue;
                const payload = JSON.parse(data);
                if (event === 'AGENT_SELECTED') {
                    progressEl.innerHTML += '<div>已选择 Agent：<strong>' + escapeHtml(payload.agentName) + '</strong></div>';
                } else if (event === 'PROGRESS' || event === 'ACTION_START' || event === 'ACTION_COMPLETE') {
                    const line = payload.text || payload.action || JSON.stringify(payload);
                    progressEl.innerHTML += '<div class="embabel-progress-line">' + escapeHtml(line) + '</div>';
                } else if (event === 'RESULT') {
                    resultEl.textContent = JSON.stringify(payload, null, 2);
                } else if (event === 'ERROR') {
                    progressEl.innerHTML += '<div style="color:#b91c1c">' + escapeHtml(payload.message) + '</div>';
                }
            }
        }
    } catch (e) {
        progressEl.innerHTML += '<div style="color:#b91c1c">' + escapeHtml(e.message) + '</div>';
    } finally {
        setEmbabelInputEnabled(true);
    }
}

document.getElementById('embabelForm')?.addEventListener('submit', function (e) {
    e.preventDefault();
    sendEmbabelMessage();
});
```

- [x] **Step 3: index.html 增加 Tab**

在 `tab-nav` 追加：

```html
<button class="tab-btn" data-tab="embabel" onclick="switchTab('embabel')">🔀 Embabel 自动选路</button>
```

在 `</div><!-- app-container -->` 前增加面板（含 `#embabelMessages`、`#embabelForm`、两个 sample 按钮），并 link `embabel.css`、script `embabel.js`。

- [x] **Step 4: 手动 UI 冒烟**（需启动应用 + API Key）

Run: `cd demo2 && mvn spring-boot:run`，打开 `http://localhost:8081`，切换 Tab，点两个示例按钮。

- [x] **Step 5: Commit**

```bash
git add demo2/src/main/resources/static/
git commit -m "feat(demo2): add Embabel auto-routing SSE chat tab"
```

---

### Task 9: 端到端验证与 spec 状态更新

**Files:**
- Modify: `demo2/docs/superpowers/specs/2026-07-13-embabel-agent-routing-design.md`（状态 → 已实现）

- [x] **Step 1: 编译 + 单元测试**

Run: `cd demo2 && mvn test`
Expected: BUILD SUCCESS（含 `EmbabelAgentServiceTest`、`PolicyKnowledgeServiceTest`）

- [x] **Step 2: curl 同步接口**

```bash
curl -X POST http://localhost:8081/embabel/agent/ask \
  -H "Content-Type: application/json" \
  -d "{\"message\":\"出差回来后报销需要哪些材料\"}"
```

Expected: `"agentName":"PolicyAgent"`, `"outputType":"PolicyAnswer"`

- [x] **Step 3: curl SSE 流式**

```bash
curl -N -X POST http://localhost:8081/embabel/agent/ask/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d "{\"message\":\"给李白写一段白羊座今日运势文案\"}"
```

Expected: 含 `event:AGENT_SELECTED`、`event:RESULT`，RESULT 中 `agentName=StarNewsAgent`

- [x] **Step 4: 更新 spec 状态**

将 `2026-07-13-embabel-agent-routing-design.md` 首屏 `状态: 待实现` 改为 `状态: 已实现`。

- [x] **Step 5: Commit**

```bash
git add demo2/docs/superpowers/specs/2026-07-13-embabel-agent-routing-design.md
git commit -m "docs(demo2): mark Embabel routing spec as implemented"
```

---

## Spec Self-Review Checklist

| Spec 章节 | 对应 Task |
|-----------|-----------|
| dependencyManagement 0.5.0 | Task 1 |
| deepseek-v4-pro 配置 | Task 2 |
| StarNewsAgent / PolicyAgent | Task 4 |
| HoroscopeService 12 星座 | Task 3 |
| PolicyKnowledgeService | Task 3 |
| Autonomy Closed 模式 | Task 6 |
| SSE 事件协议 | Task 5, 6, 8 |
| POST /embabel/agent/ask/stream | Task 7 |
| POST /embabel/agent/ask 同步 | Task 7 |
| validateOutput | Task 6 + 测试 |
| 前端聊天气泡 Tab | Task 8 |
| mvn compile 门禁 | Task 1, 4, 7, 9 |
| SB 4.1 不降级 | Task 1 冲突处理注记 |

无 TBD / TODO 占位。

---

## Execution Handoff

Plan complete and saved to `demo2/docs/superpowers/plans/2026-07-13-embabel-agent-routing.md`. Two execution options:

**1. Subagent-Driven (recommended)** — 每个 Task 派发独立 subagent，Task 间做审查，迭代快

**2. Inline Execution** — 本会话按 Task 顺序直接实现，每 2–3 个 Task 设检查点

你想用哪种方式开始实现？
