# demo2 业务模块控制层校验与 OpenAPI/Scalar 设计规范

**日期**: 2026-08-26  
**项目**: spring-ai-demo / demo2  
**状态**: 待实现

---

## 1. 背景与目标

### 1.1 背景

- `member` / `order` / `product` 三个业务模块的 Controller 仍用手写 `if` 做入参判空（如 `requireText`、`requireOrderId`、`productId == null`），ReqVO 上无 Jakarta Bean Validation 注解。
- 三个模块的 Controller、ReqVO、ResVO **均未**标注 `@Tag` / `@Operation` / `@Schema`；Scalar（`/scalar`，基于 springdoc）已配置，但业务接口文档不完整。
- AI 演示模块（如 `ChatController` + `ChatRequest`）已有 OpenAPI 注解可作样板；`Demo2GlobalExceptionHandler` 尚未处理 `MethodArgumentNotValidException` 等校验异常。
- CLAUDE.md 约定 Controller「只做校验 + 调 CmdExe + JsonResults 包装」，但校验方式未统一，也未写入 Cursor 规则。

### 1.2 目标

1. 三模块入参统一为 **Jakarta Bean Validation**（`@Valid` + VO 注解），去掉 Controller 手写入参判空。
2. 校验失败统一映射为 HTTP 200 + `JsonResult`：`PARAM_MISSING(10002)` / `BAD_REQUEST(10001)`。
3. 每个控制层方法、请求/响应 VO 字段、以及 `JsonResult` 包装字段均有 OpenAPI `@Schema` 等说明，Scalar 可完整浏览。
4. 公共能力（校验异常处理、可选自定义约束）放 `framework`；约定写入 `CLAUDE.md` 与 Cursor 规则，供后续新模块强制遵循。

### 1.3 已确认决策

| 维度 | 选择 |
|------|------|
| 方案 | Jakarta Validation + 框架统一异常 + OpenAPI 注解 |
| 校验深度 | 必填 + 格式/范围 + 自定义约束（delay）；嵌套对象用 `@Valid` 级联（当前 VO 无嵌套则预留约定） |
| 错误响应 | HTTP 200 + `JsonResult`；缺参 `10002`，格式/范围 `10001` |
| OpenAPI 范围 | `@Tag`/`@Operation` + 全部 Req/Res 字段 `@Schema` + `JsonResult` 三字段 Schema |
| 规范落盘 | `CLAUDE.md` + `demo2-new-business-module.mdc` + 新建 `demo2-api-validation-openapi.mdc` |
| 自定义约束 | 仅 delay 需要 framework 级 `@DelayFormat`；其余用标准注解 |

### 1.4 非目标

- 不改 AI / agentscope / embabel 等演示 Controller 的校验策略
- 不引入 API 版本、不改现有 URL
- 不做 MessageSource 全量 i18n（注解 `message` 写可读文案即可）
- 不把业务错误（库存不足、未登录、商品不存在等）改成 Bean Validation
- 不为无 Body 接口强行新增空 ReqVO

---

## 2. 架构与职责

```text
HTTP → Controller(@Valid) → Bean Validation(ReqVO)
                ↓ 失败
        Demo2GlobalExceptionHandler → JsonResult(PARAM_MISSING / BAD_REQUEST)
                ↓ 成功
              CmdExe → Domain / Repository（业务规则不变）
```

| 层 | 职责 |
|----|------|
| **Controller** | `@Valid` + 调 CmdExe + `JsonResults.ok`；`@Tag`/`@Operation`；**禁止**手写 `requireXxx` / 入参判空 |
| **ReqVO** | Bean Validation 注解承载入参规则；类与字段 `@Schema` |
| **ResVO** | 仅 `@Schema` 文档，不做校验 |
| **framework** | 全局校验异常映射；`@DelayFormat`（及 Validator）；`JsonResult` 补 Schema |
| **CmdExe / Domain** | 不承担缺参/格式校验；业务规则仍在此 |

依赖方向不变：`app → service.core → service.infrastructure`。校验注解只出现在 `app.vo` 与 `framework`。

**实施范围**：`member` / `order` / `product` + `framework` 公共能力 + 文档规则。

---

