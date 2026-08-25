# demo2 会员模块设计规范

**日期**: 2026-08-24
**项目**: spring-ai-demo / demo2
**状态**: 已实现

---

## 1. 背景与目标

### 1.1 背景

`demo2` 已完成 `order` 模块 DDD 分包重构，并形成业务模块分层规则。下一步需要新增简单会员模块，支撑 C 端注册、登录、个人中心，以及后续商品列表和订单流程。

### 1.2 目标

1. 新增 `member` 业务模块，提供手机号 + 密码注册、登录、退出和个人中心能力。
2. 登录成功后生成不透明 token，并在 Redis 存储登录会话；删除 Redis key 后立即视为未登录。
3. 新增公共鉴权能力 `framework.auth`，通过 `@LoginRequired` 标记需要登录的接口。
4. 鉴权通过后，将当前登录主体写入基于 `TransmittableThreadLocal` 的上下文，业务代码可读取 `memberId`、手机号和 token。
5. 订单接口接入登录鉴权，下单、支付、查询、取消必须登录，并按 `memberId` 记录和校验订单归属。
6. 新增静态页面 Tab：左侧手机 C 端，右侧操作日志、登录态展示、Redis 失效模拟和模拟支付。

### 1.3 非目标

- 本次不实现手机号 + 短信验证码登录，仅预留后续演进空间。
- 本次不实现真实商品接口，首页商品先静态展示。
- 本次不实现我的订单列表接口，订单 Tab 先静态展示，后续出接口再接。
- 本次不引入 JWT。token 是随机不透明值，用户信息以 Redis 会话为准。
- 本次不引入 Spring Security，保持 demo 代码轻量直观。

---

## 2. 总体架构

### 2.1 模块关系

公共鉴权能力放在 `com.jason.demo.demo2.framework.auth`。业务模块单向依赖公共鉴权能力，公共框架不依赖业务模块。

```text
framework.auth
   ^
   |-- member 模块：登录成功后创建/删除会话
   |-- order 模块：用 @LoginRequired 鉴权，并从上下文读取当前登录主体
```

依赖约束：

- `framework.auth` 不 import `member.*`
- `member` 可以 import `framework.auth`
- `order` 可以 import `framework.auth`
- `order` 不直接依赖 `member`

这样可以避免公共框架反向依赖业务模块，也避免订单模块通过鉴权间接耦合会员领域。

### 2.2 运行链路

```text
[登录成功]
  -> MemberLoginCmdExe 校验手机号和密码
  -> AuthSessionService 生成 token
  -> Redis 写入 demo2:auth:session:{token}
  -> 返回 token、memberId、phone

[访问受保护接口]
  -> Controller 方法标 @LoginRequired
  -> LoginRequiredInterceptor 解析 Authorization: Bearer <token>
  -> Redis 读取登录会话
  -> LoginContextHolder 写入 LoginPrincipal
  -> Controller / CmdExe 读取当前登录主体
  -> 请求结束清理 LoginContextHolder
```

---

## 3. 公共鉴权设计

### 3.1 包结构

```text
framework/auth/LoginRequired.java
framework/auth/LoginPrincipal.java
framework/auth/LoginContextHolder.java
framework/auth/AuthProperties.java
framework/auth/AuthSession.java
framework/auth/AuthSessionService.java
framework/auth/LoginRequiredInterceptor.java
framework/auth/AuthWebMvcConfiguration.java
framework/auth/AuthHttpSupport.java
```

### 3.2 核心职责

| 类型 | 职责 |
|------|------|
| `@LoginRequired` | 标记 Controller 方法必须登录后访问 |
| `LoginPrincipal` | 当前登录主体快照，包含 `memberId`、`phone`、`token` |
| `LoginContextHolder` | 使用 `TransmittableThreadLocal<LoginPrincipal>` 保存和清理上下文 |
| `AuthProperties` | 配置 Redis key 前缀和 session TTL |
| `AuthSessionService` | 生成 token、写 Redis、读 Redis、删除 Redis |
| `LoginRequiredInterceptor` | 拦截带注解的接口，校验 token 并写入上下文 |
| `AuthWebMvcConfiguration` | 注册 MVC 拦截器 |
| `AuthHttpSupport` | 鉴权失败的 HTTP 异常转换 |

### 3.3 配置

```properties
app.auth.session-key-prefix=demo2:auth:session:
app.auth.session-ttl=24h
```

### 3.4 Redis 会话

Key：

```text
demo2:auth:session:{token}
```

Value：

```json
{
  "memberId": 123456789012345678,
  "phone": "13888999999",
  "avatarUrl": "https://example.com/avatar.png",
  "loginAt": "2026-08-24T11:00:00"
}
```

