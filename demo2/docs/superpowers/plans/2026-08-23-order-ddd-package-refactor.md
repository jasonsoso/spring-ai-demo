# 订单模块 DDD 分包重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `com.jason.demo.demo2.order` 重构为 app / service 分层；引入 ReqVO/ResVO、CmdExe、MapStruct；HTTP 全部 POST+Body；新增手动取消；同步前端与文档。

**Architecture:** `app`（Controller、executor、listener、VO）→ `service.core`（Order 聚合、OrderDomainService）→ `service.infrastructure`（dao.entity/mapper、repository + OrderDoConvert）。延时框架不改；取消三条路径：Redisson/MQ、扫描兜底、HTTP cancel。

**Tech Stack:** Spring Boot 4.1、Java 21、MyBatis-Plus、MapStruct、JUnit 5 + Mockito、现有 `DelayTaskService` / `SnowflakeIdGenerator`

**Spec:** [2026-08-23-order-ddd-package-refactor-design.md](../specs/2026-08-23-order-ddd-package-refactor-design.md)

## Global Constraints

- 模块仅限 `demo2`；**不改** `framework.delay` 核心逻辑与表结构
- 依赖方向：`app → service.core → service.infrastructure`
- `Order extends OrderDO`；`OrderMapper` 仅 `BaseMapper<OrderDO>`
- `OrderRepository` 对外只用 `Order`；转换经 `OrderDoConvert`
- 仅下单使用 `OrderPlace*` / `orderPlace`；pay/get/cancel 不带 Place
- HTTP：`POST /demo/orders/orderPlace|pay|get|cancel`；无 GET、无路径参数
- 删除旧扁平包下类，不保留兼容 URL
- MapStruct processor 顺序：**lombok 在前，mapstruct 在后**

---

## File Structure（目标）

| 路径 | 职责 |
|------|------|
| `order/app/controller/OrderController.java` | 四个 POST 接口 |
| `order/app/executor/*CmdExe.java` | 用例编排 + 事务 |
| `order/app/listener/OrderCancelHandler.java` | DelayTaskHandler → OrderExpireCmdExe |
| `order/app/vo/*ReqVO.java` / `*ResVO.java` | 前后端报文 |
| `order/app/convert/OrderVoConvert.java` | MapStruct VO 转换 |
| `order/app/OrderPlaceResult.java` | 下单用例返回值 |
| `order/service/common/OrderStatus.java` | 枚举 |
| `order/service/core/domain/Order.java` | 聚合 |
| `order/service/core/OrderDomainService.java` | 领域服务 |
| `order/service/infrastructure/dao/entity/OrderDO.java` | 表映射 |
| `order/service/infrastructure/dao/mapper/OrderMapper.java` | BaseMapper |
| `order/service/infrastructure/repository/OrderRepository.java` | 仓储 |
| `order/service/infrastructure/repository/convert/OrderDoConvert.java` | Order ↔ OrderDO |
| `static/js/tabs/order-delay.js` | 前端四个 POST |
| `static/index.html` | 取消按钮 |

**删除（重构完成后）：**

- `order/OrderController.java`
- `order/OrderService.java`
- `order/OrderCancelHandler.java`
- `order/CreateOrderRequest.java`
- `order/OrderStatus.java`
- `order/repository/*`

---

### Task 1: MapStruct 依赖

**Files:**
- Modify: `demo2/pom.xml`

- [x] **Step 1: 增加 mapstruct 依赖与 processor**

在 `<properties>` 增加：

```xml
<mapstruct.version>1.6.3</mapstruct.version>
```

在 `<dependencies>` 增加：

```xml
<dependency>
    <groupId>org.mapstruct</groupId>
    <artifactId>mapstruct</artifactId>
    <version>${mapstruct.version}</version>
</dependency>
```

在 `maven-compiler-plugin` 的 `annotationProcessorPaths` 中，**在 lombok 之后**追加：

```xml
<path>
    <groupId>org.mapstruct</groupId>
    <artifactId>mapstruct-processor</artifactId>
    <version>${mapstruct.version}</version>
</path>
```

- [x] **Step 2: 验证编译**

