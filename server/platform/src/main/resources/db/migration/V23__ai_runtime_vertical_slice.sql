CREATE TABLE ai_runtime_vertical_runs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id VARCHAR(128) NOT NULL,
    project_id BIGINT NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    connection_id VARCHAR(128) NOT NULL,
    connection_version INT NOT NULL,
    profile_id VARCHAR(128) NOT NULL,
    profile_version INT NOT NULL,
    protocol VARCHAR(32) NOT NULL,
    input_hash VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_origin VARCHAR(32) NOT NULL,
    acceptance_state VARCHAR(64) NOT NULL,
    schema_outcome VARCHAR(32) NOT NULL,
    evidence_ids_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_runtime_vertical_run UNIQUE (run_id),
    CONSTRAINT fk_ai_runtime_vertical_project FOREIGN KEY (project_id) REFERENCES projects (id)
);

CREATE TABLE ai_runtime_vertical_attempts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    attempt_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    provider_request_id VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    error_category VARCHAR(128),
    usage_known BOOLEAN NOT NULL,
    input_tokens INT,
    output_tokens INT,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_runtime_vertical_attempt UNIQUE (attempt_id),
    CONSTRAINT fk_ai_runtime_vertical_attempt_run FOREIGN KEY (run_id) REFERENCES ai_runtime_vertical_runs (run_id)
);

CREATE TABLE ai_runtime_vertical_artifacts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    artifact_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    project_id BIGINT NOT NULL,
    origin VARCHAR(32) NOT NULL,
    acceptance_state VARCHAR(32) NOT NULL,
    content_json TEXT NOT NULL,
    evidence_ids_json TEXT NOT NULL,
    reviewer_actor_id VARCHAR(128),
    reviewed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_runtime_vertical_artifact UNIQUE (artifact_id),
    CONSTRAINT fk_ai_runtime_vertical_artifact_run FOREIGN KEY (run_id) REFERENCES ai_runtime_vertical_runs (run_id),
    CONSTRAINT fk_ai_runtime_vertical_artifact_project FOREIGN KEY (project_id) REFERENCES projects (id)
);

CREATE INDEX idx_ai_runtime_vertical_project_created ON ai_runtime_vertical_runs(project_id, created_at);
