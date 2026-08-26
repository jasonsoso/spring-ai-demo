# demo2 商品模块设计规范

**日期**: 2026-08-26  
**项目**: spring-ai-demo / demo2  
**状态**: 已实现（见 [archive/2026-08-26-product-module.md](../archive/2026-08-26-product-module.md)）

---

## 1. 背景与目标

### 1.1 背景

- 会员 C 端 Demo（`member.js`）首页目前为**静态**三件商品（拿铁 / 生椰拿铁 / 芝士蛋糕），无后端商品数据。
- 现有 `order` 模块仅支持手填 `amount` 下单，未关联商品与库存。
- 错误码规范已预留商品段 `4xxxx`（见 `2026-08-25-unified-json-result-design.md`）。
- 用户曾有一套 7 表商品模型（info/detail/sku/stock_center/stock_log/sku_price/price_log），demo2 需**大幅简化**。

### 1.2 目标

1. 新建 `product` 业务模块（DDD 分包，参照 `order`）。
2. 三表：**商品主表**、**库存表**、**库存流水表**；无 SKU，一商品一价一库存。
3. 库存策略 **A**：下单预占 → 支付实扣 → 取消/超时释放；流水记录每次变动，**取消回滚数量从 RESERVE 流水查询**。
4. 库存表含 `actual_stock`（现货）、`stock`（可售）、`withhold_stock`（预占）、`sell_stock`（累计已售，供排序与「已售 xx」展示）。
5. 本阶段交付：
   - DDL + seed + 后端读接口（列表 / 详情）
   - `ProductStockDomainService` 实现 `reserve/confirm/release`（写流水，供订单模块后续调用）
   - C 端 `member.js` 首页接真实接口 + 商品详情页
6. **订单主表 + 明细表改造留后续**；本阶段右侧订单调试面板仍可手填金额。

### 1.3 已确认决策

| 维度 | 选择 |
|------|------|
| SKU | 无，一商品一价一库存 |
| 表数量 | 3：`demo_product` / `demo_product_stock` / `demo_product_stock_log` |
| 详情内容 | 合并进主表 `detail_content`，不单独建详情表 |
| 库存策略 | 预占（RESERVE）→ 支付（CONFIRM）→ 取消释放（RELEASE） |
| 取消回滚 | 查 `(order_id, product_id)` 的 RESERVE 流水取 `change_qty` |
| 本阶段 HTTP | 仅 C 端读：`listProducts` / `getProduct`（无需登录） |
| 库存写操作 | 不暴露 HTTP，由 `ProductStockDomainService` 供订单模块内部调用 |
| C 端范围 | 列表 + 详情；「立即购买」预留 disabled，订单接入后续做 |
| 模块风格 | 标准 DDD，包路径 `com.jason.demo.demo2.product` |

### 1.4 非目标

- SKU / 多规格 / 分类 / 店铺维度
- B 端运营 CRUD 页面（seed 数据即可）
- 订单主表 + 明细表改造（后续 spec）
- 库存流水对外查询 API
- 拆独立 `inventory` Maven 模块

---

## 2. 数据模型

### 2.1 ER 关系

```text
demo_product (1) ── (1) demo_product_stock
       │
       └── (N) demo_product_stock_log
```

### 2.2 主键约定（对齐 `demo_member`）

三张表均采用 **无业务含义的自增 `id` 作主键**；对外关联、API、库存更新一律使用业务 ID 字段：

| 表 | 自增 PK | 业务唯一键 | MyBatis `@TableId` |
|----|---------|-----------|-------------------|
| `demo_product` | `id` | `product_id`（雪花） | `id`, `IdType.AUTO` |
| `demo_product_stock` | `id` | `stock_id`（雪花）、`product_id`（1:1 商品） | `id`, `IdType.AUTO` |
| `demo_product_stock_log` | `id` | `log_id`（雪花）、`stock_id`（关联库存行） | `id`, `IdType.AUTO` |

