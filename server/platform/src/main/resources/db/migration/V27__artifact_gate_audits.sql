CREATE TABLE ai_artifact_gate_audits (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    gate_id VARCHAR(128) NOT NULL,
    artifact_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    project_id BIGINT NOT NULL,
    result_origin VARCHAR(64) NOT NULL,
    acceptance_state VARCHAR(64) NOT NULL,
    decision VARCHAR(64) NOT NULL,
    revision INT NOT NULL,
    parent_artifact_id VARCHAR(128) NOT NULL DEFAULT '',
    idempotency_key VARCHAR(255) NOT NULL,
    evidence_ids_json TEXT NOT NULL,
    errors_json TEXT NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_artifact_gate_id UNIQUE (gate_id)
);

CREATE INDEX idx_ai_artifact_gate_artifact_revision
    ON ai_artifact_gate_audits(artifact_id, revision, created_at);
CREATE INDEX idx_ai_artifact_gate_run
    ON ai_artifact_gate_audits(run_id, created_at);