TTL 默认 24 小时。删除该 key 后，下一次访问带 `@LoginRequired` 的接口返回 401。

### 3.5 鉴权失败

| 场景 | HTTP | 信息 |
|------|------|------|
| 缺少 `Authorization` | 401 | `missing token` |
| 不是 `Bearer` token | 401 | `invalid token` |
| Redis 会话不存在或过期 | 401 | `login expired` |
| 业务代码要求登录但上下文缺失 | 401 | `login required` |

---

## 4. 会员模块设计

### 4.1 包结构

```text
member/app/controller/MemberController.java
member/app/executor/MemberRegisterCmdExe.java
member/app/executor/MemberLoginCmdExe.java
member/app/executor/MemberLogoutCmdExe.java
member/app/executor/MemberGetProfileCmdExe.java
member/app/vo/req/RegisterMemberReqVO.java
member/app/vo/req/LoginMemberReqVO.java
member/app/vo/req/LogoutMemberReqVO.java
member/app/vo/req/GetMemberProfileReqVO.java
member/app/vo/res/RegisterMemberResVO.java
member/app/vo/res/LoginMemberResVO.java
member/app/vo/res/LogoutMemberResVO.java
member/app/vo/res/GetMemberProfileResVO.java
member/app/convert/MemberVoConvert.java
member/app/support/MemberHttpSupport.java
member/service/common/MemberStatus.java
member/service/core/domain/Member.java
member/service/core/MemberDomainService.java
member/service/core/MemberDomainException.java
member/service/infrastructure/dao/entity/MemberDO.java
member/service/infrastructure/dao/mapper/MemberMapper.java
member/service/infrastructure/repository/MemberRepository.java
member/service/infrastructure/repository/convert/MemberDoConvert.java
```

### 4.2 数据表

```text
id              BIGINT AUTO_INCREMENT PRIMARY KEY
member_id       BIGINT NOT NULL UNIQUE
phone           VARCHAR(32) NOT NULL UNIQUE
password_hash   VARCHAR(255) NOT NULL
avatar_url      VARCHAR(512) NULL
status          VARCHAR(32) NOT NULL
created_at      DATETIME NOT NULL
updated_at      DATETIME NOT NULL
```

`id` 是数据库自增主键，仅用于数据库内部标识。`member_id` 是业务 ID，由现有雪花算法生成，API、Redis 会话、上下文和订单关联都使用 `member_id`。

### 4.3 领域行为

| 行为 | 规则 |
|------|------|
| 注册 | 手机号唯一；密码不能为空；创建 `NORMAL` 状态会员；头像可为空，前端使用默认头像兜底 |
| 登录 | 手机号存在、密码正确、状态为 `NORMAL` 才允许登录 |
| 退出 | 删除当前 token 对应的 Redis 会话 |
| 查看个人中心 | 从登录上下文读取当前 `memberId`，再查询会员信息 |

密码存储使用哈希值，不保存明文密码。本次不为密码哈希额外引入 Spring Security，使用 JDK 标准 `PBKDF2WithHmacSHA256` 实现带盐哈希，存储格式为 `pbkdf2$iterations$saltBase64$hashBase64`。

### 4.4 会员接口

| 接口 | 鉴权 | 请求 | 响应 |
|------|------|------|------|
| `POST /demo/members/register` | 否 | `phone`、`password`、`avatarUrl` 可选 | `memberId`、`phone`、`avatarUrl`、`status` |
| `POST /demo/members/login` | 否 | `phone`、`password` | `token`、`memberId`、`phone`、`avatarUrl`、`expiresInSeconds` |
| `POST /demo/members/logout` | 是 | 空 body | `success` |
| `POST /demo/members/getProfile` | 是 | 空 body | `memberId`、`phone`、`avatarUrl`、`status` |
| `POST /demo/members/deleteSession` | 否，演示用 | `token` | `success` |

`deleteSession` 只用于右侧操作面板模拟 Redis 登录态被删除，不作为正式业务登出接口。

### 4.5 领域异常

| 场景 | HTTP |
|------|------|
| 手机号已注册 | 409 |
| 手机号不存在 | 404 |
| 密码错误 | 400 |
| 会员状态不可登录 | 409 |

---

## 5. 订单接入设计

### 5.1 接口鉴权

以下接口加 `@LoginRequired`：

| 接口 | 变化 |
|------|------|
| `POST /demo/orders/orderPlace` | 从 `LoginContextHolder` 读取 `memberId` 并写入订单 |
| `POST /demo/orders/pay` | 校验订单归属当前 `memberId` |
| `POST /demo/orders/get` | 校验订单归属当前 `memberId` |
| `POST /demo/orders/cancel` | 校验订单归属当前 `memberId` |

### 5.2 订单表调整

订单表增加：

