# index.html 前端拆分重构设计规范

**日期**: 2026-06-30  
**项目**: spring-ai-demo / demo2  
**状态**: 待实现

**实现计划:** [docs/superpowers/plans/2026-06-30-index-html-refactor.md](../plans/2026-06-30-index-html-refactor.md)

---

## 1. 背景与目标

### 1.1 问题

`demo2/src/main/resources/static/index.html` 已膨胀至 **3130 行**，内联 CSS（~1140 行）、HTML（~880 行）、JS（~1100 行）全部堆在一个文件中，难以定位和维护。

### 1.2 目标

将 CSS 与 JS 按 Tab 功能模块拆分为独立静态文件，**不改变任何用户可见功能、API 调用、交互行为或视觉样式**。

### 1.3 已确认决策

| 维度 | 选择 |
|------|------|
| 构建工具 | **零构建** — 不引入 npm/Vite，Spring Boot 直接托管 `static/` |
| 拆分粒度 | **按 Tab 功能模块** — 每个 Tab 独立 CSS/JS 文件 |
| HTML 处理 | **保留在 `index.html`** — 不拆 HTML 片段、不用 `fetch` 注入 |
| CSS 合并策略 | 样式相近的 Tab **共用 CSS 文件**（见 §3） |
| JS 作用域 | **保留全局 `function`** — 兼容现有 `onclick="xxx()"` |
| 模块系统 | **不用 ES Module** — 普通 `<script src>` 顺序加载 |

### 1.4 成功标准

1. 拆分后 16 个 Tab 的 UI 与交互与重构前完全一致（人工冒烟测试）
2. `index.html` 从 3130 行降至约 **900 行**（HTML 面板 + 资源引用）
3. 无新增 Maven/npm 依赖，无后端代码变更
4. `mvn spring-boot:run` 启动后 `GET /` 正常加载

### 1.5 不在范围

- HTML 面板拆分为 `partials/*.html`
- 引入 Vite/Webpack/esbuild
- 将 `onclick` 改为事件委托或 ES Module
- 合并/去重相似 Tab 的 JS 逻辑（仅搬移，不重写）
- 修复现有 CSS 重复（如 `.todo-step-block` 重复定义）
- 修改 `README.md` 目录结构说明（可选后续）

---

## 2. 目录结构

```
static/
├── index.html                         # 导航 + 16 个 Tab HTML 面板 + <link>/<script> 引用
├── css/
│   ├── components.css                 # 全局布局、Tab 导航、公共组件
│   └── tabs/
│       ├── chat.css
│       ├── embedding.css
│       ├── rag.css                    # rag + rag-opt 共用
│       ├── ecommerce.css
│       ├── agent.css
│       ├── agent-memory.css
│       ├── agent-mysql-memory.css
│       ├── agent-tools.css            # agent-tools / ask-user / todo-write / subagent / a2a 共用
│       ├── mcp.css
│       └── multi-agent.css
└── js/
    ├── core/
    │   ├── tabs.js                    # switchTab()
    │   └── utils.js                   # escapeHtml()
    └── tabs/
        ├── chat.js
        ├── embedding.js
        ├── rag.js                     # rag + rag-opt
        ├── ecommerce.js
        ├── agent.js
        ├── agent-memory.js            # agent-memory + agent-mysql-memory
        ├── agent-tools.js
        ├── mcp.js
        ├── multi-agent.js
        ├── ask-user.js
        ├── todo-write.js
        ├── subagent.js
        └── a2a.js
```

**文件计数：** 1 HTML + 11 CSS + 15 JS = 27 个前端文件（含 `index.html`）。

---

## 3. CSS 拆分映射

从 `index.html` 内联 `<style>` 按注释块机械剪切，**不改选择器与属性值**。

