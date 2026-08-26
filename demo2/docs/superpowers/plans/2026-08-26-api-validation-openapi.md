# API Validation & OpenAPI/Scalar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 demo2 的 member/order/product 三模块统一 Jakarta Bean Validation 入参校验与 OpenAPI/Scalar 注解，并在 framework 落校验异常映射与 `@DelayFormat`。

**Architecture:** Controller 仅 `@Valid` + CmdExe；规则写在 ReqVO；`Demo2GlobalExceptionHandler` 将校验失败映射为 HTTP 200 + `JsonResult`（10002/10001）；delay 解析抽到 `framework.validation.DelayFormats`，供 `@DelayFormat` 与 `OrderDelayParser` 共用；约定写入 `CLAUDE.md` 与 Cursor 规则。

**Tech Stack:** Java 21, Spring Boot 4.x, `spring-boot-starter-validation`, springdoc + Scalar, JUnit 5, OpenAPI 3 annotations (`io.swagger.v3.oas.annotations`).

**Spec:** `demo2/docs/superpowers/specs/2026-08-26-api-validation-openapi-design.md`

## Global Constraints

- HTTP 始终 200；前端以 `code === 0` 判定成功。
- 缺参约束（`NotNull`/`NotBlank`/`NotEmpty`）→ `PARAM_MISSING(10002)`；其余校验失败 → `BAD_REQUEST(10001)`。
- Controller **禁止**手写 `requireXxx` / 入参判空；带 Body 接口必须 `@Valid @RequestBody`。
- 业务模块必须 `@Tag` / `@Operation` / `@Schema`；不改 AI 演示 Controller。
- 依赖方向不变：`app → service.core → service.infrastructure`；framework 不得依赖业务模块。
- 枚举名以 `Enum` 结尾；日志用 `@Slf4j`。
- 工作目录：仓库根或 `demo2/`；Maven 命令以 `demo2` 为模块：`mvn -pl demo2 ...`（若在 demo2 目录则直接 `mvn ...`）。

---

## File Structure

### Create

- `demo2/src/main/java/com/jason/demo/demo2/framework/validation/DelayFormats.java`
- `demo2/src/main/java/com/jason/demo/demo2/framework/validation/DelayFormat.java`
- `demo2/src/main/java/com/jason/demo/demo2/framework/validation/DelayFormatValidator.java`
- `demo2/src/main/java/com/jason/demo/demo2/framework/web/exception/ValidationExceptionMapper.java`
- `demo2/src/test/java/com/jason/demo/demo2/framework/validation/DelayFormatsTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/framework/validation/DelayFormatValidatorTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/framework/web/exception/ValidationExceptionMapperTest.java`
- `demo2/.cursor/rules/demo2-api-validation-openapi.mdc`

### Modify

- `demo2/src/main/java/com/jason/demo/demo2/order/app/support/OrderDelayParser.java`
- `demo2/src/main/java/com/jason/demo/demo2/framework/web/exception/Demo2GlobalExceptionHandler.java`
- `demo2/src/test/java/com/jason/demo/demo2/framework/web/exception/Demo2GlobalExceptionHandlerTest.java`
- `demo2/src/main/java/com/jason/demo/demo2/framework/web/result/JsonResult.java`
- `demo2/src/main/java/com/jason/demo/demo2/config/OpenApiConfig.java`
- Product：`ProductController`、`GetProductReqVO`、全部 product ResVO、`ProductErrorCodeEnum`
- Order：`OrderController`、全部 order ReqVO/ResVO（不含内部 `OrderPlaceResult`）
- Member：`MemberController`、全部 member ReqVO/ResVO
- `demo2/CLAUDE.md`
- `demo2/.cursor/rules/demo2-new-business-module.mdc`
- Spec 状态：`demo2/docs/superpowers/specs/2026-08-26-api-validation-openapi-design.md`（实现完成后改为「已实现」）

---

## Interfaces Produced Across Tasks

```java
package com.jason.demo.demo2.framework.validation;

public final class DelayFormats {
    private DelayFormats() {}
    /** null/blank → empty；可解析 → Optional.of；不可解析 → empty */
    public static Optional<Duration> tryParse(String raw);
}

@Documented
@Constraint(validatedBy = DelayFormatValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface DelayFormat {
    String message() default "delay 格式无效";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

package com.jason.demo.demo2.framework.web.exception;

public final class ValidationExceptionMapper {
    private ValidationExceptionMapper() {}
    public static JsonResult<Void> fromBindingResult(BindingResult bindingResult);
    public static JsonResult<Void> fromConstraintViolations(Set<? extends ConstraintViolation<?>> violations);
}
```

---