## 3. 校验规则与注解落点

### 3.1 Controller 约定

- 凡 `@RequestBody XxxReqVO`：必须 `@Valid @RequestBody XxxReqVO request`。
- 无请求体接口（`listProducts`、`logout`、`getProfile`）：保持无参或现有 `required = false`，不新增空 VO；`@Operation` 中注明「无请求体」。

### 3.2 ReqVO 字段规则

| VO | 字段 | 注解 | 失败码 |
|----|------|------|--------|
| `RegisterMemberReqVO` / `LoginMemberReqVO` | `phone` | `@NotBlank` + `@Pattern(regexp = "^1[3-9]\\d{9}$")` | 空→10002 / 格式→10001 |
| 同上 | `password` | `@NotBlank` + `@Size(min = 6, max = 32)` | 同上 |
| `RegisterMemberReqVO` | `avatarUrl` | 可选；非 null 时使用 Hibernate Validator `@URL` | 10001 |
| `DeleteSessionReqVO` | `token` | `@NotBlank` | 10002 |
| `OrderPlaceReqVO` | `amount` | `@NotNull` + `@DecimalMin("0.01")` + `@Digits(integer = 10, fraction = 2)` | 同上 |
| `OrderPlaceReqVO` | `delay` | 可选；非空时 `@DelayFormat` | 10001 |
| `GetOrderReqVO` / `PayOrderReqVO` / `CancelOrderReqVO` | `orderId` | `@NotNull` + `@Min(1)` | 同上 |
| `GetProductReqVO` | `productId` | `@NotNull` + `@Min(1)` | 同上 |

若后续出现嵌套对象字段，外层字段加 `@Valid` 以级联触发内层约束。

### 3.3 Framework：`@DelayFormat`

- 包建议：`com.jason.demo.demo2.framework.validation`（注解 + `ConstraintValidator`）。
- 语义：`null` / blank **通过**；非空则按与 `OrderDelayParser` **相同**的解析规则试解析，失败则校验失败。
- 校验阶段 **不**抛 `BusinessException(OrderErrorCodeEnum.INVALID_DELAY)`；统一走全局处理器 → `BAD_REQUEST(10001)`。
- `OrderDelayParser` 仍可在下单路径用于把字符串转为 `Duration`（校验已通过后解析应成功；parser 内业务异常可作为防御保留）。

### 3.4 清理项

- 删除 `MemberController.requireText`、`OrderController.requireOrderId`、`ProductController` 内 `productId` 手写判空。
- 删除 `ProductErrorCodeEnum.PRODUCT_ID_REQUIRED`；「商品不存在」等业务码保留。
- 入参缺失不再使用模块自定义「xxx 必填」错误码，统一 `CommonErrorCodeEnum.PARAM_MISSING` / `BAD_REQUEST`。

---

## 4. 异常映射

在 `Demo2GlobalExceptionHandler` 中新增（均 `@ResponseStatus(HttpStatus.OK)`）：

| 异常 | 映射规则 |
|------|----------|
| `MethodArgumentNotValidException` | 遍历 `FieldError`：若约束为 `@NotNull` / `@NotBlank` / `@NotEmpty` → 记为 MISSING；否则 BAD。整次请求若存在任一 MISSING 则 `code=10002`，否则 `10001`。`message` 拼接 `字段: 原因`，多条用 `; ` |
| `BindException` | 同上 |
| `ConstraintViolationException` | 同上（方法级/`@Validated` 场景） |

既有行为不变：

- `BusinessException` → `JsonResults.fail(ex.getCode(), ex.getMessage())`
- 其它 `Exception` → `INTERNAL_ERROR(10999)`

前端约定不变：HTTP 始终 200，以 `code === 0` 判定成功。

---

## 5. OpenAPI / Scalar

### 5.1 注解要求

| 位置 | 要求 |
|------|------|
| Controller 类 | `@Tag(name = "会员" \| "订单" \| "商品")` |
| 每个映射方法 | `@Operation(summary, description)` |
| ReqVO / ResVO 类 | `@Schema(description = "...")` |
| 每个对外字段 | `@Schema(description, example, requiredMode)`；必填与 Validation 对齐 |
| `JsonResult` | `code` / `message` / `data` 均有 `@Schema` |

