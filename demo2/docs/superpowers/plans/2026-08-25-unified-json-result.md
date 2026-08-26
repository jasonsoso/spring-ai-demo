# Unified JsonResult & BusinessException Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Unify `order` and `member` API responses as `JsonResult<T>` with segmented numeric error codes, replace `*DomainException` with global `BusinessException` handling, move `*VoConvert` into CmdExe, and update `member.js` to treat `code === 0` as success.

**Architecture:** Add `framework.web.result` and `framework.web.exception` with `JsonResult`, `JsonResults`, `BusinessException`, and a global `@RestControllerAdvice` that only handles `BusinessException` (HTTP 200 always). Controllers stay thin (validate → CmdExe → `JsonResults.ok`); CmdExe owns orchestration and MapStruct VO conversion. Auth interceptors throw the same `BusinessException` codes.

**Tech Stack:** Java 21, Spring Boot 4.x, Spring MVC, MapStruct, JUnit 5, Mockito, vanilla JS (`member.js`).

## Global Constraints

- HTTP status is **always 200** for business APIs in scope; clients use `JsonResult.code`, success when `code === 0`.
- Error code segments: `0` success, `1xxxx` common, `2xxxx` member, `3xxxx` order, `4xxxx` product (reserved).
- Replace `OrderDomainException` / `MemberDomainException` entirely with `BusinessException`.
- `BusinessException` constructors: `(ErrorCode)`, `(ErrorCode, overrideMessage)`, `(int code, String message)`.
- Global handler captures **only** `BusinessException` — no global `Exception` fallback.
- Controller must **not** inject `*VoConvert`; CmdExe returns `*ResVO`.
- Do not modify LockDemo, Agent, DelayTask controllers, `order-delay.js`, or `/demo/delay-tasks`.
- Spec reference: `demo2/docs/superpowers/specs/2026-08-25-unified-json-result-design.md`.

---

## File Structure

### Create

- `demo2/src/main/java/com/jason/demo/demo2/framework/web/result/JsonResult.java`
- `demo2/src/main/java/com/jason/demo/demo2/framework/web/result/JsonResults.java`
- `demo2/src/main/java/com/jason/demo/demo2/framework/web/exception/ErrorCode.java`
- `demo2/src/main/java/com/jason/demo/demo2/framework/web/exception/BusinessException.java`
- `demo2/src/main/java/com/jason/demo/demo2/framework/web/exception/CommonErrorCode.java`
- `demo2/src/main/java/com/jason/demo/demo2/framework/web/exception/GlobalExceptionHandler.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/common/OrderErrorCode.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/service/common/MemberErrorCode.java`
- `demo2/src/test/java/com/jason/demo/demo2/framework/web/result/JsonResultsTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/framework/web/exception/GlobalExceptionHandlerTest.java`

### Modify

- `demo2/src/main/java/com/jason/demo/demo2/framework/auth/web/AuthHttpSupport.java`
- `demo2/src/main/java/com/jason/demo/demo2/framework/auth/service/AuthSessionService.java`
- `demo2/src/main/java/com/jason/demo/demo2/framework/auth/context/LoginContextHolder.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/core/domain/Order.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/core/OrderDomainService.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/support/OrderDelayParser.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderPlaceCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderPaySuccessCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderGetCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderCancelCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/controller/OrderController.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/service/core/domain/Member.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/service/core/MemberDomainService.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/service/core/PasswordHasher.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/app/executor/MemberRegisterCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/app/executor/MemberLoginCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/app/executor/MemberGetProfileCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/app/executor/MemberLogoutCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/app/controller/MemberController.java`
- `demo2/src/main/resources/static/js/tabs/member.js`
- `demo2/src/test/java/com/jason/demo/demo2/framework/auth/AuthSessionServiceTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/framework/auth/LoginContextHolderTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/framework/auth/LoginRequiredInterceptorTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/member/service/core/MemberDomainServiceTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/member/MemberCmdExeTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/order/OrderCmdExeTest.java`
- `demo2/docs/superpowers/specs/2026-08-25-unified-json-result-design.md` — set status to 已实现 after completion

### Delete