```text
member_id BIGINT NOT NULL
```

下单时写入当前登录会员的 `memberId`。支付、查询、取消时校验订单归属，避免一个会员操作另一个会员的订单。

### 5.3 不实现我的订单接口

本次不新增 `POST /demo/orders/myList`。前端订单 Tab 先展示静态订单或空状态，后续订单列表需求明确后再接真实接口。

---

## 6. 前端 Tab 设计

### 6.1 静态资源

新增：

```text
src/main/resources/static/js/tabs/member.js
src/main/resources/static/css/tabs/member.css
```

修改：

```text
src/main/resources/static/index.html
```

新增 Tab 按钮：`会员 C 端 Demo`。

### 6.2 页面布局

页面采用左右布局：

```text
左侧：手机 C 端预览
右侧：操作日志 + 当前登录态 + Redis 失效模拟 + 模拟支付
```

手机 C 端底部有三个入口：

| Tab | 本次功能 |
|-----|----------|
| 首页 | 静态商品卡片，例如咖啡、奶茶、点心 |
| 订单 | 静态我的订单/空状态，后续再接真实接口 |
| 我的 | 未登录展示默认头像和登录引导；已登录展示手机号、头像和退出登录 |

### 6.3 我的页交互

未登录：

```text
[默认头像]
你好，你还没登录
点击此区域登录/注册
```

点击用户区域后，打开登录/注册面板。

已登录：

```text
[头像，优先使用 avatarUrl，缺失时使用默认头像]
你好：13888999999
memberId: 123456789012345678
[退出登录]
```

登录成功后，前端保存 token，并在后续请求中带上：

```text
Authorization: Bearer {token}
```

### 6.4 右侧操作区

| 区域 | 功能 |
|------|------|
| 操作日志 | 展示注册、登录、退出、鉴权失败、Redis session 删除等事件 |
| 当前登录态 | 展示 token、Redis key、手机号、memberId、TTL 文案 |
| Redis 失效模拟 | 删除当前 token 对应 session，再访问受保护接口应返回 401 |
| 模拟支付 | 调用订单支付接口；未登录返回 401，已登录才允许继续 |

---

## 7. 测试设计

### 7.1 单元测试

| 测试 | 覆盖 |
|------|------|
| `MemberDomainServiceTest` | 注册唯一性、登录密码校验、状态校验 |
| `AuthSessionServiceTest` | token 生成、Redis 写入、读取、删除、TTL 配置 |
| `LoginContextHolderTest` | set/get/clear，避免上下文残留 |
| `OrderCmdExeTest` | 下单写入 `memberId`，支付/查询/取消校验归属 |

### 7.2 集成或轻量验证

| 场景 | 期望 |
|------|------|
| 未带 token 访问订单接口 | 401 |
| 登录后访问 `getProfile` | 返回当前 `memberId` 和手机号 |
| 删除 Redis session 后访问 `getProfile` | 401 |
| A 会员访问 B 会员订单 | 404，避免暴露他人订单是否存在 |

### 7.3 前端手工验证

1. 打开会员 Tab，进入“我的”，未登录展示默认头像和登录引导。
2. 注册手机号 `13888999999`，成功后可登录。
3. 登录后“我的”展示手机号和头像，右侧展示 token 和 Redis key。
4. 删除 Redis session 后，再访问个人中心或订单支付，显示 401 并引导重新登录。
5. 首页和订单 Tab 可正常切换，首页商品和订单列表为静态内容。

---

## 8. 实施顺序建议

1. 新增 `framework.auth`，完成注解、上下文、Redis 会话和 MVC 拦截器。
2. 新增 `member` DDD 模块，完成表对象、仓储、领域服务和 CmdExe。
3. 完成会员 Controller 和异常映射。
4. 改造订单表和订单用例，接入 `memberId` 归属。
5. 新增会员 C 端静态 Tab。
6. 补充测试和手工验证脚本。

---

## 9. 已确认决策

| 主题 | 决策 |
|------|------|
| 登录方式 | 手机号 + 密码 |
| 后续登录方式 | 手机号 + 短信验证码，当前不实现 |
| Token | 随机不透明 token |
| 登录态 | Redis session，删除 key 即失效 |
| TTL | 可配置，默认 24 小时 |
| 鉴权方式 | `@LoginRequired` + Spring MVC 拦截器 |
| 鉴权位置 | `framework.auth` |
| 上下文 | `TransmittableThreadLocal<LoginPrincipal>` |
| 会员字段 | `id` 自增主键 + `member_id` 雪花业务 ID + `avatar_url` 头像 URL |
| 前端 | 新 Tab，左侧手机 C 端，右侧日志和模拟操作 |
| 我的订单 | 本次前端静态展示，后续再接真实接口 |