> 条件更新、JOIN 仍按 `product_id`；流水写入与对账携带 `stock_id`；取消回滚查询按 `order_id` + `product_id`（或 `order_id` + `stock_id`）。**不按自增 `id`**。

### 2.3 `demo_product`（商品主表）

```sql
CREATE TABLE IF NOT EXISTS demo_product (
    id              BIGINT         NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键',
    product_id      BIGINT         NOT NULL COMMENT '商品ID（雪花）',
    product_name    VARCHAR(128)   NOT NULL COMMENT '商品名称',
    subtitle        VARCHAR(255)   NOT NULL DEFAULT '' COMMENT '列表副标题',
    cover_url       VARCHAR(512)   NULL COMMENT '封面图 URL',
    sell_price      DECIMAL(10,2)  NOT NULL COMMENT '售价',
    market_price    DECIMAL(10,2)  NULL COMMENT '划线价（可选）',
    detail_content  TEXT           NULL COMMENT '详情页图文内容',
    status          VARCHAR(32)    NOT NULL COMMENT 'ON_SHELF / OFF_SHELF',
    sort            INT            NOT NULL DEFAULT 0 COMMENT '排序，越大越靠前',
    created_at      DATETIME(3)    NOT NULL COMMENT '创建时间',
    updated_at      DATETIME(3)    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_demo_product_product_id (product_id),
    INDEX idx_demo_product_status_sort (status, sort DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演示商品主表';
```

### 2.4 `demo_product_stock`（库存表，与商品 1:1）

```sql
CREATE TABLE IF NOT EXISTS demo_product_stock (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键',
    stock_id        BIGINT       NOT NULL COMMENT '库存业务ID（雪花）',
    product_id      BIGINT       NOT NULL COMMENT '商品ID',
    actual_stock    INT          NOT NULL DEFAULT 0 COMMENT '现货库存（后台编辑/支付出库）',
    stock           INT          NOT NULL DEFAULT 0 COMMENT '可售库存',
    withhold_stock  INT          NOT NULL DEFAULT 0 COMMENT '预占库存',
    sell_stock      INT          NOT NULL DEFAULT 0 COMMENT '累计已售库存',
    updated_at      DATETIME(3)  NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_demo_product_stock_stock_id (stock_id),
    UNIQUE KEY uk_demo_product_stock_product_id (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演示商品库存表';
```

创建商品时同步插入库存行：`stock_id` 与 `product_id` 均由雪花生成；seed 脚本写死两者便于演示。

#### 字段语义与恒等式

| 字段 | 含义 | 维护方式 |
|------|------|----------|
| `actual_stock` | 仓内现货 | 后台 ADJUST 直接改；支付 CONFIRM 时 `-n` |
| `stock` | 可售库存（C 端「还能买多少」） | RESERVE `-n`；RELEASE `+n`；ADJUST 时重算 |
| `withhold_stock` | 待支付预占 | RESERVE `+n`；CONFIRM/RELEASE `-n` |
| `sell_stock` | 累计已售（只增不减） | 仅 CONFIRM 时 `+n` |

**恒等式**（任意时刻）：

```text
stock = actual_stock - withhold_stock
```

应用层在每次库存更新后断言；demo 环境不做 DB CHECK 约束。

#### 各操作对四字段的影响

| 操作 | actual_stock | stock | withhold_stock | sell_stock |
|------|-------------|-------|----------------|------------|
| RESERVE（下单预占） | 不变 | `-n` | `+n` | 不变 |
| CONFIRM（支付出库） | `-n` | 不变 | `-n` | `+n` |
| RELEASE（取消回滚） | 不变 | `+n` | `-n` | 不变 |
| ADJUST（后台改现货） | 设为 `v` | `v - withhold` | 不变 | 不变 |

### 2.5 `demo_product_stock_log`（库存流水表）

