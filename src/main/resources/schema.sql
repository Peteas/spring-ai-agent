-- 用户表
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    email VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 用户会话关联表
CREATE TABLE IF NOT EXISTS user_sessions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    session_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, session_id)
);

-- 对话日志表（token 消耗追踪 & 用户问题记录）
CREATE TABLE IF NOT EXISTS chat_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    session_id VARCHAR(64) NOT NULL,
    user_message TEXT,
    assistant_message TEXT,
    prompt_tokens INT DEFAULT 0,
    completion_tokens INT DEFAULT 0,
    total_tokens INT DEFAULT 0,
    tools_used JSONB DEFAULT '[]',
    tool_call_count INT DEFAULT 0,
    round_count INT DEFAULT 0,
    latency_ms BIGINT DEFAULT 0,
    model VARCHAR(64),
    error TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- chat_logs 新增评测字段
ALTER TABLE chat_logs ADD COLUMN IF NOT EXISTS user_rating SMALLINT CHECK (user_rating BETWEEN 1 AND 5);
ALTER TABLE chat_logs ADD COLUMN IF NOT EXISTS quality_score DOUBLE PRECISION;

-- 每日评估指标快照表
CREATE TABLE IF NOT EXISTS evaluation_daily_metrics (
    id BIGSERIAL PRIMARY KEY,
    metric_date DATE NOT NULL,
    scope_type VARCHAR(16) NOT NULL,
    scope_id VARCHAR(64),
    total_requests INT DEFAULT 0,
    success_count INT DEFAULT 0,
    error_count INT DEFAULT 0,
    success_rate DOUBLE PRECISION DEFAULT 0,
    avg_latency_ms DOUBLE PRECISION DEFAULT 0,
    p50_latency_ms BIGINT DEFAULT 0,
    p95_latency_ms BIGINT DEFAULT 0,
    p99_latency_ms BIGINT DEFAULT 0,
    avg_prompt_tokens DOUBLE PRECISION DEFAULT 0,
    avg_completion_tokens DOUBLE PRECISION DEFAULT 0,
    avg_total_tokens DOUBLE PRECISION DEFAULT 0,
    avg_token_ratio DOUBLE PRECISION DEFAULT 0,
    avg_tool_calls DOUBLE PRECISION DEFAULT 0,
    avg_round_count DOUBLE PRECISION DEFAULT 0,
    avg_response_length DOUBLE PRECISION DEFAULT 0,
    avg_quality_score DOUBLE PRECISION DEFAULT 0,
    avg_user_rating DOUBLE PRECISION,
    top_tools JSONB DEFAULT '[]',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(metric_date, scope_type, scope_id)
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_user_sessions_user_id ON user_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_user_sessions_session_id ON user_sessions(session_id);
CREATE INDEX IF NOT EXISTS idx_chat_logs_user_id ON chat_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_chat_logs_session_id ON chat_logs(session_id);
CREATE INDEX IF NOT EXISTS idx_chat_logs_created_at ON chat_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_eval_daily_date ON evaluation_daily_metrics(metric_date);
CREATE INDEX IF NOT EXISTS idx_eval_daily_scope ON evaluation_daily_metrics(scope_type, scope_id);
