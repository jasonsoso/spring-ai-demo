# Order Module COLA State Machine Implementation Plan

> **Status:** 已实现（2026-08-29）。归档见 `docs/superpowers/archive/2026-08-28-order-module-statemachine.md`。
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 spec 重构 demo2 订单模块：COLA 5.0.0 状态机 + `items[]` 预览/下单 + Redis `placeToken` 幂等 + Action 内落库并调热库存，打通 C 端立即购买到我的订单。

**Architecture:** `app.CmdExe` 做登录、token、幂等锁、商品再校验、延时；`OrderStateMachineExecutor.fireEvent` 调 Spring Bean Action（`@Transactional`：改状态、insert/CAS、逐行 `ProductStockHotService`）。`INIT` 不落库。延时在 `fireEvent` 成功返回之后由 CmdExe 注册/撤销，不进 Action。本版 `items` `@Size(max=1)`，领域按列表循环。

**Tech Stack:** Java 21, Spring Boot 4.1, MyBatis-Plus 3.5, MapStruct, `cola-component-statemachine` 5.0.0, StringRedisTemplate + Redisson, JUnit 5 + Mockito, vanilla JS (`member.js`)。

**Spec:** `demo2/docs/superpowers/specs/2026-08-28-order-module-statemachine-design.md`

## Global Constraints

- 包：`com.jason.demo.demo2.order`；依赖 `app → service.core → service.infrastructure`。状态机包必须是 `order.service.core.statemachine`（含 `action`）。
- COLA 只加 `cola-component-statemachine` **5.0.0**（不要 4.3.2，不要完整 COLA 分层）。`machineId = orderStateMachine`。
- Action 必须是 Spring Bean + `@Transactional`；**禁止匿名 Action**（事务代理会失效）。
- CmdExe **不要**给 `execute` 加 `@Transactional`（避免延时与订单落库同一事务；避免回滚后幽灵关单）。
- HTTP：全部 `POST /demo/orders/{action}` + JSON Body + `@LoginRequired`；无路径变量。Controller **不得**注入 `*VoConvert`。
- 错误码段 `3xxxx`；枚举类名以 `Enum` 结尾。库存失败抛商品码（`ProductErrorCodeEnum`），不要包一层订单码。
- 库存只调 `ProductStockHotService.reserve(productId, orderId, qty)` / `confirm(...)` / `release(productId, orderId)`。
- Redis `SET`+TTL **必须走 Lua**（见 `AuthSessionService`：Boot4 `opsForValue().set(key, val, Expiration)` 会 StackOverflow）。`GET`/`DEL` 可用 `opsForValue()`。
- 雪花 ID 对外 JSON 已是字符串；前端用现有 `memberSnowflakeId()`。
- 本版 `items` 仅 1 行、`productId` 不重复、每行 qty∈[1,99999]。`amount = sum(sellPrice * qty)`，`setScale(2, RoundingMode.UNNECESSARY)`。
- `placeToken` TTL：`app.order.place-token-ttl`，默认 **30 分钟**。
- 提交 git 时 **只 add 本任务订单相关路径**，不要带入 `feat/redis-stock-consistency` 上未完成的热库存文件。
- 测试在 `demo2` 目录执行：`mvn -Dtest=ClassName test`。

---

## File Structure

### Create

**SQL**

- `demo2/src/main/resources/db/order-module-schema.sql`（ALTER 现表 + 建 `demo_order_item` + 历史状态映射）

**config**

- `demo2/src/main/java/com/jason/demo/demo2/order/config/OrderProperties.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/config/OrderConfiguration.java`

**service.common**

- `demo2/src/main/java/com/jason/demo/demo2/order/service/common/OrderEventEnum.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/common/PayStatusEnum.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/common/OrderListTabEnum.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/common/OrderItemsRules.java`

**service.core.domain / statemachine**

- `demo2/src/main/java/com/jason/demo/demo2/order/service/core/domain/OrderItem.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/core/statemachine/OrderContext.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/core/statemachine/OrderStateMachineConfiguration.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/core/statemachine/OrderStateMachineExecutor.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/core/statemachine/action/OrderPlaceAction.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/core/statemachine/action/OrderPaySuccessAction.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/core/statemachine/action/OrderCancelAction.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/core/statemachine/action/OrderExpireAction.java`

**infrastructure**

- `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/dao/entity/OrderItemDO.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/dao/mapper/OrderItemMapper.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/repository/OrderItemRepository.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/repository/convert/OrderItemDoConvert.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/redis/OrderPlaceTokenKeys.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/redis/OrderPlaceTokenPayload.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/redis/OrderPlaceTokenStore.java`
- `demo2/src/main/resources/mapper/order/OrderMapper.xml`

**app**

- `demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderPreviewCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderListCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderCountsCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/vo/req/OrderLineReqVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/vo/req/OrderPreviewReqVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/vo/req/OrderListReqVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/vo/res/OrderPreviewResVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/vo/res/OrderPreviewLineResVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/vo/res/OrderLineResVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/vo/res/OrderListResVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/vo/res/OrderListItemResVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/vo/res/OrderCountsResVO.java`

**tests**

- `demo2/src/test/java/com/jason/demo/demo2/order/OrderStatusEnumTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/order/OrderStateMachineExecutorTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/order/OrderMapperXmlTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/order/OrderPlaceTokenStoreTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/order/OrderPreviewCmdExeTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/order/OrderPlaceCmdExeTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/order/OrderPlaceActionTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/order/OrderPayCancelExpireTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/order/OrderListCountsCmdExeTest.java`

### Modify

- `demo2/pom.xml`（COLA 5.0.0）
- `demo2/src/main/resources/application.properties`（`app.order.place-token-ttl=30m`）
- `demo2/src/main/resources/db/delay-order-schema.sql`（新环境 `demo_order` 列与 spec 对齐）
- `demo2/src/main/java/com/jason/demo/demo2/order/service/common/OrderStatusEnum.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/common/OrderErrorCodeEnum.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/core/domain/Order.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/core/OrderDomainService.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/dao/entity/OrderDO.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/dao/mapper/OrderMapper.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/repository/OrderRepository.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/controller/OrderController.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderPlaceCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderPaySuccessCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderCancelCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderExpireCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/executor/OrderGetCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/convert/OrderVoConvert.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/vo/req/OrderPlaceReqVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/vo/res/OrderPlaceResVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/vo/res/GetOrderResVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/vo/res/PayOrderResVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/order/app/vo/res/CancelOrderResVO.java`
- `demo2/src/test/java/com/jason/demo/demo2/order/OrderCmdExeTest.java`
- `demo2/src/main/resources/static/js/tabs/member.js`
- `demo2/src/main/resources/static/css/tabs/member.css`
- `demo2/src/main/resources/static/index.html`（调试面板去掉创建订单）

---

## Interfaces Produced Across Tasks

```java
// OrderStatusEnum
boolean isFinalStatus(); // COMPLETED || CANCEL

// OrderItemsRules
static void requireOneDistinctProduct(List<Long> productIds); // 空/超 1 行/重复 productId → 30010

// Order.create
static Order create(long orderId, long memberId, BigDecimal amount, List<OrderItem> items, LocalDateTime now);

// OrderItem.create
static OrderItem create(long itemId, long orderId, long memberId, /* 快照字段 */, int qty);

// OrderStateMachineExecutor
OrderStatusEnum fireEvent(OrderStatusEnum source, OrderEventEnum event, OrderContext context);

// OrderPlaceTokenStore
void savePreview(String token, OrderPlaceTokenPayload payload, Duration ttl);
Optional<OrderPlaceTokenPayload> getPreview(String token);
boolean tryLock(String token, Duration lease);
void unlock(String token);
Optional<Long> getResult(String token);
void saveResult(String token, long orderId, Duration ttl);

// OrderRepository
void insert(Order order);
Optional<Order> findById(long orderId);
Optional<Order> findByIdAndMemberId(long orderId, long memberId);
boolean markCompleted(long orderId, Long memberId, LocalDateTime payTime); // memberId null = 不按会员过滤
boolean markCancelled(long orderId, Long memberId, LocalDateTime cancelTime);
long countByMemberAndStatus(long memberId, String orderStatus);
long countPageByMemberAndTab(long memberId, String orderStatusOrNull);
List<Order> pageByMemberAndTab(long memberId, String orderStatusOrNull, int offset, int pageSize);

// OrderItemRepository
void insertAll(List<OrderItem> items);
List<OrderItem> listByOrderId(long orderId);
Map<Long, List<OrderItem>> listByOrderIds(List<Long> orderIds);

// OrderDomainService
Order requireOrder(long orderId, long memberId);
Order requireOrder(long orderId); // 超时路径，不校验会员
Order requireOrderWithItems(long orderId, long memberId);

// ProductStockHotService（已有，不要改签名）
void reserve(long productId, long orderId, int qty);
void confirm(long productId, long orderId, int qty);
void release(long productId, long orderId);
Optional<Integer> overlayAvail(long productId);
```

