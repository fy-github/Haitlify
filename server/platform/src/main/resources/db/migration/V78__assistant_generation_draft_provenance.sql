-- One immutable generated draft per generation, across worker binding/lease retries.
-- Audit identifiers deliberately survive retirement/deletion of mutable authority rows.
CREATE TABLE assistant_generation_drafts (
    generation_id VARCHAR(36) NOT NULL PRIMARY KEY,
    draft_id VARCHAR(36) NOT NULL,
    binding_id VARCHAR(36) NOT NULL,
    lease_generation BIGINT NOT NULL,
    snapshot_digest CHAR(64) NOT NULL,
    spec_digest CHAR(64) NOT NULL,
    provenance_digest CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_assistant_generation_draft UNIQUE (draft_id)
);
