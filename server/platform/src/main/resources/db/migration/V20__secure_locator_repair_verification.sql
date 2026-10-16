-- Server-issued, single-use browser verification for locator repair proposals.
CREATE TABLE ai_locator_repair_verifications (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    verification_id VARCHAR(128) NOT NULL,
    entry_id VARCHAR(128) NOT NULL,
    project_id BIGINT NOT NULL,
    environment_id VARCHAR(128) NOT NULL DEFAULT '',
    source_point_id VARCHAR(128) NOT NULL DEFAULT '',
    page_key VARCHAR(255) NOT NULL DEFAULT '',
    page_fingerprint VARCHAR(128) NOT NULL DEFAULT '',
    page_url VARCHAR(2048) NOT NULL DEFAULT '',
    candidate_locator VARCHAR(1024) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    verified_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP NULL,
    visible BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    receives_events BOOLEAN NOT NULL DEFAULT FALSE,
    correct_page BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_locator_repair_verification_id UNIQUE (verification_id),
    CONSTRAINT fk_ai_locator_repair_verification_entry FOREIGN KEY (entry_id) REFERENCES ai_locator_knowledge_entries(entry_id),
    CONSTRAINT fk_ai_locator_repair_verification_project FOREIGN KEY (project_id) REFERENCES projects(id)
);
CREATE INDEX idx_ai_locator_repair_verifications_lookup
    ON ai_locator_repair_verifications(entry_id, owner_id, expires_at, used_at);

ALTER TABLE ai_locator_repair_proposals
    ADD COLUMN verification_id VARCHAR(128) NULL;
CREATE INDEX idx_ai_locator_repair_proposals_verification
    ON ai_locator_repair_proposals(verification_id);
