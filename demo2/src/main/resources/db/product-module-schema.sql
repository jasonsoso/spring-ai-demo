-- demo2 商品模块
-- 库：spring_ai_agent2（与 delay-order-schema.sql 一致）

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
    stock_seq       BIGINT       NOT NULL DEFAULT 0 COMMENT '已投影的 Redis seq',
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
    idempotent_key   VARCHAR(64)  NOT NULL COMMENT '幂等键',
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
    UNIQUE KEY uk_stock_log_idempotent (idempotent_key),
    INDEX idx_stock_log_stock_id (stock_id),
    INDEX idx_stock_log_order_product (order_id, product_id, opt_type),
    INDEX idx_stock_log_product_time (product_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演示商品库存流水表';

INSERT INTO demo_product (product_id, product_name, subtitle, cover_url, sell_price, market_price,
    detail_content, status, sort, created_at, updated_at)
SELECT * FROM (
    SELECT 2085550503315509001 AS product_id, '拿铁' AS product_name, '经典浓郁，口感顺滑' AS subtitle,
        NULL AS cover_url, 18.00 AS sell_price, NULL AS market_price,
        '精选咖啡豆，经典拿铁。' AS detail_content, 'ON_SHELF' AS status, 30 AS sort, NOW(3) AS created_at, NOW(3) AS updated_at
    UNION ALL
    SELECT 2085550503315509002, '生椰拿铁', '椰香清甜，清爽不腻', NULL, 20.00, NULL,
        '生椰搭配 espresso，清爽不腻。', 'ON_SHELF', 20, NOW(3), NOW(3)
    UNION ALL
    SELECT 2085550503315509003, '芝士蛋糕', '绵密芝士，下午茶推荐', NULL, 16.00, NULL,
        '绵密芝士，下午茶推荐。', 'ON_SHELF', 10, NOW(3), NOW(3)
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM demo_product LIMIT 1);

INSERT INTO demo_product_stock (stock_id, product_id, actual_stock, stock, withhold_stock, sell_stock, stock_seq, updated_at)
SELECT * FROM (
    SELECT 2085550503315509101 AS stock_id, 2085550503315509001 AS product_id, 100 AS actual_stock, 100 AS stock, 0 AS withhold_stock, 128 AS sell_stock, 0 AS stock_seq, NOW(3) AS updated_at
    UNION ALL
    SELECT 2085550503315509102, 2085550503315509002, 80, 80, 0, 86, 0, NOW(3)
    UNION ALL
    SELECT 2085550503315509103, 2085550503315509003, 50, 50, 0, 42, 0, NOW(3)
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM demo_product_stock LIMIT 1);