### Task 1: DelayFormats 共享解析

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/validation/DelayFormats.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/framework/validation/DelayFormatsTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `DelayFormats.tryParse(String) → Optional<Duration>`

- [ ] **Step 1: 写失败测试**

```java
package com.jason.demo.demo2.framework.validation;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelayFormatsTest {

    @Test
    void tryParse_nullOrBlank_returnsEmpty() {
        assertTrue(DelayFormats.tryParse(null).isEmpty());
        assertTrue(DelayFormats.tryParse("").isEmpty());
        assertTrue(DelayFormats.tryParse("  ").isEmpty());
    }

    @Test
    void tryParse_suffixForms_ok() {
        assertEquals(Optional.of(Duration.ofSeconds(30)), DelayFormats.tryParse("30s"));
        assertEquals(Optional.of(Duration.ofMillis(500)), DelayFormats.tryParse("500ms"));
        assertEquals(Optional.of(Duration.ofMinutes(2)), DelayFormats.tryParse("2m"));
        assertEquals(Optional.of(Duration.ofHours(1)), DelayFormats.tryParse("1h"));
    }

    @Test
    void tryParse_isoDuration_ok() {
        assertEquals(Optional.of(Duration.parse("PT30S")), DelayFormats.tryParse("PT30S"));
        assertEquals(Optional.of(Duration.parse("PT30S")), DelayFormats.tryParse("pt30s"));
    }

    @Test
    void tryParse_invalid_returnsEmpty() {
        assertTrue(DelayFormats.tryParse("abc").isEmpty());
        assertTrue(DelayFormats.tryParse("30x").isEmpty());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run（在 `demo2` 目录）:

```bash
mvn -q -Dtest=DelayFormatsTest test
```

Expected: 编译失败或测试失败（类不存在）。

- [ ] **Step 3: 实现 DelayFormats**

从现有 `OrderDelayParser` 抽出解析逻辑（**不抛业务异常**）：

```java
package com.jason.demo.demo2.framework.validation;

import java.time.Duration;
import java.util.Optional;

public final class DelayFormats {

    private DelayFormats() {
    }

    public static Optional<Duration> tryParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.trim();
        try {
            if (value.startsWith("P") || value.startsWith("p")) {
                return Optional.of(Duration.parse(value));
            }
            if (value.endsWith("ms")) {
                return Optional.of(Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2))));
            }
            if (value.endsWith("s")) {
                return Optional.of(Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1))));
            }
            if (value.endsWith("m")) {
                return Optional.of(Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1))));
            }
            if (value.endsWith("h")) {
                return Optional.of(Duration.ofHours(Long.parseLong(value.substring(0, value.length() - 1))));
            }
            return Optional.of(Duration.parse(value));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
```

注意：对 blank，`tryParse` 返回 empty（表示「无延时」）。Validator 会把 blank 视为**合法**（可选字段）；OrderDelayParser 对 blank 返回 `null` Duration。

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -q -Dtest=DelayFormatsTest test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/framework/validation/DelayFormats.java \
  demo2/src/test/java/com/jason/demo/demo2/framework/validation/DelayFormatsTest.java
git commit -m "feat(demo2): extract DelayFormats shared delay parser"
```

---

### Task 2: @DelayFormat 约束

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/validation/DelayFormat.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/validation/DelayFormatValidator.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/framework/validation/DelayFormatValidatorTest.java`

**Interfaces:**
- Consumes: `DelayFormats.tryParse`
- Produces: `@DelayFormat` + `DelayFormatValidator`

- [ ] **Step 1: 写失败测试**

```java
package com.jason.demo.demo2.framework.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelayFormatValidatorTest {

    private DelayFormatValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DelayFormatValidator();
    }

    @Test
    void nullOrBlank_isValid() {
        assertTrue(validator.isValid(null, null));
        assertTrue(validator.isValid("", null));
        assertTrue(validator.isValid("  ", null));
    }

    @Test
    void validForms_ok() {
        assertTrue(validator.isValid("30s", null));
        assertTrue(validator.isValid("PT30S", null));
    }

    @Test
    void invalid_fails() {
        assertFalse(validator.isValid("nope", null));
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q -Dtest=DelayFormatValidatorTest test
```

Expected: FAIL（类不存在）

- [ ] **Step 3: 实现注解与 Validator**

`DelayFormat.java`:

```java
package com.jason.demo.demo2.framework.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = DelayFormatValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface DelayFormat {

    String message() default "delay 格式无效";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
```

`DelayFormatValidator.java`:

```java
package com.jason.demo.demo2.framework.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class DelayFormatValidator implements ConstraintValidator<DelayFormat, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return DelayFormats.tryParse(value).isPresent();
    }
}
```

- [ ] **Step 4: 运行确认通过**

```bash
mvn -q -Dtest=DelayFormatValidatorTest,DelayFormatsTest test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/framework/validation/DelayFormat.java \
  demo2/src/main/java/com/jason/demo/demo2/framework/validation/DelayFormatValidator.java \
  demo2/src/test/java/com/jason/demo/demo2/framework/validation/DelayFormatValidatorTest.java
