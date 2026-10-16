-- Platform AI routing policies + run observability (additive; no existing column/type changed).
-- Policies author the ordered primary+fallback route per task type; a run copies an immutable
-- strategy snapshot (strategy_snapshot_json) at creation time, so policy edits never affect an
-- in-flight run. ai_run_events is the append-only, sensitive-payload-free visible event stream.

CREATE TABLE ai_run_policies (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    policy_id VARCHAR(128) NOT NULL,
    scope VARCHAR(32) NOT NULL DEFAULT 'default',
    task_type VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL DEFAULT '',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    per_call_timeout_ms INT NOT NULL DEFAULT 120000,
    whole_run_deadline_ms BIGINT NOT NULL DEFAULT 900000,
    retry_budget INT NOT NULL DEFAULT 2,
    max_input_chars INT NOT NULL DEFAULT 0,
    routes_json TEXT,
    actor_id VARCHAR(128) NOT NULL DEFAULT 'system-dev',
    actor_name VARCHAR(255) NOT NULL DEFAULT 'Development User',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_run_policy_scope_task UNIQUE (scope, task_type)
);

CREATE INDEX idx_ai_run_policies_scope_task ON ai_run_policies(scope, task_type, enabled);

ALTER TABLE ai_analysis_runs ADD COLUMN strategy_snapshot_json TEXT;

CREATE TABLE ai_run_events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(128) NOT NULL DEFAULT '',
    occurred_at TIMESTAMP NOT NULL,
    stage VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL DEFAULT 'info',
    summary VARCHAR(1024) NOT NULL DEFAULT '',
    chunk_index INT,
    chunk_total INT,
    attempt_index INT,
    provider_id VARCHAR(128) NOT NULL DEFAULT '',
    provider_type VARCHAR(64) NOT NULL DEFAULT '',
    model VARCHAR(255) NOT NULL DEFAULT '',
    duration_ms BIGINT NOT NULL DEFAULT 0,
    retry_reason VARCHAR(64) NOT NULL DEFAULT '',
    strategy_snapshot_id VARCHAR(128) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_run_event_id UNIQUE (event_id)
);

CREATE INDEX idx_ai_run_events_run_occurred ON ai_run_events(run_id, occurred_at, id);
