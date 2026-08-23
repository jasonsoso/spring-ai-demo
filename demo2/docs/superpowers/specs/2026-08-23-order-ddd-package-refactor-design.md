# demo2 订单模块分包与对象模型重构设计规范

**日期**: 2026-08-23  
**项目**: spring-ai-demo / demo2  
**状态**: 已确认，待实现  

---

## 1. 背景与目标

### 1.1 问题

当前 `com.jason.demo.demo2.order` 为扁平结构：Controller、Service、Handler、请求类挤在同一包；表映射类叫 `OrderEntity` 且被 GET 接口直接返回；创建/支付用 `Map<String, Object>` 出参。分层、命名和 HTTP 形态都不符合后续扩展方式。

### 1.2 目标

1. 按「接入 / 领域核心 / 基础设施」重排订单包，业务规则（待支付才能付/取消）进领域对象与领域服务。
2. 表映射类命名为 `OrderDO`；前端只用 `ReqVO` / `ResVO`。
3. 全部订单 HTTP 改为 **POST + Body**，路径不带参数。
4. 对象转换使用 **MapStruct**。
5. 保持现有下单 / 支付 / 超时取消语义与 `demo_order` 表结构不变。

### 1.3 已确认决策

| 维度 | 选择 |
|------|------|
| 分包 | `app` + `service.core` + `service.infrastructure` |
| 依赖方向 | `app → service.core → service.infrastructure`（传统分层，**不是** COLA 依赖倒置） |
| 领域对象 | `Order extends OrderDO`（领域复用表字段，省一次 DO 转换） |
| 领域服务 | `OrderDomainService` 放在 `service.core`，可直接调用 infra 仓储 |
| Maven 模块 | **不拆** `order-core` / `order-infra`；core 与 infra 同属 demo2 单模块 |
| 前端对象 | 按接口拆 `XxxReqVO` / `XxxResVO` |
| 内部 DTO | **本版不做**；DTO 留给以后微服务之间调用；不预建空 `client` 包 |
| 转换 | MapStruct：VO ↔ `Order` / 用例结果；不做 `Order` ↔ `OrderDO` |
| HTTP | 仅 POST；无 GET；无路径参数 |
| 路径 | `POST /demo/orders/create`、`/pay`、`/get` |
| 应用编排 | `OrderApplicationService` 在 `app`：事务 + 延时任务；不依赖 VO |
| 延时回调 | `OrderCancelHandler` 留在 `app` |
| 持久化 | 表 `demo_order` 不变；`OrderEntity` 更名为 `OrderDO` |
| 并发 | 支付/取消仍用 `UPDATE ... WHERE status = PENDING_PAY` |

### 1.4 非目标（本版不做）

- 新增 `client` 模块或 DTO / 二方库 API
- COLA Gateway：领域接口放 core、实现放 infra、core 不依赖 infra
- 把 core / infrastructure 拆成独立 Maven 模块
- 领域事件、独立状态机组件、改期 API
- 修改 `demo_order` / `delay_task` 表结构或延时框架本身
- 兼容旧 URL：`POST /demo/orders`、`GET /demo/orders/{id}`、`POST /demo/orders/{id}/pay`

### 1.5 与 COLA 的关系（刻意取舍）