Redis key（`OrderPlaceTokenKeys`）：

```text
demo:order:preview:{token}
demo:order:place:lock:{token}
demo:order:place:result:{token}
```

---

### Task 1: DDL、枚举、错误码、TTL 配置

**Files:**
- Create: `demo2/src/main/resources/db/order-module-schema.sql`
- Create: `demo2/src/main/java/com/jason/demo/demo2/order/config/OrderProperties.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/order/config/OrderConfiguration.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/order/service/common/OrderEventEnum.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/order/service/common/PayStatusEnum.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/order/service/common/OrderListTabEnum.java`
- Create: `demo2/src/main/java/com/jason/demo/demo2/order/service/common/OrderItemsRules.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/order/OrderStatusEnumTest.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/order/service/common/OrderStatusEnum.java`
- Modify: `demo2/src/main/java/com/jason/demo/demo2/order/service/common/OrderErrorCodeEnum.java`
- Modify: `demo2/src/main/resources/application.properties`（在 `app.delay` 段落后加 `app.order.place-token-ttl=30m`）
- Modify: `demo2/src/main/resources/db/delay-order-schema.sql`（`demo_order` 建表改为 spec 4.2 完整形态；保留 `delay_task` / `demo_member`）
- Modify: `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/dao/entity/OrderDO.java`（`status`→`orderStatus`，加 `payStatus`/`payTime`/`cancelTime`）
- Modify: `demo2/src/main/java/com/jason/demo/demo2/order/service/core/domain/Order.java`（字段与 `create` 先改成新状态名，使工程能编译；`items` 列表本任务可先空实现 getter/setter）
- Modify: `demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/repository/OrderRepository.java`（CAS 条件改为 `SUBMIT`）
- Modify: `demo2/src/main/java/com/jason/demo/demo2/order/service/core/OrderDomainService.java`（`pay()`/`cancel()` 判断改为 `SUBMIT`）
- Modify: VO / `OrderPlaceCmdExe` / `OrderVoConvert` / `OrderCmdExeTest`：响应字段 `status` 暂映射为 `orderStatus` 的值 `SUBMIT`/`COMPLETED`/`CANCEL`，保证现有单测能编译通过。

**Interfaces:**
- Consumes: 无
- Produces: `OrderStatusEnum.{INIT,SUBMIT,COMPLETED,CANCEL}` + `isFinalStatus()`；`OrderEventEnum`；`PayStatusEnum`；`OrderListTabEnum.{ALL,SUBMIT,COMPLETED}`；`OrderErrorCodeEnum` 新增 `QTY_INVALID(30007)`、`PRICE_CHANGED(30008)`、`PLACE_TOKEN_INVALID(30009)`、`ORDER_ITEMS_INVALID(30010)`；删除对 `AMOUNT_REQUIRED` 的引用（枚举常量可留但标注 `@Deprecated`，不得再使用）；`OrderProperties.getPlaceTokenTtl()` 默认 30 分钟。

- [ ] **Step 1: Write the failing test**

```java
package com.jason.demo.demo2.order;

import com.jason.demo.demo2.order.service.common.OrderErrorCodeEnum;
import com.jason.demo.demo2.order.service.common.OrderItemsRules;
import com.jason.demo.demo2.order.service.common.OrderStatusEnum;
import com.jason.demo.demo2.framework.web.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderStatusEnumTest {

    @Test
    void finalStatuses_areCompletedAndCancel() {
        assertTrue(OrderStatusEnum.COMPLETED.isFinalStatus());
        assertTrue(OrderStatusEnum.CANCEL.isFinalStatus());
        assertFalse(OrderStatusEnum.SUBMIT.isFinalStatus());
        assertFalse(OrderStatusEnum.INIT.isFinalStatus());
    }

    @Test
    void itemsRules_rejectEmptyOrTwoLines() {
        BusinessException empty = assertThrows(BusinessException.class,
                () -> OrderItemsRules.requireOneDistinctProduct(List.of()));
        assertEquals(OrderErrorCodeEnum.ORDER_ITEMS_INVALID.getCode(), empty.getCode());
    }

    @Test
    void errorCodes_matchSpec() {
        assertEquals(30007, OrderErrorCodeEnum.QTY_INVALID.getCode());
        assertEquals(30008, OrderErrorCodeEnum.PRICE_CHANGED.getCode());
        assertEquals(30009, OrderErrorCodeEnum.PLACE_TOKEN_INVALID.getCode());
        assertEquals(30010, OrderErrorCodeEnum.ORDER_ITEMS_INVALID.getCode());
    }
}
```

`BusinessException.getCode()` 返回错误码 int（见 `framework.web.exception.BusinessException`）。

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=OrderStatusEnumTest test`（工作目录 `demo2`）

Expected: FAIL（`INIT`/`isFinalStatus`/`QTY_INVALID`/`OrderItemsRules` 不存在）

- [ ] **Step 3: Write minimal implementation**

`OrderStatusEnum`：

```java
package com.jason.demo.demo2.order.service.common;

public enum OrderStatusEnum {
    INIT,
    SUBMIT,
    COMPLETED,
    CANCEL;

    public boolean isFinalStatus() {
        return this == COMPLETED || this == CANCEL;
    }
}
```

`OrderEventEnum`：`SUBMIT_ORDER`, `PAY_SUCCESS`, `CANCEL_ORDER`, `ORDER_EXPIRE`。

`PayStatusEnum`：`WAIT_PAY`, `PAY_SUCCESS`, `CLOSE`。

`OrderListTabEnum`：`ALL`, `SUBMIT`, `COMPLETED`。

`OrderItemsRules.requireOneDistinctProduct(List<Long> productIds)`：null/空/size>1 → `ORDER_ITEMS_INVALID`；`HashSet` 去重后 size != list size → 同一错误。本版调用方传入 `items.stream().map(OrderLineReqVO::getProductId).toList()`。

`OrderErrorCodeEnum` 追加 30007–30010；`AMOUNT_REQUIRED` 标 `@Deprecated`。

`OrderProperties`：

```java
@Data
@ConfigurationProperties(prefix = "app.order")
public class OrderProperties {
    private Duration placeTokenTtl = Duration.ofMinutes(30);
}
```

`OrderConfiguration`：`@Configuration` + `@EnableConfigurationProperties(OrderProperties.class)`。

`order-module-schema.sql` 原样使用 spec §4.2 ALTER + §4.3 CREATE + 历史映射：

```sql
UPDATE demo_order SET order_status = 'SUBMIT', pay_status = 'WAIT_PAY' WHERE order_status = 'PENDING_PAY';
UPDATE demo_order SET order_status = 'COMPLETED', pay_status = 'PAY_SUCCESS', pay_time = updated_at WHERE order_status = 'PAID';
UPDATE demo_order SET order_status = 'CANCEL', pay_status = 'CLOSE', cancel_time = updated_at WHERE order_status = 'CANCELLED';
```

若 ALTER 前列仍叫 `status`，先 `CHANGE COLUMN status order_status`，再 UPDATE。已执行过 CHANGE 的环境 UPDATE 用 `order_status`。脚本顶部用注释写清「已有库执行 ALTER；新库以 delay-order-schema 建表为准」。

`OrderDO` 字段：`orderStatus`、`payStatus`、`payTime`、`cancelTime`（删除 `status`）。

`Order.create` 暂仍接收 `amount`（Task 6 再改为带 items）。写入 `SUBMIT` + `WAIT_PAY`。`pay()` 仅当 `SUBMIT`，写成 `COMPLETED`+`PAY_SUCCESS`+`payTime`。`cancel()` 仅当 `SUBMIT`，写成 `CANCEL`+`CLOSE`+`cancelTime`。

`OrderRepository` 的 `eq/set` 全部改用 `OrderDO::getOrderStatus`，源/目标枚举名 `SUBMIT`/`COMPLETED`/`CANCEL`。`markPaid` 改名为 `markCompleted`（本任务可先改名并让 DomainService 调用新名）。

同步改 `OrderPlaceCmdExe` 响应：若 VO 仍有 `status` 字段，set 为 `order.getOrderStatus()`。`OrderCmdExeTest` 断言改为 `SUBMIT`。

- [ ] **Step 4: Run tests**

Run: `mvn -Dtest=OrderStatusEnumTest,OrderCmdExeTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/resources/db/order-module-schema.sql \
  demo2/src/main/resources/db/delay-order-schema.sql \
  demo2/src/main/resources/application.properties \
  demo2/src/main/java/com/jason/demo/demo2/order \
  demo2/src/test/java/com/jason/demo/demo2/order/OrderStatusEnumTest.java \
  demo2/src/test/java/com/jason/demo/demo2/order/OrderCmdExeTest.java
