-- demo2 订单模块演进
-- 已有库执行本脚本 ALTER / 历史映射；新库以 delay-order-schema.sql 建表为准。
-- MySQL 8.0 可重复执行：列/索引用 information_schema 判断（官方 8.0 无 ADD COLUMN IF NOT EXISTS）。
-- 绿场建表不要改 delay-order-schema.sql，本脚本不 DROP/重建 demo_order。

-- 仅当遗留列仍叫 status 时才 CHANGE 为 order_status（已演进库跳过）。
SET @demo_order_need_rename := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'demo_order'
      AND COLUMN_NAME = 'status'
);
SET @demo_order_rename_sql := IF(
    @demo_order_need_rename > 0,
    'ALTER TABLE demo_order CHANGE COLUMN status order_status VARCHAR(32) NOT NULL COMMENT ''订单状态: SUBMIT=已提交, COMPLETED=已完成, CANCEL=已取消''',
    'SELECT 1'
);
PREPARE demo_order_rename_stmt FROM @demo_order_rename_sql;
EXECUTE demo_order_rename_stmt;
DEALLOCATE PREPARE demo_order_rename_stmt;

SET @demo_order_need_pay_status := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'demo_order'
      AND COLUMN_NAME = 'pay_status'
);
SET @demo_order_pay_status_sql := IF(
    @demo_order_need_pay_status = 0,
    'ALTER TABLE demo_order ADD COLUMN pay_status VARCHAR(32) NOT NULL DEFAULT ''WAIT_PAY'' COMMENT ''支付状态(伴随): WAIT_PAY/PAY_SUCCESS/CLOSE'' AFTER order_status',
    'SELECT 1'
);
PREPARE demo_order_pay_status_stmt FROM @demo_order_pay_status_sql;
EXECUTE demo_order_pay_status_stmt;
DEALLOCATE PREPARE demo_order_pay_status_stmt;

SET @demo_order_need_pay_time := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'demo_order'
      AND COLUMN_NAME = 'pay_time'
);
SET @demo_order_pay_time_sql := IF(
    @demo_order_need_pay_time = 0,
    'ALTER TABLE demo_order ADD COLUMN pay_time DATETIME(3) NULL COMMENT ''支付完成时间'' AFTER pay_status',
    'SELECT 1'
);
PREPARE demo_order_pay_time_stmt FROM @demo_order_pay_time_sql;
EXECUTE demo_order_pay_time_stmt;
DEALLOCATE PREPARE demo_order_pay_time_stmt;

SET @demo_order_need_cancel_time := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'demo_order'
      AND COLUMN_NAME = 'cancel_time'
);
SET @demo_order_cancel_time_sql := IF(
    @demo_order_need_cancel_time = 0,
    'ALTER TABLE demo_order ADD COLUMN cancel_time DATETIME(3) NULL COMMENT ''取消/超时时间'' AFTER pay_time',
    'SELECT 1'
);
PREPARE demo_order_cancel_time_stmt FROM @demo_order_cancel_time_sql;
EXECUTE demo_order_cancel_time_stmt;
DEALLOCATE PREPARE demo_order_cancel_time_stmt;

SET @demo_order_need_drop_old_idx := (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'demo_order'
      AND INDEX_NAME = 'idx_demo_order_status'
);
SET @demo_order_drop_old_idx_sql := IF(
    @demo_order_need_drop_old_idx > 0,
    'ALTER TABLE demo_order DROP INDEX idx_demo_order_status',
    'SELECT 1'
);
PREPARE demo_order_drop_old_idx_stmt FROM @demo_order_drop_old_idx_sql;
EXECUTE demo_order_drop_old_idx_stmt;
DEALLOCATE PREPARE demo_order_drop_old_idx_stmt;
SET @demo_order_need_idx := (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'demo_order'
      AND INDEX_NAME = 'idx_demo_order_member_status_time'
);
SET @demo_order_add_idx_sql := IF(
    @demo_order_need_idx = 0,
    'ALTER TABLE demo_order ADD INDEX idx_demo_order_member_status_time (member_id, order_status, created_at)',
    'SELECT 1'
);
PREPARE demo_order_add_idx_stmt FROM @demo_order_add_idx_sql;
EXECUTE demo_order_add_idx_stmt;
DEALLOCATE PREPARE demo_order_add_idx_stmt;

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
    UNIQUE KEY uk_demo_order_item_order_product (order_id, product_id),
    INDEX idx_demo_order_item_member_order (member_id, order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演示订单明细（商品快照）';

SET @demo_order_item_need_subtitle := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'demo_order_item'
      AND COLUMN_NAME = 'subtitle'
);
SET @demo_order_item_add_subtitle_sql := IF(
    @demo_order_item_need_subtitle = 0,
    'ALTER TABLE demo_order_item ADD COLUMN subtitle VARCHAR(255) NOT NULL DEFAULT '''' COMMENT ''副标题快照'' AFTER product_name',
    'SELECT 1'
);
PREPARE demo_order_item_add_subtitle_stmt FROM @demo_order_item_add_subtitle_sql;
EXECUTE demo_order_item_add_subtitle_stmt;
DEALLOCATE PREPARE demo_order_item_add_subtitle_stmt;

SET @demo_order_item_need_market_price := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'demo_order_item'
      AND COLUMN_NAME = 'market_price'
);
SET @demo_order_item_add_market_price_sql := IF(
    @demo_order_item_need_market_price = 0,
    'ALTER TABLE demo_order_item ADD COLUMN market_price DECIMAL(10,2) NULL COMMENT ''划线价快照'' AFTER sell_price',
    'SELECT 1'
);
PREPARE demo_order_item_add_market_price_stmt FROM @demo_order_item_add_market_price_sql;
EXECUTE demo_order_item_add_market_price_stmt;
DEALLOCATE PREPARE demo_order_item_add_market_price_stmt;

UPDATE demo_order SET order_status = 'SUBMIT', pay_status = 'WAIT_PAY' WHERE order_status = 'PENDING_PAY';
UPDATE demo_order SET order_status = 'COMPLETED', pay_status = 'PAY_SUCCESS', pay_time = updated_at WHERE order_status = 'PAID';
UPDATE demo_order SET order_status = 'CANCEL', pay_status = 'CLOSE', cancel_time = updated_at WHERE order_status = 'CANCELLED';
