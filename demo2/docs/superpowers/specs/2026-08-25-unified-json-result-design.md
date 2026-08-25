# demo2 统一 JsonResult 与 BusinessException 设计规范

**日期**: 2026-08-25
**项目**: spring-ai-demo / demo2
**状态**: 待实现

---

## 1. 背景与目标

### 1.1 背景

当前 `order`、`member` 模块 API 存在以下不一致：

- 成功时 Controller 直接返回 `*ResVO`（扁平 JSON）
- 失败时抛 `ResponseStatusException` 或 `*DomainException`，经 `*HttpSupport` 映射为 HTTP 4xx/5xx + Spring 默认错误体
- 各模块各自维护 `OrderDomainException` / `MemberDomainException`，异常码仅为 `NOT_FOUND / CONFLICT / BAD_REQUEST` 三类字符串枚举，无统一数字码

前端 `member.js` 通过 `res.ok` 判断成败，成功时直接读取顶层 VO 字段。

### 1.2 目标

1. 引入统一响应体 `JsonResult<T> { code, message, data }`
2. 引入分段数字异常码枚举（通用 / 会员 / 订单 / 商品预留）
3. 引入 `BusinessException`，全局处理器自动组装失败 `JsonResult`
4. 全面替换 `*DomainException` 为 `BusinessException`
5. 本次改造范围：`order`、`member` 模块后端 + `member.js` 前端 Demo

### 1.3 非目标

- 不改造 LockDemo、Agent、DelayTask 等其他 Controller
- 不改造 `order-delay.js`（订单功能已迁移至会员 C 端 Demo）
- 不改造 `/demo/delay-tasks` 接口
- 不实现 i18n；`message` 直接使用枚举 `desc` 或覆盖后的中文/英文文案
- 不在本次定义 `ProductErrorCode` 具体码值（仅预留枚举占位）

---

## 2. 核心决策

| 决策项 | 选择 |
|--------|------|
| HTTP 状态码 | **始终 200**（含业务失败） |
| 成功判定 | `code === 0` |
| 异常码格式 | 分段数字：`0` / `1xxxx` 通用 / `2xxxx` 会员 / `3xxxx` 订单 / `4xxxx` 商品 |
| 领域异常 | 全面替换为 `BusinessException`，删除 `*DomainException` |
| 构造方式 | 主路径传 `ErrorCode` 枚举；少数场景可覆盖 `message` |
| Controller 返回 | 显式 `JsonResult<T>` + `JsonResults.ok(data)` |
| 全局处理器范围 | **方案 3**：全局仅捕获 `BusinessException`，无 `assignableTypes` |
| 前端 | 同步改 `member.js`；失败提示显示 `message` |

---

## 3. 包结构与核心类型

### 3.1 新增包

```text
framework/web/
├── result/
│   ├── JsonResult.java
│   └── JsonResults.java
└── exception/
    ├── ErrorCode.java
    ├── BusinessException.java
    ├── CommonErrorCode.java
    └── GlobalExceptionHandler.java

order/service/common/
└── OrderErrorCode.java

member/service/common/
└── MemberErrorCode.java

product/service/common/          # 预留，本次可不建目录
└── ProductErrorCode.java        # 空枚举占位
```

### 3.2 ErrorCode 接口

```java
public interface ErrorCode {
    int getCode();
    String getDesc();
}
```

各模块枚举实现该接口，例如：

```java
public enum OrderErrorCode implements ErrorCode {
    ORDER_NOT_FOUND(30001, "订单不存在");
    // ...
}
```

### 3.3 JsonResult

```java
public class JsonResult<T> {
    private int code;
    private String message;
    private T data;
}
```

### 3.4 JsonResults 工厂

| 方法 | 行为 |
|------|------|
| `ok(T data)` | `code=0`, `message="success"`, `data=data` |
| `fail(ErrorCode errorCode)` | `code=枚举码`, `message=枚举desc`, `data=null` |
| `fail(int code, String message)` | 自定义 code/message |

### 3.5 BusinessException

支持三种构造方式：

```java
// 主路径：code/message 来自枚举
new BusinessException(OrderErrorCode.ORDER_NOT_FOUND)

// 覆盖 message（动态补充信息，如订单状态）
new BusinessException(OrderErrorCode.ORDER_STATUS_CONFLICT,
                      "cannot pay order in status PAID")

// 纯自定义（兜底场景）
new BusinessException(10999, "unexpected error")
```

字段：

- `int code`
- `String message`（对外展示文案，优先覆盖值，否则取枚举 desc）

---

## 4. 异常码清单

### 4.1 CommonErrorCode（1xxxx）