git commit -m "$(cat <<'EOF'
feat(order): add status enums, error codes, and order schema evolution

EOF
)"
```

---

### Task 2: COLA 5.0.0 状态机 Executor

**Files:**
- Modify: `demo2/pom.xml`（在 `<dependencies>` 增加 cola，不要 BOM 全家桶）
- Create: `OrderContext.java`、`OrderStateMachineConfiguration.java`、`OrderStateMachineExecutor.java`
- Create: 四个 Action 类（本任务只改聚合字段，不碰 DB/库存；Task 6/7 往同一类里加副作用）
- Create: `demo2/src/test/java/com/jason/demo/demo2/order/OrderStateMachineExecutorTest.java`

**Interfaces:**
- Consumes: Task 1 枚举
- Produces: `OrderStateMachineExecutor.fireEvent(OrderStatusEnum source, OrderEventEnum event, OrderContext context)` 返回目标态；非法流转抛 `BusinessException(ORDER_STATUS_CONFLICT)`。`OrderContext` 持有 `Order order`（getter/setter）。

pom 依赖：

```xml
<dependency>
    <groupId>com.alibaba.cola</groupId>
    <artifactId>cola-component-statemachine</artifactId>
    <version>5.0.0</version>
</dependency>
```

- [ ] **Step 1: Write the failing test**

```java
package com.jason.demo.demo2.order;

import com.jason.demo.demo2.framework.web.exception.BusinessException;
import com.jason.demo.demo2.order.service.common.OrderErrorCodeEnum;
import com.jason.demo.demo2.order.service.common.OrderEventEnum;
import com.jason.demo.demo2.order.service.common.OrderStatusEnum;
import com.jason.demo.demo2.order.service.common.PayStatusEnum;
import com.jason.demo.demo2.order.service.core.domain.Order;
import com.jason.demo.demo2.order.service.core.statemachine.OrderContext;
import com.jason.demo.demo2.order.service.core.statemachine.OrderStateMachineExecutor;
import com.jason.demo.demo2.order.service.core.statemachine.action.OrderCancelAction;
import com.jason.demo.demo2.order.service.core.statemachine.action.OrderExpireAction;
import com.jason.demo.demo2.order.service.core.statemachine.action.OrderPaySuccessAction;
import com.jason.demo.demo2.order.service.core.statemachine.action.OrderPlaceAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderStateMachineExecutorTest {

    private OrderStateMachineExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new OrderStateMachineExecutor(
                new OrderPlaceAction(null, null, null),
                new OrderPaySuccessAction(null, null),
                new OrderCancelAction(null, null),
                new OrderExpireAction(null, null));
        // 若 Action 构造必须依赖仓库：本任务 Action 提供包内可见的 无参/可空 构造仅用于单测，
        // 或测试里用 mock(OrderPlaceAction.class, CALLS_REAL_METHODS) —— 推荐 Action 无参状态写入抽到
        // applyStatus(from,to,event,ctx) 方法，单测 new Action() 且仓库字段为 null，execute 先 applyStatus。
        executor.initForTest("orderStateMachine-test-" + java.util.UUID.randomUUID());
    }

    @Test
    void submitThenPay_reachesCompleted() {
        Order order = Order.create(1L, 9L, new BigDecimal("18.00"), LocalDateTime.now());
        OrderContext ctx = new OrderContext();
        ctx.setOrder(order);
        assertEquals(OrderStatusEnum.SUBMIT, executor.fireEvent(OrderStatusEnum.INIT, OrderEventEnum.SUBMIT_ORDER, ctx));
        assertEquals(OrderStatusEnum.SUBMIT.name(), order.getOrderStatus());
        assertEquals(PayStatusEnum.WAIT_PAY.name(), order.getPayStatus());
        assertEquals(OrderStatusEnum.COMPLETED, executor.fireEvent(OrderStatusEnum.SUBMIT, OrderEventEnum.PAY_SUCCESS, ctx));
        assertEquals(PayStatusEnum.PAY_SUCCESS.name(), order.getPayStatus());
    }

    @Test
    void submitThenCancel_andExpire_reachCancel() {
        Order order = Order.create(2L, 9L, new BigDecimal("18.00"), LocalDateTime.now());
        OrderContext ctx = new OrderContext();
        ctx.setOrder(order);
        executor.fireEvent(OrderStatusEnum.INIT, OrderEventEnum.SUBMIT_ORDER, ctx);
        assertEquals(OrderStatusEnum.CANCEL, executor.fireEvent(OrderStatusEnum.SUBMIT, OrderEventEnum.CANCEL_ORDER, ctx));
        Order order2 = Order.create(3L, 9L, new BigDecimal("18.00"), LocalDateTime.now());
        OrderContext ctx2 = new OrderContext();
        ctx2.setOrder(order2);
        executor.fireEvent(OrderStatusEnum.INIT, OrderEventEnum.SUBMIT_ORDER, ctx2);
        assertEquals(OrderStatusEnum.CANCEL, executor.fireEvent(OrderStatusEnum.SUBMIT, OrderEventEnum.ORDER_EXPIRE, ctx2));
        assertEquals(PayStatusEnum.CLOSE.name(), order2.getPayStatus());
    }

    @Test
    void payFromCompleted_throwsConflict() {
        Order order = Order.create(4L, 9L, new BigDecimal("18.00"), LocalDateTime.now());
        OrderContext ctx = new OrderContext();
        ctx.setOrder(order);
        executor.fireEvent(OrderStatusEnum.INIT, OrderEventEnum.SUBMIT_ORDER, ctx);
        executor.fireEvent(OrderStatusEnum.SUBMIT, OrderEventEnum.PAY_SUCCESS, ctx);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> executor.fireEvent(OrderStatusEnum.COMPLETED, OrderEventEnum.PAY_SUCCESS, ctx));
        assertEquals(OrderErrorCodeEnum.ORDER_STATUS_CONFLICT.getCode(), ex.getCode());
    }
}
```

上面 `initForTest` 若显得别扭：生产 `OrderStateMachineExecutor` 构造注入四个 Action，内部 `StateMachineBuilderFactory.create()` + `build(uniqueId)`。**不要**用静态 `StateMachineFactory.get("orderStateMachine")` 作为 Executor 的运行时查找方式（重复 build 同 ID 会炸）。Spring `@Bean` 只 build 一次，把 `StateMachine` 交给 Executor。

单测直接 `new OrderStateMachineExecutor(actions...)`，构造函数里 `build("order-ut-" + UUID)`。生产 `OrderStateMachineConfiguration` `@Bean OrderStateMachineExecutor` 使用 `MACHINE_ID = "orderStateMachine"`。

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=OrderStateMachineExecutorTest test`

Expected: FAIL（类不存在 / 依赖未解析）

