CREATE TABLE ai_connections (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    connection_id VARCHAR(128) NOT NULL,
    version INT NOT NULL,
    base_url VARCHAR(1024) NOT NULL,
    credential_ref VARCHAR(255) NOT NULL,
    protocol VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    allowed_projects_json TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_connection_version UNIQUE (connection_id, version)
);

CREATE TABLE ai_model_profiles (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    profile_id VARCHAR(128) NOT NULL,
    version INT NOT NULL,
    connection_id VARCHAR(128) NOT NULL,
    model_id VARCHAR(255) NOT NULL,
    capabilities_json TEXT,
    parameter_policy_json TEXT,
    verification_json TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_model_profile_version UNIQUE (profile_id, version)
);

CREATE TABLE ai_run_snapshots (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id VARCHAR(128) NOT NULL,
    connection_id VARCHAR(128) NOT NULL,
    connection_version INT NOT NULL,
    profile_id VARCHAR(128) NOT NULL,
    profile_version INT NOT NULL,
    protocol VARCHAR(32) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    project_id BIGINT NOT NULL,
    input_hash VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_run_snapshot_run UNIQUE (run_id)
);

CREATE INDEX idx_ai_run_snapshot_project ON ai_run_snapshots(project_id, created_at);