git commit -m "feat(demo2): add @DelayFormat bean validation constraint"
```

---

### Task 3: OrderDelayParser 改为委托 DelayFormats

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/order/app/support/OrderDelayParser.java`

**Interfaces:**
- Consumes: `DelayFormats.tryParse`
- Produces: 对外签名不变 `parseDelay(String) → Duration`（blank→null；非法仍抛 `INVALID_DELAY`）

- [ ] **Step 1: 改写 OrderDelayParser**

```java
package com.jason.demo.demo2.order.app.support;

import com.jason.demo.demo2.framework.validation.DelayFormats;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.order.service.common.OrderErrorCodeEnum;

import java.time.Duration;

public final class OrderDelayParser {

    private OrderDelayParser() {
    }

    public static Duration parseDelay(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return DelayFormats.tryParse(raw)
                .orElseThrow(() -> new BusinessException(OrderErrorCodeEnum.INVALID_DELAY, "invalid delay: " + raw));
    }
}
```

- [ ] **Step 2: 跑相关测试（若有）与 DelayFormats 测试**

```bash
mvn -q -Dtest=DelayFormatsTest,DelayFormatValidatorTest,Order*Test test
```

Expected: PASS（无 OrderDelayParser 专测也可；勿破坏既有 order 测试）

- [ ] **Step 3: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/order/app/support/OrderDelayParser.java
git commit -m "refactor(demo2): OrderDelayParser delegates to DelayFormats"
```

---

### Task 4: ValidationExceptionMapper + 全局异常处理

**Files:**
- Create: `demo2/src/main/java/com/jason/demo/demo2/framework/web/exception/ValidationExceptionMapper.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/framework/web/exception/Demo2GlobalExceptionHandler.java`
- Test: `demo2/src/test/java/com/jason/demo/demo2/framework/web/exception/ValidationExceptionMapperTest.java`
- Modify: `demo2/src/test/java/com/jason/demo/demo2/framework/web/exception/Demo2GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: `CommonErrorCodeEnum.PARAM_MISSING` / `BAD_REQUEST`，`JsonResults.fail`
- Produces: `ValidationExceptionMapper.fromBindingResult` / `fromConstraintViolations`；Handler 三个新方法

- [ ] **Step 1: 写 ValidationExceptionMapper 失败测试**

```java
package com.jason.demo.demo2.framework.web.exception;

import com.jason.demo.demo2.framework.web.result.JsonResult;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationExceptionMapperTest {

    @Test
    void notNullOnly_mapsToParamMissing() {
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "request");
        br.addError(new FieldError("request", "productId", null, false,
                new String[]{"NotNull.productId", "NotNull"}, null, "不能为空"));

        JsonResult<Void> result = ValidationExceptionMapper.fromBindingResult(br);

        assertEquals(CommonErrorCodeEnum.PARAM_MISSING.getCode(), result.getCode());
        assertTrue(result.getMessage().contains("productId"));
    }

    @Test
    void patternOnly_mapsToBadRequest() {
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "request");
        br.addError(new FieldError("request", "phone", "bad", false,
                new String[]{"Pattern.phone", "Pattern"}, null, "格式不正确"));

        JsonResult<Void> result = ValidationExceptionMapper.fromBindingResult(br);

        assertEquals(CommonErrorCodeEnum.BAD_REQUEST.getCode(), result.getCode());
        assertTrue(result.getMessage().contains("phone"));
    }

    @Test
    void mixedWithNotNull_prefersParamMissing() {
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "request");
        br.addError(new FieldError("request", "phone", "bad", false,
                new String[]{"Pattern.phone", "Pattern"}, null, "格式不正确"));
        br.addError(new FieldError("request", "password", null, false,
                new String[]{"NotBlank.password", "NotBlank"}, null, "不能为空"));

        JsonResult<Void> result = ValidationExceptionMapper.fromBindingResult(br);

        assertEquals(CommonErrorCodeEnum.PARAM_MISSING.getCode(), result.getCode());
        assertTrue(result.getMessage().contains("phone"));
        assertTrue(result.getMessage().contains("password"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q -Dtest=ValidationExceptionMapperTest test
```

Expected: FAIL

- [ ] **Step 3: 实现 ValidationExceptionMapper**