Run: `mvn -q compile`（在 `demo2` 目录执行；本仓库不是 Maven reactor，`-pl demo2` 不适用）
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add demo2/pom.xml
git commit -m "build(demo2): add MapStruct for order module refactor"
```

---

### Task 2: Infrastructure — DAO + Repository

**Files:**
- Create: `order/service/infrastructure/dao/entity/OrderDO.java`
- Create: `order/service/infrastructure/dao/mapper/OrderMapper.java`
- Create: `order/service/infrastructure/repository/convert/OrderDoConvert.java`
- Create: `order/service/infrastructure/repository/OrderRepository.java`
- Modify: `framework/delay/config/DelayMybatisPlusConfig.java`

- [x] **Step 1: 从 `OrderEntity` 复制为 `OrderDO`**

包名 `...order.service.infrastructure.dao.entity`；`@TableName("demo_order")` 不变。

- [x] **Step 2: 创建 `OrderMapper extends BaseMapper<OrderDO>`**

包名 `...dao.mapper`。

- [x] **Step 3: 创建 `OrderDoConvert`**

```java
@Mapper(componentModel = "spring")
public interface OrderDoConvert {
    OrderDO toDo(Order order);
    Order toDomain(OrderDO orderDO);
}
```

`toDomain` 可用 `@Mapping` + `Order.from(OrderDO)` 默认方法。

- [x] **Step 4: 迁移 `OrderRepository`**

- 注入 `OrderMapper` + `OrderDoConvert`
- `insert(Order)` / `findById` 返回 `Optional<Order>`
- `markPaid` / `markCancelled` 保持 `LambdaUpdateWrapper<OrderDO>` + `PENDING_PAY` 条件
- 方法签名中不出现 `OrderDO`（对外）

- [x] **Step 5: 更新 `@MapperScan`**

```java
@MapperScan({
    "com.jason.demo.demo2.framework.delay.repository",
    "com.jason.demo.demo2.order.service.infrastructure.dao.mapper"
})
```

- [ ] **Step 6: Commit**

```bash
git commit -m "refactor(demo2): add order infrastructure dao and repository"
```

---

### Task 3: Core — Order 聚合与领域服务

**Files:**
- Create: `order/service/common/OrderStatus.java`
- Create: `order/service/core/domain/Order.java`
- Create: `order/service/core/OrderDomainException.java`（可选，或用现有异常风格）
- Create: `order/service/core/OrderDomainService.java`

- [x] **Step 1: 迁移 `OrderStatus` 到 `service.common`**

- [x] **Step 2: 实现 `Order extends OrderDO`**

```java
public class Order extends OrderDO {
    public static Order create(long orderId, BigDecimal amount, LocalDateTime now) { ... }
    public void pay() { ... }      // 非 PENDING_PAY 抛 OrderDomainException
    public boolean cancel() { ... } // false = 已是终态，跳过
    public static Order from(OrderDO d) { ... }
}
```

- [x] **Step 3: 实现 `OrderDomainService`**

方法建议：

| 方法 | 说明 |
|------|------|
| `void place(Order order)` | insert |
| `Order requireOrder(long orderId)` | 找不到抛异常（由 app 映射 404） |
| `void payOrder(long orderId)` | 加载 → pay() → markPaid；0 行抛冲突 |
| `boolean expireCancel(long orderId)` | 加载 → cancel() false 则 return false → markCancelled |
| `void manualCancel(long orderId)` | 加载 → cancel() false 则抛冲突 → markCancelled |

- [ ] **Step 4: 单元测试 `OrderTest` + `OrderDomainServiceTest`**

Run: `mvn -pl demo2 -q test -Dtest=OrderTest,OrderDomainServiceTest`

- [ ] **Step 5: Commit**

---

### Task 4: App — VO 与 MapStruct

**Files:**
- Create: `order/app/vo/OrderPlaceReqVO.java` / `OrderPlaceResVO.java`
- Create: `order/app/vo/PayOrderReqVO.java` / `PayOrderResVO.java`
- Create: `order/app/vo/GetOrderReqVO.java` / `GetOrderResVO.java`
- Create: `order/app/vo/CancelOrderReqVO.java` / `CancelOrderResVO.java`
- Create: `order/app/OrderPlaceResult.java`
- Create: `order/app/convert/OrderVoConvert.java`

- [x] **Step 1: 创建全部 VO（Lombok `@Data`）**

字段对齐 spec §3.1。

- [x] **Step 2: 创建 `OrderPlaceResult`**

字段：`orderId, status, amount, taskId, delay`。

- [x] **Step 3: 创建 `OrderVoConvert`**

```java
@Mapper(componentModel = "spring")
public interface OrderVoConvert {
    OrderPlaceResVO toPlaceRes(OrderPlaceResult r);
    PayOrderResVO toPayRes(Order order);
    GetOrderResVO toGetRes(Order order);
    CancelOrderResVO toCancelRes(Order order);