| 目标文件 | 源行号（约） | 内容 |
|----------|-------------|------|
| `css/components.css` | 8–58 | `*`、`body`、`.app-container`、Tab 导航 |
| | 137–246 | `.card`、`.form-row`、`.result-box`、`.knowledge-list`、`.btn`、`.typing`、`@keyframes typing`、`.welcome-message` |
| | 257–262, 287–292 | `.rag-body`、`.sample-questions`（多 Tab 共用布局类） |
| `css/tabs/chat.css` | 60–119 | 聊天区域 |
| `css/tabs/embedding.css` | 121–136 | Embedding header/body（card 等已在 components） |
| `css/tabs/rag.css` | 248–256, 264–286, 293–342 | RAG header、flow、sample-btn、btn-rag、rag-answer（**rag-opt 复用**） |
| `css/tabs/ecommerce.css` | 344–458 | 电商客服 |
| `css/tabs/agent.css` | 460–551 | Agent 行程规划 |
| `css/tabs/agent-memory.css` | 553–667 | Agent 记忆行程 |
| `css/tabs/agent-tools.css` | 669–835 | Agent Tools + todo-step 样式（**ask-user / todo-write / subagent / a2a 复用**） |
| `css/tabs/agent-mysql-memory.css` | 837–907 | MySQL 持久化记忆 |
| `css/tabs/mcp.css` | 909–1015 | MCP Client |
| `css/tabs/multi-agent.css` | 1017–1144 | 多 Agent 协作 |

### CSS 加载顺序（`index.html` `<head>`）

```html
<link rel="stylesheet" href="/css/components.css">
<link rel="stylesheet" href="/css/tabs/chat.css">
<link rel="stylesheet" href="/css/tabs/embedding.css">
<link rel="stylesheet" href="/css/tabs/rag.css">
<link rel="stylesheet" href="/css/tabs/ecommerce.css">
<link rel="stylesheet" href="/css/tabs/agent.css">
<link rel="stylesheet" href="/css/tabs/agent-memory.css">
<link rel="stylesheet" href="/css/tabs/agent-mysql-memory.css">
<link rel="stylesheet" href="/css/tabs/agent-tools.css">
<link rel="stylesheet" href="/css/tabs/mcp.css">
<link rel="stylesheet" href="/css/tabs/multi-agent.css">
```

---

## 4. JS 拆分映射

从 `index.html` 内联 `<script>` 按注释块机械剪切，**保留顶层 `function` 声明与 `const` 全局变量**。

| 目标文件 | 源行号 | 导出符号（全局） |
|----------|--------|-----------------|
| `js/core/tabs.js` | 2028–2036 | `switchTab(tab)` |
| `js/core/utils.js` | 2239–2243 | `escapeHtml(text)` |
| `js/tabs/chat.js` | 2038–2139 | `sendMessage`、聊天相关 |
| `js/tabs/embedding.js` | 2141–2201 | `generateEmbedding`、`calculateSimilarity` |
| `js/tabs/rag.js` | 2203–2237, 2245–2287 | `fillRagQuestion`、`askRag`、`fillRagOptQuestion`、`askRagOptimized` |
| `js/tabs/ecommerce.js` | 2289–2342 | `selectEcommerceMode`、`fillEcommerceQuestion`、`askEcommerce` |
| `js/tabs/agent.js` | 2344–2383 | `fillAgentDemand`、`planTrip` |
| `js/tabs/agent-memory.js` | 2385–2595 | `MEMORY_TEST_CASES`、`MYSQL_MEMORY_TEST_CASES`、`fillMemoryTest`、`planTripWithMemory`、`fillMysqlMemoryTest`、`planTripWithMysqlMemory`、`clearMysqlMemory`、`listMysqlConversations` |
| `js/tabs/agent-tools.js` | 2597–2636 | `fillAgentToolsDemand`、`planWithTools` |
| `js/tabs/mcp.js` | 2638–2704 | MCP 聊天相关 |
| `js/tabs/multi-agent.js` | 2706–2776 | 多 Agent 协作相关 |
| `js/tabs/ask-user.js` | 2778–2910 | AskUserQuestion SSE 相关 |
| `js/tabs/todo-write.js` | 2912–3050 | TodoWrite SSE 相关 |
| `js/tabs/subagent.js` | 3052–3088 | `fillSubagentMessage`、`startSubagentChat` |
| `js/tabs/a2a.js` | 3090–3126 | `fillA2aMessage`、`startA2aChat` |