```sql
CREATE TABLE IF NOT EXISTS demo_product_stock_log (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键',
    log_id           BIGINT       NOT NULL COMMENT '流水业务ID（雪花）',
    stock_id         BIGINT       NOT NULL COMMENT '库存业务ID',
    product_id       BIGINT       NOT NULL COMMENT '商品ID',
    order_id         BIGINT       NULL COMMENT '关联订单ID',
    opt_type         VARCHAR(32)  NOT NULL COMMENT 'RESERVE/CONFIRM/RELEASE/ADJUST',
    change_qty       INT          NOT NULL COMMENT '变动数量（正数）',
    before_actual    INT          NOT NULL COMMENT '变动前 actual_stock',
    after_actual     INT          NOT NULL COMMENT '变动后 actual_stock',
    before_stock     INT          NOT NULL COMMENT '变动前 stock',
    after_stock      INT          NOT NULL COMMENT '变动后 stock',
    before_withhold  INT          NOT NULL COMMENT '变动前 withhold_stock',
    after_withhold   INT          NOT NULL COMMENT '变动后 withhold_stock',
    before_sell      INT          NOT NULL COMMENT '变动前 sell_stock',
    after_sell       INT          NOT NULL COMMENT '变动后 sell_stock',
    remarks          VARCHAR(255) NULL COMMENT '备注',
    created_at       DATETIME(3)  NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_demo_product_stock_log_log_id (log_id),
    INDEX idx_stock_log_stock_id (stock_id),
    INDEX idx_stock_log_order_product (order_id, product_id, opt_type),
    INDEX idx_stock_log_product_time (product_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演示商品库存流水表';
```

每条流水**必须**写入 `stock_id`（来自 `demo_product_stock.stock_id`），与库存表一一对应；`product_id` 冗余便于按商品查流水。

#### 取消回滚查询（核心）

同一 `(order_id, product_id)` 或 `(order_id, stock_id)` 仅允许一条有效 RESERVE；RELEASE 时：

1. 查 `opt_type = 'RESERVE'` 且尚未存在对应 `RELEASE` 的记录
2. 取 `change_qty` 作为回滚数量
3. 执行 RELEASE 并写流水；重复 RELEASE 幂等（已释放则跳过或返回成功）

Repository 方法：

- `findPendingReserve(orderId, productId)` → `Optional<ProductStockLogDO>`
- `findPendingReserveByStockId(orderId, stockId)` → `Optional<ProductStockLogDO>`（等价查询，按库存 ID）

### 2.6 Seed 数据

脚本路径：`src/main/resources/db/product-module-schema.sql`（DDL + seed 合一或拆分 migration）。

| 商品 | product_id | stock_id | sell_price | actual_stock | stock | withhold | sell_stock | sort |
|------|-----------|----------|-----------|--------------|-------|----------|------------|------|
| 拿铁 | （seed 固定雪花） | （seed 固定雪花） | 18.00 | 100 | 100 | 0 | 128 | 30 |
| 生椰拿铁 | … | … | 20.00 | 80 | 80 | 0 | 86 | 20 |
| 芝士蛋糕 | … | … | 16.00 | 50 | 50 | 0 | 42 | 10 |

`product_id` / `stock_id` 在 seed 中使用固定雪花 ID（便于 curl 演示）。`cover_url` 可为空（前端 emoji fallback）。`detail_content` 填简短 Markdown/纯文本。

---

## 3. 架构

### 3.1 包结构

```
com.jason.demo.demo2.product
├── app
│   ├── controller / ProductController
│   ├── executor
│   │   ├── ProductListCmdExe
│   │   └── ProductGetCmdExe
│   ├── vo
│   │   ├── req / GetProductReqVO
│   │   └── res / ProductListItemResVO, ProductListResVO, ProductDetailResVO
│   └── convert / ProductVoConvert
└── service
    ├── common
    │   ├── ProductStatusEnum          # ON_SHELF, OFF_SHELF
    │   ├── ProductStockOptTypeEnum    # RESERVE, CONFIRM, RELEASE, ADJUST
    │   └── ProductErrorCodeEnum       # 4xxxx
    ├── core
    │   ├── domain / Product, ProductStock
    │   ├── ProductDomainService
    │   └── ProductStockDomainService  # reserve / confirm / release
    └── infrastructure
        ├── dao
        │   ├── entity / ProductDO, ProductStockDO, ProductStockLogDO
        │   │            （均 `@TableId(id, IdType.AUTO)`；业务键 product_id / stock_id / log_id 为普通字段）
        │   └── mapper / ProductMapper, ProductStockMapper, ProductStockLogMapper
        └── repository
            ├── ProductRepository
            ├── ProductStockRepository
            ├── ProductStockLogRepository
            └── convert / ProductDoConvert, ProductStockDoConvert
```