```java
package com.jason.demo.demo2.framework.web.exception;

import com.jason.demo.demo2.framework.web.result.JsonResult;
import com.jason.demo.demo2.framework.web.result.JsonResults;
import jakarta.validation.ConstraintViolation;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ValidationExceptionMapper {

    private ValidationExceptionMapper() {
    }

    public static JsonResult<Void> fromBindingResult(BindingResult bindingResult) {
        List<String> messages = new ArrayList<>();
        boolean missing = false;
        for (ObjectError error : bindingResult.getAllErrors()) {
            if (isMissingConstraint(error.getCodes())) {
                missing = true;
            }
            String field = error instanceof FieldError fe ? fe.getField() : error.getObjectName();
            String defaultMessage = error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage();
            messages.add(field + ": " + defaultMessage);
        }
        return fail(missing, messages);
    }

    public static JsonResult<Void> fromConstraintViolations(Set<? extends ConstraintViolation<?>> violations) {
        List<String> messages = new ArrayList<>();
        boolean missing = false;
        for (ConstraintViolation<?> v : violations) {
            String annotation = v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
            if (isMissingAnnotationName(annotation)) {
                missing = true;
            }
            String path = v.getPropertyPath() == null ? "param" : v.getPropertyPath().toString();
            messages.add(path + ": " + v.getMessage());
        }
        return fail(missing, messages);
    }

    private static JsonResult<Void> fail(boolean missing, List<String> messages) {
        int code = missing
                ? CommonErrorCodeEnum.PARAM_MISSING.getCode()
                : CommonErrorCodeEnum.BAD_REQUEST.getCode();
        String message = messages.isEmpty()
                ? (missing ? CommonErrorCodeEnum.PARAM_MISSING.getDesc() : CommonErrorCodeEnum.BAD_REQUEST.getDesc())
                : String.join("; ", messages);
        return JsonResults.fail(code, message);
    }

    private static boolean isMissingConstraint(String[] codes) {
        if (codes == null) {
            return false;
        }
        for (String code : codes) {
            if (code == null) {
                continue;
            }
            if (code.equals("NotNull") || code.equals("NotBlank") || code.equals("NotEmpty")
                    || code.startsWith("NotNull.") || code.startsWith("NotBlank.") || code.startsWith("NotEmpty.")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMissingAnnotationName(String simpleName) {
        return "NotNull".equals(simpleName) || "NotBlank".equals(simpleName) || "NotEmpty".equals(simpleName);
    }
}
```

- [ ] **Step 4: 扩展 Demo2GlobalExceptionHandler**

在现有 `BusinessException` / `Exception` 处理之间插入：

```java
import jakarta.validation.ConstraintViolationException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;

@ExceptionHandler(MethodArgumentNotValidException.class)
@ResponseStatus(HttpStatus.OK)
public JsonResult<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
    log.warn("Validation failed: {}", ex.getMessage());
    return ValidationExceptionMapper.fromBindingResult(ex.getBindingResult());
}

@ExceptionHandler(BindException.class)
@ResponseStatus(HttpStatus.OK)
public JsonResult<Void> handleBindException(BindException ex) {
    log.warn("Bind validation failed: {}", ex.getMessage());
    return ValidationExceptionMapper.fromBindingResult(ex.getBindingResult());
}

@ExceptionHandler(ConstraintViolationException.class)
@ResponseStatus(HttpStatus.OK)
public JsonResult<Void> handleConstraintViolation(ConstraintViolationException ex) {
    log.warn("Constraint violation: {}", ex.getMessage());
    return ValidationExceptionMapper.fromConstraintViolations(ex.getConstraintViolations());
}
```

在 `Demo2GlobalExceptionHandlerTest` 增加：

```java
@Test
void handleMethodArgumentNotValid_delegatesToMapper() {
    BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "request");
    br.addError(new FieldError("request", "orderId", null, false,
            new String[]{"NotNull"}, null, "不能为空"));
    // 使用 Spring 的 MethodArgumentNotValidException 需要 MethodParameter；
    // 改为直接测 mapper 即可；此处测 BindException 委托：
    JsonResult<Void> result = handler.handleBindException(new BindException(br));
    assertEquals(CommonErrorCodeEnum.PARAM_MISSING.getCode(), result.getCode());
}
```

（若不想构造 `MethodArgumentNotValidException`，测 `handleBindException` / `handleConstraintViolation` 即可。）

- [ ] **Step 5: 运行测试**

```bash
mvn -q -Dtest=ValidationExceptionMapperTest,Demo2GlobalExceptionHandlerTest test
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/framework/web/exception/ValidationExceptionMapper.java \
  demo2/src/main/java/com/jason/demo/demo2/framework/web/exception/Demo2GlobalExceptionHandler.java \
  demo2/src/test/java/com/jason/demo/demo2/framework/web/exception/ValidationExceptionMapperTest.java \
  demo2/src/test/java/com/jason/demo/demo2/framework/web/exception/Demo2GlobalExceptionHandlerTest.java
git commit -m "feat(demo2): map bean validation failures to JsonResult 10001/10002"
```

