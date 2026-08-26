# 会员 C 端 Auth Sheet 与居中 Toast 设计规范

**日期**: 2026-08-26
**项目**: spring-ai-demo / demo2
**状态**: 待实现

---

## 1. 背景与目标

### 1.1 背景

当前会员 C 端 Demo（`member.js` / `member.css`）存在以下 UX 问题：

1. **Toast 位置偏上**（`top: 56px`），错误提示不够醒目
2. **「我的」Tab 内联登录/注册表单**：一切换 Tab 即看到三个输入框，交互不像真实 C 端 App
3. **登录与注册共用三字段**：登录模式不应展示「头像 URL」
4. **鉴权入口未复用**：右侧订单调试面板在未登录时仅写日志，无法弹出统一登录流程；后续 C 端内下单也需要同一组件

### 1.2 目标

1. 将错误 Toast 移至**手机框屏幕中央**，增强可见性
2. 抽取可复用的 **Auth Sheet**（底部半屏弹层），供「我的」Tab 与订单相关操作共用
3. **登录 2 字段、注册 3 字段**，通过底部文字链接切换模式
4. 「我的」Tab 未登录时仅展示用户卡片，点击后打开 Sheet
5. 右侧订单操作在未登录时打开 Sheet；登录成功后**只关闭弹窗，不自动重试**

### 1.3 非目标

- 不在本次实现 C 端手机框内的商品下单 UI（后续融合）
- 不移除右侧订单调试面板
- 不新增「注册/登录成功」Toast（仅保留现有错误 Toast 机制）
- 不改造后端 API（沿用 `/demo/members/login`、`/demo/members/register`）
- 不抽取到 `js/core/` 全局组件（当前仅会员 Demo 使用）

---

## 2. 核心决策

| 决策项 | 选择 |
|--------|------|
| Toast 位置 | 手机框 `.member-phone-screen` 内垂直水平居中 |
| 弹窗形态 | 底部半屏 Sheet + 半透明遮罩 |
| 登录/注册切换 | 底部文字链接；默认「登录」模式 |
| 登录字段 | 手机号 + 密码（2 个） |
| 注册字段 | 手机号 + 密码 + 头像 URL 可选（3 个） |
| 注册成功 | Toast「注册成功」，停留注册模式，不自动登录 |
| 登录成功 | 关闭 Sheet，刷新「我的」页，执行可选 `onSuccess` 回调 |
| 订单侧登录后 | 只关闭 Sheet，**不自动重试**被拦截的操作 |
| 组件组织 | 新建 `member-auth.js`，样式扩展 `member.css` |

---

## 3. Toast 居中

### 3.1 挂载与范围

- 挂载点不变：`memberEnsureToast()` 仍 append 到 `.member-phone-screen`
- 仅影响会员 Demo 手机框内提示，不影响页面其他区域

### 3.2 样式

```css
.member-toast {
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%) scale(0.96);
    /* 移除原 top: 56px 顶部定位 */
}

.member-toast.show {
    transform: translate(-50%, -50%) scale(1);
}
```

- 略增大 padding（如 `14px 16px`）与字号（如 `14px`）
- 保留：半透明深色背景、橙色感叹号图标、圆角、阴影、`backdrop-filter`
- 动画：opacity + scale 淡入（约 0.22s）

### 3.3 行为（不变）

- `memberShowError(message)` 展示，2.8s 自动消失，点击 Toast 可关闭
- `memberRequest(..., { silent: true })` 时不弹 Toast（如 `memberLoadProfile` 自动加载）

---

## 4. Auth Sheet 组件

### 4.1 DOM 结构

挂载于 `.member-phone-screen`（与 Toast 同级，避免被 page 滚动影响）：

```html
<div class="member-auth-overlay" id="memberAuthOverlay">
  <div class="member-auth-sheet" role="dialog" aria-modal="true">
    <div class="member-auth-handle"></div>
    <button type="button" class="member-auth-close" aria-label="关闭">×</button>
    <h3 class="member-auth-title">登录</h3>
    <div class="member-auth-form">
      <!-- 动态字段 -->
    </div>
    <button type="button" class="member-auth-submit btn btn-primary">登录</button>
    <p class="member-auth-switch">
      <button type="button">还没有账号？去注册</button>
    </p>
  </div>
</div>
```

### 4.2 模式与字段

| 模式 | 标题 | 字段 | 主按钮 |
|------|------|------|--------|
| `login` | 登录 | 手机号、密码 | 登录 |
| `register` | 注册 | 手机号、密码、头像 URL（可选） | 注册 |

- 手机号：`type="tel"`，`autocomplete="username"`，placeholder「请输入手机号」
- 密码：`type="password"`，`autocomplete="current-password"` / `new-password`，placeholder「请输入密码」
- 头像 URL：仅注册模式，`placeholder="头像 URL（可选）"`

### 4.3 切换规则

- 底部链接文案：
  - 登录模式：「还没有账号？去注册」
  - 注册模式：「已有账号？去登录」
