# 统一 JsonResult 与 BusinessException · 功能归档

**归档日期**: 2026-08-26
**项目**: spring-ai-demo / demo2
**状态**: 已实现

**设计规范**: [2026-08-25-unified-json-result-design.md](../specs/2026-08-25-unified-json-result-design.md)
**实施计划**: [2026-08-25-unified-json-result.md](../plans/2026-08-25-unified-json-result.md)
**参考代码**: `com.jason.demo.demo2.framework.web`

---

## 1. 做了什么

将 `order`、`member` 模块 API 统一为 `JsonResult<T>` 响应，用分段数字错误码的 `BusinessException` 替换原 `*DomainException` 与 `ResponseStatusException`：

- 新增 `framework/web/result`：`JsonResult` + `JsonResults` 工厂
- 新增 `framework/web/exception`：`ErrorCode`、`CommonErrorCode`、`BusinessException`、`Demo2GlobalExceptionHandler`（全局 `@RestControllerAdvice`）
- 新增 `OrderErrorCode`（3xxxx）、`MemberErrorCode`（2xxxx）
- 删除 `OrderDomainException`、`MemberDomainException`、`OrderHttpSupport`、`MemberHttpSupport`
- `*VoConvert` 从 Controller 下沉到 CmdExe，CmdExe 返回 `*ResVO`
- `member.js` 改为以 `code === 0` 判定成功

---

## 2. 统一响应与异常

| 项 | 约定 |
|----|------|
| HTTP 状态码 | 业务 API 始终 200（含失败） |
| 成功判定 | `code === 0` |
| 响应体 | `{ code, message, data }` |
| 失败构造 | `JsonResults.fail(errorCode)` / `fail(code, message)` |
| 异常 | `BusinessException`（三种构造：`ErrorCode` / `ErrorCode`+覆盖 message / `int`+`message`） |
| 全局处理 | `Demo2GlobalExceptionHandler` 仅捕获 `BusinessException`，无 `Exception` 兜底 |

---

## 3. 异常码分段

| 段 | 范围 | 说明 |
|----|------|------|
| 成功 | `0` | 仅 `JsonResults.ok` |
| 通用 | `1xxxx` | `CommonErrorCode`（如 `UNAUTHORIZED=10003`、`INVALID_TOKEN=10004`） |
| 会员 | `2xxxx` | `MemberErrorCode`（如 `PASSWORD_ERROR=20003`） |
| 订单 | `3xxxx` | `OrderErrorCode`（如 `ORDER_NOT_FOUND=30001`） |
| 商品 | `4xxxx` | `ProductErrorCodeEnum`（如 `PRODUCT_NOT_FOUND=40001`） |

---

## 4. 分层约定（后续模块通用）

| 层 | 职责 | 禁止 |
|----|------|------|
| Controller | 入参校验、调 CmdExe、`JsonResults.ok(...)` | 注入 `*VoConvert`；领域编排 |
| CmdExe | 用例编排、注入 `*VoConvert`、返回 `*ResVO` | 直接处理 HTTP |
| Domain | 抛 `BusinessException({Module}ErrorCode.xxx)` | 自定义 `*DomainException` |

鉴权链路（`AuthHttpSupport` / `LoginRequiredInterceptor` / `AuthSessionService` / `LoginContextHolder`）同样抛 `BusinessException(CommonErrorCode.xxx)`。

---

## 5. 测试

- 新增 `JsonResultsTest`、`Demo2GlobalExceptionHandlerTest`
- 更新 `MemberDomainServiceTest`、`OrderCmdExeTest`、`MemberCmdExeTest`、`AuthSessionServiceTest`、`LoginContextHolderTest`、`LoginRequiredInterceptorTest` 的断言为 `BusinessException.getCode()`

---

## 6. 命名说明

设计 spec 原称 `GlobalExceptionHandler`，实现时命名为 `Demo2GlobalExceptionHandler`（避免与其他 Demo 模块 / Spring 默认命名冲突），测试类同步为 `Demo2GlobalExceptionHandlerTest`。

---

## 7. 后续新增模块流程

1. 在 `{module}/service/common/` 新增 `{Module}ErrorCode`（按段取值）
2. Controller 返回 `JsonResult<T>`，只做校验 + 调 CmdExe + `JsonResults.ok`
3. CmdExe 注入 `{Module}VoConvert`，返回 `*ResVO`
4. 领域 / 应用层抛 `BusinessException({Module}ErrorCode.xxx)`
5. 无需修改 `Demo2GlobalExceptionHandler`
6. 前端以 `code === 0` 判定成功
