# Product Module Implementation Plan

> **Status:** ✅ 已完成（2026-08-26）— Task 1–9 完成；单测通过；C 端列表/详情联调通过。归档见 [archive/2026-08-26-product-module.md](../archive/2026-08-26-product-module.md)。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 新建 demo2 `product` 模块（三表 + 库存领域服务 + C 端 list/get API），并将会员 Demo 首页改为真实商品数据与详情页。

**Architecture:** 严格 DDD 分包（参照 `order`）：`app` 仅 Controller/CmdExe/VO；`service.core` 放 `Product`/`ProductStock` 领域与 `ProductStockDomainService`（reserve/confirm/release + 流水）；`service.infrastructure` 放 DO/Mapper/Repository。表主键均为自增 `id`，业务键 `product_id`/`stock_id`/`log_id`。本阶段不改造订单 HTTP。

**Tech Stack:** Java 21, Spring Boot 4.1, MyBatis-Plus 3.5, MapStruct, JUnit 5, Mockito, vanilla JS (`member.js`).

## Global Constraints

- 包路径：`com.jason.demo.demo2.product`；依赖方向 `app → service.core → service.infrastructure`。
- HTTP：`POST /demo/products/{action}` + JSON Body；**无** `@LoginRequired`（list/get）。
- 返回 `JsonResult<T>`；业务失败 `throw new BusinessException(ProductErrorCodeEnum.xxx)`。
- 错误码段 `4xxxx`；枚举类名以 `Enum` 结尾。
- Controller **不得**注入 `*VoConvert`；`*VoConvert` 仅在 CmdExe 使用。
- DO 主键：`@TableId(value = "id", type = IdType.AUTO)`；业务 ID 为普通字段。
- 库存恒等式：`stock = actual_stock - withhold_stock`；每次更新后在领域层断言。
- 流水写入必须带 `stock_id`；取消回滚从 pending RESERVE 流水取 `change_qty`。
- Spec：`demo2/docs/superpowers/specs/2026-08-26-product-module-design.md`。

---

## File Structure

### Create

**SQL**

- `demo2/src/main/resources/db/product-module-schema.sql`

**service.common**

- `demo2/src/main/java/com/jason/demo/demo2/product/service/common/ProductStatusEnum.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/common/ProductStockOptTypeEnum.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/common/ProductErrorCodeEnum.java`

**service.infrastructure — entity / mapper**

- `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/dao/entity/ProductDO.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/dao/entity/ProductStockDO.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/dao/entity/ProductStockLogDO.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/dao/mapper/ProductMapper.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/dao/mapper/ProductStockMapper.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/dao/mapper/ProductStockLogMapper.java`

**service.infrastructure — repository / convert**

- `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/repository/ProductRepository.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/repository/ProductStockRepository.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/repository/ProductStockLogRepository.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/repository/convert/ProductDoConvert.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/infrastructure/repository/convert/ProductStockDoConvert.java`

**service.core**

- `demo2/src/main/java/com/jason/demo/demo2/product/service/core/domain/Product.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/core/domain/ProductStock.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/core/domain/ProductWithStock.java`（列表/详情 JOIN 视图）
- `demo2/src/main/java/com/jason/demo/demo2/product/service/core/ProductDomainService.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/service/core/ProductStockDomainService.java`

**app**

- `demo2/src/main/java/com/jason/demo/demo2/product/app/controller/ProductController.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/app/executor/ProductListCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/app/executor/ProductGetCmdExe.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/app/vo/req/GetProductReqVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/app/vo/res/ProductListItemResVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/app/vo/res/ProductListResVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/app/vo/res/ProductDetailResVO.java`
- `demo2/src/main/java/com/jason/demo/demo2/product/app/convert/ProductVoConvert.java`

**tests**

- `demo2/src/test/java/com/jason/demo/demo2/product/ProductStockDomainServiceTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/product/ProductStockLogRepositoryTest.java`
- `demo2/src/test/java/com/jason/demo/demo2/product/ProductCmdExeTest.java`

