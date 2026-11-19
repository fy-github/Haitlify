-- Internal execution authorization foundation only. No HTTP/admin endpoint or model/runtime wiring.
CREATE TABLE assistant_execution_worker_units (
    unit_id VARCHAR(128) NOT NULL PRIMARY KEY,
    image_digest VARCHAR(80) NOT NULL,
    policy_version VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    cleanup_confirmed BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE assistant_execution_bindings (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    generation_id VARCHAR(36) NOT NULL,
    worker_unit_id VARCHAR(128) NOT NULL,
    lease_generation BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    active_generation_key VARCHAR(36) NULL,
    active_worker_unit_key VARCHAR(128) NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6) NULL,
    CONSTRAINT ck_assistant_execution_binding_state CHECK (state IN ('ACTIVE','REVOKED','CLEANED')),
    CONSTRAINT uq_assistant_execution_active_generation UNIQUE (active_generation_key),
    CONSTRAINT uq_assistant_execution_active_unit UNIQUE (active_worker_unit_key)
);
CREATE INDEX idx_assistant_execution_binding_generation ON assistant_execution_bindings(generation_id, state);
CREATE INDEX idx_assistant_execution_binding_unit ON assistant_execution_bindings(worker_unit_id, state);

CREATE TABLE assistant_execution_grants (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    binding_id VARCHAR(36) NOT NULL,
    actor_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    audience VARCHAR(128) NOT NULL,
    actions VARCHAR(1024) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_assistant_execution_grant_binding UNIQUE (binding_id),
    CONSTRAINT uq_assistant_execution_grant_token UNIQUE (token_hash)
);
CREATE INDEX idx_assistant_execution_grant_binding ON assistant_execution_grants(binding_id, revoked, expires_at);
