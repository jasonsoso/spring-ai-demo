-- demo2 延时任务 + 订单超时取消
-- 库：spring_ai_agent2
-- 新建环境可直接执行本脚本；已有表请再执行下方「同步注释」段，或整段重跑（IF NOT EXISTS 不会改已有表结构）

CREATE TABLE IF NOT EXISTS demo_order (
    order_id    BIGINT        NOT NULL COMMENT '订单ID（雪花）',
    status      VARCHAR(32)   NOT NULL COMMENT '订单状态：PENDING_PAY/PAID/CANCELLED',
    amount      DECIMAL(12,2) NOT NULL COMMENT '订单金额',
    created_at  DATETIME(3)   NOT NULL COMMENT '创建时间',
    updated_at  DATETIME(3)   NOT NULL COMMENT '更新时间',
    PRIMARY KEY (order_id),
    INDEX idx_demo_order_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演示订单表（超时未支付自动取消）';

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