### Modify

- `demo2/src/main/resources/static/js/tabs/member.js`
- `demo2/src/main/resources/static/css/tabs/member.css`

---

## Seed IDs（固定雪花，便于 curl）

| 商品 | product_id | stock_id |
|------|-----------|----------|
| 拿铁 | `2085550503315509001` | `2085550503315509101` |
| 生椰拿铁 | `2085550503315509002` | `2085550503315509102` |
| 芝士蛋糕 | `2085550503315509003` | `2085550503315509103` |

---

## Interfaces Produced Across Tasks

```java
// ProductDomainService
List<ProductWithStock> listOnShelf();
ProductWithStock requireOnShelf(long productId);

// ProductStockDomainService
void reserve(long productId, long orderId, int qty);
void confirm(long productId, long orderId, int qty);
void release(long productId, long orderId);

// ProductStockRepository
Optional<ProductStock> findByProductId(long productId);
boolean reserve(long productId, int qty);
boolean confirm(long productId, int qty);
boolean release(long productId, int qty);

// ProductStockLogRepository
void insertLog(ProductStockLogDO log);
Optional<ProductStockLogDO> findPendingReserve(long orderId, long productId);
boolean existsRelease(long orderId, long productId);

// CmdExe
ProductListResVO execute();                                    // ProductListCmdExe
ProductDetailResVO execute(long productId);                    // ProductGetCmdExe
```

---

### Task 1: Database DDL + Seed

**Files:**

- Create: `demo2/src/main/resources/db/product-module-schema.sql`

**Interfaces:**

- Produces: 三张表 + 3 商品 + 3 库存行 seed

- [x] **Step 1: 编写 DDL（复制 spec §2.3–§2.5，含自增 id / stock_id / 流水 stock_id）**

```sql
-- demo2 商品模块
-- 库：spring_ai_agent2（与 delay-order-schema.sql 一致）
-- 新建环境执行本脚本；已有库仅执行 CREATE TABLE IF NOT EXISTS + INSERT 段

CREATE TABLE IF NOT EXISTS demo_product (
    id              BIGINT         NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键',
    product_id      BIGINT         NOT NULL COMMENT '商品ID（雪花）',
    product_name    VARCHAR(128)   NOT NULL COMMENT '商品名称',
    subtitle        VARCHAR(255)   NOT NULL DEFAULT '' COMMENT '列表副标题',
    cover_url       VARCHAR(512)   NULL COMMENT '封面图 URL',
    sell_price      DECIMAL(10,2)  NOT NULL COMMENT '售价',
    market_price    DECIMAL(10,2)  NULL COMMENT '划线价',
    detail_content  TEXT           NULL COMMENT '详情页图文',
    status          VARCHAR(32)    NOT NULL COMMENT 'ON_SHELF / OFF_SHELF',
    sort            INT            NOT NULL DEFAULT 0 COMMENT '排序',
    created_at      DATETIME(3)    NOT NULL COMMENT '创建时间',
    updated_at      DATETIME(3)    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_demo_product_product_id (product_id),
    INDEX idx_demo_product_status_sort (status, sort DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演示商品主表';

CREATE TABLE IF NOT EXISTS demo_product_stock (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键',
    stock_id        BIGINT       NOT NULL COMMENT '库存业务ID（雪花）',
    product_id      BIGINT       NOT NULL COMMENT '商品ID',
    actual_stock    INT          NOT NULL DEFAULT 0 COMMENT '现货库存',
    stock           INT          NOT NULL DEFAULT 0 COMMENT '可售库存',
    withhold_stock  INT          NOT NULL DEFAULT 0 COMMENT '预占库存',
    sell_stock      INT          NOT NULL DEFAULT 0 COMMENT '累计已售',
    updated_at      DATETIME(3)  NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_demo_product_stock_stock_id (stock_id),
    UNIQUE KEY uk_demo_product_stock_product_id (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演示商品库存表';

CREATE TABLE IF NOT EXISTS demo_product_stock_log (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键',
    log_id           BIGINT       NOT NULL COMMENT '流水业务ID（雪花）',
    stock_id         BIGINT       NOT NULL COMMENT '库存业务ID',
    product_id       BIGINT       NOT NULL COMMENT '商品ID',
    order_id         BIGINT       NULL COMMENT '关联订单ID',
    opt_type         VARCHAR(32)  NOT NULL COMMENT 'RESERVE/CONFIRM/RELEASE/ADJUST',
    change_qty       INT          NOT NULL COMMENT '变动数量',
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

- [x] **Step 2: 编写 seed INSERT（固定 product_id / stock_id）**

```sql
INSERT INTO demo_product (product_id, product_name, subtitle, cover_url, sell_price, market_price,
    detail_content, status, sort, created_at, updated_at) VALUES
