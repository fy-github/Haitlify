-- T06: generic runtime attempts (two-phase: saved before the request, finalized
-- after it) with usage-unknown semantics and secret-free summaries. Separate
-- from ai_runtime_vertical_attempts, whose FK binds it to the vertical runs.
CREATE TABLE ai_runtime_attempts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    attempt_id VARCHAR(128) NOT NULL,
    job_id VARCHAR(128) NOT NULL DEFAULT '',
    node_key VARCHAR(191) NOT NULL DEFAULT '',
    run_id VARCHAR(128) NOT NULL DEFAULT '',
    request_hash VARCHAR(128) NOT NULL,
    request_summary_json TEXT NULL,
    route_summary_json TEXT NULL,
    status VARCHAR(32) NOT NULL,
    http_status INT NULL,
    provider_request_id VARCHAR(255) NOT NULL DEFAULT '',
    error_category VARCHAR(128) NOT NULL DEFAULT '',
    retry_reason VARCHAR(64) NOT NULL DEFAULT '',
    usage_known BOOLEAN NOT NULL DEFAULT TRUE,
    input_tokens INT NULL,
    output_tokens INT NULL,
    ttfb_ms BIGINT NULL,
    duration_ms BIGINT NULL,
    deployment_revision VARCHAR(128) NOT NULL DEFAULT '',
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_runtime_attempt UNIQUE (attempt_id)
);

CREATE INDEX idx_ai_runtime_attempts_job ON ai_runtime_attempts(job_id, id);
