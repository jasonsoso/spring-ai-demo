# demo2 开发规范

Spring Boot 4.x / Java 21 仓库中的 demo2 子项目。本文件只在 demo2 目录下生效。

## 业务模块 DDD（核心约定）

新业务代码放 `com.jason.demo.demo2.{module}`，**参照 `order` 模块**，不要恢复扁平 Controller+Service 单包。

分层与依赖方向（禁止反向）：

```
app → service.core → service.infrastructure
```

| 层 | 内容 |
|----|------|
| `app` | controller（只做校验 + 调 CmdExe + `JsonResults` 包装）、executor（`*CmdExe`，含 VO 转换）、listener/job、vo/req\|res、convert（`*VoConvert`） |
| `service.core` | domain（聚合根 `extends *DO`）、`*DomainService` |
| `service.infrastructure` | dao.entity（`*DO`）、dao.mapper、repository（`*Repository`）、convert（`*DoConvert`） |
| `service.common` | 状态枚举 `{Entity}StatusEnum`、错误码 `{Module}ErrorCodeEnum`（实现 `ErrorCode`） |

禁止：Controller 注入 `*VoConvert`；Controller/CmdExe 直接注入 Mapper；domain 依赖 app；infrastructure 依赖 app。

### 命名

| 类型 | 模式 | 示例 |
|------|------|------|
| 表对象 | `{Entity}DO` | `OrderDO` |
| 领域对象 | `{Entity}` | `Order` |
| 用例执行器 | `{Action}CmdExe` | `OrderPlaceCmdExe` |
| 请求/响应 | `{Action}ReqVO` / `{Action}ResVO` | `PayOrderReqVO` |
| 仓储 | `{Entity}Repository` | `OrderRepository` |
| 状态枚举 | `{Entity}StatusEnum` | `OrderStatusEnum` |
| 错误码 | `{Module}ErrorCodeEnum` | `OrderErrorCodeEnum` |

创建类用例可用 `{Module}Place*`（如 `orderPlace`）；其余动作用简短动词（pay/get/cancel）。

### 枚举与日志

- **所有 `enum` 必须以 `Enum` 结尾**（如 `OrderStatusEnum`、`MemberErrorCodeEnum`、`CommonErrorCodeEnum`）
- **日志优先用 Lombok `@Slf4j`**，不要手写 `LoggerFactory.getLogger`
- 打异常日志时，把 `Throwable` 放在 `log` 方法最后一个参数以输出堆栈

### 职责边界

- 领域规则写在 `domain` 方法（`pay()` / `cancel()` 等），不在 Controller
- 编排与 VO 转换（事务、发 MQ、注册延时、调多个仓储、`*VoConvert` → `*ResVO`）在 **CmdExe**
- 并发安全：Repository 状态更新用 `WHERE status = 期望态` 条件更新，失败抛 CONFLICT
- 转换：对外 VO 用 MapStruct `*VoConvert`（在 CmdExe 调用）；DO↔Domain 用 `*DoConvert`

### 异常与响应（统一 JsonResult）

- 业务失败抛 `BusinessException({Module}ErrorCodeEnum.xxx)`，**不要**自定义 `*DomainException`
- 全局 `Demo2GlobalExceptionHandler` 捕获 `BusinessException` 与兜底 `Exception`，HTTP 始终 200，前端以 `code === 0` 判定成功
- Controller 返回 `JsonResult<T>`，用 `JsonResults.ok(...)` 包装
- 错误码分段：`0` 成功 / `1xxxx` 通用 / `2xxxx` 会员 / `3xxxx` 订单 / `4xxxx` 商品（`ProductErrorCodeEnum`）

### HTTP（Demo API）

- 全部 `POST` + `Content-Type: application/json`，无路径变量
- 路径 `/demo/{modules}/{action}`（如 `/demo/orders/orderPlace`）

参考实现：`com.jason.demo.demo2.order`；细节见 `docs/superpowers/specs/2026-08-23-order-ddd-package-refactor-design.md`。

## 新增业务模块

先对照 `order`，别直接写代码：

1. 在 `docs/superpowers/specs/` 写 `{date}-{module}-design.md`（包结构、HTTP 表、领域行为、异常码）
2. 列全 `{Action}CmdExe` 与 ReqVO/ResVO 清单
3. 确认是否依赖 framework（delay/lock/id）；SPI 放 `app.listener` 或 `app.support`
4. 实现后补单测（CmdExe / Repository / 关键 domain 行为）

完整清单见 `docs/superpowers/archive/2026-08-23-order-ddd-package-refactor.md` 第 5 节。

## superpowers 工作流

需求 → 实现走 superpowers 流程：

1. 先写设计规范 `docs/superpowers/specs/{date}-{slug}-design.md`
2. 再写实施计划 `docs/superpowers/plans/{date}-{slug}.md`（带 checkbox，逐 task 实现）
3. 完成后：spec 状态改「已实现」、勾选 checkbox、写 `docs/superpowers/archive/{date}-{slug}.md` 归档
