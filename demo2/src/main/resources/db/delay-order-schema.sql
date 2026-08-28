-- demo2 延时任务 + 订单超时取消
-- 库：spring_ai_agent2
-- 新建环境可直接执行本脚本；已有 demo_order 表的环境请手动执行 member-module-migration.sql

CREATE TABLE IF NOT EXISTS demo_order (
    order_id     BIGINT        NOT NULL COMMENT '订单ID（雪花）',
    member_id    BIGINT        NOT NULL COMMENT '下单会员ID',
    order_status VARCHAR(32)   NOT NULL COMMENT 'SUBMIT/COMPLETED/CANCEL',
    pay_status   VARCHAR(32)   NOT NULL COMMENT 'WAIT_PAY/PAY_SUCCESS/CLOSE',
    amount       DECIMAL(12,2) NOT NULL COMMENT '应付金额 = sum(sell_price * qty)',
    pay_time     DATETIME(3)   NULL COMMENT '支付完成时间',
    cancel_time  DATETIME(3)   NULL COMMENT '取消/超时时间',
    created_at   DATETIME(3)   NOT NULL,
    updated_at   DATETIME(3)   NOT NULL,
    PRIMARY KEY (order_id),
    INDEX idx_demo_order_member_status_time (member_id, order_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演示订单主表';

CREATE TABLE IF NOT EXISTS delay_task (
    task_id      BIGINT       NOT NULL COMMENT '任务ID（雪花）',
    task_type    VARCHAR(64)  NOT NULL COMMENT '任务类型，如 ORDER_CANCEL',
    biz_key      VARCHAR(128) NOT NULL COMMENT '业务键，订单场景为 orderId 字符串',
    payload      TEXT         NULL COMMENT '可选扩展载荷（JSON）',
    execute_at   DATETIME(3)  NOT NULL COMMENT '计划到期执行时间',
    status       VARCHAR(32)  NOT NULL COMMENT '任务状态：PENDING/RUNNING/SUCCESS/FAILED/CANCELLED',
    retry_count  INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
    max_retry    INT          NOT NULL DEFAULT 3 COMMENT '最大重试次数',
    backend      VARCHAR(32)  NOT NULL COMMENT '注册时主投递后端：redisson/rocketmq',
    created_at   DATETIME(3)  NOT NULL COMMENT '创建时间',
    updated_at   DATETIME(3)  NOT NULL COMMENT '更新时间',
    PRIMARY KEY (task_id),
    INDEX idx_delay_task_due (status, execute_at),
    INDEX idx_delay_task_biz (task_type, biz_key, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='延时任务台账（调度事实，扫描兜底）';

CREATE TABLE IF NOT EXISTS demo_member (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键',
    member_id      BIGINT       NOT NULL COMMENT '会员业务ID（雪花）',
    phone          VARCHAR(32)  NOT NULL COMMENT '手机号',
    password_hash  VARCHAR(255) NOT NULL COMMENT '密码哈希',
    avatar_url     VARCHAR(512) NULL COMMENT '头像URL',
    status         VARCHAR(32)  NOT NULL COMMENT '会员状态：NORMAL/DISABLED',
    created_at     DATETIME(3)  NOT NULL COMMENT '创建时间',
    updated_at     DATETIME(3)  NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_demo_member_member_id (member_id),
    UNIQUE KEY uk_demo_member_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演示会员表';
