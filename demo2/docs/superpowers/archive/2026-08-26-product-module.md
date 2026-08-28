# 商品模块 · 功能归档

**归档日期**: 2026-08-26  
**项目**: spring-ai-demo / demo2  
**状态**: 已实现  

**设计规范**: [2026-08-26-product-module-design.md](../specs/2026-08-26-product-module-design.md)  
**实施计划**: [2026-08-26-product-module.md](../plans/2026-08-26-product-module.md)  
**参考代码**: `com.jason.demo.demo2.product`

---

## 1. 做了什么

新建 demo2 **商品模块**（三表、无 SKU），支撑 C 端商品列表与详情，并为后续订单库存联动预留领域能力：

- **三表**：`demo_product` / `demo_product_stock` / `demo_product_stock_log`
- **主键约定**：自增 `id` 作 DB PK；业务键 `product_id` / `stock_id` / `log_id`（雪花）
- **库存字段**：`actual_stock`、`stock`、`withhold_stock`、`sell_stock`；恒等式 `stock = actual_stock - withhold_stock`
- **库存策略 A**：RESERVE → CONFIRM → RELEASE；取消回滚从 RESERVE 流水取 `change_qty`
- **HTTP 读接口**（无登录）：`listProducts` / `getProduct`
- **`ProductStockDomainService`**：`reserve` / `confirm` / `release`（写流水，供订单模块内部调用，本阶段不暴露 HTTP）
- **C 端**：`member.js` 首页接真实商品列表，点击进入详情页；雪花 ID 全程字符串，避免 JS 精度丢失
- **错误码**：`ProductErrorCodeEnum`（40001–40007）

**本阶段未做**：订单主表 + 明细表改造；下单/支付/取消与库存联动；C 端「立即购买」仍为 disabled。

---

## 2. 数据模型

```text
demo_product (1) ── (1) demo_product_stock
       │
       └── (N) demo_product_stock_log
```

| 表 | 说明 |
|----|------|
| `demo_product` | 商品主表（名称、价格、封面、详情正文、上下架） |
| `demo_product_stock` | 一商品一行库存 |
| `demo_product_stock_log` | 库存变动流水（含 `stock_id`、`order_id`、`opt_type`） |

DDL + seed：`src/main/resources/db/product-module-schema.sql`（3 件演示商品）。

---

## 3. HTTP 接口

全部 **POST**，`Content-Type: application/json`，**无 `@LoginRequired`**。

| 路径 | CmdExe | 说明 |
|------|--------|------|
| `POST /demo/products/listProducts` | `ProductListCmdExe` | 上架商品 + 库存 JOIN 列表 |
| `POST /demo/products/getProduct` | `ProductGetCmdExe` | 详情（含 `detailContent`） |

响应统一 `JsonResult<T>`；`productId` 等 Long 字段经 `JacksonJsonCustomizer` 序列化为字符串。

---

## 4. 错误码 `ProductErrorCodeEnum`

| 码 | 枚举 | 说明 |
|----|------|------|
| 40001 | PRODUCT_NOT_FOUND | 商品不存在 |
| 40002 | PRODUCT_OFF_SHELF | 商品已下架 |
| 40003 | STOCK_INSUFFICIENT | 可售库存不足 |
| 40004 | RESERVE_LOG_NOT_FOUND | 无待释放预占流水 |
| 40005 | STOCK_CONFLICT | 库存并发更新冲突 |
| 40006 | PRODUCT_ID_REQUIRED | productId 不能为空 |
| 40007 | STOCK_NOT_FOUND | 库存记录不存在 |

---

## 5. 包结构

```
com.jason.demo.demo2.product
├── app
│   ├── controller/ProductController
│   ├── executor/ProductListCmdExe, ProductGetCmdExe
│   ├── vo/req, vo/res
│   └── convert/ProductVoConvert
└── service
    ├── common/（ProductStatusEnum, ProductStockOptTypeEnum, ProductErrorCodeEnum）
    ├── core/
    │   ├── domain/Product, ProductStock, ProductWithStock
    │   ├── ProductDomainService
    │   └── ProductStockDomainService
    └── infrastructure/（DO, Mapper, Repository, *DoConvert）
```

依赖方向：`app → service.core → service.infrastructure`（参照 `order` 样板）。

---

## 6. 测试

| 类 | 覆盖 |
|----|------|
| `ProductStockDomainServiceTest` | reserve / confirm / release、库存不足、幂等 |
| `ProductStockLogRepositoryTest` | findPendingReserve |
| `ProductCmdExeTest` | list/get、下架过滤、VO 字段 |

---

## 7. C 端前端要点

- `member.js`：`memberSnowflakeId()`、卡片 `data-product-id` 不用 `Number()`
- `memberHomeRenderSeq` / `memberDetailRenderSeq` 防异步覆盖
- 详情封面 `.member-detail-cover` 全宽（`member.css`）
- 「立即购买」disabled，待订单模块接入

---

## 8. 与订单模块衔接（后续 spec）

| 订单事件 | 商品模块调用 |
|----------|-------------|
| orderPlace（待支付） | 热路径 `ProductStockHotService.reserve`（开关关闭则 DomainService） |
| pay | `ProductStockHotService.confirm` |
| cancel / 超时 cancel | `ProductStockHotService.release` |

取消回滚数量以 RESERVE 流水为准。热闸门与 MQ 投影见 [2026-08-27-redis-stock-consistency.md](./2026-08-27-redis-stock-consistency.md)。订单 HTTP 接入热路径仍待后续 spec。

---

## 9. 变更记录

| 日期 | 说明 |
|------|------|
| 2026-08-26 | 初版归档：三表 + 读 API + 库存领域服务 + C 端列表/详情 |
| 2026-08-28 | 衔接热库存：订单应调 `ProductStockHotService`（见 Redis 热库存归档） |
