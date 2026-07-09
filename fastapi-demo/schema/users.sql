-- users 表结构（对应 mysql_users.py 中的 User 模型）
CREATE TABLE IF NOT EXISTS users (
    id INT AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID',
    username VARCHAR(255) NOT NULL COMMENT '用户名',
    email VARCHAR(255) NOT NULL COMMENT '邮箱',
    UNIQUE KEY uk_users_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 初始化示例数据
INSERT INTO users (username, email) VALUES
    ('admin', 'admin@example.com'),
    ('jason', 'jason@example.com'),
    ('demo', 'demo@example.com');