- `demo2/src/main/java/com/jason/demo/demo2/order/service/core/OrderDomainException.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/service/core/MemberDomainException.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/support/OrderHttpSupport.java`
- `demo2/src/main/java/com/jason/demo/demo2/member/app/support/MemberHttpSupport.java`

---

## Interfaces Produced Across Tasks

```java
// framework.web
public interface ErrorCode { int getCode(); String getDesc(); }

public class JsonResult<T> {
    private int code;
    private String message;
    private T data;
    // getters/setters or Lombok @Data
}

public final class JsonResults {
    public static <T> JsonResult<T> ok(T data);
    public static <T> JsonResult<T> fail(ErrorCode errorCode);
    public static <T> JsonResult<T> fail(int code, String message);
}

public class BusinessException extends RuntimeException {
    public BusinessException(ErrorCode errorCode);
    public BusinessException(ErrorCode errorCode, String overrideMessage);
    public BusinessException(int code, String message);
    public int getCode();
    // getMessage() returns display message
}

// CmdExe return types after migration
public OrderPlaceResVO execute(BigDecimal amount, Duration delay);
public PayOrderResVO execute(long orderId);
public GetOrderResVO execute(long orderId);
public CancelOrderResVO execute(long orderId);
public RegisterMemberResVO execute(String phone, String password, String avatarUrl);
public LoginMemberResVO execute(String phone, String password);
public GetMemberProfileResVO execute();
public LogoutMemberResVO logout();
public DeleteSessionResVO deleteSession(String token);
```

---

### Task 1: Framework Web Core (JsonResult + BusinessException)

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/web/exception/ErrorCode.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/web/exception/CommonErrorCode.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/web/exception/BusinessException.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/web/result/JsonResult.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/web/result/JsonResults.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/framework/web/result/JsonResultsTest.java`

**Interfaces:**
- Consumes: none.
- Produces: `ErrorCode`, `CommonErrorCode`, `BusinessException`, `JsonResult`, `JsonResults`.

- [x] **Step 1: Write failing JsonResultsTest**

```java
package com.jason.demo.demo2.framework.web.result;