**注意：** `escapeHtml` 原定义在 RAG 段内（L2239），拆出后移入 `utils.js`；从 `rag.js` 中删除该函数定义，逻辑不变。

### JS 加载顺序（`index.html` `</body>` 前）

```html
<script src="/js/core/utils.js"></script>
<script src="/js/core/tabs.js"></script>
<script src="/js/tabs/chat.js"></script>
<script src="/js/tabs/embedding.js"></script>
<script src="/js/tabs/rag.js"></script>
<script src="/js/tabs/ecommerce.js"></script>
<script src="/js/tabs/agent.js"></script>
<script src="/js/tabs/agent-memory.js"></script>
<script src="/js/tabs/agent-tools.js"></script>
<script src="/js/tabs/mcp.js"></script>
<script src="/js/tabs/multi-agent.js"></script>
<script src="/js/tabs/ask-user.js"></script>
<script src="/js/tabs/todo-write.js"></script>
<script src="/js/tabs/subagent.js"></script>
<script src="/js/tabs/a2a.js"></script>
```

---

## 5. index.html 改造

### 5.1 删除

- `<style>...</style>` 整块（L7–L1145）
- `<script>...</script>` 整块（L2027–L3127）

### 5.2 保留不变

- `<head>` 中 `<meta>`、`<title>`
- `<body>` 内 Tab 导航与各 `#tab-*` 面板 HTML（L1147–L2025）
- 所有 `onclick`、`id`、`class` 属性

### 5.3 新增

- `<head>` 内 11 条 `<link rel="stylesheet">`（§3）
- `</div>`（app-container 结束）后 15 条 `<script src>`（§4）

---

## 6. 错误处理与风险

| 风险 | 缓解 |
|------|------|
| 脚本加载顺序错误导致 `escapeHtml is not defined` | `utils.js` 必须第一个加载；实施后用浏览器控制台检查 |
| CSS 遗漏导致样式缺失 | 按行号映射表逐文件核对；对比重构前后截图 |
| 全局函数未暴露 | 不用 IIFE/Module；保持 `function foo()` 顶层声明 |
| Spring Boot 静态资源 404 | 路径以 `/css/`、`/js/` 开头（相对 static 根目录） |

---

## 7. 验证清单

启动 `mvn spring-boot:run`（端口 8081），逐项确认：

| # | Tab | 验证动作 |
|---|-----|----------|
| 1 | AI 聊天 | 发送消息，流式响应正常 |
| 2 | Embedding | 生成向量 + 计算相似度 |
| 3 | RAG 基础版 | 点击示例问题并问答 |
| 4 | RAG 优化版 | 同上（需 Milvus） |
| 5 | 电商客服 RAG | 切换精准/增强模式并问答 |
| 6 | Agent 行程规划 | 点击示例并生成行程 |
| 7 | Agent 记忆行程 | 测试1/2/3 流程 |
| 8 | DB 持久化记忆 | 规划 + 查询会话 + 清除 |
| 9 | Agent Tools | 工具调用规划 |
| 10 | MCP Client | 发送 MCP 聊天 |
| 11 | 多 Agent 协作 | 发起协作请求 |
| 12 | AskUserQuestion | SSE 澄清问答流程 |
| 13 | TodoWrite | SSE 任务板 + 分步产出 |
| 14 | Subagent 编排 | 同步 HTTP 返回结果 |
| 15 | A2A 跨系统对话 | 同步 HTTP 返回结果 |
| 16 | Tab 切换 | 16 个 Tab 切换无样式错乱 |

额外检查：浏览器 DevTools → Network 确认 26 个 CSS/JS 资源均 **200**；Console 无报错。

---

## 8. 后端影响

**无。** `IndexController` 继续 `forward:/index.html`，静态资源由 Spring Boot 默认机制从 `classpath:/static/` 提供。
