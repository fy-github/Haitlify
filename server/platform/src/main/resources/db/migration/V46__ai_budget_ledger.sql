-- T13：预算台账（项目/用户/任务/全局 × 日窗口）。
-- reserve/settle 走单语句 CAS（无行锁、无存储过程），并发下不会超发。
CREATE TABLE ai_budget_ledger (
    ledger_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scope_key VARCHAR(160) NOT NULL,
    window_day DATE NOT NULL,
    reserved_tokens BIGINT NOT NULL DEFAULT 0,
    settled_tokens BIGINT NOT NULL DEFAULT 0,
    reserved_calls INT NOT NULL DEFAULT 0,
    settled_calls INT NOT NULL DEFAULT 0,
    limit_tokens BIGINT NOT NULL,
    limit_calls INT NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_ai_budget_ledger_scope UNIQUE (scope_key, window_day)
);

CREATE INDEX idx_ai_budget_ledger_day ON ai_budget_ledger (window_day, scope_key);

-- 预留/结算明细：每个 reservation 一行，未知 usage 按预留额结算（绝不按 0 计）。
CREATE TABLE ai_budget_reservations (
    reservation_id VARCHAR(128) PRIMARY KEY,
    scope_key VARCHAR(160) NOT NULL,
    window_day DATE NOT NULL,
    reserved_tokens BIGINT NOT NULL,
    reserved_calls INT NOT NULL,
    settled_tokens BIGINT NULL,
    settled_calls INT NULL,
    usage_known BOOLEAN NOT NULL DEFAULT FALSE,
    pricing_mode VARCHAR(32) NOT NULL,
    state VARCHAR(32) NOT NULL,
    attempt_ref VARCHAR(160) NOT NULL DEFAULT '',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    settled_at TIMESTAMP(3) NULL
);

CREATE INDEX idx_ai_budget_reservations_state ON ai_budget_reservations (state, window_day);
