# Embabel 实战 Quizzard 设计规范

**日期**: 2026-07-15  
**项目**: spring-ai-demo / demo2  
**状态**: 已实现  
**参考文章**: [6. Embabel实战Quizzard：根据技术文章生成测验题](https://mp.weixin.qq.com/s/gHG78rBVANCM8Xk6Xn55_w)

---

## 1. 背景与目标

### 1.1 需求

在已集成 Embabel 的 `demo2` 中新增 **Quizzard**：根据技术文章（URL 或粘贴正文）生成面向开发者复盘的单选测验题。

核心不是「一次 Prompt 出 3 道题」，而是把任务拆成有清晰输入/输出的 Action 链：

```text
UserInput → ArticleInput → ConceptDigest → QuizDraft → QuizPack
```

串起 Embabel 的 `@Agent` / `@Action` / `@AchievesGoal` 与 Java Record 结构化输出，并在返回前做工程校验。

### 1.2 已确认决策

| 维度 | 选择 |
|------|------|
| 集成方式 | **并入现有三 Agent 选路**（保留 StarNews / Policy，新增 QuizAgent） |
| API | 复用 `POST /embabel/agent/ask` 与 `/ask/stream`，不新增独立路由 |
| 前端 | 复用「Embabel 自动选路」Tab；`QuizPack` 结构化渲染 |
| SSE | 只推过程事件 + 最终 `QuizPack`（不推 ConceptDigest / QuizDraft） |
| Prompt | `application.properties` import `application-embabel-prompts.yml`，追加 `demo.quiz-agent.prompts.*` |
| 模型 / Embabel 版本 | 沿用现有 `deepseek-v4-pro` 与 `embabel-agent 2.0.0-SNAPSHOT` |
| Action 拆分 | 文章四段：抓取 → 知识点 → 候选题 → 审核收口 |

### 1.3 成功标准

1. 含 URL 的消息能选中 `QuizAgent`，返回合法 `QuizPack`（3 道单选、每题 4 选项、answer 命中 option）
2. 粘贴 Markdown/正文（无 URL）同样可生成 `QuizPack`
3. 星座 / 制度样例仍分别路由到原 Agent
4. SSE Tab 对 `QuizPack` 做可读渲染；过程区展示选 Agent 与 Action 进度
5. `validateOutput` 挡住明显坏结果（选项数、答案不在选项内等）
6. `mvn -DskipTests compile`（demo2）通过；相关单元测试通过

### 1.4 不在范围

- 独立 `/embabel/quizzard` 路由或专用 Tab
- SSE 推送知识点 / 草稿中间产物
- 难度分级、多题型、题库持久化、资料检索增强
- 禁用或删除现有 StarNews / Policy Agent
- 更换 Embabel / Spring Boot 主版本

---

## 2. 架构设计

### 2.1 运行时流程

```text
用户 message
  → EmbabelAgentService（ask / streamAsk）
  → Autonomy.chooseAndRunAgent（Closed 模式，三 Agent 竞争）
  → QuizAgent Action 链
  → validateOutput(QuizPack)
  → AgentResponse / SSE RESULT
```

### 2.2 包与文件

在现有 `com.jason.demo.demo2.embabel` 下增量：

```text
embabel/
├── agent/QuizAgent.java              # 新增
├── config/QuizAgentProperties.java   # 新增
├── service/ArticleFetchService.java  # 新增：URL / Markdown / 截断
├── service/EmbabelAgentService.java  # 扩展 validateOutput
resources/
├── application-embabel-prompts.yml   # 追加 quiz-agent prompts
├── static/index.html                 # 示例按钮「测试3」
├── static/js/tabs/embabel.js         # QuizPack 渲染
└── static/css/tabs/embabel.css       # 题目展示样式
pom.xml                               # 增加 jsoup
```

领域 Record（`ArticleInput`、`ConceptPoint`、`ConceptDigest`、`QuizQuestion`、`QuizDraft`、`QuizPack`）优先内嵌在 `QuizAgent` 中，与 `StarNewsAgent` / `PolicyAgent` 风格一致。

### 2.3 对象模型

| Record | 字段 | 说明 |
|--------|------|------|
| `ArticleInput` | `title`, `content`, `sourceUrl` | 清洗后的文章；粘贴正文时 `sourceUrl` 可为 null |
| `ConceptPoint` | `name`, `explanation` | 单个知识点 |
| `ConceptDigest` | `List<ConceptPoint> concepts` | 通常 3–5 个 |
| `QuizQuestion` | `question`, `options`, `answer`, `explanation` | `answer` 必须与某一 option **全文一致**（非仅 A/B/C/D） |
| `QuizDraft` | `List<QuizQuestion> questions` | 候选题 |
| `QuizPack` | `title`, `questions`, `review` | 最终结果；`review` 为一句完整中文说明 |

### 2.4 QuizAgent Action 链

`@Agent(description = "根据技术文章内容生成面向开发者学习复盘的测验题")`

| Action | 输入 | 输出 | LLM? |
|--------|------|------|------|
| `extractArticle` | `UserInput` | `ArticleInput` | 否 |
| `extractConcepts` | `ArticleInput`, `Ai` | `ConceptDigest` | 是 |
| `generateQuiz` | `ArticleInput`, `ConceptDigest`, `Ai` | `QuizDraft` | 是 |
| `reviewQuiz` | `ArticleInput`, `QuizDraft`, `Ai` | `QuizPack` | 是 + `@AchievesGoal` |

Prompt 模板占位符：`{title}` / `{content}` / `{concepts}` / `{questions}`；知识点与候选题用稳定文本格式渲染（一行一条），禁止直接 `toString()`。

---

## 3. 文章抓取（ArticleFetchService）

### 3.1 输入分流

1. 从 `userInput.getContent()` 用正则抽取**第一个** `http(s)` URL  
2. 有 URL → jsoup 抓取：去 script/nav/footer，优先 `article` / `main` / `body` 正文；`sourceUrl` 记 URL  
3. 无 URL → 按本地粘贴处理：支持「标题：… / 正文：…」或整段 Markdown；标题可从首行 / `#` 标题推断  

### 3.2 依赖与失败语义

- Maven：`org.jsoup:jsoup:1.22.2`（或与 BOM 兼容的等价版本）
- 抓取失败或正文过短 → 抛异常，接口 **502**，不进入后续 LLM
- 无 URL 且粘贴正文为空/过短 → 同样 **502**（与「无法形成 ArticleInput」统一）
- 正文过长 → Java 侧截断（建议上限约 12_000 字符，实现时定数）后再进模型

---

## 4. 配置（Prompts）

`application.properties` 已有：

```properties
spring.config.import=optional:classpath:application-embabel-prompts.yml
```

在 `application-embabel-prompts.yml` 追加（与文章一致）：

```yaml
demo:
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

`QuizAgentProperties`：

```java
@ConfigurationProperties(prefix = "demo.quiz-agent")
public record QuizAgentProperties(Prompts prompts) {
    public record Prompts(String extractConcepts, String generateQuiz, String reviewQuiz) {}
}
```

需在应用启动处与现有 Star/Policy 一样注册 `@EnableConfigurationProperties`（若项目已用扫描则跟随现有方式）。

---

## 5. API 与校验

### 5.1 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/embabel/agent/ask/stream` | SSE 主入口 |
| POST | `/embabel/agent/ask` | 同步调试 |

请求：`{ "message": "..." }`  
响应：`AgentResponse(processId, agentName, outputType, output)`；Quiz 成功时 `agentName=QuizAgent`，`outputType=QuizPack`。

### 5.2 validateOutput(QuizPack)

在 `EmbabelAgentService.validateOutput` 增加分支：

1. `title` 非空  
2. `questions` 非空；实现时**严格要求恰好 3 道**（与 prompt / 文章一致）  
3. 每题：`question` 非空；`options` 恰好 4 且无重复；`answer` 等于某一 option；`explanation` 为完整句子（句末 `。！？.!?`）  
4. `review` 为完整句子  

失败 → `ResponseStatusException` 502。

选路 / 执行失败语义保持现状：`NoAgentFound`、`ProcessExecutionException` → 502。

---

## 6. 前端

- Tab 文案可略更新：说明现支持「技术文章出题」
- 新增示例按钮「测试3：技术文章出题」（短 URL 样例或粘贴正文样例之一）
- `RESULT` 且 `outputType === 'QuizPack'`：渲染 `title`、每题题干 + A/B/C/D 选项、正确答案与解释、底部 `review`
- 其他 `outputType` 仍 JSON 美化展示
- SSE 解析逻辑不变；不增加中间态 payload 类型

输入框：粘贴长文场景下，可将 `input` 改为可伸缩 `textarea`（小改动；若与现有布局冲突，至少保证 placeholder 提示可粘贴正文）。

---

## 7. 测试计划

| 用例 | 期望 |
|------|------|
| `ArticleFetchService`：从文本抽 URL | 取第一个 http(s) URL |
| `ArticleFetchService`：Markdown/「标题+正文」 | 得到非空 title/content |
| `ArticleFetchService`：截断 | 超长 content 被 limit |
| `validateOutput`：合法 QuizPack | 通过 |
| `validateOutput`：options≠4 / answer 不在 options / 缺 review | 502 |
| 手工：星座样例 | 仍选中 StarNewsAgent |
| 手工：制度样例 | 仍选中 PolicyAgent |
| 手工：URL 出题 / 粘贴出题 | 选中 QuizAgent，返回 3 道题 |

集成测试不强制 mock 全链路 LLM；以单元测试 + curl/前端手工验证为主。

---

## 8. 与现有 Embabel Demo 的关系

| 现有 | Quizzard 增量 |
|------|----------------|
| `StarNewsAgent` / `PolicyAgent` | 保留；`QuizAgent` 第三能力边界 |
| `Autonomy.chooseAndRunAgent` | 不变；依赖 `@Agent description` 区分「出测验题」意图 |
| `EmbabelSseBridge` | 不变 |
| Prompt YAML | 追加 quiz 段，不改既有两段 |

Agent description 必须写清能力边界（「根据技术文章…测验题」），避免与制度问答等「处理文本」意图误撞。

---

## 9. 风险与缓解

| 风险 | 缓解 |
|------|------|
| 文档站反爬 / Cloudflare | 抓取失败直接 502；提供粘贴正文路径 |
| 三 Agent 误选 | description + 示例 message 带「测验题/单选」关键词；手工回归原两样例 |
| 长文 token | `limitLength` + prompt 强调核心知识点 |
| 模型偶发 answer=A | 校验要求 answer 全文匹配 option；review Action 纠偏 |

---

## 10. 实现顺序建议

1. `jsoup` + `ArticleFetchService` + 单元测试  
2. Records + `QuizAgent` + Properties + YAML prompts  
3. `validateOutput` + 单测  
4. 前端示例与 `QuizPack` 渲染  
5. 手工端到端（URL / 粘贴 / 原两 Agent）
