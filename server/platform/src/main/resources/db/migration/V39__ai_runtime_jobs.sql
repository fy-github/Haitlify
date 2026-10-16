-- T06: persistent runtime jobs with leases and fencing, node checkpoints,
-- attempt linkage on the vertical attempts table, and a monotonic per-run
-- event sequence for afterSeq catch-up.
CREATE TABLE ai_runtime_jobs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    job_id VARCHAR(128) NOT NULL,
    job_type VARCHAR(64) NOT NULL,
    project_id BIGINT NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(191) NOT NULL,
    input_hash VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    lease_owner VARCHAR(128) NOT NULL DEFAULT '',
    lease_until TIMESTAMP NULL,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    attempt_count INT NOT NULL DEFAULT 0,
    deployment_revision VARCHAR(128) NOT NULL DEFAULT '',
    legacy_run_ref VARCHAR(128) NULL,
    node_plan_json TEXT NOT NULL,
    error_summary VARCHAR(1024) NOT NULL DEFAULT '',
    queued_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP NULL,
    first_token_at TIMESTAMP NULL,
    finished_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_runtime_jobs UNIQUE (job_id),
    CONSTRAINT uk_ai_runtime_jobs_idem UNIQUE (idempotency_key),
    CONSTRAINT fk_ai_runtime_jobs_project FOREIGN KEY (project_id) REFERENCES projects (id)
);

CREATE TABLE ai_runtime_job_nodes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    job_id VARCHAR(128) NOT NULL,
    node_key VARCHAR(191) NOT NULL,
    node_order INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    side_effect VARCHAR(32) NOT NULL DEFAULT 'none',
    attempt_ref VARCHAR(128) NOT NULL DEFAULT '',
    output_ref VARCHAR(255) NOT NULL DEFAULT '',
    error_summary VARCHAR(1024) NOT NULL DEFAULT '',
    started_at TIMESTAMP NULL,
    finished_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_runtime_job_nodes UNIQUE (job_id, node_key),
    CONSTRAINT fk_ai_runtime_job_nodes_job FOREIGN KEY (job_id) REFERENCES ai_runtime_jobs (job_id)
);

CREATE INDEX idx_ai_runtime_jobs_claim ON ai_runtime_jobs(status, queued_at, id);
CREATE INDEX idx_ai_runtime_jobs_lease ON ai_runtime_jobs(lease_until);

ALTER TABLE ai_runtime_vertical_attempts ADD COLUMN request_hash VARCHAR(128) NOT NULL DEFAULT '';
ALTER TABLE ai_runtime_vertical_attempts ADD COLUMN job_id VARCHAR(128) NULL;
ALTER TABLE ai_runtime_vertical_attempts ADD COLUMN node_key VARCHAR(191) NULL;

ALTER TABLE ai_run_events ADD COLUMN event_seq BIGINT NOT NULL DEFAULT 0;
-- Backfill with the global id: within one run ids increase strictly, so the
-- per-run sequence stays monotonic (gaps are allowed; only order matters).
UPDATE ai_run_events SET event_seq = id;
CREATE UNIQUE INDEX uk_ai_run_events_run_seq ON ai_run_events(run_id, event_seq);
