# demo2 订单分库分表与订单号基因法设计规范

**日期**: 2026-08-30  
**项目**: spring-ai-demo / demo2  
**状态**: 已实现  
**前置**: [2026-08-28-order-module-statemachine-design.md](./2026-08-28-order-module-statemachine-design.md)、[2026-08-07-snowflake-service-isolation-design.md](./2026-08-07-snowflake-service-isolation-design.md)、[2026-08-23-order-ddd-package-refactor-design.md](./2026-08-23-order-ddd-package-refactor-design.md)  
**范围**: 仅 `demo_order` / `demo_order_item`。会员、商品、延时任务、库存流水仍在默认库 `spring_ai_agent2`。

---

## 1. 背景与目标

### 1.1 背景

- 订单主表、明细已是单库单表（`spring_ai_agent2.demo_order` / `demo_order_item`），两表都有 `member_id`（当时为分片预留）。
- 订单号由 Hutool 雪花发出，**不含分片信息**。列表/计数按 `memberId` 可直达分片；超时关单、`selectById`、按单查明细经常**只带 orderId**。
- 状态机 spec 把「分库分表落地」列为非目标。本 spec 补上。

### 1.2 目标

1. 按 **用户 ID（`memberId`）** 对订单主表、明细做分库分表。
2. 订单号采用**基因法**：低位嵌入虚拟分片，只拿 `orderId` 也能直达目标库表。
3. 本机可跑：同一 MySQL 实例两个 schema × 每库 32 张主表 + 32 张明细。
4. 会员 Tab 右侧可算路由；业务查询打库表日志。C 端下单/支付/列表接口形态不变。

### 1.3 已确认决策

| 维度 | 选择 |
|------|------|
| 现在拓扑 | **2 库 × 32 表**（逻辑分片 64） |
| 未来天花板 | **2 库 × 256 表**（逻辑分片 512）；库数保持 2 |
| 基因 | **9 bit**（虚拟分片 512 = 天花板；2⁸=256 不够） |
| 分片键 | `member_id`；仅有 `order_id` 时从订单号拆基因 |
| 路由实现 | ShardingSphere **CLASS_BASED 复合算法**（方案 1） |
| 接入 | 官方 `ShardingSphereDriver` + `shardingsphere.yaml`（5.3+ 已去掉 Spring Starter） |
| 旧数据 | **绿场**：新 schema 空表；不迁 `spring_ai_agent2` 旧单；不 DROP 旧表 |
| 事务 | **LOCAL**，不上 XA；`@Transactional` 只保证订单分片 |
| 库存 | 本阶段 **热库存必须开启**；冷路径与分片叠加不作保证 |
| 调试 | 右侧「分片调试」卡片 + `POST /demo/orders/shardExplain`（不登录、不查库） |
| 业务日志 | 算法 `log.info`（gene / ds / table / source）+ YAML `sql-show: true` |
| 明细 | 与主表 **binding table**；`item_id` 仍是普通雪花，不做基因 |

### 1.4 非目标

- 跨分片扫全表（运营「全部订单」）
- 旧雪花订单迁移 / 双写 / 无基因兜底广播
- `itemId` 基因、会员/商品/延时任务分片
- XA / BASE 分布式事务
- 冷库存（`redis-hot-enabled=false`）与分片同事务
- 2 库改 3 库（取模从 `% 2` 变 `% 3`，必须大迁，本天花板不覆盖）

---

## 2. 架构

### 2.1 物理拓扑

同一 MySQL 实例新建：

- `order_ds_0` / `order_ds_1`
- 每库：`demo_order_0`～`demo_order_31`、`demo_order_item_0`～`demo_order_item_31`

列定义与现网 `demo_order` / `demo_order_item` 相同。脚本循环生成，共 **128** 张表。旧库单表只留作历史，应用不再读写。

应用只认逻辑表 `demo_order`、`demo_order_item`。

YAML 挂 3 个真实数据源：

| 逻辑名 | 物理库 | 用途 |
|--------|--------|------|
| `ds_default` | `spring_ai_agent2` | 会员、商品、延时任务、其它未分片表 |
| `order_ds_0` | `order_ds_0` | 订单分片 |
| `order_ds_1` | `order_ds_1` | 订单分片 |