| 枚举 | code | desc |
|------|------|------|
| `SUCCESS` | 0 | success（仅 `JsonResults.ok` 使用，不用于抛异常） |
| `BAD_REQUEST` | 10001 | 请求参数错误 |
| `PARAM_MISSING` | 10002 | 缺少必填参数 |
| `UNAUTHORIZED` | 10003 | 未登录或登录已失效 |
| `INVALID_TOKEN` | 10004 | token 无效 |
| `INTERNAL_ERROR` | 10999 | 系统繁忙，请稍后重试 |

### 4.2 MemberErrorCode（2xxxx）

| 枚举 | code | desc |
|------|------|------|
| `PHONE_ALREADY_REGISTERED` | 20001 | 手机号已注册 |
| `MEMBER_NOT_FOUND` | 20002 | 会员不存在 |
| `PASSWORD_ERROR` | 20003 | 密码错误 |
| `MEMBER_CANNOT_LOGIN` | 20004 | 会员状态不可登录 |
| `PHONE_REQUIRED` | 20005 | 手机号不能为空 |
| `PASSWORD_REQUIRED` | 20006 | 密码不能为空 |

### 4.3 OrderErrorCode（3xxxx）

| 枚举 | code | desc |
|------|------|------|
| `ORDER_NOT_FOUND` | 30001 | 订单不存在 |
| `ORDER_STATUS_CONFLICT` | 30002 | 订单状态冲突 |
| `AMOUNT_INVALID` | 30003 | 订单金额必须大于 0 |
| `ORDER_ID_REQUIRED` | 30004 | orderId 不能为空 |
| `AMOUNT_REQUIRED` | 30005 | amount 不能为空 |
| `INVALID_DELAY` | 30006 | delay 格式无效 |

### 4.4 ProductErrorCode（4xxxx，预留）

本次仅保留空枚举或占位注释，不定义具体码值。

### 4.5 旧异常映射

| 旧行为 | 新 ErrorCode |
|--------|--------------|
| `ResponseStatusException(BAD_REQUEST, "xxx is required")` | `CommonErrorCode.PARAM_MISSING`（message 可覆盖为 `{field} is required`） |
| `AuthHttpSupport.unauthorized("missing token")` | `CommonErrorCode.INVALID_TOKEN` |
| `AuthHttpSupport.unauthorized("invalid token")` | `CommonErrorCode.INVALID_TOKEN` |
| `AuthHttpSupport.unauthorized("login expired")` | `CommonErrorCode.UNAUTHORIZED` |
| `AuthHttpSupport.unauthorized("login required")` | `CommonErrorCode.UNAUTHORIZED` |
| `AuthHttpSupport.unauthorized("invalid session")` | `CommonErrorCode.UNAUTHORIZED` |
| `phone already registered` | `MemberErrorCode.PHONE_ALREADY_REGISTERED` |
| `member not found` | `MemberErrorCode.MEMBER_NOT_FOUND` |
| `password error` | `MemberErrorCode.PASSWORD_ERROR` |
| `member cannot login` | `MemberErrorCode.MEMBER_CANNOT_LOGIN` |
| `order not found` | `OrderErrorCode.ORDER_NOT_FOUND` |
| `cannot pay/cancel order in status X` | `OrderErrorCode.ORDER_STATUS_CONFLICT`（message 带具体 status） |
| `amount must be positive` | `OrderErrorCode.AMOUNT_INVALID` |
| `invalid delay: xxx` | `OrderErrorCode.INVALID_DELAY`（message 可覆盖） |

---

## 5. 全局异常处理器（方案 3）

