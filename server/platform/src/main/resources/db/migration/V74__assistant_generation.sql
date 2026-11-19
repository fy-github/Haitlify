-- Immutable admission snapshots. Deliberately no FK to mutable/deletable users,
-- projects or tenants: preserve audit identity; access always revalidates live rows.
CREATE TABLE assistant_generations (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    payload_digest VARCHAR(64) NOT NULL,
    snapshot_ciphertext LONGTEXT NOT NULL,
    state VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_assistant_generation_request UNIQUE (actor_id, project_id, request_id)
);