`application.properties` 只改驱动与 URL，连接信息写在 yaml：

```properties
spring.datasource.driver-class-name=org.apache.shardingsphere.driver.ShardingSphereDriver
spring.datasource.url=jdbc:shardingsphere:classpath:shardingsphere.yaml
```

不再设置 `spring.datasource.url` 直连单库，避免 Boot 再自动建一套闲置池。

依赖：`org.apache.shardingsphere:shardingsphere-jdbc` **5.5.2**（或实施时同系列最新稳定版）。不引入已删除的 `shardingsphere-jdbc-core-spring-boot-starter`。

### 2.2 基因与路由公式

基因是 64 位订单号里最低 **9 个 bit**，不是十进制「订单号有几位」。外观仍是雪花长整型。

```text
GENE_BITS     = 9
VIRTUAL_COUNT = 512          // 2^9，等于天花板 2×256
DB_COUNT      = 2
TABLE_COUNT   = 32           // 现在；天花板 256

virtual = memberId % 512
        = orderId  & 0x1FF   // 只拿订单号时

ds      = virtual % 2                    // 0..1，扩表不变
table   = (virtual / 2) % TABLE_COUNT    // 现在 32；以后改 256
```

**禁止**写成 `table = virtual % 32`：2 与 32 不互质，会出现「一库只落偶数表、另一库只落奇数表」。

发号：`raw = SnowflakeIdGenerator.nextId()`，再

```text
orderId = (raw & ~0x1FF) | (memberId % 512)
```

雪花序号原 12 bit，被占 9 bit，剩 3 bit（每毫秒每节点 8 个号）。Demo 够用。`itemId`、延时任务 ID 仍走未改写的 `nextId()`。

### 2.3 扩容（本天花板内）

**2×32 → 2×256（只改 `TABLE_COUNT`）：**

- 库取模仍是 `virtual % 2`，行不换库。
- 每张旧表在同库内拆成 8 张：旧表 `T` 容纳 `(virtual/2)%32 == T`；新表为 `(virtual/2)%256 ∈ {T, T+32, …, T+224}`。
- **要搬行，不用改订单号**；只拿 `orderId` 仍落到唯一一张新表。
- 发号算法不用改。

**超出天花板（例如 2×512 或 2 库改 3 库）：** 9 位不够或库取模变化，需改基因位数/公式并迁移。不在本 spec。

### 2.4 复合分片算法

`OrderComplexShardingAlgorithm`（ShardingSphere `CLASS_BASED` + `COMPLEX`），主表与明细共用，binding。

| SQL 分片列 | 怎么算 `virtual` |
|------------|------------------|
| 有 `member_id` | `memberId % 512` |
| 只有 `order_id` | `orderId & 0x1FF` |
| 两个都有 | 用 `member_id`；与订单号低 9 位不一致则本分片无行，业务 404 |
| 两个都没有 | **抛错，禁止广播 64 张表** |

`IN` 多个 `order_id`（同会员列表查明细）：各 ID 基因相同则单分片；若不一致按值分别路由，禁止无键广播。

下单插入主表+明细都带 `member_id`，同一物理库，本地事务即可。

### 2.5 事务与默认库

当前 `OrderPlaceAction` `@Transactional` 内：写订单 + `reserve`。热库存只改 Redis，MySQL 库存/流水由 MQ 投影到默认库。延时任务在 Action **提交之后** 写 `delay_task`。

| 路径 | 事务内物理库 | LOCAL |
|------|--------------|-------|
| 下单/支付/取消 + 热库存（默认） | 只写一个 `order_ds_*` | 正常 |
| 延时关单注册 | 订单事务外写默认库 | 无变化 |
| 冷库存关在同一 `@Transactional` | 订单分片 + 默认库库存 | 跨库 DML，LOCAL 不原子，5.x 常直接失败 |

约定：事务类型 **LOCAL**；`@Transactional` **只保证订单分片**（主表+明细）；本阶段热库存保持开启。

---

## 3. 组件与接口

