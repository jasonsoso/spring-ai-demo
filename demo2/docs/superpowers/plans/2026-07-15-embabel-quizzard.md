# Embabel Quizzard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 demo2 现有 Embabel 自动选路中新增 QuizAgent（Quizzard），支持从技术文章 URL 或粘贴正文生成 `QuizPack`，并在 SSE Tab 结构化展示。

**Architecture:** 在 Closed 模式 `Autonomy.chooseAndRunAgent` 下新增第三 Agent；`ArticleFetchService`（jsoup）确定性产出 `ArticleInput`；三个 LLM Action 产出知识点 → 候选题 → `QuizPack`；`EmbabelAgentService.validateOutput` 工程兜底；前端复用 Embabel Tab 渲染测验题。

**Tech Stack:** Java 21, Spring Boot 4.1.0, Embabel Agent 2.0.0-SNAPSHOT, DeepSeek `deepseek-v4-pro`, jsoup 1.22.2, 原生 HTML/CSS/JS

**设计规范:** [docs/superpowers/specs/2026-07-15-embabel-quizzard-design.md](../specs/2026-07-15-embabel-quizzard-design.md)

## Global Constraints

- **Embabel 版本**：沿用 `2.0.0-SNAPSHOT`，不降级 / 不升级主版本
- **模型**：`deepseek-v4-pro`（现有配置不动）
- **API**：只复用 `POST /embabel/agent/ask` 与 `/ask/stream`，禁止新增 Quizzard 专用路由
- **Agent**：保留 `StarNewsAgent` / `PolicyAgent`，新增 `QuizAgent`
- **Prompt**：只追加到 `application-embabel-prompts.yml`（经 `application.properties` import）
- **SSE**：不推送 `ConceptDigest` / `QuizDraft` 中间对象
- **题目规则**：恰好 3 道题；每题恰好 4 选项；`answer` 必须与某 option 全文一致
- **正文截断**：`MAX_CONTENT_LENGTH = 12_000`
- **失败语义**：抓取/正文过短/校验失败 → HTTP 502
- **编译门禁**：`mvn -DskipTests compile` 必须 SUCCESS（workdir: `demo2`）
- **测试命令**：`mvn -Dtest=ArticleFetchServiceTest,EmbabelAgentServiceTest test`（workdir: `demo2`）
- **不修改** 其他非 Embabel Tab 后端

---

## File Structure

| 文件 | 职责 |
|------|------|
| `demo2/pom.xml` | 增加 jsoup 1.22.2 |
| `embabel/agent/QuizAgent.java` | 四段 Action + 领域 records |
| `embabel/config/QuizAgentProperties.java` | `demo.quiz-agent.prompts.*` |
| `embabel/service/ArticleFetchService.java` | URL 抽取、jsoup、本地正文、截断 |
| `embabel/service/EmbabelAgentService.java` | `validateOutput` 增加 QuizPack |
| `application-embabel-prompts.yml` | quiz-agent 三段 prompt |
| `test/.../ArticleFetchServiceTest.java` | 抓取/解析单测 |
| `test/.../EmbabelAgentServiceTest.java` | QuizPack 校验用例 |
| `static/index.html` | 测试3 按钮 + 文案 + textarea |
| `static/js/tabs/embabel.js` | QuizPack 渲染 + 示例 |
| `static/css/tabs/embabel.css` | 测验题样式 |

---

### Task 1: jsoup + ArticleFetchService（TDD）

**Files:**
- Modify: `demo2/pom.xml`
- Create: `demo2/src/main/java/com/jason/demo/demo2/embabel/agent/QuizAgent.java`（先放 records，Action 下任务再补全）
- Create: `demo2/src/main/java/com/jason/demo/demo2/embabel/service/ArticleFetchService.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/embabel/service/ArticleFetchServiceTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `QuizAgent.ArticleInput(String title, String content, String sourceUrl)`
  - `ArticleFetchService.resolve(String rawInput): ArticleInput`
  - `ArticleFetchService.extractFirstUrl(String): Optional<String>`
  - `ArticleFetchService.MAX_CONTENT_LENGTH = 12_000`

- [ ] **Step 1: 在 `pom.xml` 的 `<dependencies>` 中追加 jsoup**

放在 Embabel 依赖块附近：

```xml
        <dependency>
            <groupId>org.jsoup</groupId>
            <artifactId>jsoup</artifactId>
            <version>1.22.2</version>
        </dependency>