- 切换时**保留**已填写的手机号与密码，仅增减头像字段 DOM
- 打开 Sheet 时聚焦第一个输入框

### 4.4 动画与关闭

- 打开：遮罩 `opacity 0→1`；Sheet `translateY(100%)→0`，约 0.28s ease-out
- 关闭：反向动画，结束后 `display: none` 或移除 `open` class
- 关闭方式：点遮罩、点 ×、登录/注册成功后自动关闭
- Sheet 内点击不冒泡到遮罩（`stopPropagation`）

### 4.5 对外 API（`member-auth.js`）

```javascript
const MemberAuth = {
    open(options) {},   // { mode?: 'login'|'register', onSuccess?: () => void }
    close() {},
    isOpen() {}         // boolean
};
```

- `open` 默认 `mode: 'login'`
- `onSuccess` 在登录成功、Sheet 关闭后调用；注册成功不调用
- 依赖：`memberRequest`、`memberShowError`、`memberAppendLog` 等由 `member.js` 提供或在 `member-auth.js` 内调用同名全局函数（与现有 Demo 脚本风格一致）

### 4.6 业务逻辑

**登录：**

1. 校验手机号、密码非空
2. `memberRequest('/demo/members/login', { phone, password })`
3. 成功：写入 `memberToken` / `memberProfile` / `localStorage`，`memberRender()`，`MemberAuth.close()`，执行 `onSuccess`
4. 失败：由 `memberRequest` 弹居中 Toast；日志写入 `memberAppendLog`

**注册：**

1. 校验手机号、密码非空
2. `memberRequest('/demo/members/register', { phone, password, avatarUrl })`
3. 成功：`memberShowError('注册成功')` 或专用成功样式 Toast；**不关闭 Sheet、不自动登录**
4. 失败：居中 Toast + 日志

---

## 5. 「我的」Tab 改造

### 5.1 未登录

```text
┌─────────────────────────┐
│ 我的                     │
│ ┌─────────────────────┐ │
│ │ [头像] 你好，你还没登录 │ │  ← 点击打开 MemberAuth.open({ mode: 'login' })
│ │       点击登录/注册   │ │
│ └─────────────────────┘ │
└─────────────────────────┘
```

- **移除** `.member-auth-form` 内联表单
- **移除** `memberFocusLogin()`（改为打开 Sheet）
- 用户卡片保留 `cursor: pointer`

### 5.2 已登录

- 展示头像、手机号、memberId
- 底部「退出登录」按钮（`memberLogout()`）
- 点击用户卡片无操作

---

## 6. 订单侧接入

### 6.1 当前（右侧调试面板）

以下函数在未登录时改为打开 Auth Sheet，而非仅 `memberAppendLog('请先登录')`：

- `memberOrderCreate`
- `memberOrderPay`
- `memberOrderCancel`
- `memberOrderRefresh`

```javascript
if (!memberToken) {
    MemberAuth.open({ mode: 'login' });
    return;
}
```

登录成功后按决策 **不自动重试**原操作。

### 6.2 后续（C 端内下单）

- 手机框内商品/订单页触发相同 `MemberAuth.open({ mode: 'login' })`
- 本 spec 仅需保证 API 稳定，不在本次改 C 端 Tab 内容

---

## 7. 文件变更

| 文件 | 操作 |
|------|------|
| `static/js/tabs/member-auth.js` | **新建** Auth Sheet 模块 |
| `static/js/tabs/member.js` | 移除内联表单与 `memberRegister`/`memberLogin` 表单逻辑；接入 `MemberAuth`；订单函数改调 Sheet |
| `static/css/tabs/member.css` | Toast 居中；新增 overlay/sheet/form 样式；移除或保留 `.member-auth-form` 内联样式（改用于 Sheet 内） |
| `static/index.html` | 在 `member.js` **之前**引入 `member-auth.js` |

---

## 8. 测试与验收

### 8.1 手动验收清单

- [ ] 登录失败（如不存在账号）：Toast 在手机框**正中央**显示「会员不存在」
- [ ] 「我的」Tab 未登录：无内联表单，点击卡片弹出底部 Sheet
- [ ] Sheet 默认登录模式：仅 2 个输入框
- [ ] 点击「还没有账号？去注册」：切换为 3 字段，手机号/密码保留
- [ ] 注册成功：Toast 提示，Sheet 不关闭
- [ ] 登录成功：Sheet 关闭，「我的」页展示用户信息
- [ ] 未登录点「创建待支付订单」：弹出 Sheet；登录成功后 Sheet 关闭，**订单未自动创建**
- [ ] 点遮罩 / × 可关闭 Sheet
- [ ] `memberLoadProfile` 静默失败仍不弹 Toast

### 8.2 自动化

- 无新增后端测试；前端为静态 JS，本次不引入 E2E 框架

---

## 9. 参考

- 现有实现：`static/js/tabs/member.js`、`static/css/tabs/member.css`
- 会员模块设计：`docs/superpowers/specs/2026-08-24-member-module-design.md`
- 统一 JsonResult：`docs/superpowers/specs/2026-08-25-unified-json-result-design.md`
