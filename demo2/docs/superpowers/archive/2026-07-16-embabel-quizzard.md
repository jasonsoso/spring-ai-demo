# Embabel Quizzard · 功能归档

**归档日期**: 2026-07-16  
**项目**: spring-ai-demo / demo2  
**状态**: 已实现，可联调演示  
**分支**: `feat/embabel-quizzard`（已合入 `main`）

---

## 1. 功能概述

在已有 **Embabel 自动选路** Demo（`StarNewsAgent` / `PolicyAgent`）上新增第三能力 **Quizzard**：

1. 输入技术文章 **URL** 或 **粘贴正文**
2. Java 侧确定性抓取 / 解析为 `ArticleInput`
3. LLM 抽知识点 → 生成候选题 → 审核收口 → `QuizPack`
4. 工程校验（3 题 × 4 选项、`answer` 命中 option）后返回
5. 前端「🔀 Embabel 自动选路」Tab 结构化渲染测验题

与「一次 Prompt 出 3 道题」不同：任务拆成清晰 Action 链，中间产物显式化，便于排查与演进。

**原文**: [6. Embabel实战Quizzard：根据技术文章生成测验题](https://mp.weixin.qq.com/s/gHG78rBVANCM8Xk6Xn55_w)

**相关前置**: [2. Embabel 上手实战：让 Java Agent 自己选择执行链路](https://mp.weixin.qq.com/s/DghJktDEvPMfzm4f70acOA)（已实现于 `2026-07-13` Embabel 选路 Demo）

---

## 2. 架构（当前实现）

```text
用户 message（Tab / curl）
  → POST /embabel/agent/ask[/stream]
  → Autonomy.chooseAndRunAgent（Closed 模式，三 Agent 竞争）
       ├─ StarNewsAgent → Writeup
       ├─ PolicyAgent   → PolicyAnswer
       └─ QuizAgent     → QuizPack
  → EmbabelAgentService.validateOutput
  → AgentResponse / SSE RESULT
```

**QuizAgent Action 链**：

```text
UserInput
  → extractArticle      （jsoup / 粘贴正文，不调 LLM）
  → extractConcepts     → ConceptDigest
  → generateQuiz        → QuizDraft
  → reviewQuiz (@AchievesGoal) → QuizPack
```

**设计决策（相对文章示例）**：

| 维度 | 文章示例 | 本仓库实现 |
|------|----------|------------|
| Agent 集合 | 分支只留 QuizAgent | **并入**现有三 Agent 选路 |
| API | `/agent/ask` | 复用 `/embabel/agent/ask` + `/ask/stream` |
| Prompt 存放 | `application.yml` | `application-embabel-prompts.yml`（经 properties import） |
| Embabel 版本 | 文章当时版本 | **2.0.0-SNAPSHOT** |
| 前端 | 未强调 | Embabel Tab + QuizPack 卡片渲染 |

---

## 3. 文件清单

### 后端

| 文件 | 职责 |
|------|------|
| `embabel/agent/QuizAgent.java` | 四段 Action + 领域 records |
| `embabel/config/QuizAgentProperties.java` | `demo.quiz-agent.prompts.*` |
| `embabel/service/ArticleFetchService.java` | URL 抽取、jsoup 抓取、本地正文、截断 |
| `embabel/service/EmbabelAgentService.java` | `validateOutput(QuizPack)` 扩展 |
| `embabel/agent/StarNewsAgent.java` | 星座文案（既有） |
| `embabel/agent/PolicyAgent.java` | 制度问答（既有） |
| `embabel/controller/EmbabelAgentController.java` | `/embabel/agent/ask` + `/ask/stream` |
| `embabel/sse/EmbabelSseBridge.java` | Action 进度 → SSE |

### 配置 / 依赖

| 文件 | 职责 |
|------|------|
| `application-embabel-prompts.yml` | 三段 quiz prompt（UTF-8） |
| `application.properties` | `spring.config.import` + Embabel LLM |
| `pom.xml` | `jsoup 1.22.2` + Embabel 2.0.0-SNAPSHOT |

### 前端

| 文件 | 职责 |
|------|------|
| `static/index.html` | 测试3 按钮 + textarea |
| `static/js/tabs/embabel.js` | QuizPack 结构化渲染 |
| `static/css/tabs/embabel.css` | 测验题卡片样式 |

### 测试

| 文件 | 覆盖 |
|------|------|
| `ArticleFetchServiceTest` | URL 抽取、标题/正文、截断、HTML 解析、过短拒绝 |
| `EmbabelAgentServiceTest` | QuizPack 合法通过 / 选项数 / 答案不在选项 / 题数 |

---

## 4. 配置与启动

### 环境变量

```powershell
$env:DEEPSEEK_API_KEY = "..."
```

复用现有 DeepSeek Key；无需额外 Key。

### application.properties 要点

```properties
embabel.models.default-llm=deepseek-v4-pro
embabel.agent.platform.models.openai.custom.api-key=${DEEPSEEK_API_KEY:}
embabel.agent.platform.models.openai.custom.base-url=https://api.deepseek.com
embabel.agent.platform.models.openai.custom.models=deepseek-v4-pro
spring.config.import=optional:classpath:application-embabel-prompts.yml
```

### Prompt 位置

中文 Prompt 在 `application-embabel-prompts.yml`：

```yaml
demo:
  quiz-agent:
    prompts:
      extract-concepts: |
        ...
      generate-quiz: |
        ...
      review-quiz: |
        ...
```

Java 侧：`QuizAgentProperties`（`@ConfigurationProperties(prefix = "demo.quiz-agent")`），由 `Demo2Application` 的 `@ConfigurationPropertiesScan` 扫描注册。

### 启动与访问

```powershell
cd demo2
.\mvnw.cmd spring-boot:run
# 打开 http://localhost:8081 → 「🔀 Embabel 自动选路」Tab
```

---

## 5. API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/embabel/agent/ask/stream` | SSE 主入口（选路 + Action 进度 + RESULT） |
| `POST` | `/embabel/agent/ask` | 同步调试（curl / Scalar） |

请求体：`{ "message": "..." }`

响应：`AgentResponse(processId, agentName, outputType, output)`  
Quiz 成功时：`agentName=QuizAgent`，`outputType=QuizPack`。

**SSE 事件**：`PROGRESS` · `ACTION_START` · `ACTION_COMPLETE` · `AGENT_SELECTED` · `RESULT` · `ERROR`  
（不推送 `ConceptDigest` / `QuizDraft` 中间对象）

### curl 示例

```bash
# 粘贴正文出题
curl -X POST "http://localhost:8081/embabel/agent/ask" \
  -H "Content-Type: application/json" \
  -d "{\"message\":\"请根据下面技术文章生成 3 道单选测验题。标题：工具调用不是 Agent。正文：Tool Calling 解决的是模型如何请求外部工具。Agent 更关注任务目标与停止条件。\"}"

# URL 出题（文档站可能反爬 → 502，可改粘贴路径）
curl -X POST "http://localhost:8081/embabel/agent/ask" \
  -H "Content-Type: application/json" \
  -d "{\"message\":\"请根据这篇技术文章生成 3 道单选测验题：https://docs.spring.io/spring-ai/reference/api/chatclient.html\"}"
```

---

## 6. QuizPack 结构与校验

```json
{
  "title": "…",
  "questions": [
    {
      "question": "…",
      "options": ["A选项全文", "B…", "C…", "D…"],
      "answer": "必须与某一 option 全文一致",
      "explanation": "完整中文句子。"
    }
  ],
  "review": "这套题主要考察……。"
}
```

`validateOutput` 规则：

- 恰好 **3** 道题
- 每题恰好 **4** 个不重复选项
- `answer` 必须等于某一 option
- `explanation` / `review` 为完整句子（句末标点）
- 失败 → HTTP **502**

正文抓取：上限 `12_000` 字符；过短 / 抓取失败 → **502**。

---

## 7. 典型使用流程（前端）

1. 打开 **🔀 Embabel 自动选路** Tab
2. 点「测试1：星座文案」→ 应选中 `StarNewsAgent`
3. 点「测试2：差旅报销」→ 应选中 `PolicyAgent`
4. 点「测试3：技术文章出题」→ 粘贴样例正文 → `QuizAgent` + 卡片渲染 3 题
5. 过程区可见选 Agent / Action 进度；结果区对 `QuizPack` 结构化展示

---

## 8. 已知限制与排错

| 项 | 说明 |
|----|------|
| 文档站反爬 | jsoup 抓取失败返回 502；优先用粘贴正文路径 |
| 长文 token | 正文截断至 12k；仍可能较慢（多轮 LLM） |
| 误选 Agent | 描述已写清「测验题」边界；样例 message 带「单选测验题」关键词 |
| URL 出题未强制 E2E | 验收以粘贴路径为主；URL 为可选路径 |
| SSRF | Demo 未做 URL allowlist，生产需限制 |
| 无题库持久化 | 本期不在范围内 |

---

## 9. 验收记录（归档时）

| 项 | 结果 |
|----|------|
| `ArticleFetchServiceTest` + `EmbabelAgentServiceTest` | 11/11 PASS |
| `mvn -DskipTests compile` | BUILD SUCCESS |
| 粘贴出题 E2E | `QuizAgent` / `QuizPack`（3×4） |
| 星座 / 制度 smoke | `StarNewsAgent` / `PolicyAgent` |
| 标题解析 I3 | 单行「标题/正文」不再贪婪吞正文（`93ce8b5`） |

---

## 10. 文档索引

| 类型 | 路径 |
|------|------|
| Spec | `docs/superpowers/specs/2026-07-15-embabel-quizzard-design.md` |
| Plan | `docs/superpowers/plans/2026-07-15-embabel-quizzard.md` |
| 选路前置 Spec | `docs/superpowers/specs/2026-07-13-embabel-agent-routing-design.md` |
| 选路前置 Plan | `docs/superpowers/plans/2026-07-13-embabel-agent-routing.md` |
| 归档 | `docs/superpowers/archive/2026-07-16-embabel-quizzard.md`（本文） |

---

## 11. 参考链接

- [Embabel Agent](https://github.com/embabel/embabel-agent)
- [微信原文 · Quizzard](https://mp.weixin.qq.com/s/gHG78rBVANCM8Xk6Xn55_w)
- [微信原文 · Embabel 自动选路](https://mp.weixin.qq.com/s/DghJktDEvPMfzm4f70acOA)
- [jsoup](https://jsoup.org/)