依赖方向：`app → service.core → service.infrastructure`（与 `order` 一致）。

### 3.2 领域职责

**ProductDomainService**

- `listOnShelf()`：上架商品，JOIN 库存，按 `sort DESC, sell_stock DESC, product_id ASC`
- `requireOnShelf(productId)`：存在且 `ON_SHELF`，否则抛错

**ProductStockDomainService**（本阶段实现，订单后续调用）

| 方法 | 说明 |
|------|------|
| `reserve(productId, orderId, qty)` | 条件更新库存 + 写 RESERVE 流水；库存不足抛 `40003` |
| `confirm(productId, orderId, qty)` | 查 RESERVE 校验 qty 一致（或按流水 qty）；更新 actual/withhold/sell + CONFIRM 流水 |
| `release(productId, orderId)` | 查 pending RESERVE 取 qty；更新 stock/withhold + RELEASE 流水；无记录抛 `40004` |
| `releaseByOrder(orderId)` | 按 orderId 批量 release（多明细订单预留，本阶段可只支持单商品） |

事务：`@Transactional`，库存更新 + 写流水同一事务。写流水时冗余 `stock_id`（从库存行读取），便于按库存维度对账。

### 3.3 并发安全

库存更新使用条件 SQL，失败抛业务异常：

```sql
-- RESERVE
UPDATE demo_product_stock
SET stock = stock - #{qty},
    withhold_stock = withhold_stock + #{qty},
    updated_at = NOW(3)
WHERE product_id = #{productId} AND stock >= #{qty}

-- CONFIRM
UPDATE demo_product_stock
SET actual_stock = actual_stock - #{qty},
    withhold_stock = withhold_stock - #{qty},
    sell_stock = sell_stock + #{qty},
    updated_at = NOW(3)
WHERE product_id = #{productId} AND withhold_stock >= #{qty}

-- RELEASE
UPDATE demo_product_stock
SET stock = stock + #{qty},
    withhold_stock = withhold_stock - #{qty},
    updated_at = NOW(3)
WHERE product_id = #{productId} AND withhold_stock >= #{qty}
```

`updatedRows == 0` → `ProductErrorCodeEnum.STOCK_CONFLICT (40005)` 或库存不足 `40003`。

---

## 4. HTTP 接口

全部 **POST**，`Content-Type: application/json`，**无 `@LoginRequired`**（浏览商品无需登录）。

| 路径 | CmdExe | ReqVO | ResVO |
|------|--------|-------|-------|
| `POST /demo/products/listProducts` | `ProductListCmdExe` | 空 body 或 `{}` | `ProductListResVO` |
| `POST /demo/products/getProduct` | `ProductGetCmdExe` | `GetProductReqVO` | `ProductDetailResVO` |

### 4.1 列表项字段 `ProductListItemResVO`

| 字段 | 类型 | 说明 |
|------|------|------|
| productId | long | 商品 ID |
| productName | string | 名称 |
| subtitle | string | 副标题 |
| coverUrl | string | 封面（可空） |
| sellPrice | decimal | 售价 |
| marketPrice | decimal | 划线价（可空） |
| availableStock | int | = `stock` |
| sellStock | int | 累计已售 |

### 4.2 详情字段 `ProductDetailResVO`

列表字段 + `detailContent`（string）。

### 4.3 报文示例

**listProducts**