[COLA](https://github.com/alibaba/COLA) 2.0 之后官方是 **Domain 不依赖 Infrastructure**，用 Gateway 倒置依赖。本版按调用链习惯选择 **core 依赖 infrastructure**，并允许 `Order` 继承 `OrderDO`。这是明确取舍：写起来短，换存储或拆 Maven 模块时成本更高。单模块内 Java 允许 `Order`（core）继承 `OrderDO`（infra）、同时 `OrderMapper` 使用 `Order`。

---

## 2. 架构

### 2.1 逻辑架构

```text
HTTP POST + ReqVO
        │
        ▼
   order.app
   ├── OrderController
   ├── vo / convert（MapStruct）
   ├── OrderApplicationService   事务、DelayTaskService
   └── OrderCancelHandler
        │
        ▼
   order.service.core
   ├── Order extends OrderDO
   ├── OrderStatus
   └── OrderDomainService        直接调仓储
        │
        ▼
   order.service.infrastructure
   ├── OrderDO / OrderMapper / OrderRepository
   └── MySQL demo_order
```

依赖：**app → core → infrastructure**。app 不直接依赖 Mapper/`OrderDO` 类型做业务（查询结果类型是 `Order`）。`DelayTaskService` 仍由 app 调用，领域不感知延时框架。

### 2.2 包与类

```
com.jason.demo.demo2.order
├── app
│   ├── OrderController
│   ├── OrderApplicationService
│   ├── OrderCancelHandler
│   ├── CreateOrderResult
│   ├── vo
│   │   ├── CreateOrderReqVO / CreateOrderResVO
│   │   ├── PayOrderReqVO / PayOrderResVO
│   │   └── GetOrderReqVO / GetOrderResVO
│   └── convert
│       └── OrderVoConvert          # MapStruct
└── service
    ├── core
    │   ├── Order                   # extends OrderDO
    │   ├── OrderStatus
    │   └── OrderDomainService
    └── infrastructure
        ├── OrderDO
        ├── OrderMapper             # BaseMapper<Order>
        └── OrderRepository
```

| 原类型 | 去向 |
|--------|------|
| `OrderController` | `app.OrderController` |
| `OrderService` | 拆为 `app.OrderApplicationService` + `core.OrderDomainService` |
| `OrderCancelHandler` | `app.OrderCancelHandler` |
| `CreateOrderRequest` | `CreateOrderReqVO` 等 |
| `OrderStatus` | `service.core.OrderStatus` |
| `OrderEntity` | `service.infrastructure.OrderDO`；`Order` 继承它 |
| `OrderMapper` / `OrderRepository` | `service.infrastructure` |

### 2.3 各层职责

**app（接入 + 用例编排）**

- Controller：三个 POST，校验协议层错误（缺字段、delay 格式）。
- MapStruct：ReqVO → 应用入参；`Order` / `CreateOrderResult` → ResVO。
- `OrderApplicationService`：入参为基本类型或 `Order`，**不依赖 VO**。负责 `@Transactional`、调 `OrderDomainService`、注册/取消延时任务。
- `CreateOrderResult`：仅 create 用例需要（`taskId`、`delay` 不属于订单表）。不是微服务 DTO。
- `OrderCancelHandler`：实现既有 `DelayTaskHandler`，解析 `bizKey` 后走领域取消。

**service.core（领域核心）**

- `Order`：在 `OrderDO` 字段上增加 `create` / `pay` / `cancel`。
- `OrderDomainService`：组织「找单、调聚合、调仓储」；可注入 `OrderRepository`。
- 不调用 `DelayTaskService`，不返回 ResVO。

**service.infrastructure**

- `OrderDO`：`@TableName("demo_order")`，字段与现表一致。
- `OrderMapper extends BaseMapper<Order>`：插入/更新的是子类 `Order`，避免只认父类导致行为字段丢失。
- `OrderRepository`：`insert` / `findById`（返回 `Order`）/ `markPaid` / `markCancelled`（条件更新）。

---

## 3. HTTP 与对象

全部 **POST**，`Content-Type: application/json`，id 只出现在 Body。

| 路径 | 入参 | 出参 |
|------|------|------|
| `POST /demo/orders/create` | `CreateOrderReqVO`：`amount`，可选 `delay`（`30s` / `PT30S` 等，语义与现解析一致） | `CreateOrderResVO`：`orderId, status, amount, taskId, delay` |
| `POST /demo/orders/pay` | `PayOrderReqVO`：`orderId` | `PayOrderResVO`：`orderId, status` |
| `POST /demo/orders/get` | `GetOrderReqVO`：`orderId` | `GetOrderResVO`：`orderId, status, amount, createdAt, updatedAt` |

删除：`POST /demo/orders`、`GET /demo/orders/{id}`、`POST /demo/orders/{id}/pay`。

`CreateOrderReqVO.delay` 为字符串；应用层使用 `Duration`。转换在 `OrderVoConvert` 的自定义方法中复用现有 `parseDelay` 规则；非法格式 → 400。

---

## 4. 领域行为

与现网规则一致，从 `OrderService` 挪到 `Order` / `OrderDomainService`。

| 行为 | 规则 |
|------|------|
| `Order.create(orderId, amount, now)` | `amount > 0`，状态 `PENDING_PAY`，写入金额与时间戳 |
| `pay()` | 仅 `PENDING_PAY` → `PAID` 并刷新 `updatedAt`；否则抛领域异常 |
| `cancel()` | 仅 `PENDING_PAY` → `CANCELLED` 并返回 `true`；否则返回 `false`（超时任务跳过，不抛错） |

雪花 ID 仍由 app 向 `SnowflakeIdGenerator` 申请后传入 `create`。状态在库中存枚举名字符串，与现实现一致。

### 4.1 用例数据流

**create：** ReqVO → Duration → `OrderApplicationService.create(amount, delay)` → `Order.create` → `repository.insert` → `delayTaskService.schedule`（同一事务）→ `CreateOrderResult` → `CreateOrderResVO`。未传 delay 时用 `DelayProperties.getDefaultDelay()`。

**pay：** `orderId` → DomainService 加载 → `order.pay()` → `markPaid`（0 行则冲突）→ `delayTaskService.cancelByBizKey` → `PayOrderResVO`。

**get：** `orderId` → `findById`，空则 404 → `GetOrderResVO`。

**超时取消：** Handler 解析 `bizKey` → 找不到则打日志返回 → `order.cancel()` 为 false 则跳过 → 否则 `markCancelled`。

---

## 5. 异常与并发

| 情况 | HTTP |
|------|------|
| `amount` 缺失或非正、`delay` 非法、`orderId` 缺失 | 400 |
| 订单不存在 | 404 |
| 非待支付却 pay，或条件更新 0 行 | 409 |

领域异常由 app 映射为 HTTP，**不要**在 `OrderDomainService` 里抛 `ResponseStatusException`。超时取消路径不把「已支付/已取消」当成错误。

`markPaid` / `markCancelled` 保持：

```text
UPDATE ... SET status=目标, updated_at=now
WHERE order_id=? AND status='PENDING_PAY'
```

---

## 6. MapStruct 与工程配置

1. `demo2/pom.xml` 增加 `mapstruct` 与 `mapstruct-processor`；`maven-compiler-plugin` 的 `annotationProcessorPaths` 中 **先 lombok、后 mapstruct**，避免与现有 Lombok 冲突。
2. `OrderVoConvert`：`componentModel = "spring"`。
3. `@MapperScan`（`DelayMybatisPlusConfig`）从 `com.jason.demo.demo2.order.repository` 改为 `com.jason.demo.demo2.order.service.infrastructure`。

---

## 7. 测试与文档

| 项 | 要求 |
|----|------|
| `Order` / `OrderDomainService` | 覆盖 create 金额校验、pay 成功/错误状态、cancel true/false |
| `OrderApplicationService` | 原 `OrderServiceTest`：下单注册延时、支付取消延时、404、非待支付 冲突 |
| `OrderCancelHandlerTest` | 待支付取消、已支付跳过；import 改到新包 |
| `JacksonJsonCustomizerTest` | 不再把表实体当 API 响应；可对 `OrderDO` 或 `Order` 测 Long/时间序列化 |
| README | 更新 `/demo/orders` 三段 curl 为 create/pay/get + JSON Body |

行为断言与现测试一致，只改类型与 URL，不改延时/支付语义。

---

## 8. 范围外联动

- `framework.delay`、`SnowflakeIdGenerator`、订单表 SQL **不改**。
- 延时 Demo 的其它文档若写死旧 URL，实现时与 README 一并改。
- 以后若做微服务：在 `client` 增加 API + DTO，app 的 RPC 实现转调 `OrderApplicationService`；本版不为它预留空类。
