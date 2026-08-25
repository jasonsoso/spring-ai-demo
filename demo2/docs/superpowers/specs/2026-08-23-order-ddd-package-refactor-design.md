# demo2 订单模块分包与对象模型重构设计规范

**日期**: 2026-08-23  
**项目**: spring-ai-demo / demo2  
**状态**: 已实现（样板模块，见 archive/2026-08-23-order-ddd-package-refactor.md）  

---

## 1. 背景与目标

### 1.1 问题

当前 `com.jason.demo.demo2.order` 为扁平结构：Controller、Service、Handler、请求类挤在同一包；表映射类叫 `OrderEntity` 且被 GET 接口直接返回；创建/支付用 `Map<String, Object>` 出参。分层、命名和 HTTP 形态都不符合后续扩展方式。

### 1.2 目标

1. 按「接入 / 领域核心 / 基础设施」重排订单包，业务规则进领域对象与领域服务。
2. 表映射类命名为 `OrderDO`（`dao.entity`）；前端只用 `ReqVO` / `ResVO`。
3. 全部订单 HTTP 改为 **POST + Body**；**仅下单**接口/action 使用 **orderPlace** 命名（替代原 create），其余动作用 pay / get / cancel。
4. **新增手动取消订单**：未支付（`PENDING_PAY`）可取消；与延时 MQ/扫描触发的超时取消并列，共三条取消路径。
5. 应用层用 **CmdExe** + **MapStruct**；同步更新前端 Demo 与 README。

### 1.3 已确认决策

| 维度 | 选择 |
|------|------|
| 下单命名 | 仅 **`OrderPlace*`** / **`orderPlace`**（CmdExe、VO、URL）；pay/get/cancel **不带 Place** |
| 分包 | `app` + `service.common` + `service.core` + `service.infrastructure` |
| 依赖方向 | `app → service.core → service.infrastructure` |
| CmdExe | `OrderPlaceCmdExe`、`OrderPaySuccessCmdExe`、`OrderGetCmdExe`、`OrderExpireCmdExe`、`OrderCancelCmdExe` |
| 取消路径 | ① 延时主路径（Redisson/MQ）→ `listener.OrderCancelHandler` → `OrderExpireCmdExe`；② 扫描兜底触发同一延时执行链；③ **手动** `POST /demo/orders/cancel` → `OrderCancelCmdExe` |
| 取消规则 | 仅 `PENDING_PAY` 可取消；手动取消成功后 **取消对应延时任务**（`cancelByBizKey`） |
| 延时 SPI | `OrderCancelHandler` 薄适配，只调 `OrderExpireCmdExe` |
| DAO | `dao.entity.OrderDO` + `dao.mapper.OrderMapper`（`BaseMapper<OrderDO>`） |
| Repository | `OrderRepository` + `OrderDoConvert`；对外只用 `Order` |
| 转换 | `OrderVoConvert`（VO）；`OrderDoConvert`（`Order` ↔ `OrderDO`） |
| HTTP 路径 | `orderPlace`、`pay`、`get`、`cancel`（均 `POST /demo/orders/{action}`） |

### 1.4 非目标

- `client` 模块 / 微服务 DTO
- COLA 依赖倒置、拆 Maven 子模块
- 兼容旧 URL（`POST /demo/orders`、`GET .../{id}`、`POST .../{id}/pay`）

---

## 2. 架构

### 2.1 包与类

```
com.jason.demo.demo2.order
├── app
│   ├── controller / OrderController
│   ├── executor
│   │   ├── OrderPlaceCmdExe
│   │   ├── OrderPaySuccessCmdExe
│   │   ├── OrderGetCmdExe
│   │   ├── OrderExpireCmdExe          # 超时取消（延时框架回调）
│   │   └── OrderCancelCmdExe          # 手动取消（HTTP）
│   ├── listener
│   │   └── OrderCancelHandler         # DelayTaskHandler → OrderExpireCmdExe
│   ├── job                              # 预留
│   ├── OrderPlaceResult
│   ├── vo
│   │   ├── OrderPlaceReqVO / OrderPlaceResVO
│   │   ├── PayOrderReqVO / PayOrderResVO
│   │   ├── GetOrderReqVO / GetOrderResVO
│   │   └── CancelOrderReqVO / CancelOrderResVO
│   └── convert / OrderVoConvert
└── service
    ├── common / OrderStatus
    ├── core
    │   ├── domain / Order               # extends OrderDO
    │   └── OrderDomainService
    └── infrastructure
        ├── dao
        │   ├── entity / OrderDO
        │   └── mapper / OrderMapper
        └── repository
            ├── OrderRepository
            └── convert / OrderDoConvert
```

### 2.2 取消订单三条路径

```text
路径 1 & 2（延时框架，主投递 Redisson/MQ + 扫描兜底）
  DelayTask 到期 → OrderCancelHandler.handle
    → OrderExpireCmdExe.execute(orderId)
    → cancel() + markCancelled（已是终态则跳过，不抛错）

路径 3（用户手动）
  POST /demo/orders/cancel
    → OrderCancelCmdExe.execute(orderId)
    → cancel() + markCancelled + delayTaskService.cancelByBizKey
    → 非 PENDING_PAY → 409
```

`OrderExpireCmdExe` 与 `OrderCancelCmdExe` 共用 `OrderDomainService` 的取消逻辑（加载、`order.cancel()`、条件更新）；**仅手动取消**额外撤销台账/延时投递。