- [ ] **Step 3: Write implementation**

`OrderContext`：`private Order order;` Lombok `@Data`。

四个 Action 均 `implements Action<OrderStatusEnum, OrderEventEnum, OrderContext>`，`execute` 方法加 `@Transactional`。本任务 `execute` 只调用私有 `applyTransition(to, event, ctx)`：

| event | orderStatus | payStatus | 时间 |
|-------|-------------|-----------|------|
| SUBMIT_ORDER | SUBMIT | WAIT_PAY | — |
| PAY_SUCCESS | COMPLETED | PAY_SUCCESS | payTime=now |
| CANCEL_ORDER / ORDER_EXPIRE | CANCEL | CLOSE | cancelTime=now |

仓库字段本任务允许为 `null`；后面 `insert`/`CAS` 用 `if (orderRepository != null)` 保护，**更好做法**：本任务 Action 构造注入 Repository 但 execute **还不调用**，只改内存 Order。单测 `new OrderPlaceAction(null, null, null)`。

`OrderStateMachineConfiguration`：

```java
@Configuration
public class OrderStateMachineConfiguration {
    public static final String MACHINE_ID = "orderStateMachine";

    @Bean
    public OrderStateMachineExecutor orderStateMachineExecutor(
            OrderPlaceAction placeAction,
            OrderPaySuccessAction payAction,
            OrderCancelAction cancelAction,
            OrderExpireAction expireAction) {
        return OrderStateMachineExecutor.build(
                MACHINE_ID, placeAction, payAction, cancelAction, expireAction);
    }
}
```

`OrderStateMachineExecutor.build`：

```java
StateMachineBuilder<OrderStatusEnum, OrderEventEnum, OrderContext> builder =
        StateMachineBuilderFactory.create();
builder.externalTransition().from(INIT).to(SUBMIT).on(SUBMIT_ORDER).perform(placeAction);
builder.externalTransition().from(SUBMIT).to(COMPLETED).on(PAY_SUCCESS).perform(payAction);
builder.externalTransition().from(SUBMIT).to(CANCEL).on(CANCEL_ORDER).perform(cancelAction);
builder.externalTransition().from(SUBMIT).to(CANCEL).on(ORDER_EXPIRE).perform(expireAction);
builder.setFailCallback((from, event, ctx) -> {
    throw new BusinessException(OrderErrorCodeEnum.ORDER_STATUS_CONFLICT);
});
StateMachine<OrderStatusEnum, OrderEventEnum, OrderContext> sm = builder.build(machineId);
return new OrderStateMachineExecutor(sm);
```

`fireEvent`：`return stateMachine.fireEvent(source, event, context);`

COLA 包名：`com.alibaba.cola.statemachine.*`（`StateMachineBuilderFactory`、`Action`、`FailCallback`）。

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=OrderStateMachineExecutorTest,OrderStatusEnumTest test`

Expected: PASS。若报 `The state machine with id [] is already built`，单测 machineId 必须唯一。

- [ ] **Step 5: Commit**

```bash
git add demo2/pom.xml demo2/src/main/java/com/jason/demo/demo2/order/service/core/statemachine \
  demo2/src/test/java/com/jason/demo/demo2/order/OrderStateMachineExecutorTest.java
git commit -m "$(cat <<'EOF'
feat(order): wire COLA 5.0.0 order state machine

