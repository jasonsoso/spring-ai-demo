# demo2 订单模块重构（COLA 状态机）设计规范

**日期**: 2026-08-28  
**项目**: spring-ai-demo / demo2  
**状态**: 待实现  
**前置**: [2026-08-23-order-ddd-package-refactor-design.md](./2026-08-23-order-ddd-package-refactor-design.md)、[2026-08-26-product-module-design.md](./2026-08-26-product-module-design.md)、[2026-08-27-redis-stock-consistency-design.md](./2026-08-27-redis-stock-consistency-design.md)  
**参考**: [digital-food-market-center 订单状态机深度分析](https://my.feishu.cn/wiki/Ui0iwV6GsijkqZkPSZXc0eonnCh)（COLA 4.3.2 装配与流转；demo2 不搬促销/0 元单/退单/Dubbo）

---

## 1. 背景与目标

### 1.1 背景

- 现有 `order` 模块是延时取消 Demo：手填 `amount` 下单，状态 `PENDING_PAY / PAID / CANCELLED`，不关联商品与库存。
- 商品模块已交付列表/详情与 `ProductStockHotService.reserve/confirm/release`；C 端「立即购买」仍 disabled；订单列表为静态占位。
- 商品 spec 明确把「订单主表 + 明细表改造」留给本 spec。

### 1.2 目标

1. 用阿里 COLA 状态机管理**订单状态**流转；支付状态只作伴随字段，不单独做状态机。
2. C 端打通：商品详情 → 预览（不落库）→ 下单（预占库存）→ 模拟支付完成 → 我的订单（全部 / 待支付 / 已完成）。
3. 订单主表演进 + 新建 `demo_order_item`（下单商品快照）；一单一品、数量 1~99999。
4. 废弃手填金额下单；库存写路径只调 `ProductStockHotService`。

### 1.3 已确认决策

| 维度 | 选择 |
|------|------|
| 预下单 | 不落库、不占库存；`INIT` 仅作状态机起点 |
| 状态机职责 | COLA 只管订单状态；CmdExe 编排校验/落库/库存/延时 |
| 依赖 | 只引入 `cola-component-statemachine`（4.3.2，对齐参考项目），不改成完整 COLA 分层 |
| 列表 Tab | 全部 / 待支付（`SUBMIT`）/ 已完成（`COMPLETED`）；已取消只出现在「全部」 |
| 冒泡 | 待支付、已完成各有数量；「全部」无冒泡；**独立** `counts` 接口 |
| 购买形态 | 立即购买；一单一品，qty∈[1,99999]；表结构预留一单多行 |
| 价格 | 下单再校验：下架/库存不足失败；售价与预览不一致 → `PRICE_CHANGED` |
| 登录 | 点「立即购买」先登录，再进预览；预览/下单/列表/详情均 `@LoginRequired` |
| 调试面板 | **去掉创建订单**；支付/取消/查询 + 延时台账保留（用 C 端产生的 orderId） |
| 库存 | 只接 `ProductStockHotService`（开关开走 Redis Lua，关走行锁） |
| 超时 | 沿用现有 delay：`OrderCancelHandler` → `OrderExpireCmdExe` → `ORDER_EXPIRE` |
| 支付 | 仍为模拟成功，无真实渠道 |

### 1.4 非目标

- 真支付 / 预支付单 / 购物车 / 一单多品下单（表可扩展，本阶段接口只收一个商品）
- 0 元单（`ZERO_ORDER`）、退单（`RETURN`）、优惠券、活动库存
- 完整 COLA 多模块、Dubbo、分库分表落地（明细带 `member_id` 仅为后续分片预留）
- 兼容旧 `orderPlace({ amount })` 与旧状态名 `PENDING_PAY / PAID / CANCELLED`

---

## 2. 状态机

### 2.1 流转

```text
INIT --SUBMIT_ORDER--> SUBMIT --PAY_SUCCESS--> COMPLETED
                       SUBMIT --CANCEL_ORDER--> CANCEL
                       SUBMIT --ORDER_EXPIRE--> CANCEL
```

`INIT` 不落库。终态：`COMPLETED`、`CANCEL`（`OrderStatusEnum.isFinalStatus`）。

### 2.2 枚举

**`OrderStatusEnum`**：`INIT`（仅状态机）、`SUBMIT`、`COMPLETED`、`CANCEL`。

**`OrderEventEnum`**：`SUBMIT_ORDER`、`PAY_SUCCESS`、`CANCEL_ORDER`、`ORDER_EXPIRE`。

**`PayStatusEnum`**（伴随字段，非状态机）：`WAIT_PAY`、`PAY_SUCCESS`、`CLOSE`。

订单事件成功后顺手写支付状态与时间：

| 事件 | `order_status` | `pay_status` | 时间字段 |
|------|----------------|--------------|----------|
| `SUBMIT_ORDER` | `SUBMIT` | `WAIT_PAY` | — |
| `PAY_SUCCESS` | `COMPLETED` | `PAY_SUCCESS` | `pay_time = now` |
| `CANCEL_ORDER` / `ORDER_EXPIRE` | `CANCEL` | `CLOSE` | `cancel_time = now` |

列表 Tab、能否支付/取消，**只看 `order_status`**。

### 2.3 组件与职责

只加 COLA 组件，包仍是现有 DDD：`app → service.core → service.infrastructure`。

| 类 | 层 | 职责 |
|----|----|------|
| `OrderStateMachineConfiguration` | `order.app.statemachine` | `@Configuration`；Builder 声明 4 条 `externalTransition`；`machineId = orderStateMachine` |
| `OrderStateMachineExecutor` | `order.app.statemachine` | 统一 `fireEvent(source, event, context)`；FailCallback → `ORDER_STATUS_CONFLICT`（HTTP 的 pay/cancel 使用；超时路径见 2.4，**先判断状态再 fire**） |
| `OrderContext` | `order.app.statemachine` | 承载 `Order` 聚合（下单时内存中新建） |
| `OrderPlaceAction` / `OrderPaySuccessAction` / `OrderCancelAction` / `OrderExpireAction` | **Spring Bean**（禁止匿名 Action，避免事务代理失效） | **只改聚合根字段**（`orderStatus` / `payStatus` / `payTime` / `cancelTime`） |
| `*CmdExe` | `order.app.executor` | 校验、落库、调库存、注册/撤销延时 |

Action 与参考项目的差别：飞书文档里 `OrderPlaceAction` 做校验/算价/库存/落库；demo2 把副作用留在 CmdExe。

### 2.4 调用链

**预览**（不进状态机）：

```text
POST /preview → OrderPreviewCmdExe
  → 校验登录、qty、上架、可售库存
  → 返回快照 + amount（sellPrice * qty）
```

**下单**：

```text
POST /orderPlace → OrderPlaceCmdExe
  → 再读商品：下架 / 库存不足 / sellPrice.compareTo(当前售价) != 0
  → 组装内存 Order（尚无 order_status）
  → fireEvent(INIT, SUBMIT_ORDER) → SUBMIT + WAIT_PAY
  → ProductStockHotService.reserve(productId, orderId, qty)
  → insert 主表 + 一行明细
  → delayTaskService.schedule(ORDER_CANCEL, orderId, delay)
```

若 `reserve` 已成功而后续 insert/schedule 失败：CmdExe 捕获后 `release` 再抛错，避免预占悬挂。

**支付**：

```text
fireEvent(SUBMIT, PAY_SUCCESS) → CAS WHERE order_status=SUBMIT
  → confirm(productId, orderId, qty)
  → cancelByBizKey(ORDER_CANCEL, orderId)
```

**手动取消**：同上，事件 `CANCEL_ORDER`，`release`，撤销延时。非 `SUBMIT` → `ORDER_STATUS_CONFLICT`。

**超时**：`OrderExpireCmdExe` 无登录态，按 `orderId` 加载。若订单不存在或 `order_status != SUBMIT`，打日志跳过且**不** `fireEvent`（避免 FailCallback 把「已支付后到期」变成错误）。仅 `SUBMIT` 时 `fireEvent(SUBMIT, ORDER_EXPIRE)`；CAS 0 行同样跳过；成功则 `release`。

---

## 3. 数据模型

### 3.1 ER

```text
demo_order (1) ── (N) demo_order_item     -- 本阶段 N=1
     │
     └── 库存流水按 (order_id, product_id) 关联 demo_product_stock_log
```

### 3.2 `demo_order`（演进现表）

将 `status` **重命名**为 `order_status`，避免与新列并存。新增 `pay_status`、`pay_time`、`cancel_time`。

```sql
ALTER TABLE demo_order
    CHANGE COLUMN status order_status VARCHAR(32) NOT NULL
        COMMENT '订单状态: SUBMIT=已提交, COMPLETED=已完成, CANCEL=已取消',
    ADD COLUMN pay_status VARCHAR(32) NOT NULL DEFAULT 'WAIT_PAY'
        COMMENT '支付状态(伴随): WAIT_PAY/PAY_SUCCESS/CLOSE' AFTER order_status,
    ADD COLUMN pay_time DATETIME(3) NULL COMMENT '支付完成时间' AFTER pay_status,
    ADD COLUMN cancel_time DATETIME(3) NULL COMMENT '取消/超时时间' AFTER pay_time;

ALTER TABLE demo_order
    DROP INDEX idx_demo_order_status,
    ADD INDEX idx_demo_order_member_status_time (member_id, order_status, created_at);
```

完整建表形态（新环境以模块 DDL 为准）：

```sql
CREATE TABLE IF NOT EXISTS demo_order (
    order_id     BIGINT        NOT NULL COMMENT '订单ID（雪花）',
    member_id    BIGINT        NOT NULL COMMENT '下单会员ID',
    order_status VARCHAR(32)   NOT NULL COMMENT 'SUBMIT/COMPLETED/CANCEL',
    pay_status   VARCHAR(32)   NOT NULL COMMENT 'WAIT_PAY/PAY_SUCCESS/CLOSE',
    amount       DECIMAL(12,2) NOT NULL COMMENT '应付金额 = sell_price * qty',
    pay_time     DATETIME(3)   NULL COMMENT '支付完成时间',
    cancel_time  DATETIME(3)   NULL COMMENT '取消/超时时间',
    created_at   DATETIME(3)   NOT NULL,
    updated_at   DATETIME(3)   NOT NULL,
    PRIMARY KEY (order_id),
    INDEX idx_demo_order_member_status_time (member_id, order_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演示订单主表';
```

已有 Demo 数据映射（一次性）：`PENDING_PAY`→`SUBMIT`+`WAIT_PAY`；`PAID`→`COMPLETED`+`PAY_SUCCESS`（`pay_time=updated_at`）；`CANCELLED`→`CANCEL`+`CLOSE`（`cancel_time=updated_at`）。无明细的历史单不进入 C 端列表（`list` 只返回有明细的订单），调试 `get` 若无明细则 `items` 为空数组。

### 3.3 `demo_order_item`（新建）

```sql
CREATE TABLE IF NOT EXISTS demo_order_item (
    id            BIGINT         NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键',
    item_id       BIGINT         NOT NULL COMMENT '明细业务ID（雪花）',
    order_id      BIGINT         NOT NULL COMMENT '订单ID',
    member_id     BIGINT         NOT NULL COMMENT '会员ID（后续分片键预留）',
    product_id    BIGINT         NOT NULL COMMENT '商品ID',
    product_name  VARCHAR(128)   NOT NULL COMMENT '商品名称快照',
    subtitle      VARCHAR(255)   NOT NULL DEFAULT '' COMMENT '副标题快照',
    cover_url     VARCHAR(512)   NULL COMMENT '封面快照',
    sell_price    DECIMAL(10,2)  NOT NULL COMMENT '售价快照',
    market_price  DECIMAL(10,2)  NULL COMMENT '划线价快照',
    qty           INT UNSIGNED   NOT NULL DEFAULT 1 COMMENT '购买数量，1~99999',
    created_at    DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_demo_order_item_item_id (item_id),
    INDEX idx_demo_order_item_order (order_id),
    INDEX idx_demo_order_item_member_order (member_id, order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演示订单明细（商品快照）';
```

快照以下单当时商品为准；之后改价/改名/下架不影响已下单。本阶段一单一行；以后一单多商品不必改表。

`OrderDO` 仍以 `order_id` 为 `@TableId(INPUT)`。`OrderItemDO` 以自增 `id` 为 `@TableId(AUTO)`，对外用 `item_id`。

---

## 4. 包与类清单

在现有 `com.jason.demo.demo2.order` 上增量，不恢复扁平包。

```text
order
├── app
│   ├── controller / OrderController
│   ├── executor
│   │   ├── OrderPreviewCmdExe
│   │   ├── OrderPlaceCmdExe          # 入参改为商品，不再收 amount
│   │   ├── OrderPaySuccessCmdExe
│   │   ├── OrderCancelCmdExe
│   │   ├── OrderExpireCmdExe
│   │   ├── OrderGetCmdExe
│   │   ├── OrderListCmdExe
│   │   └── OrderCountsCmdExe
│   ├── listener / OrderCancelHandler
│   ├── statemachine
│   │   ├── OrderStateMachineConfiguration
│   │   ├── OrderStateMachineExecutor
│   │   ├── OrderContext
│   │   └── action / OrderPlaceAction, OrderPaySuccessAction,
│   │                 OrderCancelAction, OrderExpireAction
│   ├── vo/req|res …
│   └── convert / OrderVoConvert
└── service
    ├── common
    │   ├── OrderStatusEnum
    │   ├── OrderEventEnum
    │   ├── PayStatusEnum
    │   └── OrderErrorCodeEnum
    ├── core
    │   ├── domain / Order, OrderItem
    │   └── OrderDomainService
    └── infrastructure
        ├── dao/entity / OrderDO, OrderItemDO
        ├── dao/mapper / OrderMapper, OrderItemMapper
        └── repository / OrderRepository, OrderItemRepository
                         + convert
```

自定义 SQL 仍写 `src/main/resources/mapper/order/*.xml`。`pom.xml` 增加 `cola-component-statemachine` 4.3.2。

---

## 5. HTTP

全部 **POST** + JSON Body + **`@LoginRequired`**。无路径变量。

| 路径 | CmdExe | ReqVO | ResVO |
|------|--------|-------|-------|
| `/demo/orders/preview` | `OrderPreviewCmdExe` | `OrderPreviewReqVO` | `OrderPreviewResVO` |
| `/demo/orders/orderPlace` | `OrderPlaceCmdExe` | `OrderPlaceReqVO` | `OrderPlaceResVO` |
| `/demo/orders/pay` | `OrderPaySuccessCmdExe` | `PayOrderReqVO` | `PayOrderResVO` |
| `/demo/orders/cancel` | `OrderCancelCmdExe` | `CancelOrderReqVO` | `CancelOrderResVO` |
| `/demo/orders/get` | `OrderGetCmdExe` | `GetOrderReqVO` | `GetOrderResVO` |
| `/demo/orders/list` | `OrderListCmdExe` | `OrderListReqVO` | `OrderListResVO` |
| `/demo/orders/counts` | `OrderCountsCmdExe` | 无请求体 | `OrderCountsResVO` |

`counts` 的 `@Operation` 注明「无请求体」，签名与 `listProducts` 相同：`@RequestBody(required = false) Object ignored`。

### 5.1 字段约定

**`OrderPreviewReqVO` / `OrderPlaceReqVO` 公共**

| 字段 | 校验 |
|------|------|
| `productId` | `@NotNull` |
| `qty` | `@NotNull` `@Min(1)` `@Max(99999)` |

**`OrderPlaceReqVO` 另加**

| 字段 | 校验 |
|------|------|
| `sellPrice` | `@NotNull` `@DecimalMin("0.01")` `@Digits`；必须与当前商品售价 `compareTo == 0` |
| `delay` | 可选 `@DelayFormat`；空则用 `DelayProperties.defaultDelay`（现 30s）。C 端不传 |

**`OrderListReqVO`**

| 字段 | 说明 |
|------|------|
| `tab` | `@NotNull`：`ALL` / `SUBMIT` / `COMPLETED`（用枚举 `OrderListTabEnum`） |
| `pageNo` | 默认 1，`@Min(1)` |
| `pageSize` | 默认 20，`@Min(1)` `@Max(50)` |

列表按 `created_at DESC`。`ALL` 含 `CANCEL`；`SUBMIT`/`COMPLETED` 不含取消。只返回当前登录会员、且至少有一行明细的订单。

**`OrderCountsResVO`**：`pendingCount`（`SUBMIT`）、`completedCount`（`COMPLETED`）。C 端进订单 Tab / 下拉刷新时调一次，与 `list` 解耦。

**`GetOrderResVO` / 列表项**：`orderId`、`orderStatus`、`payStatus`、`amount`、`payTime`、`cancelTime`、`createdAt`、`items[]`（明细快照 + `qty`）。列表项可只带封面/名称/qty/金额以减小 payload，详情带全量快照。废弃响应字段名 `status`，统一 `orderStatus`。

### 5.2 报文示例

**preview**

```json
// Req
{ "productId": "2085550503315509001", "qty": 2 }
// Res.data
{
  "productId": "2085550503315509001",
  "productName": "拿铁",
  "subtitle": "经典浓郁，口感顺滑",
  "coverUrl": null,
  "sellPrice": 18.00,
  "marketPrice": null,
  "qty": 2,
  "amount": 36.00,
  "availableStock": 100
}
```

**orderPlace**

```json
// Req
{ "productId": "2085550503315509001", "qty": 2, "sellPrice": 18.00 }
// Res.data
{ "orderId": "…", "orderStatus": "SUBMIT", "payStatus": "WAIT_PAY", "amount": 36.00, "taskId": "…", "delay": "PT30S" }
```

**counts**（无 Body）

```json
{ "pendingCount": 3, "completedCount": 11 }
```

**list**

```json
// Req
{ "tab": "SUBMIT", "pageNo": 1, "pageSize": 20 }
// Res.data
{ "pageNo": 1, "pageSize": 20, "total": 3, "items": [ { "orderId": "…", "orderStatus": "SUBMIT", "amount": 36.00, "items": [ { "productName": "拿铁", "qty": 2, "sellPrice": 18.00, "coverUrl": null } ] } ] }
```

---

## 6. 领域与仓储

### 6.1 行为

| 行为 | 规则 |
|------|------|
| 预览 | qty∈[1,99999]；商品上架；`availableStock >= qty`；不算价优惠 |
| 下单 | 再读商品；`sellPrice` 与当前售价 `compareTo == 0`；`amount = sellPrice * qty`（`RoundingMode.UNNECESSARY` 两位小数） |
| 支付 | 仅 `SUBMIT`；写 `pay_time` |
| 取消 | 仅 `SUBMIT`；写 `cancel_time`；HTTP 路径失败抛冲突 |
| 超时 | 仅 `SUBMIT` 成功；否则 skip |

`Order.create` 不再接收手填 `amount`。领域对象不再用 if-else 替代状态机；`pay()`/`cancel()` 改为由 Action 写字段，Repository CAS 与状态机目标态一致。

### 6.2 Repository

- `insert` 主表 + 明细（同一本地事务）。
- `markCompleted`：`WHERE order_status = SUBMIT`，set `COMPLETED` / `PAY_SUCCESS` / `pay_time`。
- `markCancelled`：`WHERE order_status = SUBMIT`，set `CANCEL` / `CLOSE` / `cancel_time`。超时版不带 `member_id` 条件。
- `countByMemberAndStatus(memberId, SUBMIT|COMPLETED)` 供 `counts`。
- `pageByMemberAndTab`：XML 分页；`ALL` 不滤状态。
- 明细按 `order_id`（或 `order_id IN`）批量加载，避免 N+1。

---

## 7. 异常码 `OrderErrorCodeEnum`

| 码 | 枚举 | 说明 |
|----|------|------|
| 30001 | `ORDER_NOT_FOUND` | 订单不存在或不属于当前会员 |
| 30002 | `ORDER_STATUS_CONFLICT` | 非法流转、CAS 0 行、COLA FailCallback |
| 30007 | `QTY_INVALID` | qty 非 1~99999（Bean Validation 已拦一层；领域再拦则用此码） |
| 30008 | `PRICE_CHANGED` | 下单售价与当前商品售价不一致 |

复用商品码（预览与下单）：`PRODUCT_NOT_FOUND`、`PRODUCT_OFF_SHELF`、`STOCK_INSUFFICIENT`、`STOCK_SYNC_LAG` 等。

废弃不再使用：`AMOUNT_REQUIRED`（30005）。`AMOUNT_INVALID`（30003）仅当计算出的应付 ≤ 0 时保留。`ORDER_ID_REQUIRED` / `INVALID_DELAY` 仍由 Validation + 全局处理器覆盖。

超时路径不向 HTTP 返回；已终态只打日志。

---

## 8. 并发与库存

- 支付/取消/超时更新必须带 `WHERE order_status = SUBMIT`。
- 库存：`ProductStockHotService.reserve/confirm/release`；订单侧按 `orderId + productId + qty` 调用，不直接碰 Mapper/Lua。
- 预览**只读**可售（与商品详情同一数据源：热库存开启则 overlay Redis `avail`），不 reserve。
- 一单一品：confirm/release 针对该行 `product_id`。

下单补偿：`reserve` 成功、`insert` 失败 → `release` 后抛原异常。

---

## 9. C 端（`member.js`）

### 9.1 购买流

1. 商品详情「立即购买」启用。未登录 → 现有 Auth Sheet；登录成功后进入预览（记住 `productId`）。
2. 预览页：调 `preview`；可改 qty（1~99999，且 ≤ `availableStock`）；展示快照与应付。
3. 「提交订单」带预览返回的 `sellPrice` 调 `orderPlace`；`PRICE_CHANGED` 提示刷新并重新 `preview`。
4. 成功进入待支付详情：去支付 / 取消。
5. 支付走现有模拟 `pay`。

### 9.2 订单 Tab

- 三个 Tab：全部 / 待支付 / 已完成。
- 进入 Tab 或支付/取消成功后：并行 `counts` + `list`。
- 待支付、已完成 Tab 上显示 `pendingCount` / `completedCount`（为 0 不显示冒泡）。
- 点卡片 → `get` 详情；`SUBMIT` 显示支付与取消。

### 9.3 右侧调试面板

去掉「创建待支付订单」及金额输入。保留 orderId 输入、支付、取消、刷新订单+台账，便于测超时。C 端下单后可把 `orderId` 填进去。

---

## 10. 测试

| 项 | 场景 |
|----|------|
| 状态机 | INIT→SUBMIT→COMPLETED；SUBMIT→CANCEL（手动与超时）；终态再 pay/cancel → 30002 |
| 预览 | qty 0/100000；下架；库存不足；成功金额 |
| 下单 | 价格变动；reserve 后 insert 失败要 release（可用 mock）；成功有明细且 `member_id` 一致 |
| 支付/取消 | confirm/release 各调一次 HotService；撤销延时 |
| 超时 | 已支付 skip 且不 release；未支付 CANCEL + release |
| `counts` / `list` | ALL 含取消；SUBMIT/COMPLETED 不含；分页 `total` |
| 鉴权 | 未登录 预览/列表失败；不能 get 别人的单 |

现有 `OrderCmdExeTest` 按新入参改写，不再测手填 `amount`。

---

## 11. 实现顺序建议

1. DDL + DO/枚举 + COLA 配置与 Executor 单测（纯流转、无 Spring 事务）。
2. Repository CAS / 分页 / counts。
3. CmdExe：preview → place（接 HotService）→ pay/cancel/expire。
4. HTTP + OpenAPI。
5. C 端购买流与订单 Tab；清理调试面板下单。
6. 回归 delay 超时（C 端下单 + 短 delay）。

---

## 12. 范围外（再次收口）

- 一单多商品的下单 API 与购物车 UI
- 真实支付回调（本阶段 `pay` 即 `PAY_SUCCESS`）
- 把 `ProductStockHotService` 改到 Action 内
- 修改 delay 框架本身