(2085550503315509001, '拿铁', '经典浓郁，口感顺滑', NULL, 18.00, NULL,
 '精选咖啡豆，经典拿铁。', 'ON_SHELF', 30, NOW(3), NOW(3)),
(2085550503315509002, '生椰拿铁', '椰香清甜，清爽不腻', NULL, 20.00, NULL,
 '生椰搭配 espresso，清爽不腻。', 'ON_SHELF', 20, NOW(3), NOW(3)),
(2085550503315509003, '芝士蛋糕', '绵密芝士，下午茶推荐', NULL, 16.00, NULL,
 '绵密芝士，下午茶推荐。', 'ON_SHELF', 10, NOW(3), NOW(3));

INSERT INTO demo_product_stock (stock_id, product_id, actual_stock, stock, withhold_stock, sell_stock, updated_at) VALUES
(2085550503315509101, 2085550503315509001, 100, 100, 0, 128, NOW(3)),
(2085550503315509102, 2085550503315509002, 80, 80, 0, 86, NOW(3)),
(2085550503315509103, 2085550503315509003, 50, 50, 0, 42, NOW(3));
```

- [x] **Step 3: 本地执行脚本**

Run（MySQL 客户端，库名按 `application.yml` 调整）:

```bash
mysql -u root -p spring_ai_agent2 < demo2/src/main/resources/db/product-module-schema.sql
```

Expected: 3 tables created; `SELECT COUNT(*) FROM demo_product` → 3

---

### Task 2: Enums + Error Codes

**Files:**

- Create: `ProductStatusEnum.java`, `ProductStockOptTypeEnum.java`, `ProductErrorCodeEnum.java`

**Interfaces:**

- Produces: `ProductStatusEnum.ON_SHELF`, `ProductStockOptTypeEnum.RESERVE`, `ProductErrorCodeEnum.STOCK_INSUFFICIENT` 等

- [x] **Step 1: 创建三个枚举（参照 `OrderErrorCodeEnum`）**

```java
// ProductStatusEnum.java
public enum ProductStatusEnum {
    ON_SHELF, OFF_SHELF
}

// ProductStockOptTypeEnum.java
public enum ProductStockOptTypeEnum {
    RESERVE, CONFIRM, RELEASE, ADJUST
}

