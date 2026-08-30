-- demo2 订单分库分表绿场脚本（2 schema × 32 主表 × 32 明细 = 128 张表）
-- 用 root 执行一次。不 DROP、不迁 spring_ai_agent2.demo_order / demo_order_item。
-- PowerShell 管道会坏 DELIMITER，请用：cmd /c "mysql -uroot -p123456 < order-shard-schema.sql"
-- 存储过程必须先 USE 某个库（下面用 order_ds_0）才能 CREATE PROCEDURE。

CREATE DATABASE IF NOT EXISTS order_ds_0 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS order_ds_1 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE order_ds_0;
DROP PROCEDURE IF EXISTS demo2_create_order_shards;
DELIMITER $$
CREATE PROCEDURE demo2_create_order_shards()
BEGIN
  DECLARE i INT DEFAULT 0;
  DECLARE d INT DEFAULT 0;
  DECLARE dbn VARCHAR(32);
  DECLARE ddl TEXT;
  WHILE d < 2 DO
    SET dbn = CONCAT('order_ds_', d);
    SET i = 0;
    WHILE i < 32 DO
      SET ddl = CONCAT(
        'CREATE TABLE IF NOT EXISTS `', dbn, '`.`demo_order_', i, '` (',
        'order_id BIGINT NOT NULL COMMENT ''订单ID（雪花+9bit基因）'',',
        'member_id BIGINT NOT NULL COMMENT ''下单会员ID（分片键）'',',
        'order_status VARCHAR(32) NOT NULL COMMENT ''SUBMIT/COMPLETED/CANCEL'',',
        'pay_status VARCHAR(32) NOT NULL COMMENT ''WAIT_PAY/PAY_SUCCESS/CLOSE'',',
        'amount DECIMAL(12,2) NOT NULL,',
        'pay_time DATETIME(3) NULL,',
        'cancel_time DATETIME(3) NULL,',
        'created_at DATETIME(3) NOT NULL,',
        'updated_at DATETIME(3) NOT NULL,',
        'PRIMARY KEY (order_id),',
        'INDEX idx_demo_order_member_status_time (member_id, order_status, created_at)',
        ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=''演示订单主表分片'''
      );
      SET @ddl = ddl;
      PREPARE stmt FROM @ddl;
      EXECUTE stmt;
      DEALLOCATE PREPARE stmt;

      SET ddl = CONCAT(
        'CREATE TABLE IF NOT EXISTS `', dbn, '`.`demo_order_item_', i, '` (',
        'id BIGINT NOT NULL AUTO_INCREMENT,',
        'item_id BIGINT NOT NULL COMMENT ''明细业务ID（普通雪花）'',',
        'order_id BIGINT NOT NULL,',
        'member_id BIGINT NOT NULL COMMENT ''会员ID（与主表同分片）'',',
        'product_id BIGINT NOT NULL,',
        'product_name VARCHAR(128) NOT NULL,',
        'subtitle VARCHAR(255) NOT NULL DEFAULT '''',',
        'cover_url VARCHAR(512) NULL,',
        'sell_price DECIMAL(10,2) NOT NULL,',
        'market_price DECIMAL(10,2) NULL,',
        'qty INT UNSIGNED NOT NULL DEFAULT 1,',
        'created_at DATETIME(3) NOT NULL,',
        'PRIMARY KEY (id),',
        'UNIQUE KEY uk_demo_order_item_item_id (item_id),',
        'INDEX idx_demo_order_item_order (order_id),',
        'UNIQUE KEY uk_demo_order_item_order_product (order_id, product_id),',
        'INDEX idx_demo_order_item_member_order (member_id, order_id)',
        ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=''演示订单明细分片'''
      );
      SET @ddl = ddl;
      PREPARE stmt FROM @ddl;
      EXECUTE stmt;
      DEALLOCATE PREPARE stmt;
      SET i = i + 1;
    END WHILE;
    SET d = d + 1;
  END WHILE;
END$$
DELIMITER ;

CALL demo2_create_order_shards();
DROP PROCEDURE IF EXISTS demo2_create_order_shards;
