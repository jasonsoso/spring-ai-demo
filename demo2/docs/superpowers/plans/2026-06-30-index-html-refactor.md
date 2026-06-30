# index.html 前端拆分重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `index.html` 内联 CSS/JS 按 Tab 模块拆分为独立静态文件，功能与样式零变更。

**Architecture:** 零构建静态拆分 — `index.html` 保留 HTML 面板，通过 `<link>` / `<script src>` 引入 `css/` 与 `js/` 目录下的文件；全局函数保持顶层声明以兼容 `onclick`。

**Tech Stack:** 原生 HTML/CSS/JS，Spring Boot 4.1 静态资源托管（`src/main/resources/static/`）

## Global Constraints

- **不修改功能：** API 路径、请求体、SSE 逻辑、DOM 操作行为必须与重构前一致
- **不引入构建工具：** 禁止 npm/Vite/Webpack
- **不用 ES Module：** `<script src>` 普通脚本，禁止 `type="module"`
- **保留全局函数：** 所有 `onclick` 引用的函数名不变
- **CSS/JS 内容机械搬移：** 仅去除原文件中 4 空格缩进前缀，不改选择器/属性/逻辑
- **脚本顺序：** `utils.js` → `tabs.js` → 各 `tabs/*.js`（顺序见 spec §4）
- **样式顺序：** `components.css` → 各 `tabs/*.css`（顺序见 spec §3）

**设计规范:** [docs/superpowers/specs/2026-06-30-index-html-refactor-design.md](../specs/2026-06-30-index-html-refactor-design.md)

---

### Task 1: 创建目录骨架

**Files:**
- Create: `demo2/src/main/resources/static/css/tabs/`（目录）
- Create: `demo2/src/main/resources/static/js/core/`（目录）
- Create: `demo2/src/main/resources/static/js/tabs/`（目录）

- [ ] **Step 1: 创建目录**

```bash
mkdir -p demo2/src/main/resources/static/css/tabs
mkdir -p demo2/src/main/resources/static/js/core
mkdir -p demo2/src/main/resources/static/js/tabs
```

- [ ] **Step 2: 确认目录存在**

Run: `ls demo2/src/main/resources/static/css/tabs demo2/src/main/resources/static/js/core demo2/src/main/resources/static/js/tabs`
Expected: 三个空目录

---

### Task 2: 提取 `css/components.css`

**Files:**
- Create: `demo2/src/main/resources/static/css/components.css`
- Source: `demo2/src/main/resources/static/index.html` 行 8–58, 137–246, 257–262, 287–292

**Interfaces:**
- Produces: 全局样式文件，被所有 Tab 依赖

- [ ] **Step 1: 从 index.html 剪切以下 CSS 块合并写入 `components.css`**
  - L8–58：全局 reset、body、app-container、tab 导航
  - L137–246：card、form-row、result-box、knowledge-list、btn、typing、welcome
  - L257–262：`.rag-body`
  - L287–292：`.sample-questions`

- [ ] **Step 2: 去除每行 leading 4 空格**（原 `<style>` 内缩进）

- [ ] **Step 3: 确认文件非空**

Run: `wc -l demo2/src/main/resources/static/css/components.css`
Expected: 约 150–200 行

---

### Task 3: 提取 Tab 专属 CSS（11 个文件）

**Files:**
- Create: `demo2/src/main/resources/static/css/tabs/chat.css`（L60–119）
- Create: `demo2/src/main/resources/static/css/tabs/embedding.css`（L121–136）
- Create: `demo2/src/main/resources/static/css/tabs/rag.css`（L248–256, L264–286, L293–342）
- Create: `demo2/src/main/resources/static/css/tabs/ecommerce.css`（L344–458）
- Create: `demo2/src/main/resources/static/css/tabs/agent.css`（L460–551）
- Create: `demo2/src/main/resources/static/css/tabs/agent-memory.css`（L553–667）
- Create: `demo2/src/main/resources/static/css/tabs/agent-tools.css`（L669–835）
- Create: `demo2/src/main/resources/static/css/tabs/agent-mysql-memory.css`（L837–907）
- Create: `demo2/src/main/resources/static/css/tabs/mcp.css`（L909–1015）
- Create: `demo2/src/main/resources/static/css/tabs/multi-agent.css`（L1017–1144）

- [ ] **Step 1: 按 spec §3 行号映射逐文件剪切 CSS**

- [ ] **Step 2: 去除每行 leading 4 空格**

- [ ] **Step 3: 确认 10 个 tab CSS 文件均已创建**

Run: `ls demo2/src/main/resources/static/css/tabs/ | wc -l`
Expected: 10

---

### Task 4: 提取 `js/core/` 工具脚本

**Files:**
- Create: `demo2/src/main/resources/static/js/core/tabs.js`（L2028–2036）
- Create: `demo2/src/main/resources/static/js/core/utils.js`（L2239–2243）