```

- [ ] **Step 2: 创建 QuizAgent 占位（仅 records + 空壳，便于 FetchService 返回类型）**

`demo2/src/main/java/com/jason/demo/demo2/embabel/agent/QuizAgent.java`：

```java
package com.jason.demo.demo2.embabel.agent;

import java.util.List;

/**
 * Quizzard — 根据技术文章生成测验题。Action 实现见后续任务。
 */
public class QuizAgent {

    public record ArticleInput(String title, String content, String sourceUrl) {
    }

    public record ConceptPoint(String name, String explanation) {
    }

    public record ConceptDigest(List<ConceptPoint> concepts) {
    }

    public record QuizQuestion(
            String question,
            List<String> options,
            String answer,
            String explanation) {
    }

    public record QuizDraft(List<QuizQuestion> questions) {
    }

    public record QuizPack(String title, List<QuizQuestion> questions, String review) {
    }
}
```

- [ ] **Step 3: 写失败单测 `ArticleFetchServiceTest`**

```java
package com.jason.demo.demo2.embabel.service;

import com.jason.demo.demo2.embabel.agent.QuizAgent;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArticleFetchServiceTest {

    private final ArticleFetchService service = new ArticleFetchService();

    @Test
    void extractFirstUrl_returnsFirstHttpUrl() {
        String raw = "请出题：https://docs.spring.io/spring-ai/reference/api/chatclient.html 谢谢";
        assertThat(service.extractFirstUrl(raw))
                .contains("https://docs.spring.io/spring-ai/reference/api/chatclient.html");
    }

    @Test
    void resolve_parsesTitleAndBodyMarkers() {
        String raw = """
                请根据下面技术文章生成 3 道单选测验题。标题：工具调用不是 Agent。正文：Tool Calling 解决的是模型如何请求外部工具。Agent 更关注任务目标与停止条件。
                """;
        QuizAgent.ArticleInput article = service.resolve(raw);
        assertThat(article.title()).contains("工具调用");
        assertThat(article.content()).contains("Tool Calling");
        assertThat(article.sourceUrl()).isNull();
    }

    @Test
    void resolve_truncatesLongContent() {
        String body = "正文：" + "甲".repeat(ArticleFetchService.MAX_CONTENT_LENGTH + 500);
        QuizAgent.ArticleInput article = service.resolve("标题：长文\n" + body);
        assertThat(article.content().length()).isLessThanOrEqualTo(ArticleFetchService.MAX_CONTENT_LENGTH);
    }

    @Test
    void resolve_rejectsBlankPastedBody() {
        assertThatThrownBy(() -> service.resolve("请出题"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void parseHtmlDocument_prefersArticleElement() {
        String html = """
                <html><body>
                <nav>导航</nav>
                <article><h1>ChatClient</h1><p>Spring AI ChatClient 简介。</p></article>
                <footer>页脚</footer>
                </body></html>
                """;
        QuizAgent.ArticleInput article = service.parseHtmlDocument(html, "https://example.com/doc");
        assertThat(article.title()).containsIgnoringCase("ChatClient");
        assertThat(article.content()).contains("ChatClient");
        assertThat(article.content()).doesNotContain("导航");
        assertThat(article.sourceUrl()).isEqualTo("https://example.com/doc");
    }
}
```

- [ ] **Step 4: 跑测确认失败**

Run:

```bash
cd demo2
mvn -Dtest=ArticleFetchServiceTest test
```

Expected: 编译失败或测试失败（类不存在）。

- [ ] **Step 5: 实现 `ArticleFetchService`**

```java
package com.jason.demo.demo2.embabel.service;

import com.jason.demo.demo2.embabel.agent.QuizAgent;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ArticleFetchService {

    public static final int MAX_CONTENT_LENGTH = 12_000;
    private static final int MIN_CONTENT_LENGTH = 20;
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    public Optional<String> extractFirstUrl(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = URL_PATTERN.matcher(rawInput);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String url = matcher.group().replaceAll("[)，。,.]+$", "");
        return Optional.of(url);
    }

    public QuizAgent.ArticleInput resolve(String rawInput) {
        String raw = rawInput == null ? "" : rawInput.strip();
        return extractFirstUrl(raw)
                .map(this::fetchArticle)
                .orElseGet(() -> parseLocalArticle(raw));
    }

    public QuizAgent.ArticleInput fetchArticle(String url) {
        try {
            Document document = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; QuizzardBot/1.0)")
                    .timeout(15_000)
                    .get();
            return toArticleInput(document, url);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to fetch article: " + url, ex);
        }
    }

    /** 单测入口：不发起网络请求。 */
    public QuizAgent.ArticleInput parseHtmlDocument(String html, String sourceUrl) {
        Document document = Jsoup.parse(html, sourceUrl == null ? "" : sourceUrl);
        return toArticleInput(document, sourceUrl);
    }

    public QuizAgent.ArticleInput parseLocalArticle(String rawInput) {
        String title = extractLocalTitle(rawInput);
        String content = extractLocalBody(rawInput);
        content = limitLength(content);
        requireMinContent(content);
        return new QuizAgent.ArticleInput(title, content, null);
    }

    public String limitLength(String content) {
        if (content == null) {
            return "";
        }
        String text = content.strip();
        if (text.length() <= MAX_CONTENT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_CONTENT_LENGTH);
    }

    private QuizAgent.ArticleInput toArticleInput(Document document, String sourceUrl) {
        document.select("script, style, nav, footer, noscript").remove();
        Element root = firstPresent(document.selectFirst("article"),
                document.selectFirst("main"),
                document.body());
        String title = Optional.ofNullable(document.title())
                .filter(t -> !t.isBlank())
                .or(() -> Optional.ofNullable(root)
                        .map(el -> el.selectFirst("h1"))
                        .map(Element::text)
                        .filter(t -> !t.isBlank()))
                .orElse("未命名文章");
        String content = limitLength(root == null ? "" : root.text());
        requireMinContent(content);
        return new QuizAgent.ArticleInput(title, content, sourceUrl);
    }

    private void requireMinContent(String content) {
        if (content == null || content.strip().length() < MIN_CONTENT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Article content too short");
        }
    }

    private String extractLocalTitle(String raw) {
        Matcher titled = Pattern.compile("标题[:：]\\s*(.+)").matcher(raw);
        if (titled.find()) {
            return titled.group(1).strip().split("[\\r\\n]")[0].strip();
        }
        Matcher md = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE).matcher(raw);
        if (md.find()) {
            return md.group(1).strip();
        }
        String firstLine = raw.lines().map(String::strip).filter(s -> !s.isBlank()).findFirst().orElse("未命名文章");
        return firstLine.length() > 80 ? firstLine.substring(0, 80) : firstLine;
    }

    private String extractLocalBody(String raw) {
        Matcher body = Pattern.compile("正文[:：]\\s*([\\s\\S]+)").matcher(raw);
        if (body.find()) {
            return body.group(1).strip();
        }
        String withoutUrl = URL_PATTERN.matcher(raw).replaceAll("").strip();
        return withoutUrl;
    }

    @SafeVarargs
    private final Element firstPresent(Element... elements) {
        for (Element element : elements) {
            if (element != null) {
                return element;
            }
        }
        return null;
    }
}
```

注意：若 `firstPresent` 的 `@SafeVarargs` / `final` 在实例方法上编译告警，改为普通循环内联即可。

- [ ] **Step 6: 跑测确认通过**

Run:

```bash
cd demo2
mvn -Dtest=ArticleFetchServiceTest test
```

Expected: `BUILD SUCCESS`，全部测试绿色。

- [ ] **Step 7: Commit**

```bash
git add demo2/pom.xml \
  demo2/src/main/java/com/jason/demo/demo2/embabel/agent/QuizAgent.java \
  demo2/src/main/java/com/jason/demo/demo2/embabel/service/ArticleFetchService.java \
  demo2/src/test/java/com/jason/demo/demo2/embabel/service/ArticleFetchServiceTest.java
git commit -m "feat(demo2): add ArticleFetchService and jsoup for Quizzard"
```

---

### Task 2: QuizAgentProperties + Prompt YAML

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/embabel/config/QuizAgentProperties.java`
- Modify: `demo2/src/main/resources/application-embabel-prompts.yml`

**Interfaces:**
- Consumes: `@ConfigurationPropertiesScan` 已在 `Demo2Application` 启用
- Produces: `QuizAgentProperties.prompts().extractConcepts() / generateQuiz() / reviewQuiz()`

- [ ] **Step 1: 创建 Properties**

```java
package com.jason.demo.demo2.embabel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demo.quiz-agent")
public record QuizAgentProperties(Prompts prompts) {

    public record Prompts(String extractConcepts, String generateQuiz, String reviewQuiz) {
    }
}
```

- [ ] **Step 2: 在 `application-embabel-prompts.yml` 末尾追加**

保持既有 `star-news-agent` / `policy-agent` 不动，追加：

```yaml
  quiz-agent:
    prompts:
      extract-concepts: |
        请从技术文章中抽取 3 到 5 个适合出测验题的核心知识点。
        每个知识点包含 name 和 explanation。
        不要抽取太泛的词，比如“AI”“Java”。
        只返回结构化对象，不要解释。

        标题：{title}
        正文：
        {content}
      generate-quiz: |
        请根据技术文章和核心知识点生成 3 道单选题。
        题干、选项、答案和解释都用中文输出。
        每道题必须包含 question、options、answer、explanation。
        options 必须正好 4 个选项。
        answer 必须和 options 中的一个选项完全一致。
        explanation 必须说明为什么选这个答案。
        题目要考理解，不要考死记硬背。
        只返回结构化对象，不要解释。

        标题：{title}
        核心知识点：{concepts}
        正文：
        {content}
      review-quiz: |
        请检查下面的测验题是否适合技术文章复盘。
        如果题目过泛、答案不在选项里、解释不完整，请修正后返回。
        最终返回 QuizPack：title、questions、review。
        title、questions、review 都用中文输出。
        questions 保留 3 道题，每题 4 个选项。
        review 用一句完整中文说明这套题主要考察什么，以中文句号结尾。

        文章标题：{title}
        候选题：
        {questions}
```

注意 YAML 缩进与现有 `demo:` 同级子项对齐（两个空格缩进 `quiz-agent`）。

- [ ] **Step 3: 编译**

Run:

```bash
cd demo2
mvn -DskipTests compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/embabel/config/QuizAgentProperties.java \
  demo2/src/main/resources/application-embabel-prompts.yml
git commit -m "feat(demo2): add Quizzard prompt config"
```

---

### Task 3: 实现 QuizAgent 四段 Action

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/embabel/agent/QuizAgent.java`

**Interfaces:**
- Consumes: `ArticleFetchService.resolve`, `QuizAgentProperties.prompts()`, Embabel `UserInput` / `Ai`
- Produces: `@Agent QuizAgent` 可被 Autonomy 选中；Goal 输出 `QuizPack`

- [ ] **Step 1: 用完整 Agent 替换占位类**

```java
package com.jason.demo.demo2.embabel.agent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.domain.io.UserInput;
import com.jason.demo.demo2.embabel.config.QuizAgentProperties;
import com.jason.demo.demo2.embabel.service.ArticleFetchService;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Agent(description = "根据技术文章内容生成面向开发者学习复盘的测验题")
public class QuizAgent {

    private final ArticleFetchService articleFetchService;
    private final QuizAgentProperties properties;

    public QuizAgent(ArticleFetchService articleFetchService, QuizAgentProperties properties) {
        this.articleFetchService = articleFetchService;
        this.properties = properties;
    }

    @Action
    public ArticleInput extractArticle(UserInput userInput) {
        return articleFetchService.resolve(userInput.getContent());
    }

    @Action
    public ConceptDigest extractConcepts(ArticleInput article, Ai ai) {
        String prompt = properties.prompts().extractConcepts()
                .replace("{title}", nullToEmpty(article.title()))
                .replace("{content}", nullToEmpty(article.content()));
        return ai.withDefaultLlm().createObject(prompt, ConceptDigest.class);
    }

    @Action
    public QuizDraft generateQuiz(ArticleInput article, ConceptDigest digest, Ai ai) {
        String prompt = properties.prompts().generateQuiz()
                .replace("{title}", nullToEmpty(article.title()))
                .replace("{concepts}", renderConcepts(digest == null ? List.of() : digest.concepts()))
                .replace("{content}", nullToEmpty(article.content()));
        return ai.withDefaultLlm().createObject(prompt, QuizDraft.class);
    }

    @AchievesGoal(description = "生成一套可用于技术文章复盘的测验题")
    @Action
    public QuizPack reviewQuiz(ArticleInput article, QuizDraft draft, Ai ai) {
        String prompt = properties.prompts().reviewQuiz()
                .replace("{title}", nullToEmpty(article.title()))
                .replace("{questions}", renderQuestions(draft == null ? List.of() : draft.questions()));
        return ai.withDefaultLlm().createObject(prompt, QuizPack.class);
    }

    private String renderConcepts(List<ConceptPoint> concepts) {
        if (concepts == null || concepts.isEmpty()) {
            return "(无)";
        }
        return IntStream.range(0, concepts.size())
                .mapToObj(i -> {
                    ConceptPoint c = concepts.get(i);
                    return (i + 1) + ". " + nullToEmpty(c.name()) + " — " + nullToEmpty(c.explanation());
                })
                .collect(Collectors.joining("\n"));
    }

    private String renderQuestions(List<QuizQuestion> questions) {
        if (questions == null || questions.isEmpty()) {
            return "(无)";
        }
        return IntStream.range(0, questions.size())
                .mapToObj(i -> {
                    QuizQuestion q = questions.get(i);
                    String options = q.options() == null ? "" : String.join(" | ", q.options());
                    return (i + 1) + ". " + nullToEmpty(q.question())
                            + "\n选项: " + options
                            + "\n答案: " + nullToEmpty(q.answer())
                            + "\n解释: " + nullToEmpty(q.explanation());
                })
                .collect(Collectors.joining("\n\n"));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record ArticleInput(String title, String content, String sourceUrl) {
    }

    public record ConceptPoint(String name, String explanation) {
    }

    public record ConceptDigest(List<ConceptPoint> concepts) {
    }

    public record QuizQuestion(
            String question,
            List<String> options,
            String answer,
            String explanation) {
    }

    public record QuizDraft(List<QuizQuestion> questions) {
    }

    public record QuizPack(String title, List<QuizQuestion> questions, String review) {
    }
}
```

- [ ] **Step 2: 编译**

Run:

```bash
cd demo2
mvn -DskipTests compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/embabel/agent/QuizAgent.java
git commit -m "feat(demo2): implement QuizAgent action pipeline"
```

---

### Task 4: validateOutput(QuizPack)（TDD）

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/embabel/service/EmbabelAgentService.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/embabel/service/EmbabelAgentServiceTest.java`

**Interfaces:**
- Consumes: `QuizAgent.QuizPack` / `QuizQuestion`
- Produces: `validateOutput` 接受合法 QuizPack；拒绝坏结构（502）

- [ ] **Step 1: 在 `EmbabelAgentServiceTest` 追加用例**

```java
import com.jason.demo.demo2.embabel.agent.QuizAgent;

import java.util.List;

// 在既有类中追加：

    @Test
    void validateOutput_acceptsValidQuizPack() {
        var ok = sampleQuizPack();
        assertThatCode(() -> service.validateOutput(ok)).doesNotThrowAnyException();
    }

    @Test
    void validateOutput_rejectsWrongOptionCount() {
        var bad = new QuizAgent.QuizPack(
                "标题",
                List.of(new QuizAgent.QuizQuestion(
                        "题干？",
                        List.of("A1", "A2", "A3"),
                        "A1",
                        "解释完整。")),
                "这套题考察核心概念。");
        assertThatThrownBy(() -> service.validateOutput(bad))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void validateOutput_rejectsAnswerNotInOptions() {
        var q = new QuizAgent.QuizQuestion(
                "题干？",
                List.of("甲", "乙", "丙", "丁"),
                "戊",
                "解释完整。");
        var bad = new QuizAgent.QuizPack("标题", List.of(q, q, q), "这套题考察核心概念。");
        assertThatThrownBy(() -> service.validateOutput(bad))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void validateOutput_rejectsWrongQuestionCount() {
        var q = new QuizAgent.QuizQuestion(
                "题干？",
                List.of("甲", "乙", "丙", "丁"),
                "甲",
                "解释完整。");
        var bad = new QuizAgent.QuizPack("标题", List.of(q), "这套题考察核心概念。");
        assertThatThrownBy(() -> service.validateOutput(bad))
                .isInstanceOf(ResponseStatusException.class);
    }

    private static QuizAgent.QuizPack sampleQuizPack() {
        var q1 = new QuizAgent.QuizQuestion(
                "Tool Calling 与 Agent 的根本区别？",
                List.of("多工具", "单次交互 vs 目标状态循环", "仅 API", "无错误处理"),
                "单次交互 vs 目标状态循环",
                "文章指出 Agent 关注目标、状态与停止条件。");
        var q2 = new QuizAgent.QuizQuestion(
                "为什么先抽知识点？",
                List.of("好看", "显式化出题依据", "省 token", "换模型"),
                "显式化出题依据",
                "中间对象便于排查题目是否偏题。");
        var q3 = new QuizAgent.QuizQuestion(
                "answer 字段应如何表示？",
                List.of("只写 A", "与某 option 全文一致", "任意文字", "留空"),
                "与某 option 全文一致",
                "便于校验答案是否落在选项内。");
        return new QuizAgent.QuizPack("测验标题", List.of(q1, q2, q3), "这套题主要考察 Agent 拆分与校验要点。");
    }
```

- [ ] **Step 2: 跑测确认新用例失败**

Run:

```bash
cd demo2
mvn -Dtest=EmbabelAgentServiceTest test
```

Expected: QuizPack 相关用例因 `Unsupported agent output type` 失败。

- [ ] **Step 3: 扩展 `validateOutput`**

在 `EmbabelAgentService`：

1. 增加 import：`QuizAgent`、`java.util.HashSet`、`java.util.List`、`java.util.Set`
2. 在 Policy 分支之后、最终 `throw` 之前插入：

```java
        if (output instanceof QuizAgent.QuizPack quizPack) {
            requireText(quizPack.title(), "QuizPack.title");
            requireQuestions(quizPack.questions());
            requireCompleteSentence(quizPack.review(), "QuizPack.review");
            return;
        }
```

3. 新增私有方法：

```java
    private void requireQuestions(List<QuizAgent.QuizQuestion> questions) {
        if (questions == null || questions.size() != 3) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "QuizPack.questions must contain exactly 3 items");
        }
        for (int i = 0; i < questions.size(); i++) {
            QuizAgent.QuizQuestion q = questions.get(i);
            if (q == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "QuizPack.questions[" + i + "] is null");
            }
            requireText(q.question(), "QuizPack.questions[" + i + "].question");
            List<String> options = q.options();
            if (options == null || options.size() != 4) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "QuizPack.questions[" + i + "].options must contain exactly 4 items");
            }
            for (int j = 0; j < options.size(); j++) {
                requireText(options.get(j), "QuizPack.questions[" + i + "].options[" + j + "]");
            }
            Set<String> unique = new HashSet<>(options);
            if (unique.size() != 4) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "QuizPack.questions[" + i + "].options must be unique");
            }
            requireText(q.answer(), "QuizPack.questions[" + i + "].answer");
            if (!options.contains(q.answer())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "QuizPack.questions[" + i + "].answer must match one option");
            }
            requireCompleteSentence(q.explanation(), "QuizPack.questions[" + i + "].explanation");
        }
    }
```

- [ ] **Step 4: 跑测确认通过**

Run:

```bash
cd demo2
mvn -Dtest=ArticleFetchServiceTest,EmbabelAgentServiceTest test
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/embabel/service/EmbabelAgentService.java \
  demo2/src/test/java/com/jason/demo/demo2/embabel/service/EmbabelAgentServiceTest.java
git commit -m "feat(demo2): validate QuizPack output in EmbabelAgentService"
```

---

### Task 5: 前端 — 示例按钮 + QuizPack 渲染

**Files:**
- Modify: `demo2/src/main/resources/static/index.html`（Embabel Tab 区域约 1207–1228 行）
- Modify: `demo2/src/main/resources/static/js/tabs/embabel.js`
- Modify: `demo2/src/main/resources/static/css/tabs/embabel.css`

**Interfaces:**
- Consumes: SSE `RESULT` payload：`{ agentName, outputType, output }`（与现有 `AgentResponse` 一致；若前端收到的是整包 `AgentResponse`，按 `payload.outputType` / `payload.output` 读取）
- Produces: QuizPack 卡片 UI；示例 3 填充粘贴正文样例

- [ ] **Step 1: 更新 `index.html` Embabel 区块**

替换欢迎文案、样例按钮区与输入控件：

```html
        <div class="embabel-header">
            <h1>Embabel 自动选路</h1>
            <p>Closed 模式：Autonomy 在 StarNews / Policy / Quiz 间选路，再跑 Action（SSE）</p>
        </div>
        <div class="embabel-samples">
            <button type="button" class="embabel-sample-btn" onclick="fillEmbabelSample(1)">测试1：星座文案</button>
            <button type="button" class="embabel-sample-btn" onclick="fillEmbabelSample(2)">测试2：差旅报销</button>
            <button type="button" class="embabel-sample-btn" onclick="fillEmbabelSample(3)">测试3：技术文章出题</button>
        </div>
        ...
                        同一入口三类请求：星座文案、制度问答、技术文章测验题（Quizzard）。
        ...
            <form id="embabelForm" class="embabel-input-bar">
                <textarea id="embabelMessageInput" rows="3" placeholder="输入问题，或粘贴技术文章正文 / URL 做出题" required></textarea>
                <button type="submit" class="btn-embabel" id="embabelSendBtn">发送</button>
            </form>
```

- [ ] **Step 2: 更新 `embabel.js` — 样例 + QuizPack 渲染**

在文件顶部扩展样例：

```javascript
const EMBABEL_SAMPLES = {
    1: '给李白写一段白羊座今日运势文案',
    2: '出差回来后报销需要哪些材料',
    3: '请根据下面技术文章生成 3 道单选测验题。标题：为什么 Spring AI 的 Tool Calling 不等于完整 Agent。正文：Tool Calling 解决的是模型如何请求外部工具，以及应用侧如何执行这些工具。一次工具调用循环可以让模型先查资料、再继续回答，但它通常只发生在一次模型调用上下文里。Agent 更关注任务目标、状态、动作链路和停止条件。'
};
```

将 `RESULT` 分支改为：

```javascript
                } else if (event === 'RESULT') {
                    renderEmbabelResult(resultEl, payload);
                } else if (event === 'ERROR') {
```

新增函数（放在 `sendEmbabelMessage` 之前）：

```javascript
function renderEmbabelResult(resultEl, payload) {
    const outputType = payload.outputType;
    const output = payload.output;
    if (outputType === 'QuizPack' && output) {
        resultEl.className = 'embabel-quiz-pack';
        resultEl.innerHTML = buildQuizPackHtml(output);
        return;
    }
    resultEl.className = 'embabel-result-json';
    resultEl.textContent = JSON.stringify(payload, null, 2);
}

function buildQuizPackHtml(pack) {
    const letters = ['A', 'B', 'C', 'D'];
    let html = '<div class="embabel-quiz-title">' + escapeHtml(pack.title || '') + '</div>';
    const questions = pack.questions || [];
    questions.forEach(function (q, idx) {
        html += '<div class="embabel-quiz-q">';
        html += '<div class="embabel-quiz-stem">' + (idx + 1) + '. ' + escapeHtml(q.question || '') + '</div>';
        html += '<ul class="embabel-quiz-options">';
        (q.options || []).forEach(function (opt, oi) {
            const letter = letters[oi] || String(oi + 1);
            const isAnswer = opt === q.answer;
            html += '<li class="' + (isAnswer ? 'is-answer' : '') + '">'
                + '<strong>' + letter + '.</strong> ' + escapeHtml(opt)
                + (isAnswer ? ' <span class="embabel-quiz-badge">正确</span>' : '')
                + '</li>';
        });
        html += '</ul>';
        html += '<div class="embabel-quiz-explain"><strong>解释：</strong>'
            + escapeHtml(q.explanation || '') + '</div>';
        html += '</div>';
    });
    html += '<div class="embabel-quiz-review">' + escapeHtml(pack.review || '') + '</div>';
    return html;
}
```

确认 `escapeHtml` 已由 `/js/core/utils.js` 提供（`index.html` 已引入）。

- [ ] **Step 3: 追加 CSS**

在 `embabel.css` 末尾追加：

```css
.embabel-input-bar textarea {
    flex: 1;
    min-height: 72px;
    padding: 10px 12px;
    border: 1px solid #cbd5e1;
    border-radius: 8px;
    resize: vertical;
    font: inherit;
}

.embabel-quiz-pack {
    margin-top: 8px;
    padding: 12px;
    background: #f8fafc;
    border-radius: 8px;
    border: 1px solid #e2e8f0;
}

.embabel-quiz-title {
    font-size: 1.1rem;
    font-weight: 700;
    margin-bottom: 12px;
}

.embabel-quiz-q {
    margin-bottom: 14px;
    padding-bottom: 12px;
    border-bottom: 1px solid #e2e8f0;
}

.embabel-quiz-stem {
    font-weight: 600;
    margin-bottom: 6px;
}

.embabel-quiz-options {
    margin: 0 0 8px 0;
    padding-left: 1.1rem;
}

.embabel-quiz-options li.is-answer {
    color: #166534;
    font-weight: 600;
}

.embabel-quiz-badge {
    font-size: 12px;
    color: #15803d;
    margin-left: 4px;
}

.embabel-quiz-explain {
    font-size: 0.92rem;
    color: #334155;
}

.embabel-quiz-review {
    margin-top: 8px;
    padding: 8px 10px;
    background: #ecfdf5;
    border-radius: 6px;
    color: #14532d;
}
```

同时检查 `.embabel-input-bar` 是否对 `input` 有 flex 样式；保证 `textarea` 同样 `flex: 1`。

- [ ] **Step 4: 静态检查**

手动打开 `index.html` Embabel Tab：三个样例按钮存在，测试3 填充长文本，textarea 可多行。

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/resources/static/index.html \
  demo2/src/main/resources/static/js/tabs/embabel.js \
  demo2/src/main/resources/static/css/tabs/embabel.css
git commit -m "feat(demo2): render QuizPack in Embabel tab"
```

---

### Task 6: 编译门禁 + 手工验收清单

**Files:**
- 无强制代码改动（仅验证）

**Interfaces:**
- Consumes: Task 1–5 全部交付物

- [x] **Step 1: 全量相关测试 + 编译**

```bash
cd demo2
mvn -Dtest=ArticleFetchServiceTest,EmbabelAgentServiceTest test
mvn -DskipTests compile
```

Expected: 两次均 `BUILD SUCCESS`

- [x] **Step 2: 启动应用（需 `DEEPSEEK_API_KEY`）手工验收**

```bash
cd demo2
mvn spring-boot:run
```

| # | 操作 | 期望 |
|---|------|------|
| 1 | 测试1 星座 | `agentName=StarNewsAgent`，`Writeup` |
| 2 | 测试2 差旅 | `agentName=PolicyAgent`，`PolicyAnswer` |
| 3 | 测试3 粘贴出题 | `agentName=QuizAgent`，卡片渲染 3 题 |
| 4 | 同步 URL（可选） | `curl -X POST http://localhost:8081/embabel/agent/ask -H "Content-Type: application/json" -d "{\"message\":\"请根据这篇技术文章生成 3 道单选测验题：https://docs.spring.io/spring-ai/reference/api/chatclient.html\"}"` → `QuizPack`；若文档站反爬则 502，改用粘贴路径 |

- [x] **Step 3: 若手工发现问题则修复并补测后另提 commit；无代码变更则跳过 commit**

- [x] **Step 4: 更新设计规范状态（可选小改）**

将 `2026-07-15-embabel-quizzard-design.md` 顶部 `状态: 待实现` 改为 `已实现`，并 commit：

```bash
git add demo2/docs/superpowers/specs/2026-07-15-embabel-quizzard-design.md
git commit -m "docs(demo2): mark Quizzard design as implemented"
```

---

## Spec Coverage Checklist（自审）

| Spec 要求 | Task |
|-----------|------|
| 三 Agent 并入选路 | Task 3 |
| 四段 Action + records | Task 3 |
| jsoup URL / 粘贴正文 / 截断 / 过短 502 | Task 1 |
| Prompt → YAML + Properties | Task 2 |
| validateOutput 严格 3 题 / 4 选项 / answer 命中 | Task 4 |
| 复用 API，不新路由 | 全任务（未新增 Controller） |
| SSE 只最终 QuizPack | Task 5（仅 RESULT 渲染） |
| 前端 Tab 示例 + 结构化渲染 | Task 5 |
| 星座/制度回归 | Task 6 |
| 单元测试 | Task 1, 4 |

无独立子系统需拆计划。
