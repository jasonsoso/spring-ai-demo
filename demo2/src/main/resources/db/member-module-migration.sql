-- 会员模块已有环境迁移脚本
-- 仅用于 demo_order 表已经存在的环境，由运维或开发人员手动执行。
-- 新建环境请直接执行 delay-order-schema.sql，不需要执行本脚本。
-- 本脚本通过 INFORMATION_SCHEMA 判断字段和索引是否存在，可安全重复执行。

SET @demo_order_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'demo_order'
);

SET @member_id_column_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'demo_order'
      AND COLUMN_NAME = 'member_id'
);

SET @add_member_id_sql := IF(
    @demo_order_exists = 1 AND @member_id_column_exists = 0,
    'ALTER TABLE demo_order ADD COLUMN member_id BIGINT NULL COMMENT ''下单会员ID（雪花）'' AFTER order_id',
    'SELECT ''demo_order.member_id already exists or demo_order is absent; skipped'' AS migration_message'
);
PREPARE add_member_id_stmt FROM @add_member_id_sql;
EXECUTE add_member_id_stmt;
DEALLOCATE PREPARE add_member_id_stmt;

-- 新增列后重新读取元数据，确保首次执行也会完成回填和非空约束收紧。
SET @member_id_column_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'demo_order'
      AND COLUMN_NAME = 'member_id'
);

SET @backfill_member_id_sql := IF(
    @demo_order_exists = 1 AND @member_id_column_exists = 1,
    'UPDATE demo_order SET member_id = 0 WHERE member_id IS NULL',
    'SELECT ''demo_order.member_id is absent; backfill skipped'' AS migration_message'
);
PREPARE backfill_member_id_stmt FROM @backfill_member_id_sql;
EXECUTE backfill_member_id_stmt;
DEALLOCATE PREPARE backfill_member_id_stmt;

SET @member_id_is_nullable := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'demo_order'
      AND COLUMN_NAME = 'member_id'
      AND IS_NULLABLE = 'YES'
);

SET @make_member_id_not_null_sql := IF(
    @demo_order_exists = 1
        AND @member_id_column_exists = 1
        AND @member_id_is_nullable = 1,
    'ALTER TABLE demo_order MODIFY COLUMN member_id BIGINT NOT NULL COMMENT ''下单会员ID（雪花）''',
    'SELECT ''demo_order.member_id is already NOT NULL or absent; skipped'' AS migration_message'
);
PREPARE make_member_id_not_null_stmt FROM @make_member_id_not_null_sql;
EXECUTE make_member_id_not_null_stmt;
DEALLOCATE PREPARE make_member_id_not_null_stmt;

SET @member_index_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'demo_order'
      AND INDEX_NAME = 'idx_demo_order_member'
);

SET @add_member_index_sql := IF(
    @demo_order_exists = 1 AND @member_index_exists = 0,
    'CREATE INDEX idx_demo_order_member ON demo_order (member_id)',
    'SELECT ''idx_demo_order_member already exists or demo_order is absent; skipped'' AS migration_message'
);
PREPARE add_member_index_stmt FROM @add_member_index_sql;
EXECUTE add_member_index_stmt;
DEALLOCATE PREPARE add_member_index_stmt;