### 5.1 设计

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public JsonResult<Void> handleBusinessException(BusinessException ex) {
        return JsonResults.fail(ex.getCode(), ex.getMessage());
    }
}
```

### 5.2 范围说明

- **无 `assignableTypes`、无包路径限制、无标记注解**
- 任意 Controller / Interceptor 抛出 `BusinessException`，均返回 HTTP 200 + `JsonResult`
- **不**全局捕获 `Exception`：未迁移的旧 Controller 若发生 NPE 等，仍走 Spring 默认 500，避免误伤
- 后续新增业务模块（如商品）：只需返回 `JsonResult<T>` 并抛 `BusinessException`，**无需修改 Handler**

### 5.3 已知权衡

`order` / `member` 若发生未捕获的运行时异常（非 `BusinessException`），将返回 Spring 默认 500 而非 `JsonResult(10999)`。本次接受该权衡，以保持与其他 Demo Controller 的隔离。若后续需要统一兜底，可再引入 `@JsonResultApi` 标记注解做范围化兜底。

---

## 6. 模块改造

### 6.1 删除

| 文件 | 原因 |
|------|------|
| `OrderDomainException.java` | 由 `BusinessException` 替代 |
| `MemberDomainException.java` | 由 `BusinessException` 替代 |
| `OrderHttpSupport.java` | 不再需要 HTTP 状态映射 |
| `MemberHttpSupport.java` | 同上 |

### 6.2 鉴权链路

以下类改抛 `BusinessException(CommonErrorCode.xxx)`，删除对 `ResponseStatusException` 的依赖：

- `AuthHttpSupport`（可简化为直接抛异常，或保留静态工厂方法）
- `LoginRequiredInterceptor`
- `AuthSessionService`
- `LoginContextHolder`

鉴权失败示例响应：

```json
{ "code": 10003, "message": "未登录或登录已失效", "data": null }
```

### 6.3 Controller

**之前：**

```java
public OrderPlaceResVO orderPlace(@RequestBody OrderPlaceReqVO request) {
    try {
        return orderVoConvert.toPlaceRes(...);
    } catch (OrderDomainException e) {
        throw OrderHttpSupport.toHttpException(e);
    }
}
```

**之后：**

```java
public JsonResult<OrderPlaceResVO> orderPlace(@RequestBody OrderPlaceReqVO request) {
    if (request == null || request.getAmount() == null) {
        throw new BusinessException(CommonErrorCode.PARAM_MISSING, "amount is required");
    }
    return JsonResults.ok(orderVoConvert.toPlaceRes(...));
}
```

涉及 Controller：

- `OrderController`（4 个接口）
- `MemberController`（5 个接口）

### 6.4 领域层与应用层

所有原 `*DomainException` 抛点改为 `BusinessException`：

- `Order.java` / `OrderDomainService.java` / `OrderDelayParser.java`
- `Member.java` / `MemberDomainService.java` / `PasswordHasher.java` / `MemberLoginCmdExe.java`

`service.core` 允许依赖 `framework.web.exception.BusinessException` 与各模块 `*ErrorCode`。

---

## 7. 前端改造（member.js）

### 7.1 统一请求解析

```javascript
async function memberRequest(url, body) {
    const res = await memberPost(url, body);
    let result;
    try {
        result = JSON.parse(await res.text());
    } catch (e) {
        throw new Error('响应解析失败');
    }
    if (result.code !== 0) {
        throw new Error(result.message || '请求失败');
    }
    return result.data;
}
```

### 7.2 改造点

| 函数 | 改动 |
|------|------|
| `memberRegister` | 使用 `memberRequest`；失败 catch 显示 `e.message` |
| `memberLogin` | 从 `data.token`、`data.phone` 读取；失败显示 `message` |
| `memberLoadProfile` | 从 `data` 读 profile；`code` 为 10003/10004 时清空登录态 |
| `memberLogout` / `memberDeleteSession` | 改用 `memberRequest` 或手动判断 `code` |
| `memberOrderCreate` | 从 `data.orderId`、`data.taskId` 读取 |
| `memberOrderPay/Cancel/Refresh` | 成功读 `data` 或原始 JSON；失败显示 `message` |

### 7.3 不在范围

- `order-delay.js`
- `/demo/delay-tasks` 请求保持原逻辑（仍用 `res.ok`）

---

## 8. 测试策略

| 测试类 | 改动 |
|--------|------|
| `MemberDomainServiceTest` | 断言 `BusinessException` + `getCode()` |
| `OrderCmdExeTest` | 同上 |
| `AuthSessionServiceTest` | 改断言 `BusinessException(CommonErrorCode.UNAUTHORIZED)` |
| `LoginRequiredInterceptorTest` | 同上 |
| `LoginContextHolderTest` | 同上 |
| 新增 `GlobalExceptionHandlerTest` | 验证 `BusinessException` → `JsonResult` |
| 新增 `JsonResultsTest`（可选） | 验证 `ok/fail` 工厂 |

验收标准：

1. `mvn test` 全绿
2. 会员 C 端 Demo：注册 → 登录 → 下单 → 支付 → 取消 → 刷新，均可正常操作
3. 故意输错密码，页面显示 `message`（如「密码错误」），HTTP 状态仍为 200

---

## 9. 后续扩展指南

新增业务模块（如商品）步骤：

1. 在 `{module}/service/common/` 新增 `{Module}ErrorCode`（4xxxx 段）
2. Controller 返回 `JsonResult<T>`，成功用 `JsonResults.ok`
3. 领域/应用层抛 `BusinessException({Module}ErrorCode.xxx)`
4. **无需修改** `GlobalExceptionHandler`
5. 前端对接时以 `code === 0` 判断成功

---

## 10. 参考

- DDD 分包规则：`demo2/.cursor/rules/demo2-business-ddd.mdc`
- 会员模块设计：`demo2/docs/superpowers/specs/2026-08-24-member-module-design.md`