---

### Task 5: JsonResult Schema + OpenApiConfig

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/framework/web/result/JsonResult.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/config/OpenApiConfig.java`

**Interfaces:**
- Consumes: 无
- Produces: 带 `@Schema` 的 `JsonResult`

- [ ] **Step 1: 更新 JsonResult**

```java
package com.jason.demo.demo2.framework.web.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "统一 API 响应包装")
public class JsonResult<T> {

    @Schema(description = "业务状态码，0 表示成功", example = "0")
    private int code;

    @Schema(description = "提示信息", example = "success")
    private String message;

    @Schema(description = "业务数据")
    private T data;
}
```

- [ ] **Step 2: 更新 OpenApiConfig description（可选但本任务一并做）**

```java
.description("Spring AI 与业务 Demo API（会员/订单/商品）；统一 JsonResult 包装")
```

- [ ] **Step 3: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/framework/web/result/JsonResult.java \
  demo2/src/main/java/com/jason/demo/demo2/config/OpenApiConfig.java
git commit -m "docs(demo2): annotate JsonResult and refresh OpenAPI info"
```

---

### Task 6: Product 模块校验 + OpenAPI

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/app/vo/req/GetProductReqVO.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/app/vo/res/ProductListItemResVO.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/app/vo/res/ProductListResVO.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/app/vo/res/ProductDetailResVO.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/app/controller/ProductController.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/product/service/common/ProductErrorCodeEnum.java`

**Interfaces:**
- Consumes: `@Valid`、全局校验异常处理
- Produces: 商品 API 文档与校验完备；删除 `PRODUCT_ID_REQUIRED`

- [ ] **Step 1: 更新 GetProductReqVO**

```java
package com.jason.demo.demo2.product.app.vo.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "查询商品详情请求")
public class GetProductReqVO {

    @NotNull(message = "不能为空")
    @Min(value = 1, message = "必须大于 0")
    @Schema(description = "商品业务 ID", example = "2085550503315509001", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long productId;
}
```

- [ ] **Step 2: 更新 ResVO Schema**

`ProductListItemResVO`：类与字段均加 `@Schema`（productId/productName/subtitle/coverUrl/sellPrice/marketPrice/availableStock/sellStock）。  
`ProductListResVO`：`items` 列表说明。  
`ProductDetailResVO`：`detailContent` 说明（继承字段已在父类标注）。

- [ ] **Step 3: 重写 ProductController**

```java
package com.jason.demo.demo2.product.app.controller;

import com.jason.demo.demo2.framework.web.result.JsonResult;
import com.jason.demo.demo2.framework.web.result.JsonResults;
import com.jason.demo.demo2.product.app.executor.ProductGetCmdExe;
import com.jason.demo.demo2.product.app.executor.ProductListCmdExe;
import com.jason.demo.demo2.product.app.vo.req.GetProductReqVO;
import com.jason.demo.demo2.product.app.vo.res.ProductDetailResVO;
import com.jason.demo.demo2.product.app.vo.res.ProductListResVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "商品")
@RestController
@RequestMapping("/demo/products")
public class ProductController {

    private final ProductListCmdExe productListCmdExe;
    private final ProductGetCmdExe productGetCmdExe;

    public ProductController(ProductListCmdExe productListCmdExe, ProductGetCmdExe productGetCmdExe) {
        this.productListCmdExe = productListCmdExe;
        this.productGetCmdExe = productGetCmdExe;
    }

    @Operation(summary = "商品列表", description = "查询上架商品列表（含库存摘要）。无请求体。")
    @PostMapping("/listProducts")
    public JsonResult<ProductListResVO> listProducts(@RequestBody(required = false) Object ignored) {
        return JsonResults.ok(productListCmdExe.execute());
    }

