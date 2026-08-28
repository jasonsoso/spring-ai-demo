ALTER TABLE demo_product_stock
    ADD COLUMN stock_seq BIGINT NOT NULL DEFAULT 0 COMMENT '已投影的 Redis seq' AFTER sell_stock;

ALTER TABLE demo_product_stock_log
    ADD COLUMN idempotent_key VARCHAR(64) NULL COMMENT '幂等键' AFTER opt_type;

UPDATE demo_product_stock_log
SET idempotent_key = CONCAT(IFNULL(order_id, '0'), ':', product_id, ':', opt_type)
WHERE idempotent_key IS NULL;

ALTER TABLE demo_product_stock_log
    MODIFY idempotent_key VARCHAR(64) NOT NULL,
    ADD UNIQUE KEY uk_stock_log_idempotent (idempotent_key);
