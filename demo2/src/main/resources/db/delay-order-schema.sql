-- demo2 延时任务 + 订单超时取消
-- 在 spring_ai_agent2 执行一次

CREATE TABLE IF NOT EXISTS demo_order (
    order_id    BIGINT       NOT NULL PRIMARY KEY,
    status      VARCHAR(32)  NOT NULL,
    amount      DECIMAL(12,2) NOT NULL,
    created_at  DATETIME(3)  NOT NULL,
    updated_at  DATETIME(3)  NOT NULL,
    INDEX idx_demo_order_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS delay_task (
    task_id      BIGINT       NOT NULL PRIMARY KEY,
    task_type    VARCHAR(64)  NOT NULL,
    biz_key      VARCHAR(128) NOT NULL,
    payload      TEXT         NULL,
    execute_at   DATETIME(3)  NOT NULL,
    status       VARCHAR(32)  NOT NULL,
    retry_count  INT          NOT NULL DEFAULT 0,
    max_retry    INT          NOT NULL DEFAULT 3,
    backend      VARCHAR(32)  NOT NULL,
    created_at   DATETIME(3)  NOT NULL,
    updated_at   DATETIME(3)  NOT NULL,
    INDEX idx_delay_task_due (status, execute_at),
    INDEX idx_delay_task_biz (task_type, biz_key, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