**Interfaces:**
- Produces: `switchTab(tab)` — 全局函数
- Produces: `escapeHtml(text)` — 全局函数，返回 HTML 转义字符串

- [ ] **Step 1: 写入 `tabs.js`**

```javascript
// ========== Tab 切换 ==========
function switchTab(tab) {
    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.tab === tab);
    });
    document.querySelectorAll('.tab-content').forEach(el => {
        el.classList.toggle('active', el.id === 'tab-' + tab);
    });
}
```

- [ ] **Step 2: 写入 `utils.js`**

```javascript
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
```

---

### Task 5: 提取 Tab 专属 JS（13 个文件）

**Files:**
- Create: `demo2/src/main/resources/static/js/tabs/chat.js`（L2038–2139）
- Create: `demo2/src/main/resources/static/js/tabs/embedding.js`（L2141–2201）
- Create: `demo2/src/main/resources/static/js/tabs/rag.js`（L2203–2237, L2245–2287，**不含** escapeHtml）
- Create: `demo2/src/main/resources/static/js/tabs/ecommerce.js`（L2289–2342）
- Create: `demo2/src/main/resources/static/js/tabs/agent.js`（L2344–2383）
- Create: `demo2/src/main/resources/static/js/tabs/agent-memory.js`（L2385–2595）
- Create: `demo2/src/main/resources/static/js/tabs/agent-tools.js`（L2597–2636）
- Create: `demo2/src/main/resources/static/js/tabs/mcp.js`（L2638–2704）
- Create: `demo2/src/main/resources/static/js/tabs/multi-agent.js`（L2706–2776）
- Create: `demo2/src/main/resources/static/js/tabs/ask-user.js`（L2778–2910）
- Create: `demo2/src/main/resources/static/js/tabs/todo-write.js`（L2912–3050）
- Create: `demo2/src/main/resources/static/js/tabs/subagent.js`（L3052–3088）
- Create: `demo2/src/main/resources/static/js/tabs/a2a.js`（L3090–3126）

- [ ] **Step 1: 按 spec §4 行号映射逐文件剪切 JS**

- [ ] **Step 2: 从 `rag.js` 中确认不含 `escapeHtml` 定义**（已移至 utils.js）

- [ ] **Step 3: 确认 13 个 tab JS 文件均已创建**

Run: `ls demo2/src/main/resources/static/js/tabs/ | wc -l`
Expected: 13

---

### Task 6: 改造 `index.html`

**Files:**
- Modify: `demo2/src/main/resources/static/index.html`

- [ ] **Step 1: 删除 `<style>...</style>` 整块（L7–L1145）**

- [ ] **Step 2: 在 `<head>` 内 `<title>` 之后插入 CSS 引用**

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

- [ ] **Step 3: 删除 `<script>...</script>` 整块（原 L2027–L3127）**

- [ ] **Step 4: 在 `</div>`（app-container 结束）与 `</body>` 之间插入 JS 引用**

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

- [ ] **Step 5: 确认 index.html 不再含内联 `<style>` 或 `<script>` 块**

Run: `rg '<style>|<script>' demo2/src/main/resources/static/index.html`
Expected: 仅匹配 `<script src=` 和 `<link rel="stylesheet"` 行，无内联块

- [ ] **Step 6: 确认行数大幅下降**

Run: `wc -l demo2/src/main/resources/static/index.html`
Expected: 约 880–920 行

---

### Task 7: 冒烟验证

**Files:** 无新增

- [ ] **Step 1: 启动应用**

Run: `cd demo2 && mvn spring-boot:run -q`
Expected: 应用在 8081 端口启动

- [ ] **Step 2: 验证首页与静态资源**

Run: `curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/`
Expected: `200`

Run: `curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/css/components.css`
Expected: `200`

Run: `curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/js/core/utils.js`
Expected: `200`

- [ ] **Step 3: 浏览器手动验证**

按 spec §7 验证清单逐项测试 16 个 Tab；DevTools Console 无 JS 报错；Network 面板 26 个 CSS/JS 资源均 200。

---

### Task 8: 提交

- [ ] **Step 1: 确认变更文件列表**

Run: `git status`
Expected: `index.html` modified + `css/` + `js/` 新增 + `docs/superpowers/` 文档

- [ ] **Step 2: 提交（用户确认后）**

```bash
git add demo2/src/main/resources/static/ demo2/docs/superpowers/specs/2026-06-30-index-html-refactor-design.md demo2/docs/superpowers/plans/2026-06-30-index-html-refactor.md
git commit -m "$(cat <<'EOF'
refactor(demo2): split index.html into modular static CSS/JS files

Improve maintainability by extracting per-tab styles and scripts without changing behavior.
EOF
)"
```