    @Operation(summary = "商品详情", description = "按 productId 查询上架商品详情")
    @PostMapping("/getProduct")
    public JsonResult<ProductDetailResVO> getProduct(@Valid @RequestBody GetProductReqVO request) {
        return JsonResults.ok(productGetCmdExe.execute(request.getProductId()));
    }
}
```

- [ ] **Step 4: 删除 PRODUCT_ID_REQUIRED**

从 `ProductErrorCodeEnum` 删除枚举常量 `PRODUCT_ID_REQUIRED(40006, ...)`。全仓库搜索确认无引用。

- [ ] **Step 5: 编译与相关测试**

```bash
mvn -q -Dtest=ProductCmdExeTest,ProductStockDomainServiceTest,ProductStockLogRepositoryTest test
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/product/
git commit -m "feat(demo2): add validation and OpenAPI annotations to product API"
```

---

### Task 7: Order 模块校验 + OpenAPI

**Files:**
- Modify: `OrderPlaceReqVO` / `GetOrderReqVO` / `PayOrderReqVO` / `CancelOrderReqVO`
- Modify: `OrderPlaceResVO` / `GetOrderResVO` / `PayOrderResVO` / `CancelOrderResVO`
- Modify: `OrderController.java`
- 不修改内部 `OrderPlaceResult.java`（非 HTTP VO）

**Interfaces:**
- Consumes: `@DelayFormat`、`ValidationExceptionMapper`
- Produces: 订单 API 校验与文档完备

- [ ] **Step 1: 更新 ReqVO**

`OrderPlaceReqVO`:

```java
package com.jason.demo.demo2.order.app.vo.req;

import com.jason.demo.demo2.framework.validation.DelayFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "下单请求")
public class OrderPlaceReqVO {