// ProductErrorCodeEnum.java — implements ErrorCode
PRODUCT_NOT_FOUND(40001, "商品不存在"),
PRODUCT_OFF_SHELF(40002, "商品已下架"),
STOCK_INSUFFICIENT(40003, "可售库存不足"),
RESERVE_LOG_NOT_FOUND(40004, "无待释放的预占流水"),
STOCK_CONFLICT(40005, "库存状态冲突"),
PRODUCT_ID_REQUIRED(40006, "productId 不能为空");
```

- [x] **Step 2: 编译验证**

Run: `mvn -f demo2/pom.xml compile -q`
Expected: BUILD SUCCESS

---

### Task 3: DO + Mapper

**Files:**

- Create: `ProductDO.java`, `ProductStockDO.java`, `ProductStockLogDO.java`
- Create: `ProductMapper.java`, `ProductStockMapper.java`, `ProductStockLogMapper.java`

**Interfaces:**

- Consumes: 表字段命名 snake_case → Java camelCase（MyBatis-Plus 默认）
- Produces: `BaseMapper<*DO>`；`ProductStockMapper` 含条件更新方法

- [x] **Step 1: 创建三个 DO（Lombok `@Data`，`ProductDO` 参照 `MemberDO`）**

```java
@Data
@TableName("demo_product")
public class ProductDO {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long productId;
    private String productName;
    private String subtitle;
    private String coverUrl;
    private BigDecimal sellPrice;
    private BigDecimal marketPrice;
    private String detailContent;
    private String status;
    private Integer sort;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

`ProductStockDO`：`id`, `stockId`, `productId`, `actualStock`, `stock`, `withholdStock`, `sellStock`, `updatedAt`

`ProductStockLogDO`：`id`, `logId`, `stockId`, `productId`, `orderId`, `optType`, `changeQty`, `beforeActual`, `afterActual`, `beforeStock`, `afterStock`, `beforeWithhold`, `afterWithhold`, `beforeSell`, `afterSell`, `remarks`, `createdAt`

- [x] **Step 2: 创建 Mapper 接口**

```java
@Mapper
public interface ProductMapper extends BaseMapper<ProductDO> {}

@Mapper
public interface ProductStockMapper extends BaseMapper<ProductStockDO> {
    @Update("""
        UPDATE demo_product_stock
        SET stock = stock - #{qty}, withhold_stock = withhold_stock + #{qty}, updated_at = NOW(3)
        WHERE product_id = #{productId} AND stock >= #{qty}
        """)
    int reserve(@Param("productId") long productId, @Param("qty") int qty);

    @Update("""
        UPDATE demo_product_stock
        SET actual_stock = actual_stock - #{qty}, withhold_stock = withhold_stock - #{qty},
            sell_stock = sell_stock + #{qty}, updated_at = NOW(3)
        WHERE product_id = #{productId} AND withhold_stock >= #{qty}
        """)
    int confirm(@Param("productId") long productId, @Param("qty") int qty);

    @Update("""
        UPDATE demo_product_stock
        SET stock = stock + #{qty}, withhold_stock = withhold_stock - #{qty}, updated_at = NOW(3)
        WHERE product_id = #{productId} AND withhold_stock >= #{qty}
        """)
    int release(@Param("productId") long productId, @Param("qty") int qty);
}

@Mapper
public interface ProductStockLogMapper extends BaseMapper<ProductStockLogDO> {}
```

- [x] **Step 3: 编译**

Run: `mvn -f demo2/pom.xml compile -q`

---

### Task 4: Repository Layer

**Files:**

- Create: `ProductDoConvert.java`, `ProductStockDoConvert.java`（MapStruct `componentModel = "spring"`）
- Create: `ProductRepository.java`, `ProductStockRepository.java`, `ProductStockLogRepository.java`

**Interfaces:**

- Produces:
  - `ProductRepository.listOnShelfWithStock()` → `List<ProductWithStock>`
  - `ProductStockRepository.reserve/confirm/release`
  - `ProductStockLogRepository.findPendingReserve`, `existsRelease`, `insertLog`

- [x] **Step 1: ProductRepository — JOIN 查询上架商品+库存**

```java
public List<ProductWithStock> listOnShelfWithStock() {
    // LambdaQueryWrapper ProductDO status=ON_SHELF
    // orderByDesc sort, 再按 sell_stock（需 join 或两次查询）
    // 简单实现：查 product 列表 + 批量查 stock Map by productId，内存组装 ProductWithStock
}
```

排序规则：`sort DESC` → `sellStock DESC` → `productId ASC`

- [x] **Step 2: ProductStockRepository**

```java
public Optional<ProductStock> findByProductId(long productId);
public boolean reserve(long productId, int qty) { return productStockMapper.reserve(productId, qty) > 0; }
public boolean confirm(long productId, int qty) { return productStockMapper.confirm(productId, qty) > 0; }
public boolean release(long productId, int qty) { return productStockMapper.release(productId, qty) > 0; }
public ProductStock requireByProductId(long productId); // 不存在抛 PRODUCT_NOT_FOUND 或专用码
```

- [x] **Step 3: ProductStockLogRepository**

```java
public void insertLog(ProductStockLogDO log) { productStockLogMapper.insert(log); }

public Optional<ProductStockLogDO> findPendingReserve(long orderId, long productId) {
    ProductStockLogDO reserve = productStockLogMapper.selectOne(
        new LambdaQueryWrapper<ProductStockLogDO>()
            .eq(ProductStockLogDO::getOrderId, orderId)
            .eq(ProductStockLogDO::getProductId, productId)
            .eq(ProductStockLogDO::getOptType, ProductStockOptTypeEnum.RESERVE.name())
            .orderByDesc(ProductStockLogDO::getCreatedAt)
            .last("LIMIT 1"));
    if (reserve == null) return Optional.empty();
    if (existsRelease(orderId, productId)) return Optional.empty();
    return Optional.of(reserve);
}

public boolean existsRelease(long orderId, long productId) {
    return productStockLogMapper.selectCount(
        new LambdaQueryWrapper<ProductStockLogDO>()
            .eq(ProductStockLogDO::getOrderId, orderId)
            .eq(ProductStockLogDO::getProductId, productId)
            .eq(ProductStockLogDO::getOptType, ProductStockOptTypeEnum.RELEASE.name())) > 0;
}
```

- [x] **Step 4: 编写 `ProductStockLogRepositoryTest`（Mockito mock Mapper 或 `@MybatisPlusTest` 若项目已有）**

优先 Mockito 单测 `findPendingReserve`：有 RESERVE 无 RELEASE 返回；有 RELEASE 返回 empty。

Run: `mvn -f demo2/pom.xml test -Dtest=ProductStockLogRepositoryTest -q`

---

### Task 5: Domain + ProductDomainService

**Files:**

- Create: `Product.java`, `ProductStock.java`, `ProductWithStock.java`, `ProductDomainService.java`

- [x] **Step 1: Domain 对象**

```java
public class Product extends ProductDO { /* static from(ProductDO) */ }

public class ProductStock extends ProductStockDO {
    public void assertBalance() {
        if (getStock() != getActualStock() - getWithholdStock()) {
            throw new IllegalStateException("stock balance violated");
        }
    }
}

public class ProductWithStock {
    private Product product;
    private ProductStock stock;
    // getters; availableStock() => stock.getStock(); sellStock() => stock.getSellStock();
}
```

- [x] **Step 2: ProductDomainService**

```java
@Service
public class ProductDomainService {
    public List<ProductWithStock> listOnShelf() {
        return productRepository.listOnShelfWithStock();
    }

    public ProductWithStock requireOnShelf(long productId) {
        ProductWithStock row = productRepository.findOnShelfWithStock(productId)
            .orElseThrow(() -> new BusinessException(ProductErrorCodeEnum.PRODUCT_NOT_FOUND));
        if (!ProductStatusEnum.ON_SHELF.name().equals(row.getProduct().getStatus())) {
            throw new BusinessException(ProductErrorCodeEnum.PRODUCT_OFF_SHELF);
        }
        return row;
    }
}
```

---

### Task 6: ProductStockDomainService + Tests

**Files:**

- Create: `ProductStockDomainService.java`
- Create: `ProductStockDomainServiceTest.java`

- [x] **Step 1: 编写失败测试 `reserve_insufficientStock`**

```java
@Test
void reserve_insufficientStock() {
    when(productStockRepository.findByProductId(9001L)).thenReturn(Optional.of(stock(100, 0)));
    when(productStockRepository.reserve(9001L, 5)).thenReturn(false);

    assertThrows(BusinessException.class,
        () -> service.reserve(9001L, 100L, 5));
}
```

- [x] **Step 2: 实现 `ProductStockDomainService`**

核心私有方法 `writeLog(...)`：从当前 `ProductStock` 快照 before/after，生成 `logId` via `SnowflakeIdGenerator`，写入 `stock_id`。

```java
@Transactional
public void reserve(long productId, long orderId, int qty) {
    if (qty <= 0) throw new BusinessException(CommonErrorCodeEnum.PARAM_INVALID, "qty must be positive");
    ProductStock before = productStockRepository.requireByProductId(productId);
    if (!productStockRepository.reserve(productId, qty)) {
        throw new BusinessException(ProductErrorCodeEnum.STOCK_INSUFFICIENT);
    }
    ProductStock after = productStockRepository.requireByProductId(productId);
    after.assertBalance();
    writeLog(before, after, ProductStockOptTypeEnum.RESERVE, orderId, qty, null);
}

@Transactional
public void confirm(long productId, long orderId, int qty) {
    ProductStockLogDO reserve = productStockLogRepository.findPendingReserve(orderId, productId)
        .orElseThrow(() -> new BusinessException(ProductErrorCodeEnum.RESERVE_LOG_NOT_FOUND));
    int effectiveQty = reserve.getChangeQty();
    ProductStock before = productStockRepository.requireByProductId(productId);
    if (!productStockRepository.confirm(productId, effectiveQty)) {
        throw new BusinessException(ProductErrorCodeEnum.STOCK_CONFLICT);
    }
    ProductStock after = productStockRepository.requireByProductId(productId);
    after.assertBalance();
    writeLog(before, after, ProductStockOptTypeEnum.CONFIRM, orderId, effectiveQty, null);
}

@Transactional
public void release(long productId, long orderId) {
    ProductStockLogDO reserve = productStockLogRepository.findPendingReserve(orderId, productId)
        .orElseThrow(() -> new BusinessException(ProductErrorCodeEnum.RESERVE_LOG_NOT_FOUND));
    int qty = reserve.getChangeQty();
    ProductStock before = productStockRepository.requireByProductId(productId);
    if (!productStockRepository.release(productId, qty)) {
        throw new BusinessException(ProductErrorCodeEnum.STOCK_CONFLICT);
    }
    ProductStock after = productStockRepository.requireByProductId(productId);
    after.assertBalance();
    writeLog(before, after, ProductStockOptTypeEnum.RELEASE, orderId, qty, "cancel rollback");
}
```

幂等 `release`：若 `findPendingReserve` 为空，可改为 silent return（spec 允许）；单测覆盖「重复 release 不抛错」。

- [x] **Step 3: 补充测试**

- `reserve_success_writesLog`
- `confirm_incrementsSellStock`
- `release_restoresStock_fromReserveQty`
- `release_idempotent_whenAlreadyReleased`

Run: `mvn -f demo2/pom.xml test -Dtest=ProductStockDomainServiceTest -q`

---

### Task 7: App Layer (VO / CmdExe / Controller)

**Files:**

- Create: VO、 `ProductVoConvert`、`ProductListCmdExe`、`ProductGetCmdExe`、`ProductController`
- Create: `ProductCmdExeTest.java`

- [x] **Step 1: VO 类**

```java
@Data
public class ProductListItemResVO {
    private Long productId;
    private String productName;
    private String subtitle;
    private String coverUrl;
    private BigDecimal sellPrice;
    private BigDecimal marketPrice;
    private Integer availableStock;
    private Integer sellStock;
}

@Data
public class ProductListResVO {
    private List<ProductListItemResVO> items;
}

@Data
public class ProductDetailResVO extends ProductListItemResVO {
    private String detailContent;
}

@Data
public class GetProductReqVO {
    private Long productId;
}
```

- [x] **Step 2: ProductVoConvert（MapStruct）**

```java
@Mapper(componentModel = "spring")
public interface ProductVoConvert {
    ProductListItemResVO toListItem(ProductWithStock row);
    ProductDetailResVO toDetail(ProductWithStock row);
}
```

MapStruct 映射：`availableStock` ← `stock.stock`，`sellStock` ← `stock.sellStock`，`detailContent` ← `product.detailContent`

- [x] **Step 3: CmdExe + Controller**

```java
@Service
public class ProductListCmdExe {
    public ProductListResVO execute() {
        List<ProductListItemResVO> items = productDomainService.listOnShelf().stream()
            .map(productVoConvert::toListItem).toList();
        ProductListResVO res = new ProductListResVO();
        res.setItems(items);
        return res;
    }
}

@Service
public class ProductGetCmdExe {
    public ProductDetailResVO execute(long productId) {
        return productVoConvert.toDetail(productDomainService.requireOnShelf(productId));
    }
}

@RestController
@RequestMapping("/demo/products")
public class ProductController {
    @PostMapping("/listProducts")
    public JsonResult<ProductListResVO> listProducts(@RequestBody(required = false) Object ignored) {
        return JsonResults.ok(productListCmdExe.execute());
    }

    @PostMapping("/getProduct")
    public JsonResult<ProductDetailResVO> getProduct(@RequestBody GetProductReqVO request) {
        if (request == null || request.getProductId() == null) {
            throw new BusinessException(ProductErrorCodeEnum.PRODUCT_ID_REQUIRED);
        }
        return JsonResults.ok(productGetCmdExe.execute(request.getProductId()));
    }
}
```

- [x] **Step 4: ProductCmdExeTest（Mock DomainService + VoConvert）**

- [x] **Step 5: 启动应用手动验证**

```bash
curl -s -X POST http://localhost:8080/demo/products/listProducts -H "Content-Type: application/json" -d "{}"
curl -s -X POST http://localhost:8080/demo/products/getProduct -H "Content-Type: application/json" \
  -d "{\"productId\":2085550503315509001}"
```

Expected: `code: 0`，拿铁数据与 seed 一致

Run: `mvn -f demo2/pom.xml test -Dtest=ProductCmdExeTest -q`

---

### Task 8: C 端前端（member.js / member.css）

**Files:**

- Modify: `demo2/src/main/resources/static/js/tabs/member.js`
- Modify: `demo2/src/main/resources/static/css/tabs/member.css`

- [x] **Step 1: 增加状态变量**

```javascript
let memberMobileView = 'home'; // 'home' | 'detail'
let memberSelectedProductId = '';
```

在 `memberSwitchMobileTab` 切回 home 时重置 `memberMobileView = 'home'`。

- [x] **Step 2: 改造 `memberRenderHome` — 异步拉列表**

```javascript
async function memberRenderHome() {
    const page = document.getElementById('memberPhonePage');
    page.innerHTML = '<h2>首页</h2><div class="member-home-banner">...</div><div class="member-products loading">加载中...</div>';
    try {
        const data = await memberRequest('/demo/products/listProducts', {}, { silent: true });
        page.innerHTML = '<h2>首页</h2>' + bannerHtml + '<div class="member-products">' +
            data.items.map(memberProductCardHtml).join('') + '</div>';
    } catch (e) {
        page.querySelector('.member-products').textContent = '商品加载失败';
    }
}

function memberProductIcon(name, coverUrl) {
    if (coverUrl) return '<img class="member-product-icon" src="' + memberEscapeHtml(coverUrl) + '" alt="">';
    const icons = { '拿铁': '☕', '生椰拿铁': '🥥', '芝士蛋糕': '🍰' };
    return '<span class="member-product-icon">' + (icons[name] || '🛍️') + '</span>';
}

function memberProductCardHtml(item) {
    const sold = item.sellStock > 0 ? '<span class="member-product-sold">已售 ' + item.sellStock + '</span>' : '';
    return '<div class="member-product-card" onclick="memberOpenProduct(' + item.productId + ')">' +
        memberProductIcon(item.productName, item.coverUrl) +
        '<div><strong>' + memberEscapeHtml(item.productName) + '</strong>' +
        '<p>' + memberEscapeHtml(item.subtitle) + '</p>' + sold + '</div>' +
        '<span class="member-product-price">¥' + item.sellPrice + '</span></div>';
}

function memberOpenProduct(productId) {
    memberSelectedProductId = String(productId);
    memberMobileView = 'detail';
    memberRender();
}
```

- [x] **Step 3: 新增 `memberRenderDetail`**

```javascript
async function memberRenderDetail() {
    const page = document.getElementById('memberPhonePage');
    page.innerHTML = '<button type="button" class="member-back-btn" onclick="memberBackHome()">← 返回</button><div class="member-detail loading">加载中...</div>';
    try {
        const item = await memberRequest('/demo/products/getProduct',
            { productId: Number(memberSelectedProductId) }, { silent: true });
        // 渲染封面、价格、availableStock、sellStock、detailContent
        // 底部：<button disabled title="订单模块后续接入">立即购买</button>
    } catch (e) { /* 错误态 */ }
}

function memberBackHome() {
    memberMobileView = 'home';
    memberRender();
}
```

- [x] **Step 4: 更新 `memberRender` 分支**

```javascript
if (memberMobileTab === 'home') {
    if (memberMobileView === 'detail') memberRenderDetail();
    else memberRenderHome();
}
```

- [x] **Step 5: CSS — 返回按钮、详情布局、已售标签、disabled 购买按钮**

```css
.member-back-btn { margin-bottom: 12px; background: none; border: none; color: #2563eb; }
.member-product-sold { font-size: 12px; color: #94a3b8; }
.member-detail-buy { margin-top: 16px; width: 100%; opacity: 0.5; }
.member-product-card { cursor: pointer; }
```

- [x] **Step 6: 浏览器验证**

打开 Demo 页 → 会员 Tab → 首页三件商品来自 API → 点击进详情 → 返回正常

---

### Task 9: 收尾

- [x] **Step 1: 全量测试**

Run: `mvn -f demo2/pom.xml test -q`
Expected: BUILD SUCCESS

- [x] **Step 2: 更新 spec 状态**

修改 `demo2/docs/superpowers/specs/2026-08-26-product-module-design.md` 头部 `状态: 待实现` → `状态: 已实现`（实现完成后再改）

- [x] **Step 3: 勾选 spec §9 交付清单**

---

## Spec Coverage Self-Review

| Spec 要求 | Task |
|-----------|------|
| 三表 DDL + seed | Task 1 |
| 自增 id + product_id/stock_id/log_id | Task 1, 3 |
| actual_stock / stock / withhold / sell_stock | Task 3, 6 |
| 流水 stock_id + 取消查 RESERVE | Task 4, 6 |
| listProducts / getProduct 无登录 | Task 7 |
| ProductStockDomainService reserve/confirm/release | Task 6 |
| 错误码 40001–40006 | Task 2 |
| C 端列表 + 详情 + 已售展示 | Task 8 |
| 单测覆盖 | Task 4, 6, 7 |
| 订单衔接（非本阶段 HTTP） | Task 6 领域方法就绪 |

无 TBD / 占位符。

---

## Execution Handoff

Plan complete and saved to `demo2/docs/superpowers/plans/2026-08-26-product-module.md`. Two execution options:

**1. Subagent-Driven (recommended)** — 每个 Task 派发独立 subagent，Task 间人工/Agent 复核

**2. Inline Execution** — 本会话按 Task 顺序直接实现，每 2–3 个 Task 设检查点

你想用哪种方式开始实现？
