-- spring-ai-session 0.8.0：AI_SESSION_EVENT 增加 seq 与 archived。
-- 0.2.0 建表没有这两列，查询会报 Unknown column 'e.archived'。只执行一次。
ALTER TABLE AI_SESSION_EVENT
    ADD COLUMN seq BIGINT NOT NULL AUTO_INCREMENT,
    ADD UNIQUE KEY uq_ai_session_event_seq (seq),
    ADD COLUMN archived TINYINT(1) NOT NULL DEFAULT 0,
    ADD INDEX idx_ai_session_event_session_seq (session_id, seq);