    @NotNull(message = "不能为空")
    @DecimalMin(value = "0.01", message = "必须大于 0")
    @Digits(integer = 10, fraction = 2, message = "最多两位小数")
    @Schema(description = "订单金额", example = "18.00", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal amount;

    @DelayFormat
    @Schema(description = "可选超时延时，如 30s / PT30S；空则用配置默认", example = "30s")
    private String delay;
}
```

`GetOrderReqVO` / `PayOrderReqVO` / `CancelOrderReqVO` 均：

```java
@NotNull(message = "不能为空")
@Min(value = 1, message = "必须大于 0")
@Schema(description = "订单 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
private Long orderId;
```

（各类加对应 `@Schema(description = "...")`。）

- [ ] **Step 2: ResVO 全部字段补 `@Schema`**

- [ ] **Step 3: 重写 OrderController**

去掉 `requireOrderId` 与 amount 手写判空；方法签名示例：

```java
@Tag(name = "订单")
@RestController
@RequestMapping("/demo/orders")
public class OrderController {
    // 构造器不变

    @LoginRequired
    @Operation(summary = "下单", description = "创建待支付订单并注册超时延时任务")
    @PostMapping("/orderPlace")
    public JsonResult<OrderPlaceResVO> orderPlace(@Valid @RequestBody OrderPlaceReqVO request) {
        Duration delay = OrderDelayParser.parseDelay(request.getDelay());
        return JsonResults.ok(orderPlaceCmdExe.execute(request.getAmount(), delay));
    }

    @LoginRequired
    @Operation(summary = "支付成功", description = "将订单置为已支付")
    @PostMapping("/pay")
    public JsonResult<PayOrderResVO> pay(@Valid @RequestBody PayOrderReqVO request) {
        return JsonResults.ok(orderPaySuccessCmdExe.execute(request.getOrderId()));
    }

    @LoginRequired
    @Operation(summary = "查询订单", description = "按 orderId 查询订单")
    @PostMapping("/get")
    public JsonResult<GetOrderResVO> get(@Valid @RequestBody GetOrderReqVO request) {
        return JsonResults.ok(orderGetCmdExe.execute(request.getOrderId()));
    }

    @LoginRequired
    @Operation(summary = "取消订单", description = "取消待支付订单")
    @PostMapping("/cancel")
    public JsonResult<CancelOrderResVO> cancel(@Valid @RequestBody CancelOrderReqVO request) {
        return JsonResults.ok(orderCancelCmdExe.execute(request.getOrderId()));
    }
}
```

删除私有方法 `requireOrderId` 及相关 `BusinessException`/`CommonErrorCodeEnum` 入参校验 import（若不再使用）。

- [ ] **Step 4: 测试**

```bash
mvn -q -Dtest=Order*Test,DelayFormat*Test,DelayFormatsTest test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/order/app/
git commit -m "feat(demo2): add validation and OpenAPI annotations to order API"
```

---

### Task 8: Member 模块校验 + OpenAPI

**Files:**
- Modify: `RegisterMemberReqVO` / `LoginMemberReqVO` / `DeleteSessionReqVO`
- Modify: 全部 member ResVO
- Modify: `MemberController.java`

**Interfaces:**
- Consumes: 标准 Jakarta 约束 + Hibernate `@URL`
- Produces: 会员 API 校验与文档完备

- [ ] **Step 1: 更新 ReqVO**

`RegisterMemberReqVO`:

```java
package com.jason.demo.demo2.member.app.vo.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

@Data
@Schema(description = "会员注册请求")
public class RegisterMemberReqVO {

    @NotBlank(message = "不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    @Schema(description = "大陆手机号", example = "13800138000", requiredMode = Schema.RequiredMode.REQUIRED)
    private String phone;

    @NotBlank(message = "不能为空")
    @Size(min = 6, max = 32, message = "密码长度须为 6-32")
    @Schema(description = "登录密码", example = "secret12", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;

    @URL(message = "头像 URL 格式不正确")
    @Schema(description = "头像 URL（可选，绝对地址）", example = "https://example.com/a.png")
    private String avatarUrl;
}
```

`LoginMemberReqVO`：`phone`/`password` 同注册（无 avatarUrl）。

`DeleteSessionReqVO`：

```java
@NotBlank(message = "不能为空")
@Schema(description = "会话 token", requiredMode = Schema.RequiredMode.REQUIRED)
private String token;
```

- [ ] **Step 2: ResVO 补 `@Schema`**

覆盖：`RegisterMemberResVO`、`LoginMemberResVO`、`LogoutMemberResVO`、`GetMemberProfileResVO`、`DeleteSessionResVO`。

- [ ] **Step 3: 重写 MemberController**

去掉 `requireText`；保留业务 trim（校验通过后）：

```java
@Tag(name = "会员")
@RestController
@RequestMapping("/demo/members")
public class MemberController {
    // 构造器不变

    @Operation(summary = "注册", description = "手机号注册会员")
    @PostMapping("/register")
    public JsonResult<RegisterMemberResVO> register(@Valid @RequestBody RegisterMemberReqVO request) {
        return JsonResults.ok(memberRegisterCmdExe.execute(
                request.getPhone().trim(), request.getPassword(), request.getAvatarUrl()));
    }

    @Operation(summary = "登录", description = "手机号密码登录，返回 token")
    @PostMapping("/login")
    public JsonResult<LoginMemberResVO> login(@Valid @RequestBody LoginMemberReqVO request) {
        return JsonResults.ok(memberLoginCmdExe.execute(
                request.getPhone().trim(), request.getPassword()));
    }

    @LoginRequired
    @Operation(summary = "登出", description = "注销当前登录会话。无请求体。")
    @PostMapping("/logout")
    public JsonResult<LogoutMemberResVO> logout() {
        return JsonResults.ok(memberLogoutCmdExe.logout());
    }

    @LoginRequired
    @Operation(summary = "会员资料", description = "查询当前登录会员资料。无请求体。")
    @PostMapping("/getProfile")
    public JsonResult<GetMemberProfileResVO> getProfile() {
        return JsonResults.ok(memberGetProfileCmdExe.execute());
    }

    @Operation(summary = "删除会话", description = "按 token 删除指定会话（调试用）")
    @PostMapping("/deleteSession")
    public JsonResult<DeleteSessionResVO> deleteSession(@Valid @RequestBody DeleteSessionReqVO request) {
        return JsonResults.ok(memberLogoutCmdExe.deleteSession(request.getToken()));
    }
}
```

- [ ] **Step 4: 测试**

```bash
mvn -q -Dtest=Member*Test test
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/member/app/
git commit -m "feat(demo2): add validation and OpenAPI annotations to member API"
```

---

### Task 9: 规范落盘（CLAUDE.md + Cursor 规则）

**Files:**
- Modify: `demo2/CLAUDE.md`
- Modify: `demo2/.cursor/rules/demo2-new-business-module.mdc`
- Create: `demo2/.cursor/rules/demo2-api-validation-openapi.mdc`

**Interfaces:**
- Consumes: 本 spec 已确认约定
- Produces: 长期强制规则文本

- [ ] **Step 1: 更新 CLAUDE.md**

在「职责边界」增加：

```markdown
- Controller 入参必须 `@Valid @RequestBody`；校验规则只写在 ReqVO（Jakarta Validation），**禁止**手写 `requireXxx` / 入参判空
- 校验失败由 `Demo2GlobalExceptionHandler` 映射：`NotNull`/`NotBlank`/`NotEmpty` → `PARAM_MISSING(10002)`，其余 → `BAD_REQUEST(10001)`；HTTP 仍为 200
- 业务模块 Controller 必须 `@Tag` + 方法 `@Operation`；ReqVO/ResVO 类与字段必须 `@Schema`（与校验 required 对齐）；统一包装见 `JsonResult` Schema
- 可选延时字符串用 framework `@DelayFormat`（与 `DelayFormats` / `OrderDelayParser` 同规则）
```

在「异常与响应」补充一句：全局处理器还捕获 `MethodArgumentNotValidException` / `BindException` / `ConstraintViolationException`。

- [ ] **Step 2: 更新 demo2-new-business-module.mdc**

在「实施前」或「完成后」清单增加：

```markdown
4. ReqVO：Jakarta Validation（必填/格式）；Controller：`@Valid`；禁止手写判空
5. Controller `@Tag`/`@Operation`；全部 Req/Res VO 字段 `@Schema`
6. 无 Body 接口在 `@Operation` 注明「无请求体」
```

- [ ] **Step 3: 新建 demo2-api-validation-openapi.mdc**

```markdown
---
description: demo2 业务 API 入参校验与 OpenAPI/Scalar 注解约定
globs: demo2/src/main/java/com/jason/demo/demo2/{member,order,product}/app/**/*.java,demo2/src/main/java/com/jason/demo/demo2/framework/web/**/*.java,demo2/src/main/java/com/jason/demo/demo2/framework/validation/**/*.java
alwaysApply: false
---

# 业务 API 校验与 OpenAPI

## 校验
- 带 JSON Body 的接口：`@Valid @RequestBody`
- 规则写在 ReqVO：`@NotNull`/`@NotBlank`/`@Size`/`@Pattern`/`@DecimalMin`/`@Digits`/`@Min`/`@URL`/`@DelayFormat`
- 嵌套对象字段加 `@Valid` 级联
- 禁止 Controller 手写判空；业务错误仍用 `BusinessException`

## 错误码
- 缺参（NotNull/NotBlank/NotEmpty）→ 10002
- 格式/范围 → 10001
- HTTP 200 + JsonResult

## OpenAPI / Scalar
- Controller：`@Tag`；方法：`@Operation`
- VO 类与字段：`@Schema(description, example, requiredMode)`
- `JsonResult` 已在 framework 标注 code/message/data
- 调试入口：`/scalar`

## 范围
- 强制：member/order/product 及后续业务模块
- 不强制：AI/agentscope 等演示 Controller
```

- [ ] **Step 4: Commit**

```bash
git add demo2/CLAUDE.md \
  demo2/.cursor/rules/demo2-new-business-module.mdc \
  demo2/.cursor/rules/demo2-api-validation-openapi.mdc
git commit -m "docs(demo2): codify API validation and OpenAPI conventions"
```

---

### Task 10: 回归验收 + Spec 收尾

**Files:**
- Modify: `demo2/docs/superpowers/specs/2026-08-26-api-validation-openapi-design.md`（状态 → 已实现）
- 本 plan 勾选全部 checkbox

**Interfaces:**
- Consumes: Tasks 1–9 产物
- Produces: 可合并的完成态

- [ ] **Step 1: 全量相关单测**

```bash
mvn -q -Dtest=DelayFormatsTest,DelayFormatValidatorTest,ValidationExceptionMapperTest,Demo2GlobalExceptionHandlerTest,Product*Test,Order*Test,Member*Test test
```

Expected: PASS

- [ ] **Step 2: 手工 / curl 冒烟（应用已启动时）**

```bash
# 缺 productId → 10002
curl -s -X POST http://localhost:8081/demo/products/getProduct \
  -H "Content-Type: application/json" -d "{}"

# 非法 phone → 10001
curl -s -X POST http://localhost:8081/demo/members/register \
  -H "Content-Type: application/json" \
  -d "{\"phone\":\"123\",\"password\":\"secret12\"}"
```

Expected: 响应 `code` 分别为 `10002`、`10001`；HTTP 200。

Scalar：打开 `http://localhost:8081/scalar`，确认 Tag「会员」「订单」「商品」及字段说明。

- [ ] **Step 3: Spec 状态改为已实现**

将设计文档头部 `**状态**: 待实现` 改为 `**状态**: 已实现`，并可加一句指向本 plan。

- [ ] **Step 4: Commit**

```bash
git add demo2/docs/superpowers/specs/2026-08-26-api-validation-openapi-design.md \
  demo2/docs/superpowers/plans/2026-08-26-api-validation-openapi.md
git commit -m "docs(demo2): mark API validation OpenAPI spec implemented"
```

（归档 `archive/` 可在全部合并后另开，非本任务阻断项。）

---

## Self-Review (plan author)

| Spec 项 | 对应 Task |
|---------|-----------|
| DelayFormats + `@DelayFormat` | 1–2 |
| OrderDelayParser 共用解析 | 3 |
| 全局校验异常 10001/10002 | 4 |
| JsonResult Schema + OpenAPI info | 5 |
| product / order / member 校验+注解 | 6–8 |
| 删除 PRODUCT_ID_REQUIRED | 6 |
| CLAUDE.md + Cursor 规则 | 9 |
| 测试与 Scalar 验收 | 1–4, 10 |
| 不改 AI Controller / 不改 URL | Global Constraints |

无 TBD/TODO 占位；接口名前后一致（`DelayFormats.tryParse`、`ValidationExceptionMapper.fromBindingResult`）。