---

## 3. HTTP 与对象

全部 **POST**，`Content-Type: application/json`，Body 传参，**无路径变量**。

| 路径 | Controller 方法 | CmdExe | ReqVO | ResVO |
|------|-----------------|--------|-------|-------|
| `POST /demo/orders/orderPlace` | `orderPlace` | `OrderPlaceCmdExe` | `OrderPlaceReqVO` | `OrderPlaceResVO` |
| `POST /demo/orders/pay` | `pay` | `OrderPaySuccessCmdExe` | `PayOrderReqVO` | `PayOrderResVO` |
| `POST /demo/orders/get` | `get` | `OrderGetCmdExe` | `GetOrderReqVO` | `GetOrderResVO` |
| `POST /demo/orders/cancel` | `cancel` | `OrderCancelCmdExe` | `CancelOrderReqVO` | `CancelOrderResVO` |

> 相对旧版：仅 **`create` → `orderPlace`**；`pay` / `get` 保持简短动作名；`get` 由 GET 改为 POST+Body；新增 `cancel`。

### 3.1 报文示例

**orderPlace**

```json
// Req
{ "amount": 9.9, "delay": "10s" }
// Res
{ "orderId": "...", "status": "PENDING_PAY", "amount": "9.9", "taskId": "...", "delay": "PT10S" }
```

**pay**

```json
// Req
{ "orderId": "2085550503315509248" }
// Res
{ "orderId": "2085550503315509248", "status": "PAID" }
```

**get**

```json
// Req
{ "orderId": "2085550503315509248" }
// Res
{ "orderId": "...", "status": "CANCELLED", "amount": "9.9", "createdAt": "...", "updatedAt": "..." }
```

**cancel**

```json
// Req
{ "orderId": "2085550503315509248" }
// Res
{ "orderId": "2085550503315509248", "status": "CANCELLED" }
```

Long / BigDecimal 序列化沿用 `JacksonJsonCustomizer`。`delay` 在 `OrderVoConvert` 中转 `Duration`。

---

## 4. 领域行为

| 行为 | 规则 |
|------|------|
| `Order.create(...)` | `amount > 0` → `PENDING_PAY` |
| `pay()` | 仅 `PENDING_PAY` → `PAID`；否则异常 |
| `cancel()` | 仅 `PENDING_PAY` → `CANCELLED` 返回 `true`；否则 `false`（超时路径跳过） |

### 4.1 用例数据流

| 用例 | 流程 |
|------|------|
| orderPlace | `Order.create` → insert → schedule 延时取消任务 → `OrderPlaceResult` |
| pay | 加载 → `pay()` → markPaid → cancelByBizKey |
| get | findById → 404 或 ResVO |
| cancel（手动） | 加载 → `cancel()` 失败则 409 → markCancelled → cancelByBizKey |
| expire（超时） | Handler → `OrderExpireCmdExe` → cancel() false 则日志跳过 → markCancelled |

---

## 5. Repository 与 DAO

```text
OrderRepository + OrderDoConvert
  insert(Order)       → toDo → mapper.insert
  findById            → toDomain
  markPaid / markCancelled → LambdaUpdateWrapper<OrderDO>，WHERE status=PENDING_PAY
```

`@MapperScan` → `...infrastructure.dao.mapper`。

---

## 6. 异常与并发

| 情况 | HTTP |
|------|------|
| 参数非法 | 400 |
| 订单不存在 | 404 |
| 非待支付 pay / cancel，或条件更新 0 行 | 409 |

超时取消（expire）不返回 HTTP，只打日志。

---

## 7. 前端与文档（实现必做）

| 文件 | 改动 |
|------|------|
| `static/js/tabs/order-delay.js` | `orderPlace` / `pay` / `get` / **cancel** 四个 POST + JSON Body |
| `static/index.html` | 订单 Demo 区增加 **「取消订单」** 按钮，绑定 `orderDelayCancel()` |
| `demo2/README.md` | 更新 curl（含 cancel 示例） |

示例 fetch：

```javascript
// 下单
fetch('/demo/orders/orderPlace', { method: 'POST', headers: {...}, body: JSON.stringify({ amount, delay }) })
// 支付
fetch('/demo/orders/pay', { method: 'POST', body: JSON.stringify({ orderId }) })
// 查询
fetch('/demo/orders/get', { method: 'POST', body: JSON.stringify({ orderId }) })
// 取消
fetch('/demo/orders/cancel', { method: 'POST', body: JSON.stringify({ orderId }) })
```

台账 `/demo/delay-tasks?bizKey=` 不变。

---

## 8. 测试

| 项 | 场景 |
|----|------|
| `OrderPlaceCmdExe` | 下单 + 注册延时 |
| `OrderPaySuccessCmdExe` | 支付 + 取消延时任务 |
| `OrderCancelCmdExe` | 待支付取消成功；已支付/已取消 → 409；取消后 cancelByBizKey |
| `OrderExpireCmdExe` + Handler | 超时取消；已支付跳过 |
| `OrderGetCmdExe` | 查询 / 404 |
| `OrderRepository` + `OrderDoConvert` | Order 往返 |
| 前端冒烟 | orderPlace → cancel → get 为 CANCELLED；或 orderPlace → 等 expire |

---

## 9. 范围外

- `framework.delay`、表结构不改
- 以后微服务：`client` + DTO → 调 `app.executor`