EOF
)"
```

---

### Task 3: 明细表、仓储 CAS / 分页 / counts

**Files:**
- Create: `OrderItemDO`、`OrderItem.java`、`OrderItemMapper`、`OrderItemRepository`、`OrderItemDoConvert`
- Create: `demo2/src/main/resources/mapper/order/OrderMapper.xml`
- Create: `demo2/src/test/java/com/jason/demo/demo2/order/OrderMapperXmlTest.java`
- Modify: `OrderMapper.java` 增加 XML 方法签名
- Modify: `OrderRepository` 实现 `markCompleted`/`markCancelled`（XML 或 LambdaUpdateWrapper）、`countByMemberAndStatus`、`pageByMemberAndTab`
- Modify: `Order` 增加 `List<OrderItem> items`（`@TableField(exist = false)` 若 Order 仍继承 DO）
- Modify: `OrderDomainService.requireOrderWithItems`

**Interfaces:**
- Consumes: Task 1 `OrderDO` 新列
- Produces: 见「Interfaces Produced」里 Repository 方法。超时 `markCancelled(orderId, null, now)` 不带 `member_id`。列表只返回 **存在明细行** 的订单。

- [ ] **Step 1: Write the failing test**

```java
package com.jason.demo.demo2.order;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderMapperXmlTest {

    private static final String NAMESPACE =
            "com.jason.demo.demo2.order.service.infrastructure.dao.mapper.OrderMapper";
    private static final String RESOURCE = "mapper/order/OrderMapper.xml";

    @Test
    void xmlMapper_registersCasAndPageStatements() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(RESOURCE)) {
            assertNotNull(in, () -> "missing classpath resource: " + RESOURCE);
            new XMLMapperBuilder(in, configuration, RESOURCE, configuration.getSqlFragments()).parse();
        }
        assertTrue(configuration.hasStatement(NAMESPACE + ".markCompleted"));
        assertTrue(configuration.hasStatement(NAMESPACE + ".markCancelled"));
        assertTrue(configuration.hasStatement(NAMESPACE + ".countByMemberAndStatus"));
        assertTrue(configuration.hasStatement(NAMESPACE + ".countPageByMemberAndTab"));
        assertTrue(configuration.hasStatement(NAMESPACE + ".pageByMemberAndTab"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=OrderMapperXmlTest test`

Expected: FAIL（缺 XML）

- [ ] **Step 3: Write implementation**

`OrderItemDO`：`@TableName("demo_order_item")`，`@TableId(value = "id", type = IdType.AUTO)`，字段与 spec 4.3 一致（`itemId`/`orderId`/`memberId`/`productId`/`productName`/`subtitle`/`coverUrl`/`sellPrice`/`marketPrice`/`qty`/`createdAt`）。

`OrderItem extends OrderItemDO`，`create(...)` 校验 qty 1..99999 否则 `QTY_INVALID`。`lineAmount()`：`sellPrice.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.UNNECESSARY)`。

`OrderMapper`：

```java
int markCompleted(@Param("orderId") long orderId,
                  @Param("memberId") Long memberId,
                  @Param("payTime") LocalDateTime payTime);

int markCancelled(@Param("orderId") long orderId,
                  @Param("memberId") Long memberId,
                  @Param("cancelTime") LocalDateTime cancelTime);

long countByMemberAndStatus(@Param("memberId") long memberId,
                            @Param("orderStatus") String orderStatus);

long countPageByMemberAndTab(@Param("memberId") long memberId,
                             @Param("orderStatus") String orderStatus);

List<OrderDO> pageByMemberAndTab(@Param("memberId") long memberId,
                                 @Param("orderStatus") String orderStatus,
                                 @Param("offset") int offset,
                                 @Param("pageSize") int pageSize);
```

XML `markCompleted`：`WHERE order_id=#{orderId} AND order_status='SUBMIT'`，`<if test="memberId != null">AND member_id=#{memberId}</if>`，set `COMPLETED`/`PAY_SUCCESS`/`pay_time`/`updated_at`。

`markCancelled`：set `CANCEL`/`CLOSE`/`cancel_time`。

`pageByMemberAndTab`：

```xml
SELECT o.* FROM demo_order o
WHERE o.member_id = #{memberId}
  AND EXISTS (SELECT 1 FROM demo_order_item i WHERE i.order_id = o.order_id)
  <if test="orderStatus != null">AND o.order_status = #{orderStatus}</if>
ORDER BY o.created_at DESC
LIMIT #{offset}, #{pageSize}
```

`countPageByMemberAndTab` 同条件 `COUNT(*)`。`ALL` 时 Java 传入 `orderStatus=null`。

`OrderItemRepository.insertAll`：循环 `orderItemMapper.insert`（同一调用方事务内）。`listByOrderIds`：`IN` 查询后 `groupingBy(OrderItem::getOrderId)`。

`OrderRepository.insert` 只插主表；明细由 `OrderItemRepository` 插。提供 `OrderRepository.insertWithItems(Order order)` 连续调用两者，供 PlaceAction 使用。

- [ ] **Step 4: Run test**

Run: `mvn -Dtest=OrderMapperXmlTest,OrderCmdExeTest,OrderStateMachineExecutorTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/order/service \
  demo2/src/main/resources/mapper/order/OrderMapper.xml \
  demo2/src/test/java/com/jason/demo/demo2/order/OrderMapperXmlTest.java
git commit -m "$(cat <<'EOF'
feat(order): add order item persistence and CAS/page SQL

EOF
)"
```

---

### Task 4: Redis placeToken 存储

**Files:**
- Create: `OrderPlaceTokenKeys.java`、`OrderPlaceTokenPayload.java`、`OrderPlaceTokenStore.java`
- Create: `demo2/src/test/java/com/jason/demo/demo2/order/OrderPlaceTokenStoreTest.java`

**Interfaces:**
- Consumes: `OrderProperties.getPlaceTokenTtl()`、`StringRedisTemplate`、`JsonMapper`（`tools.jackson.databind.json.JsonMapper`，与 `AuthSessionService` 相同）
- Produces: Task 总表里的 Store 方法。`tryLock` 用 Lua `SET key token NX EX seconds`，成功返回 true。`unlock` `DEL`。payload JSON：`{ "memberId": 1, "items": [ { "productId": 1, "qty": 2, "sellPrice": 18.00 } ] }`。

**不要**用 `opsForValue().set(key, val, Duration)`。Lua 与 AuthSession 相同：

```java
new DefaultRedisScript<>("return redis.call('SET', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[2]))", String.class);
redis.execute(script, List.of(key), json, String.valueOf(ttl.toSeconds()));
```

锁：

```java
new DefaultRedisScript<>("return redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', tonumber(ARGV[2]))", String.class);
```

返回 `"OK"` 为抢到。result 的 TTL 用 `Duration.ofHours(24)`。

- [ ] **Step 1: Write the failing test**

```java
@ExtendWith(MockitoExtension.class)
class OrderPlaceTokenStoreTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;
    JsonMapper jsonMapper = JsonMapper.builder().build(); // 若项目已有测试用法，跟 Auth 单测一致
    OrderProperties properties = new OrderProperties();

    @Test
    void getPreview_blank_returnsEmpty() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("demo:order:preview:t")).thenReturn(null);
        OrderPlaceTokenStore store = new OrderPlaceTokenStore(redis, jsonMapper, properties);
        assertTrue(store.getPreview("t").isEmpty());
    }

    @Test
    void keys_matchSpec() {
        assertEquals("demo:order:preview:abc", OrderPlaceTokenKeys.preview("abc"));
        assertEquals("demo:order:place:lock:abc", OrderPlaceTokenKeys.lock("abc"));
        assertEquals("demo:order:place:result:abc", OrderPlaceTokenKeys.result("abc"));
    }
}
```

`JsonMapper.builder()` 若 Boot 测试不便构造，只测 `OrderPlaceTokenKeys` + mock get 返回 JSON 字符串再反序列化。

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=OrderPlaceTokenStoreTest test`

Expected: FAIL

- [ ] **Step 3: Implement Store**

`savePreview` / `saveResult` 走 SET EX Lua。`getResult` 把字符串解析为 `Long`。非法 JSON 视为 empty 或抛 `PLACE_TOKEN_INVALID`（Store 返回 empty，CmdExe 统一抛 30009）。

- [ ] **Step 4: Run test**

Run: `mvn -Dtest=OrderPlaceTokenStoreTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/order/service/infrastructure/redis \
  demo2/src/test/java/com/jason/demo/demo2/order/OrderPlaceTokenStoreTest.java
git commit -m "$(cat <<'EOF'
feat(order): add Redis placeToken preview and lock keys

EOF
)"
```

---

### Task 5: 预览 CmdExe

**Files:**
- Create: `OrderLineReqVO`、`OrderPreviewReqVO`、`OrderPreviewResVO`、`OrderPreviewLineResVO`、`OrderPreviewCmdExe`
- Create: `demo2/src/test/java/com/jason/demo/demo2/order/OrderPreviewCmdExeTest.java`
- Modify: `OrderVoConvert`（可新增 `toPreviewLine`，或 CmdExe 手写映射）

**Interfaces:**
- Consumes: `ProductDomainService.requireOnShelf`、`ProductStockHotService.overlayAvail`、`OrderPlaceTokenStore.savePreview`、`LoginContextHolder.require()`、`OrderItemsRules`
- Produces: `OrderPreviewCmdExe.execute(OrderPreviewReqVO req)` → `OrderPreviewResVO`（`placeToken` UUID、`amount`、`items[]` 含快照与 `availableStock`、`lineAmount`）

可售：`hotService.overlayAvail(productId).orElse(row.getStock().getStock())`。`< qty` → `STOCK_INSUFFICIENT`。不落订单、不 reserve。token：`UUID.randomUUID().toString()`。TTL：`orderProperties.getPlaceTokenTtl()`。

- [ ] **Step 1: Write the failing test**

```java
@ExtendWith(MockitoExtension.class)
class OrderPreviewCmdExeTest {
    @Mock ProductDomainService productDomainService;
    @Mock ProductStockHotService productStockHotService;
    @Mock OrderPlaceTokenStore tokenStore;
    OrderProperties orderProperties = new OrderProperties();

    @BeforeEach
    void login() {
        LoginContextHolder.set(new LoginPrincipal(9001L, "13888999999", "t1"));
    }
    @AfterEach
    void clear() { LoginContextHolder.clear(); }

    @Test
    void twoLines_throwsItemsInvalid() {
        OrderPreviewCmdExe exe = new OrderPreviewCmdExe(
                productDomainService, productStockHotService, tokenStore, orderProperties);
        OrderPreviewReqVO req = new OrderPreviewReqVO();
        OrderLineReqVO a = new OrderLineReqVO(); a.setProductId(1L); a.setQty(1);
        OrderLineReqVO b = new OrderLineReqVO(); b.setProductId(2L); b.setQty(1);
        req.setItems(List.of(a, b));
        BusinessException ex = assertThrows(BusinessException.class, () -> exe.execute(req));
        assertEquals(OrderErrorCodeEnum.ORDER_ITEMS_INVALID.getCode(), ex.getCode());
    }

    @Test
    void success_savesTokenAndReturnsAmount() {
        Product product = new Product();
        product.setProductId(2085550503315509001L);
        product.setProductName("拿铁");
        product.setSubtitle("x");
        product.setSellPrice(new BigDecimal("18.00"));
        ProductStock stock = new ProductStock();
        stock.setStock(100);
        when(productDomainService.requireOnShelf(2085550503315509001L))
                .thenReturn(new ProductWithStock(product, stock));
        when(productStockHotService.overlayAvail(2085550503315509001L)).thenReturn(Optional.empty());
        OrderPreviewCmdExe exe = new OrderPreviewCmdExe(
                productDomainService, productStockHotService, tokenStore, orderProperties);
        OrderPreviewReqVO req = new OrderPreviewReqVO();
        OrderLineReqVO line = new OrderLineReqVO();
        line.setProductId(2085550503315509001L);
        line.setQty(2);
        req.setItems(List.of(line));
        OrderPreviewResVO res = exe.execute(req);
        assertEquals(new BigDecimal("36.00"), res.getAmount());
        assertNotNull(res.getPlaceToken());
        verify(tokenStore).savePreview(eq(res.getPlaceToken()), any(), eq(Duration.ofMinutes(30)));
        assertEquals(100, res.getItems().get(0).getAvailableStock());
    }
}
```

`Product`/`ProductStock` 按现有 setter 补齐测试需要的字段。`overlayAvail` 返回 `Optional.of(77)` 的用例断言 `availableStock=77`。

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=OrderPreviewCmdExeTest test`

Expected: FAIL

- [ ] **Step 3: Implement preview**

`OrderLineReqVO`：`productId` `@NotNull`；`qty` `@NotNull @Min(1) @Max(99999)`；`sellPrice` 无预览校验（可空）。