分层仍遵守 `app → service.core → service.infrastructure`。算法类由 ShardingSphere 反射创建，**无 Spring 注入**；只调用纯函数 `OrderShardGene`。

### 3.1 类与职责

| 类型 | 包 / 类 | 职责 |
|------|---------|------|
| 纯计算 | `order.service.infrastructure.shard.OrderShardGene` | `virtualOfMember` / `virtualOfOrderId` / `dsIndex` / `tableIndex` / `embed(raw, memberId)` / `geneBits` |
| 发号 | `order.service.infrastructure.shard.OrderIdGenerator` | `nextOrderId(memberId)` = 雪花 + `embed` |
| 算法 | `order.service.infrastructure.shard.OrderComplexShardingAlgorithm` | 实现 SS 复合精确分片；打路由日志 |
| 配置 | `src/main/resources/shardingsphere.yaml` | 三数据源、逻辑表、binding、`sql-show` |
| 建表 | `src/main/resources/db/order-shard-schema.sql` | `CREATE DATABASE` + 循环建 128 张表 |
| 调试用例 | `order.app.executor.OrderShardExplainCmdExe` | 调 `OrderShardGene`，不碰 Mapper |
| HTTP | `order.app.controller.OrderShardController` | `POST /demo/orders/shardExplain`，**无** `@LoginRequired` |
| VO | `OrderShardExplainReqVO` / `OrderShardExplainResVO` | Jakarta Validation + `@Schema` |

`OrderPlaceCmdExe` 只把订单号从 `idGenerator.nextId()` 改为 `orderIdGenerator.nextOrderId(memberId)`。明细 `itemId` 仍用 `SnowflakeIdGenerator`。

`OrderShardGene` 常量与公式写死（9 / 512 / 2 / 32），**不做成可配项**，避免与已发出的订单号对不上。

### 3.2 调试接口

`POST /demo/orders/shardExplain`，统一 `JsonResult`。不查库、不登录。

请求（至少一个，`@AssertTrue` 或 CmdExe 校验）：

```json
{ "orderId": "2085...", "memberId": 612 }
```

`612 % 512 = 100`。响应：

```json
{
  "virtual": 100,
  "geneBits": "001100100",
  "ds": "order_ds_0",
  "table": "demo_order_18",
  "itemTable": "demo_order_item_18",
  "source": "MEMBER_ID",
  "memberVirtual": 100,
  "orderVirtual": 100,
  "geneMatch": true
}
```

（`ds = 100 % 2 = 0`，`table = (100 / 2) % 32 = 18`。）

| 输入 | `source` | 展示用的 ds/table | `geneMatch` |
|------|----------|-------------------|-------------|
| 仅 `orderId` | `ORDER_ID` | 从订单号拆 | `null` |
| 仅 `memberId` | `MEMBER_ID` | 从会员算 | `null` |
| 两个都有 | `MEMBER_ID` | 跟运行时算法一致，用 `memberId` | 两边 `virtual` 是否相等 |
| 两个都空 | — | — | `PARAM_MISSING(10002)` |

`geneBits` 为 9 位二进制，高位补 0。

### 3.3 右侧调试台

会员 Tab `member-side-panel`，在「查询 / 台账」**上方**加卡片：

- 标题：分片调试
- 输入：`orderId`、`memberId`（可只填一个）
- 按钮：计算路由
- 下单成功后自动填入最近 `orderId`（复用 `memberOrderLastOrderId`）
- 结果：`virtual`、9 位二进制、目标库、主表、明细表、依据列、是否匹配

不改 C 端下单/支付/列表/详情报文。

### 3.4 YAML 要点

- `actualDataNodes`：`order_ds_$->{0..1}.demo_order_$->{0..31}`（明细同构）
- binding：`demo_order, demo_order_item`
- 库、表策略均为 COMPLEX，分片列 `member_id, order_id`，算法指向 `OrderComplexShardingAlgorithm`
- 未配置逻辑表走 `ds_default`
- `props.sql-show: true`

算法日志示例：`order shard route, logic=demo_order, virtual=100, ds=order_ds_0, table=demo_order_18, source=member_id`。

---

## 4. 数据流

