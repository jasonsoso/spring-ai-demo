-- demo2 订单模块演进
-- 已有库执行本脚本 ALTER / 历史映射；新库以 delay-order-schema.sql 建表为准。
-- 若现表列仍叫 status，先 CHANGE 为 order_status，再执行下方 UPDATE（已 CHANGE 的环境直接用 order_status）。

ALTER TABLE demo_order
    CHANGE COLUMN status order_status VARCHAR(32) NOT NULL
        COMMENT '订单状态: SUBMIT=已提交, COMPLETED=已完成, CANCEL=已取消',
    ADD COLUMN pay_status VARCHAR(32) NOT NULL DEFAULT 'WAIT_PAY'
        COMMENT '支付状态(伴随): WAIT_PAY/PAY_SUCCESS/CLOSE' AFTER order_status,
    ADD COLUMN pay_time DATETIME(3) NULL COMMENT '支付完成时间' AFTER pay_status,
    ADD COLUMN cancel_time DATETIME(3) NULL COMMENT '取消/超时时间' AFTER pay_time;

ALTER TABLE demo_order
    DROP INDEX idx_demo_order_status,
    ADD INDEX idx_demo_order_member_status_time (member_id, order_status, created_at);

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

UPDATE demo_order SET order_status = 'SUBMIT', pay_status = 'WAIT_PAY' WHERE order_status = 'PENDING_PAY';
UPDATE demo_order SET order_status = 'COMPLETED', pay_status = 'PAY_SUCCESS', pay_time = updated_at WHERE order_status = 'PAID';
UPDATE demo_order SET order_status = 'CANCEL', pay_status = 'CLOSE', cancel_time = updated_at WHERE order_status = 'CANCELLED';