`OrderPreviewReqVO`：`items` `@NotEmpty @Valid @Size(min=1, max=1)`。

CmdExe 内仍调用 `OrderItemsRules`（防重复 productId）。下架/不存在走 `requireOnShelf` 已抛的商品码。

- [ ] **Step 4: Run test**

Run: `mvn -Dtest=OrderPreviewCmdExeTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/order/app \
  demo2/src/test/java/com/jason/demo/demo2/order/OrderPreviewCmdExeTest.java
git commit -m "$(cat <<'EOF'
feat(order): add preview command that issues placeToken

EOF
)"
```

---

### Task 6: 下单 PlaceAction + PlaceCmdExe

**Files:**
- Modify: `OrderPlaceAction`（insert 主表+明细 → 逐行 reserve；回滚补偿 release）
- Modify: `OrderPlaceCmdExe`（去掉 amount；去掉方法上 `@Transactional`）
- Modify: `OrderPlaceReqVO` / `OrderPlaceResVO`（`placeToken`+`items[]`；响应 `orderStatus`/`payStatus`，删除手填 `amount` 入参）
- Modify: `Order.create` 改为接收 `List<OrderItem>` 并 `amount=sum(lineAmount)`
- Modify: `OrderCmdExeTest` 删除 `execute(BigDecimal, Duration)` 用例（迁到 `OrderPlaceCmdExeTest`）
- Create: `OrderPlaceCmdExeTest.java`、`OrderPlaceActionTest.java`

**Interfaces:**
- Consumes: TokenStore、`ProductDomainService.requireOnShelf`、Executor、`DelayTaskService.schedule`、`SnowflakeIdGenerator`、`DelayProperties`
- Produces: `OrderPlaceCmdExe.execute(OrderPlaceReqVO, Duration delay)` → `OrderPlaceResVO`

下单顺序（锁内）：

1. `getPreview(token)` 空 / memberId 不匹配 / items 的 productId+qty+sellPrice（`compareTo==0`）与请求不一致 → `PLACE_TOKEN_INVALID`
2. `getResult(token)` 已有 orderId → `requireOrderWithItems` 组装 `OrderPlaceResVO`（`taskId` 可 null；不要再 reserve）
3. 逐行 `requireOnShelf`：下架/不存在用商品码；`overlayAvail.orElse(stock)` < qty → `STOCK_INSUFFICIENT`；当前 `sellPrice.compareTo(请求 sellPrice) != 0` → `PRICE_CHANGED`
4. 组装内存 `Order`（新 orderId、itemId 均雪花）→ `fireEvent(INIT, SUBMIT_ORDER, ctx)`
5. `schedule(ORDER_CANCEL, String.valueOf(orderId), null, delay)`（delay null 用 `DelayProperties.defaultDelay`）
6. `saveResult(token, orderId, 24h)`

`tryLock(token, 30s)` 失败：再读 result，有则返回已有单，否则 `BusinessException(CommonErrorCodeEnum.INTERNAL_ERROR)`。`finally unlock`。

**PlaceAction.execute**（`@Transactional`）：

```java
applyTransition(...); // SUBMIT / WAIT_PAY
orderRepository.insertWithItems(ctx.getOrder());
List<Long> reserved = new ArrayList<>();
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCompletion(int status) {
        if (status == STATUS_ROLLED_BACK) {
            for (Long productId : reserved) {
                try {
                    productStockHotService.release(productId, ctx.getOrder().getOrderId());
                } catch (RuntimeException ex) {
                    log.warn("compensate release failed, orderId={}, productId={}",
                            ctx.getOrder().getOrderId(), productId, ex);
                }
            }
        }
    }
});
for (OrderItem item : ctx.getOrder().getItems()) {
    productStockHotService.reserve(item.getProductId(), ctx.getOrder().getOrderId(), item.getQty());
    reserved.add(item.getProductId());
}
```

先 MySQL 后 reserve。任一行 reserve 抛错 → 事务回滚 → afterCompletion 补偿已 reserve 行（HotService 幂等）。**不写** place result（CmdExe 只在 fireEvent 正常返回后写）。

C 端不传 delay；调试面板本任务仍可让 Controller 解析 delay（Task 9/10 再决定面板是否保留 delay 输入——spec 说去掉创建，delay 仅调试超时用，可留在支付卡或忽略）。

- [ ] **Step 1: Write the failing tests**

`OrderPlaceActionTest`：mock repository + hotService。`execute` 验证 `insertWithItems` 在 `reserve` 之前（`InOrder`）。`reserve` 抛 `STOCK_INSUFFICIENT` 时仍调用了 insert（随后由事务回滚，单测不验 DB）。

`OrderPlaceCmdExeTest`：

- token 空：`getPreview` empty → 30009
- 价格不一致：preview payload sellPrice 18，商品 19 → 30008，且 `never().fireEvent` / `never().saveResult`
- 同一 token 第二次：`getResult` 返回 55L → 不再 `fireEvent`，返回 55
- 成功：`fireEvent` 后 `schedule(ORDER_CANCEL, "55", null, PT10S)`，`saveResult`

登录与 `OrderCmdExeTest` 相同 `LoginPrincipal(9001L, ...)`。

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -Dtest=OrderPlaceActionTest,OrderPlaceCmdExeTest test`

Expected: FAIL

- [ ] **Step 3: Implement**

`OrderPlaceReqVO`：

```java
@NotBlank private String placeToken;
@NotEmpty @Valid @Size(min = 1, max = 1) private List<OrderLineReqVO> items;
@DelayFormat private String delay;
```

`OrderLineReqVO.sellPrice`：下单路径 CmdExe 校验 `@NotNull`（预览 VO 复用同一类时，不要在字段上加 `@NotNull`，在 PlaceCmdExe 里对每行 `if (sellPrice == null) throw PARAM_MISSING`）。

`OrderPlaceResVO`：`orderId`、`orderStatus`、`payStatus`、`amount`、`taskId`、`delay`。删除 `status`。

`OrderPlaceCmdExe` 构造注入：tokenStore、productDomainService、hotService、executor、delayTaskService、idGenerator、delayProperties、orderDomainService。**无** `@Transactional`。

`Order.create(orderId, memberId, items, now)`：amount 为各行 `lineAmount` 之和；≤0 抛 `AMOUNT_INVALID`。

- [ ] **Step 4: Run tests**

Run: `mvn -Dtest=OrderPlaceActionTest,OrderPlaceCmdExeTest,OrderStateMachineExecutorTest,OrderPreviewCmdExeTest test`

Expected: PASS。删除或改写旧 `OrderCmdExeTest.orderPlace_schedulesCancelTask`，避免调用已删除的 `execute(BigDecimal, Duration)`。

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/order demo2/src/test/java/com/jason/demo/demo2/order
git commit -m "$(cat <<'EOF'
feat(order): place order via token, state machine, and stock reserve

EOF
)"
```

---

### Task 7: 支付 / 手动取消 / 超时

**Files:**
- Modify: `OrderPaySuccessAction`、`OrderCancelAction`、`OrderExpireAction`
- Modify: `OrderPaySuccessCmdExe`、`OrderCancelCmdExe`、`OrderExpireCmdExe`（去掉 `@Transactional`）
- Create: `demo2/src/test/java/com/jason/demo/demo2/order/OrderPayCancelExpireTest.java`
- Modify: `PayOrderResVO` / `CancelOrderResVO` 使用 `orderStatus`/`payStatus`

**Interfaces:**
- Consumes: `OrderDomainService.requireOrder` / `requireOrderWithItems`、Executor、`DelayTaskService.cancelByBizKey(DelayTaskType.ORDER_CANCEL, String.valueOf(orderId))`、`ProductStockHotService.confirm/release`
- Produces: 支付/取消 HTTP 路径非法状态 → 30002；超时非 SUBMIT **不 fireEvent**、不抛给调用方、不 release

支付 Action：`applyTransition` 后 `markCompleted(orderId, memberId, now)`，0 行 → `ORDER_STATUS_CONFLICT`。成功则逐行 `confirm(productId, orderId, qty)`。confirm 失败同样靠 Action 事务回滚；Redis confirm 不在 JDBC 里，**不要**做 confirm 补偿（与 spec 支付时序一致：CAS 成功后再 confirm）。