```json
// Res.data
{
  "items": [
    {
      "productId": "2085550503315509001",
      "productName": "拿铁",
      "subtitle": "经典浓郁，口感顺滑",
      "coverUrl": null,
      "sellPrice": 18.00,
      "marketPrice": null,
      "availableStock": 100,
      "sellStock": 128
    }
  ]
}
```

**getProduct**

```json
// Req
{ "productId": "2085550503315509001" }
// Res.data
{
  "productId": "2085550503315509001",
  "productName": "拿铁",
  "subtitle": "经典浓郁，口感顺滑",
  "coverUrl": null,
  "sellPrice": 18.00,
  "marketPrice": null,
  "availableStock": 100,
  "sellStock": 128,
  "detailContent": "精选咖啡豆，经典拿铁……"
}
```

---

## 5. 错误码 `ProductErrorCodeEnum`

| 码 | 枚举 | 说明 |
|----|------|------|
| 40001 | PRODUCT_NOT_FOUND | 商品不存在 |
| 40002 | PRODUCT_OFF_SHELF | 商品已下架 |
| 40003 | STOCK_INSUFFICIENT | 可售库存不足 |
| 40004 | RESERVE_LOG_NOT_FOUND | 取消时无待释放预占流水 |
| 40005 | STOCK_CONFLICT | 库存并发更新冲突 |
| 40006 | PRODUCT_ID_REQUIRED | productId 不能为空 |

---

## 6. C 端前端（`member.js` / `member.css`）

### 6.1 首页

- 进入 / 切到 home Tab 时调用 `POST /demo/products/listProducts`
- 渲染商品卡片：图标（cover 空则用 emoji fallback：☕/🥥/🍰 或首字）、名称、副标题、价格、`已售 {sellStock}`（`sellStock > 0` 时显示）
- 点击卡片 → 进入详情视图

### 6.2 详情页

- 状态：`memberMobileView = 'home' | 'detail'`，`memberSelectedProductId`
- 调用 `getProduct`；展示封面、名称、价格、库存、已售、详情正文
- 顶部返回按钮回首页
- 「立即购买」按钮：**disabled** + title「订单模块后续接入」

### 6.3 非改动范围

- 右侧订单调试面板逻辑不变（仍手填 amount）
- Auth Sheet 逻辑不变

---

## 7. 与订单模块衔接（后续 spec，本文档仅约定接口）

| 订单事件 | 商品模块调用 |
|----------|-------------|
| orderPlace（待支付） | `ProductStockDomainService.reserve(productId, orderId, qty)` |
| pay | `confirm(productId, orderId, qty)` |
| cancel / 超时 cancel | `release(productId, orderId)` 或 `releaseByOrder(orderId)` |

订单明细表将保存 `product_id`、`qty`、`sell_price` 快照；取消时**回滚数量以 RESERVE 流水为准**，与明细 `qty` 交叉校验（不一致时以流水为准并打 warn 日志）。

---

## 8. 测试

| 层级 | 覆盖 |
|------|------|
| `ProductStockDomainServiceTest` | reserve 成功/库存不足；confirm 更新 sell_stock；release 从流水回滚；重复 release 幂等 |
| `ProductStockRepositoryTest` | 条件更新 rows |
| `ProductListCmdExeTest` / `ProductGetCmdExeTest` | 上架过滤、下架 40002、VO 字段 |
| `ProductStockLogRepositoryTest` | findPendingReserve 查询 |

---

## 9. 交付清单

- [x] `db/product-module-schema.sql`
- [x] `product` 模块 Java 代码（含 StockDomainService + 流水）
- [x] 单测
- [x] `member.js` / `member.css` C 端列表 + 详情
- [x] README curl 示例（见 `README.md` 快速开始 · 商品模块）

---

## 10. 参考

- `demo2/CLAUDE.md` — DDD 分包与错误码分段
- `com.jason.demo.demo2.order` — 样板模块
- `2026-08-25-unified-json-result-design.md` — JsonResult / BusinessException
- `2026-08-26-member-auth-sheet-design.md` — 会员 C 端 UI 约定
