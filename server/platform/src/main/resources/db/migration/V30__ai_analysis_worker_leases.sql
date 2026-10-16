CREATE TABLE ai_analysis_worker_leases (
    run_id VARCHAR(128) PRIMARY KEY,
    owner_id VARCHAR(128) NOT NULL,
    lease_until TIMESTAMP NOT NULL,
    fencing_token BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_ai_analysis_worker_lease_run FOREIGN KEY (run_id) REFERENCES ai_analysis_runs(run_id)
);

CREATE INDEX idx_ai_analysis_worker_lease_until ON ai_analysis_worker_leases(lease_until);