```text
下单（有 memberId）
  OrderPlaceCmdExe
    → OrderIdGenerator.nextOrderId(memberId)
    → itemId = SnowflakeIdGenerator.nextId()
    → Action @Transactional
         insert 主表+明细（均带 member_id）
         → 算法 virtual=memberId%512 → order_ds_{v%2}.demo_order_{(v/2)%32}
         → 明细 binding 同库同后缀
         → Redis reserve（不在 JDBC 事务）
    → 提交后 delay_task 写 ds_default

列表 / 计数
  SQL 仅 member_id → 单分片

详情 / 支付 / 取消 / 超时关单 / 按单查明细
  SQL 仅 order_id 或 order_id+member_id
    → 仅 order_id：virtual=orderId&0x1FF
    → 两者都有：按 member_id；基因不一致则 404
  登录路径仍 findByIdAndMemberId / CAS 带 memberId
  OrderExpireCmdExe 仍只拿 orderId，靠基因直达

调试台
  shardExplain → OrderShardGene 纯计算 → 展示
```

---

## 5. 错误处理

| 情况 | 行为 |
|------|------|
| 分片 SQL 无 `member_id` 且无 `order_id` | 算法抛错，禁止广播 |
| 登录用户查别人的单 / 基因不一致无行 | `ORDER_NOT_FOUND(30001)`，与现在一致 |
| `shardExplain` 两个 ID 都空 | `PARAM_MISSING(10002)` |
| 冷库存与订单同事务写两库 | 本阶段不支持；保持热库存开启 |
| 逻辑表误写到未分片库 | 不支持 |

下单失败时 Redis 预占补偿（`afterCompletion` release）保持现状。延时任务失败不回滚已提交订单（与现网一致）。

不新增订单错误码。

---

## 6. 测试

| 对象 | 断言 |
|------|------|
| `OrderShardGene` | embed / 拆 virtual / ds / table；边界 virtual 0、511；禁止 `table=virtual%32` 的错误分布（两库 0～31 表都有行） |
| `OrderIdGenerator` | 同一 `memberId` 连续发号，低 9 位恒等于 `memberId%512`，高位仍变化 |
| 算法 | 仅 member_id、仅 order_id、两者一致、两者不一致、两者皆无（禁止广播）；主表与明细节点名 |
| `OrderShardExplainCmdExe` | 三种输入 + 空输入 10002 |
| 现有 CmdExe | 继续 mock 仓储；下单 mock `OrderIdGenerator` |

不在单元测试里起 128 张真实表。本地用脚本建库后，靠调试台 + `sql-show` / 算法日志手工验收：下单、详情、超时关单、列表。

---

## 7. 前端与手工验收

1. 执行 `order-shard-schema.sql`，确认 2 个 schema、128 张表。
2. 启动后下单，记录 `orderId`。
3. 调试台只填 `orderId`：基因、库、表与算法日志一致。
4. 只填该用户 `memberId`：与上一步同一库表。
5. 详情 / 支付 / 取消 / 等待超时关单：日志显示单库单表，无 64 表广播。
6. 列表 / 计数仍按登录会员，单分片。
7. C 端接口字段与登录要求不变。

---

## 8. 包与文件清单（实施时按此裁剪）

```
order/service/infrastructure/shard/OrderShardGene.java
order/service/infrastructure/shard/OrderIdGenerator.java
order/service/infrastructure/shard/OrderComplexShardingAlgorithm.java
order/app/controller/OrderShardController.java
order/app/executor/OrderShardExplainCmdExe.java
order/app/vo/req/OrderShardExplainReqVO.java
order/app/vo/res/OrderShardExplainResVO.java
src/main/resources/shardingsphere.yaml
src/main/resources/db/order-shard-schema.sql
src/test/java/.../order/OrderShardGeneTest.java
src/test/java/.../order/OrderIdGeneratorTest.java
src/test/java/.../order/OrderComplexShardingAlgorithmTest.java
src/test/java/.../order/OrderShardExplainCmdExeTest.java
static/index.html + js/tabs/member.js   # 分片调试卡片
```

改动：`application.properties` 数据源、`OrderPlaceCmdExe` 发号、`pom.xml` 增加 `shardingsphere-jdbc`。