import com.jason.demo.demo2.framework.web.exception.CommonErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JsonResultsTest {

    @Test
    void ok_wrapsDataWithSuccessCode() {
        JsonResult<String> result = JsonResults.ok("hello");

        assertEquals(0, result.getCode());
        assertEquals("success", result.getMessage());
        assertEquals("hello", result.getData());
    }

    @Test
    void fail_fromErrorCode() {
        JsonResult<Void> result = JsonResults.fail(CommonErrorCode.UNAUTHORIZED);

        assertEquals(10003, result.getCode());
        assertEquals("未登录或登录已失效", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void fail_fromCustomCodeAndMessage() {
        JsonResult<Void> result = JsonResults.fail(10002, "amount is required");

        assertEquals(10002, result.getCode());
        assertEquals("amount is required", result.getMessage());
        assertNull(result.getData());
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd demo2 && mvn -q test -Dtest=JsonResultsTest`
Expected: FAIL — classes not found.

- [x] **Step 3: Implement framework web core**

`ErrorCode.java`:

```java
package com.jason.demo.demo2.framework.web.exception;

public interface ErrorCode {
    int getCode();
    String getDesc();
}
```

`CommonErrorCode.java` — implement all codes from spec §4.1 (`SUCCESS(0,"success")` through `INTERNAL_ERROR(10999,...)`).

`BusinessException.java`:

```java
package com.jason.demo.demo2.framework.web.exception;

public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode.getCode(), errorCode.getDesc());
    }

    public BusinessException(ErrorCode errorCode, String overrideMessage) {
        this(errorCode.getCode(), overrideMessage);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
```

`JsonResult.java` — plain POJO with `code`, `message`, `data` fields and getters/setters.

`JsonResults.java`:

```java
package com.jason.demo.demo2.framework.web.result;

import com.jason.demo.demo2.framework.web.exception.CommonErrorCode;
import com.jason.demo.demo2.framework.web.exception.ErrorCode;

public final class JsonResults {

    private JsonResults() {
    }

    public static <T> JsonResult<T> ok(T data) {
        JsonResult<T> result = new JsonResult<>();
        result.setCode(CommonErrorCode.SUCCESS.getCode());
        result.setMessage(CommonErrorCode.SUCCESS.getDesc());
        result.setData(data);
        return result;
    }

    public static <T> JsonResult<T> fail(ErrorCode errorCode) {
        return fail(errorCode.getCode(), errorCode.getDesc());
    }

    public static <T> JsonResult<T> fail(int code, String message) {
        JsonResult<T> result = new JsonResult<>();
        result.setCode(code);
        result.setMessage(message);
        result.setData(null);
        return result;
    }
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `cd demo2 && mvn -q test -Dtest=JsonResultsTest`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/framework/web/ \
        demo2/src/test/java/com/jason/demo/demo2/framework/web/result/JsonResultsTest.java
git commit -m "feat(demo2): add JsonResult, JsonResults, and BusinessException core"
```

---

### Task 2: Module Error Codes + GlobalExceptionHandler

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/order/service/common/OrderErrorCode.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/member/service/common/MemberErrorCode.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/web/exception/GlobalExceptionHandler.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/framework/web/exception/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: Task 1 types.
- Produces: `OrderErrorCode`, `MemberErrorCode`, `GlobalExceptionHandler`.

- [x] **Step 1: Write failing GlobalExceptionHandlerTest**

```java
package com.jason.demo.demo2.framework.web.exception;

import com.jason.demo.demo2.framework.web.result.JsonResult;
import com.jason.demo.demo2.order.service.common.OrderErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleBusinessException_returnsOkJsonResult() {
        JsonResult<Void> result = handler.handleBusinessException(
                new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));

        assertEquals(30001, result.getCode());
        assertEquals("订单不存在", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void handleBusinessException_preservesOverrideMessage() {
        JsonResult<Void> result = handler.handleBusinessException(
                new BusinessException(OrderErrorCode.ORDER_STATUS_CONFLICT,
                        "cannot pay order in status PAID"));

        assertEquals(30002, result.getCode());
        assertEquals("cannot pay order in status PAID", result.getMessage());
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `cd demo2 && mvn -q test -Dtest=GlobalExceptionHandlerTest`
Expected: FAIL

- [x] **Step 3: Implement error enums and handler**

`OrderErrorCode.java` — all codes from spec §4.3 (`ORDER_NOT_FOUND(30001,...)` … `INVALID_DELAY(30006,...)`).

`MemberErrorCode.java` — all codes from spec §4.2.

`GlobalExceptionHandler.java`:

```java
package com.jason.demo.demo2.framework.web.exception;

import com.jason.demo.demo2.framework.web.result.JsonResult;
import com.jason.demo.demo2.framework.web.result.JsonResults;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public JsonResult<Void> handleBusinessException(BusinessException ex) {
        return JsonResults.fail(ex.getCode(), ex.getMessage());
    }
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `cd demo2 && mvn -q test -Dtest=GlobalExceptionHandlerTest`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/framework/web/exception/GlobalExceptionHandler.java \
        demo2/src/main/java/com/jason/demo/demo2/order/service/common/OrderErrorCode.java \
        demo2/src/main/java/com/jason/demo/demo2/member/service/common/MemberErrorCode.java \
        demo2/src/test/java/com/jason/demo/demo2/framework/web/exception/GlobalExceptionHandlerTest.java
git commit -m "feat(demo2): add module error codes and global BusinessException handler"
```

---

### Task 3: Auth Chain Migration

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/framework/auth/web/AuthHttpSupport.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/framework/auth/service/AuthSessionService.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/framework/auth/context/LoginContextHolder.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/framework/auth/AuthSessionServiceTest.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/framework/auth/LoginContextHolderTest.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/framework/auth/LoginRequiredInterceptorTest.java`

**Interfaces:**
- Consumes: `BusinessException`, `CommonErrorCode`.
- Produces: auth classes throw `BusinessException` instead of `ResponseStatusException`.

- [x] **Step 1: Update AuthHttpSupport**

Replace `ResponseStatusException` factories with:

```java
public static BusinessException unauthorized() {
    return new BusinessException(CommonErrorCode.UNAUTHORIZED);
}

public static BusinessException invalidToken() {
    return new BusinessException(CommonErrorCode.INVALID_TOKEN);
}
```

Map call sites per spec §4.5:
- `"missing token"` / `"invalid token"` → `invalidToken()`
- `"login expired"` / `"login required"` / `"invalid session"` → `unauthorized()`

- [x] **Step 2: Update AuthSessionService and LoginContextHolder**

Replace `throw AuthHttpSupport.unauthorized(...)` with the new static methods (no string messages needed — use enum desc).

- [x] **Step 3: Update auth tests**

Replace `ResponseStatusException` assertions:

```java
BusinessException ex = assertThrows(BusinessException.class, () -> service.requireSession("gone"));
assertEquals(CommonErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
```

Apply same pattern in `LoginContextHolderTest` and `LoginRequiredInterceptorTest`.

- [x] **Step 4: Run auth tests**

Run: `cd demo2 && mvn -q test -Dtest=AuthSessionServiceTest,LoginContextHolderTest,LoginRequiredInterceptorTest`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/framework/auth/ \
        demo2/src/test/java/com/jason/demo/demo2/framework/auth/
git commit -m "refactor(demo2): migrate auth failures to BusinessException"
```

---

### Task 4: Domain Layer — Replace *DomainException

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/order/service/core/domain/Order.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/order/service/core/OrderDomainService.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/order/app/support/OrderDelayParser.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/member/service/core/domain/Member.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/member/service/core/MemberDomainService.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/member/service/core/PasswordHasher.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/member/app/executor/MemberLoginCmdExe.java`
- Delete: `demo2/src/main/java/com/jason/demo/demo2/order/service/core/OrderDomainException.java`
- Delete: `demo2/src/main/java/com/jason/demo/demo2/member/service/core/MemberDomainException.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/member/service/core/MemberDomainServiceTest.java`

**Interfaces:**
- Consumes: `BusinessException`, `OrderErrorCode`, `MemberErrorCode`.
- Produces: domain/service classes throw `BusinessException`.

- [x] **Step 1: Update MemberDomainServiceTest for BusinessException**

```java
BusinessException ex = assertThrows(BusinessException.class,
        () -> service.register(member));
assertEquals(MemberErrorCode.PHONE_ALREADY_REGISTERED.getCode(), ex.getCode());
```

- [x] **Step 2: Replace throws in order domain**

Examples:

```java
// Order.java
throw new BusinessException(OrderErrorCode.AMOUNT_INVALID);

// OrderDomainService.java
.orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
throw new BusinessException(OrderErrorCode.ORDER_STATUS_CONFLICT,
        "cannot pay order in status " + latest.getStatus());

// OrderDelayParser.java
throw new BusinessException(OrderErrorCode.INVALID_DELAY, "invalid delay: " + raw);
```

- [x] **Step 3: Replace throws in member domain**

```java
// Member.java
throw new BusinessException(MemberErrorCode.PHONE_REQUIRED);
throw new BusinessException(MemberErrorCode.MEMBER_CANNOT_LOGIN);

// MemberDomainService.java
throw new BusinessException(MemberErrorCode.PHONE_ALREADY_REGISTERED);
.orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));

// PasswordHasher.java
throw new BusinessException(MemberErrorCode.PASSWORD_REQUIRED);

// MemberLoginCmdExe.java
throw new BusinessException(MemberErrorCode.PASSWORD_ERROR);
```

- [x] **Step 4: Delete OrderDomainException and MemberDomainException**

- [x] **Step 5: Run domain tests**

Run: `cd demo2 && mvn -q test -Dtest=MemberDomainServiceTest`
Expected: PASS

- [x] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/order/ \
        demo2/src/main/java/com/jason/demo/demo2/member/ \
        demo2/src/test/java/com/jason/demo/demo2/member/service/core/MemberDomainServiceTest.java
git commit -m "refactor(demo2): replace domain exceptions with BusinessException"
```

---

### Task 5: Order CmdExe — VoConvert Inside Executor

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderPlaceCmdExe.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderPaySuccessCmdExe.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderGetCmdExe.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderCancelCmdExe.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/order/OrderCmdExeTest.java`

**Interfaces:**
- Consumes: `OrderVoConvert`, existing domain services.
- Produces: CmdExe methods return `*ResVO`.

- [x] **Step 1: Update OrderCmdExeTest imports and assertions**

Change `OrderPlaceResult` assertions to `OrderPlaceResVO`:

```java
OrderPlaceResVO result = exe.execute(new BigDecimal("9.90"), Duration.ofSeconds(10));
assertEquals(55L, result.getOrderId());
assertEquals(77L, result.getTaskId());
```

Mock `OrderVoConvert` in tests or use MapStruct generated impl via `@ExtendWith(SpringExtension.class)` only if needed — prefer manual stub:

```java
@Mock
private OrderVoConvert orderVoConvert;

when(orderVoConvert.toPlaceRes(any())).thenAnswer(inv -> {
    OrderPlaceResult r = inv.getArgument(0);
    OrderPlaceResVO vo = new OrderPlaceResVO();
    vo.setOrderId(r.getOrderId());
    vo.setTaskId(r.getTaskId());
    // ...
    return vo;
});
```

- [x] **Step 2: Refactor OrderPlaceCmdExe**

Inject `OrderVoConvert`; keep internal `OrderPlaceResult` assembly; return `orderVoConvert.toPlaceRes(result)`.

- [x] **Step 3: Refactor pay/get/cancel executors**

Each injects `OrderVoConvert` and returns `PayOrderResVO` / `GetOrderResVO` / `CancelOrderResVO`.

- [x] **Step 4: Run order CmdExe tests**

Run: `cd demo2 && mvn -q test -Dtest=OrderCmdExeTest`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/order/app/executor/ \
        demo2/src/test/java/com/jason/demo/demo2/order/OrderCmdExeTest.java
git commit -m "refactor(demo2): move order VoConvert into CmdExe"
```

---

### Task 6: Member CmdExe — VoConvert Inside Executor

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/member/app/executor/MemberRegisterCmdExe.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/member/app/executor/MemberLoginCmdExe.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/member/app/executor/MemberGetProfileCmdExe.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/member/app/executor/MemberLogoutCmdExe.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/member/MemberCmdExeTest.java`

**Interfaces:**
- Consumes: `MemberVoConvert`.
- Produces:
  - `RegisterMemberResVO execute(String phone, String password, String avatarUrl)`
  - `LoginMemberResVO execute(String phone, String password)`
  - `GetMemberProfileResVO execute()`
  - `LogoutMemberResVO logout()`
  - `DeleteSessionResVO deleteSession(String token)`

- [x] **Step 1: Split MemberLogoutCmdExe into two explicit methods**

```java
public LogoutMemberResVO logout() {
    String token = LoginContextHolder.require().token();
    LogoutMemberResVO res = new LogoutMemberResVO();
    res.setSuccess(authSessionService.deleteSession(token));
    return res;
}

public DeleteSessionResVO deleteSession(String token) {
    DeleteSessionResVO res = new DeleteSessionResVO();
    res.setSuccess(authSessionService.deleteSession(token));
    return res;
}
```

- [x] **Step 2: Inject MemberVoConvert into register/login/getProfile executors**

Return `*ResVO` via MapStruct at end of `execute`.

- [x] **Step 3: Update MemberCmdExeTest return types**

- [x] **Step 4: Run member CmdExe tests**

Run: `cd demo2 && mvn -q test -Dtest=MemberCmdExeTest`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/member/app/executor/ \
        demo2/src/test/java/com/jason/demo/demo2/member/MemberCmdExeTest.java
git commit -m "refactor(demo2): move member VoConvert into CmdExe"
```

---

### Task 7: Controllers — JsonResult + Thin Controller

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/order/app/controller/OrderController.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/member/app/controller/MemberController.java`
- Delete: `demo2/src/main/java/com/jason/demo/demo2/order/app/support/OrderHttpSupport.java`
- Delete: `demo2/src/main/java/com/jason/demo/demo2/member/app/support/MemberHttpSupport.java`

**Interfaces:**
- Consumes: Task 5/6 CmdExe return types, `JsonResults`, `BusinessException`, `CommonErrorCode`.
- Produces: HTTP APIs returning `JsonResult<*ResVO>`.

- [x] **Step 1: Refactor OrderController**

Remove `OrderVoConvert` field and all try-catch blocks. Example:

```java
@PostMapping("/orderPlace")
public JsonResult<OrderPlaceResVO> orderPlace(@RequestBody OrderPlaceReqVO request) {
    if (request == null || request.getAmount() == null) {
        throw new BusinessException(CommonErrorCode.PARAM_MISSING, "amount is required");
    }
    Duration delay = OrderDelayParser.parseDelay(request.getDelay());
    return JsonResults.ok(orderPlaceCmdExe.execute(request.getAmount(), delay));
}
```

Apply to `/pay`, `/get`, `/cancel` — replace `requireOrderId` throws with `BusinessException(CommonErrorCode.PARAM_MISSING, "orderId is required")`.

- [x] **Step 2: Refactor MemberController**

Remove `MemberVoConvert`. Use `memberLogoutCmdExe.logout()` and `deleteSession(token)`.

- [x] **Step 3: Delete OrderHttpSupport and MemberHttpSupport**

- [x] **Step 4: Compile**

Run: `cd demo2 && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS

- [x] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/order/app/controller/ \
        demo2/src/main/java/com/jason/demo/demo2/member/app/controller/
git commit -m "feat(demo2): return JsonResult from order and member controllers"
```

---

### Task 8: Frontend member.js

**Files:**
- Modify: `demo2/src/main/resources/static/js/tabs/member.js`

**Interfaces:**
- Consumes: API responses `{ code, message, data }`.

- [x] **Step 1: Add memberRequest helper** (per spec §7.1)

- [x] **Step 2: Migrate memberRegister, memberLogin, memberLoadProfile**

Login example:

```javascript
const data = await memberRequest('/demo/members/login', input);
memberToken = data.token;
memberProfile = data;
```

For `memberLoadProfile`, on catch check if message indicates auth failure and clear profile.

- [x] **Step 3: Migrate memberLogout and memberDeleteSession**

- [x] **Step 4: Migrate memberOrderCreate/Pay/Cancel/Refresh**

Use `memberRequest` for order APIs; read `data.orderId`, `data.taskId`. Keep `/demo/delay-tasks` fetch unchanged in `memberOrderRefresh`.

- [x] **Step 5: Commit**

```bash
git add demo2/src/main/resources/static/js/tabs/member.js
git commit -m "feat(demo2): adapt member demo JS to JsonResult responses"
```

---

### Task 9: Final Verification

**Files:**
- Modify: `demo2/docs/superpowers/specs/2026-08-25-unified-json-result-design.md` — status → 已实现

- [x] **Step 1: Run full test suite**

Run: `cd demo2 && mvn test`
Expected: all tests PASS

- [x] **Step 2: Manual smoke test checklist**

1. 注册 → 登录 → 个人中心
2. 创建订单 → 支付 → 刷新 → 取消
3. 故意输错密码 → 日志显示 `密码错误`，HTTP 200
4. 删除 Redis session → 访问受保护接口 → 显示 `未登录或登录已失效`

- [x] **Step 3: Update spec status and commit**

```bash
git add demo2/docs/superpowers/specs/2026-08-25-unified-json-result-design.md
git commit -m "docs(demo2): mark unified JsonResult spec as implemented"
```

---

## Spec Coverage Checklist

| Spec section | Task |
|--------------|------|
| §3 JsonResult / BusinessException / ErrorCode | Task 1 |
| §4 Error code tables | Task 2 |
| §5 Global handler (方案 3) | Task 2 |
| §6.1 Delete *DomainException / *HttpSupport | Task 4, 7 |
| §6.2 Auth chain | Task 3 |
| §6.3 CmdExe owns VoConvert | Task 5, 6 |
| §6.3 Controller JsonResult | Task 7 |
| §6.4 Domain BusinessException | Task 4 |
| §7 member.js | Task 8 |
| §8 Tests | Tasks 1–9 |

---

## Execution Handoff

Plan complete and saved to `demo2/docs/superpowers/plans/2026-08-25-unified-json-result.md`.

**Two execution options:**

1. **Subagent-Driven (recommended)** — fresh subagent per task, review between tasks, fast iteration
2. **Inline Execution** — implement tasks in this session with checkpoints

Which approach?