    @Named("parseDelay")
    default Duration parseDelay(String raw) { ... } // 复用原 OrderController.parseDelay 逻辑
}
```

- [ ] **Step 4: Commit**

---

### Task 5: App — CmdExe 执行器

**Files:**
- Create: `order/app/executor/OrderPlaceCmdExe.java`
- Create: `order/app/executor/OrderPaySuccessCmdExe.java`
- Create: `order/app/executor/OrderGetCmdExe.java`
- Create: `order/app/executor/OrderExpireCmdExe.java`
- Create: `order/app/executor/OrderCancelCmdExe.java`

- [x] **Step 1: `OrderPlaceCmdExe`**

```java
@Transactional
public OrderPlaceResult execute(BigDecimal amount, Duration delay) {
    // 校验 amount > 0
    // idGenerator.nextId → Order.create → domainService.place
    // delayTaskService.schedule(ORDER_CANCEL, orderId, null, effectiveDelay)
    // 返回 OrderPlaceResult
}
```

- [x] **Step 2: `OrderPaySuccessCmdExe`**

```java
@Transactional
public Order execute(long orderId) {
    domainService.payOrder(orderId);
    delayTaskService.cancelByBizKey(ORDER_CANCEL, String.valueOf(orderId));
    return domainService.requireOrder(orderId);
}
```

- [x] **Step 3: `OrderGetCmdExe`**

```java
public Order execute(long orderId) {
    return domainService.requireOrder(orderId);
}
```

- [x] **Step 4: `OrderExpireCmdExe`**

```java
@Transactional
public void execute(long orderId) {
    if (!domainService.expireCancel(orderId)) {
        log.info("skip expire cancel, orderId={}", orderId);
    }
}
```

- [x] **Step 5: `OrderCancelCmdExe`**

```java
@Transactional
public Order execute(long orderId) {
    domainService.manualCancel(orderId);
    delayTaskService.cancelByBizKey(ORDER_CANCEL, String.valueOf(orderId));
    return domainService.requireOrder(orderId);
}
```

- [x] **Step 6: 迁移测试**

将 `OrderServiceTest` 拆为：
- `OrderPlaceCmdExeTest`
- `OrderPaySuccessCmdExeTest`
- `OrderCancelCmdExeTest`（新增：成功 / 已支付 409 / cancelByBizKey）

Run: `mvn -pl demo2 -q test -Dtest=Order*CmdExeTest,Order*Test`

- [ ] **Step 7: Commit**

---

### Task 6: App — Controller 与 Listener

**Files:**
- Create: `order/app/controller/OrderController.java`
- Create: `order/app/listener/OrderCancelHandler.java`
- Delete: 旧 `order/*.java` 与 `order/repository/*`

- [x] **Step 1: `OrderController`**

```java
@RestController
@RequestMapping("/demo/orders")
public class OrderController {
    @PostMapping("/orderPlace")
    public OrderPlaceResVO orderPlace(@RequestBody OrderPlaceReqVO req) { ... }

    @PostMapping("/pay")
    public PayOrderResVO pay(@RequestBody PayOrderReqVO req) { ... }

    @PostMapping("/get")
    public GetOrderResVO get(@RequestBody GetOrderReqVO req) { ... }

    @PostMapping("/cancel")
    public CancelOrderResVO cancel(@RequestBody CancelOrderReqVO req) { ... }
}
```

- 参数校验、404/409 映射：`ResponseStatusException` 或统一 `@ControllerAdvice`（沿用现有风格）
- `orderPlace`：`OrderVoConvert.parseDelay(req.getDelay())` → `OrderPlaceCmdExe`

- [x] **Step 2: `OrderCancelHandler`（listener）**

```java
@Component
public class OrderCancelHandler implements DelayTaskHandler {
    public void handle(DelayTaskEntity task) {
        long orderId = Long.parseLong(task.getBizKey());
        orderExpireCmdExe.execute(orderId);
    }
}
```

- [x] **Step 3: 删除旧类**

删除 Task 开头列出的 8 个旧文件。

- [x] **Step 4: 迁移 `OrderCancelHandlerTest` → 测 Handler 委托 + `OrderExpireCmdExeTest`**

- [ ] **Step 5: 全量编译测试**

Run: `mvn -pl demo2 -q test`
Expected: 全部通过（含 `JacksonJsonCustomizerTest` 改 import 为 `OrderDO`）

- [ ] **Step 6: Commit**

```bash
git commit -m "refactor(demo2): restructure order module with CmdExe and new HTTP API"
```

---

### Task 7: 前端与 README

**Files:**
- Modify: `demo2/src/main/resources/static/js/tabs/order-delay.js`
- Modify: `demo2/src/main/resources/static/index.html`
- Modify: `demo2/README.md`

- [x] **Step 1: 更新 `order-delay.js`**

| 函数 | 新请求 |
|------|--------|
| `orderDelayCreate` | `POST /demo/orders/orderPlace` + `{ amount, delay }` |
| `orderDelayPay` | `POST /demo/orders/pay` + `{ orderId }` |
| `orderDelayRefresh` | `POST /demo/orders/get` + `{ orderId }` |
| `orderDelayCancel`（新增） | `POST /demo/orders/cancel` + `{ orderId }` |

- [x] **Step 2: `index.html` 增加按钮**

在订单 Demo 操作区增加：

```html
<button class="btn" onclick="orderDelayCancel()">取消订单</button>
```

- [x] **Step 3: 更新 README curl 示例（约 L1319–1338）**

```bash
curl -s -X POST http://localhost:8081/demo/orders/orderPlace \
  -H "Content-Type: application/json" \
  -d '{"amount":9.9,"delay":"10s"}'

curl -s -X POST http://localhost:8081/demo/orders/get \
  -H "Content-Type: application/json" \
  -d '{"orderId":"{orderId}"}'

curl -s -X POST http://localhost:8081/demo/orders/pay \
  -H "Content-Type: application/json" \
  -d '{"orderId":"{orderId}"}'

curl -s -X POST http://localhost:8081/demo/orders/cancel \
  -H "Content-Type: application/json" \
  -d '{"orderId":"{orderId}"}'
```

- [ ] **Step 4: Commit**

```bash
git commit -m "docs(demo2): update order-delay demo for new order API"
```

---

### Task 8: 验收

- [ ] **Step 1: 全量测试**

Run: `mvn -pl demo2 test`

- [x] **Step 2: 手工冒烟（本地起服务后）**

1. orderPlace → get 为 PENDING_PAY
2. cancel → get 为 CANCELLED
3. orderPlace → pay → get 为 PAID
4. orderPlace → 等待 delay → get 为 CANCELLED

- [x] **Step 3: 确认无残留旧 import**

Run: `rg "order\.repository|OrderEntity|CreateOrderRequest|OrderService" demo2/src`

Expected: 无匹配（测试与文档除外若已更新）

- [ ] **Step 4: 更新 spec 状态（可选）**

将 spec 文首 `状态` 改为「已实现」。

---

## 实现顺序建议

```text
Task 1 → Task 2 → Task 3 → Task 4 → Task 5 → Task 6 → Task 7 → Task 8
```

Task 2–3 可并行准备，但 Task 5 依赖 2+3+4 均完成。

## 风险与注意

| 项 | 处理 |
|----|------|
| `Order extends OrderDO` 跨包 | core.domain import dao.entity；单模块可编译 |
| MapStruct + Lombok | processor 顺序：lombok → mapstruct |
| 手动 cancel 与 expire 并发 | `markCancelled` 条件更新；幂等 |
| 前端 orderId 为字符串 | JSON 中 Long 序列化为字符串，前端 `String(data.orderId)` 保持 |