依赖已具备：`springdoc-openapi-starter-webmvc-scalar`、`application.properties` 中 `scalar.path=/scalar`。无需新增 Maven 依赖即可让注解生效。

### 5.2 验收

1. 访问 `http://localhost:8081/scalar`，可见「会员」「订单」「商品」三个 Tag。
2. 任一接口可展开查看请求/响应字段说明与必填标记。
3. 请求体缺少 `productId` → `code=10002`；非法 `phone` → `code=10001`。

可选：更新 `OpenApiConfig` 的 `Info.description`，注明含业务 Demo API（非阻断项）。

---

## 6. 规范落盘

### 6.1 `demo2/CLAUDE.md`

在「职责边界」/「HTTP」相关节增补：

- Controller 入参必须 `@Valid`；规则只写在 ReqVO（Jakarta Validation），禁止手写判空工具方法。
- 校验失败由 `Demo2GlobalExceptionHandler` 映射为 `10001` / `10002`，HTTP 200。
- 业务模块 Controller / VO 必须具备 `@Tag` / `@Operation` / `@Schema`（含 `JsonResult` 已在 framework 标注）。

### 6.2 Cursor 规则

1. **更新** `demo2/.cursor/rules/demo2-new-business-module.mdc`  
   Checklist 增加：ReqVO Validation、ResVO Schema、Controller OpenAPI、无 `requireXxx`。

2. **新建** `demo2/.cursor/rules/demo2-api-validation-openapi.mdc`  
   - globs：业务模块 `app/controller`、`app/vo` 及 framework web/validation  
   - 写明：校验深度默认、错误码映射、`@DelayFormat` 用法、OpenAPI 注解清单、与 AI 演示模块可不强制对齐的说明

---

## 7. 测试计划

| 类型 | 内容 |
|------|------|
| 单元 | `DelayFormatValidator`：null/blank 通过；合法 `30s`/`PT30S` 通过；非法失败 |
| 单元 | 全局处理器：仅 NotNull 失败 → 10002；仅 Pattern/Size/DecimalMin 失败 → 10001；混合含 NotNull → 10002 |
| 可选 Web | MockMvc/WebTestClient：`getProduct` 缺 `productId`；`register` 非法 phone |
| 回归 | 现有 CmdExe / Domain 单测不受影响；若有依赖 Controller 手写校验行为的测试则改为 Validation 语义 |

---

## 8. 实施清单（摘要）

1. framework：校验异常处理 + `@DelayFormat` + `JsonResult` Schema  
2. member / order / product：ReqVO 注解、ResVO Schema、Controller `@Valid` + OpenAPI，删除手写判空  
3. 删除 `PRODUCT_ID_REQUIRED`  
4. 更新 `CLAUDE.md` 与 Cursor 规则  
5. 单测 + Scalar 手工验收  

详细分步与 checkbox 在后续 `docs/superpowers/plans/2026-08-26-api-validation-openapi.md` 中展开。

---

## 9. 风险与兼容性

| 风险 | 缓解 |
|------|------|
| 原先部分接口允许更松的入参（如未校验手机号格式）现在会 10001 | 按已确认规则收紧；前端 Demo 需使用合法手机号 |
| `avatarUrl` 使用 `@URL` 对相对路径过严 | 若 seed/前端只用绝对 URL 则无问题；否则改为 `@Pattern` 放宽并在实现计划注明 |
| delay 校验与 `OrderDelayParser` 双份逻辑漂移 | Validator 与 Parser 共用同一解析工具方法（推荐抽 private/shared 静态方法） |
| 多字段错误 message 过长 | 实现时可限制最多 N 条；默认拼接全部即可 |

---

## 10. 成功标准

1. 三模块 Controller 无手写入参判空；带 Body 接口均 `@Valid`。  
2. 缺参 → `10002`，格式/范围错误 → `10001`，HTTP 200。  
3. Scalar 上三模块 Tag、方法、请求/响应字段（含 `JsonResult`）均有说明。  
4. `CLAUDE.md` 与 Cursor 规则已更新，新建模块 checklist 含本约定。  
5. 相关单测通过。