取消 Action：`markCancelled(orderId, memberId, now)`，0 行 → 30002；逐行 `release(productId, orderId)`。

超时 Action：`markCancelled(orderId, null, now)`，0 行 **return**（不抛、不 release）。成功则逐行 release。

`OrderExpireCmdExe`：

```java
Order order = orderDomainService.findById(orderId).orElse(null);
if (order == null || !OrderStatusEnum.SUBMIT.name().equals(order.getOrderStatus())) {
    log.info("skip expire cancel, orderId={}, status={}", orderId, order == null ? null : order.getOrderStatus());
    return;
}
OrderContext ctx = new OrderContext();
ctx.setOrder(orderDomainService.requireOrderWithItems(orderId));
executor.fireEvent(OrderStatusEnum.SUBMIT, OrderEventEnum.ORDER_EXPIRE, ctx);
```

`findById` 若尚无，给 DomainService 加 `Optional<Order> findById(long orderId)`。

Pay/Cancel CmdExe：加载本会员订单（无单 30001）；`fireEvent` 后 `cancelByBizKey`。非 SUBMIT 由 FailCallback 或 Action CAS 抛 30002。

- [ ] **Step 1: Write the failing test**

```java
@Test
void expire_skipsWhenCompleted_noRelease() {
    Order order = new Order();
    order.setOrderId(8L);
    order.setOrderStatus(OrderStatusEnum.COMPLETED.name());
    when(orderDomainService.findById(8L)).thenReturn(Optional.of(order));
    new OrderExpireCmdExe(orderDomainService, executor).execute(8L);
    verify(executor, never()).fireEvent(any(), any(), any());
    verify(productStockHotService, never()).release(anyLong(), anyLong());
}

@Test
void pay_confirmsEachLine_andCancelsDelay() {
    // mock requireOrderWithItems 返回 1 行明细；fireEvent 调真实 PaySuccessAction 或 mock executor
    // 推荐：测 Action 与 CmdExe 分开
}
```

`OrderPaySuccessActionTest`：CAS true → `confirm` 被调用一次；CAS false → 抛 30002 且 never confirm。

`OrderCancelCmdExe`：DomainService 返回 CANCEL 状态订单 → fireEvent 触发 FailCallback 30002。

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=OrderPayCancelExpireTest test`

Expected: FAIL

- [ ] **Step 3: Implement Actions + CmdExe**

Pay/Cancel 加载明细：Action 内 `orderItemRepository.listByOrderId`，不要假设 Ctx 一定带齐 items（超时/支付以 DB 为准）。若 Ctx.items 为空则从仓库加载。

- [ ] **Step 4: Run tests**

Run: `mvn -Dtest=OrderPayCancelExpireTest,OrderPlaceCmdExeTest,OrderStateMachineExecutorTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/order demo2/src/test/java/com/jason/demo/demo2/order
git commit -m "$(cat <<'EOF'
feat(order): pay, cancel, and expire via state machine actions

EOF
)"
```

---

### Task 8: 列表、冒泡、详情带明细

**Files:**
- Create: `OrderListReqVO`、`OrderListResVO`、`OrderListItemResVO`、`OrderCountsResVO`、`OrderLineResVO`、`OrderListCmdExe`、`OrderCountsCmdExe`
- Modify: `OrderGetCmdExe`、`GetOrderResVO`、`OrderVoConvert`
- Create: `OrderListCountsCmdExeTest.java`

**Interfaces:**
- Consumes: `OrderRepository.countByMemberAndStatus` / `pageByMemberAndTab` / `countPageByMemberAndTab`、`OrderItemRepository.listByOrderIds`
- Produces: `counts` → `{ pendingCount, completedCount }`；`list` → `{ pageNo, pageSize, total, items }`；`get` → 含 `items[]`，无明细则空数组

`OrderListReqVO`：`tab` `@NotNull OrderListTabEnum`；`pageNo` 默认 1 `@Min(1)`；`pageSize` 默认 20 `@Min(1) @Max(50)`。用 `Integer` + 在 CmdExe 里 `pageNo = req.getPageNo() == null ? 1 : req.getPageNo()`。

Tab→SQL：`ALL` 传 `orderStatus=null`；`SUBMIT`/`COMPLETED` 传对应名。

列表项可只带封面/名称/qty/sellPrice；详情带全量快照。批量加载明细，禁止 N+1。

- [ ] **Step 1: Write the failing test**

```java
@Test
void counts_mapsSubmitAndCompleted() {
    when(orderRepository.countByMemberAndStatus(9001L, "SUBMIT")).thenReturn(3L);
    when(orderRepository.countByMemberAndStatus(9001L, "COMPLETED")).thenReturn(11L);
    OrderCountsResVO vo = new OrderCountsCmdExe(orderRepository).execute();
    assertEquals(3L, vo.getPendingCount());
    assertEquals(11L, vo.getCompletedCount());
}

@Test
void list_all_passesNullStatus() {
    when(orderRepository.countPageByMemberAndTab(9001L, null)).thenReturn(1L);
    when(orderRepository.pageByMemberAndTab(eq(9001L), isNull(), eq(0), eq(20)))
            .thenReturn(List.of(order));
    when(orderItemRepository.listByOrderIds(List.of(55L))).thenReturn(Map.of(55L, List.of(item)));
    OrderListReqVO req = new OrderListReqVO();
    req.setTab(OrderListTabEnum.ALL);
    OrderListResVO vo = exe.execute(req);
    assertEquals(1L, vo.getTotal());
    assertEquals("CANCEL", vo.getItems().get(0).getOrderStatus()); // 构造一条 CANCEL 证明 ALL 可含取消
}
```

`get` 别人的单：`requireOrder` 抛 30001。

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=OrderListCountsCmdExeTest test`

Expected: FAIL

- [ ] **Step 3: Implement CmdExe + VO**

废弃所有 ResVO 的 `status` 字段，统一 `orderStatus`。MapStruct 显式 `@Mapping(target = "orderStatus", source = "orderStatus")`。

- [ ] **Step 4: Run tests**

Run: `mvn -Dtest=OrderListCountsCmdExeTest,OrderPayCancelExpireTest test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/order/app demo2/src/test/java/com/jason/demo/demo2/order/OrderListCountsCmdExeTest.java
git commit -m "$(cat <<'EOF'
feat(order): add order list, counts, and item snapshots on get

EOF
)"
```

---

### Task 9: Controller OpenAPI

**Files:**
- Modify: `demo2/src/main/java/com/jason/demo/demo2/order/app/controller/OrderController.java`

**Interfaces:**
- Consumes: 全部 CmdExe
- Produces: spec §6 七条 POST。`counts`：`@RequestBody(required = false) Object ignored`，`@Operation` 写明「无请求体」。

- [ ] **Step 1: Write the failing test**

若无 WebMvc 测试习惯，用编译 + 反射断言方法存在：

```java
@Test
void controller_exposesPreviewPlaceListCounts() throws Exception {
    assertNotNull(OrderController.class.getMethod("preview", OrderPreviewReqVO.class));
    assertNotNull(OrderController.class.getMethod("list", OrderListReqVO.class));
    assertNotNull(OrderController.class.getMethod("counts", Object.class));
}
```

放进 `OrderControllerMappingTest`。`orderPlace` 方法签名改为 `(OrderPlaceReqVO)`，内部 `OrderDelayParser.parseDelay(request.getDelay())` 再交给 CmdExe。

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=OrderControllerMappingTest test`

Expected: FAIL（无 preview/counts）

- [ ] **Step 3: Implement controller**

```java
@LoginRequired
@Operation(summary = "预览下单", description = "不落库、不占库存，签发 placeToken")
@PostMapping("/preview")
public JsonResult<OrderPreviewResVO> preview(@Valid @RequestBody OrderPreviewReqVO request) {
    return JsonResults.ok(orderPreviewCmdExe.execute(request));
}

@LoginRequired
@Operation(summary = "下单", description = "校验 placeToken 后预占库存并创建 SUBMIT 订单")
@PostMapping("/orderPlace")
public JsonResult<OrderPlaceResVO> orderPlace(@Valid @RequestBody OrderPlaceReqVO request) {
    return JsonResults.ok(orderPlaceCmdExe.execute(request, OrderDelayParser.parseDelay(request.getDelay())));
}

@LoginRequired
@Operation(summary = "订单数量", description = "无请求体。返回待支付/已完成数量供 Tab 冒泡")
@PostMapping("/counts")
public JsonResult<OrderCountsResVO> counts(@RequestBody(required = false) Object ignored) {
    return JsonResults.ok(orderCountsCmdExe.execute());
}

@LoginRequired
@Operation(summary = "订单列表")
@PostMapping("/list")
public JsonResult<OrderListResVO> list(@Valid @RequestBody OrderListReqVO request) {
    return JsonResults.ok(orderListCmdExe.execute(request));
}
```

pay/get/cancel 保持路径不变。Controller 只注入 CmdExe。

- [ ] **Step 4: Run tests**

Run: `mvn -Dtest=OrderControllerMappingTest,OrderPreviewCmdExeTest,OrderPlaceCmdExeTest,OrderListCountsCmdExeTest test`

Expected: PASS。再跑 `mvn -Dtest=com.jason.demo.demo2.order.*Test test` 保证订单包全绿。

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/java/com/jason/demo/demo2/order/app/controller/OrderController.java \
  demo2/src/test/java/com/jason/demo/demo2/order/OrderControllerMappingTest.java
git commit -m "$(cat <<'EOF'
feat(order): expose preview, list, and counts HTTP endpoints

EOF
)"
```

---

### Task 10: C 端购买流、订单 Tab、调试面板

**Files:**
- Modify: `demo2/src/main/resources/static/js/tabs/member.js`
- Modify: `demo2/src/main/resources/static/css/tabs/member.css`
- Modify: `demo2/src/main/resources/static/index.html`

**Interfaces:**
- Consumes: `/demo/orders/preview|orderPlace|pay|cancel|get|list|counts`、现有 `MemberAuth.open({ onSuccess })`、`memberRequest` 的 `error.code`
- Produces: 立即购买启用；未登录先 Auth 再预览；订单 Tab 三个页签 + 冒泡；调试面板无「创建待支付订单」

- [ ] **Step 1: Write a failing characterization check**

无法跑 JS 单测时，先改 HTML：删除 `#memberOrderAmount` 与 `onclick="memberOrderCreate()"`。随后 `rg memberOrderCreate` 必须无匹配。本步在实现前用 grep 确认按钮仍在（当前 `index.html` 约 1411 行仍有「创建待支付订单」）。

- [ ] **Step 2: Confirm current UI still has the old create button**

`rg "创建待支付订单" demo2/src/main/resources/static/index.html` 有命中。

- [ ] **Step 3: Implement frontend**

状态：`memberMobileView` 增加 `'preview'`、`'orderDetail'`。`memberPreviewQty` 默认 1。`memberOrderListTab` 默认 `'ALL'`。

立即购买（替换 disabled 按钮）：

```javascript
function memberBuyNow(productId) {
    memberSelectedProductId = memberSnowflakeId(productId);
    if (!memberToken) {
        MemberAuth.open({
            mode: 'login',
            onSuccess: function () {
                memberMobileView = 'preview';
                memberPreviewQty = 1;
                memberRender();
            }
        });
        return;
    }
    memberMobileView = 'preview';
    memberPreviewQty = 1;
    memberRender();
}
```

详情按钮：`onclick="memberBuyNow('` + memberSnowflakeId(item.productId) + `')"`，去掉 `disabled`。

`memberRender()` 分支增加 preview / orderDetail。

预览页：`POST /demo/orders/preview` body `{ items: [{ productId, qty: memberPreviewQty }] }`。qty 输入 1..min(99999, availableStock)，blur/change 后重新 preview。提交：

```javascript
await memberRequest('/demo/orders/orderPlace', {
    placeToken: preview.placeToken,
    items: preview.items.map(function (row) {
        return {
            productId: memberSnowflakeId(row.productId),
            qty: row.qty,
            sellPrice: row.sellPrice
        };
    })
});
```

按钮提交后 `disabled` 直到返回。`error.code === 30008 || error.code === 30009` 提示「价格或凭证已失效，请刷新预览」并重新 preview。成功：`memberOrderLastOrderId = memberSnowflakeId(data.orderId)`，填 `#memberOrderId`（若还在），`memberMobileView = 'orderDetail'`，`get` 渲染待支付详情（去支付 / 取消）。

订单 Tab `memberRenderOrders`：三个按钮「全部 / 待支付 / 已完成」。进入时：

```javascript
const [counts, list] = await Promise.all([
    memberRequest('/demo/orders/counts', {}),
    memberRequest('/demo/orders/list', { tab: memberOrderListTab, pageNo: 1, pageSize: 20 })
]);
```

待支付 Tab 冒泡 `counts.pendingCount`，已完成 `counts.completedCount`，为 0 不渲染红点。全部不冒泡。点卡片 `get` 进详情。支付/取消成功后再并行 counts+list。

未登录进订单 Tab：调用 `memberRequireLogin()`，失败则只显示「请先登录」。

`index.html` 侧栏：标题改为「支付 / 查询 / 台账」；删除金额、超时输入和创建按钮；保留 orderId、模拟支付、取消、刷新订单+台账。删除 `memberOrderCreate` 函数。可用 C 端下单后的 orderId 测超时（短 delay 仅能通过临时改 `app.delay.default-delay` 或保留隐藏 delay，**按 spec 不在面板创建订单**）。

CSS：`.member-order-tabs`、`.member-order-badge`（小红点）、`.member-preview-qty`。

- [ ] **Step 4: Verify**

- `rg "memberOrderCreate|创建待支付订单" demo2/src/main/resources/static` 无业务命中。
- `rg "立即购买" demo2/src/main/resources/static/js/tabs/member.js` 按钮无 `disabled`。
- 启动应用后手工：登录 → 详情立即购买 → 改 qty 重新 preview → 提交 → 支付 → 订单 Tab 冒泡；未登录立即购买弹出 Auth。若本机未起服务，在回复中写明未做浏览器联调。

- [ ] **Step 5: Commit**

```bash
git add demo2/src/main/resources/static/js/tabs/member.js \
  demo2/src/main/resources/static/css/tabs/member.css \
  demo2/src/main/resources/static/index.html
git commit -m "$(cat <<'EOF'
feat(order): wire C-end buy preview list tabs and drop debug place

EOF
)"
```

---

## Self-review（对照 spec）

| Spec 条目 | 任务 |
|-----------|------|
| INIT 不落库；SUBMIT→COMPLETED/CANCEL | Task 2、6、7 |
| pay_status 伴随字段表 | Task 1、2 |
| COLA 5.0.0；包 `service.core.statemachine` | Task 2 |
| Action `@Transactional` 落库+库存；CmdExe 延时在 fireEvent 后 | Task 6、7 |
| 禁止匿名 Action | Task 2 |
| preview 不占库存；token TTL 默认 30m | Task 4、5 |
| place 锁 + result 幂等；失败不写 result | Task 6 |
| items[] max=1；明细 member_id | Task 3、5、6 |
| 下单再校验价格 PRICE_CHANGED | Task 6 |
| counts / list 分离；ALL 含取消；冒泡 | Task 8、10 |
| HTTP 七条 POST + LoginRequired | Task 9 |
| 废弃 amount 下单与 VO `status` | Task 6、8、9 |
| 错误码 30007–30010；复用商品码 | Task 1、5、6 |
| 超时 skip 不抛错 | Task 7 |
| 立即购买先登录；调试面板去掉创建 | Task 10 |
| 只调 ProductStockHotService；预览 overlayAvail | Task 5、6、7 |
| 历史单无明细不进 list | Task 3、8 |
| OrderCmdExeTest 改新入参 | Task 6 |

无 TBD/TODO 占位。类型名前后任务一致：`placeTokenTtl`、`markCompleted`、`countPageByMemberAndTab`、`OrderListTabEnum`。
