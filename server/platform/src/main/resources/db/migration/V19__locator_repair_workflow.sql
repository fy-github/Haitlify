-- Locator repair proposals remain pending until explicit human review.
ALTER TABLE ai_locator_knowledge_history ADD COLUMN revision INT NOT NULL DEFAULT 1;

CREATE TABLE ai_locator_repair_proposals (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    proposal_id VARCHAR(128) NOT NULL,
    entry_id VARCHAR(128) NOT NULL,
    project_id BIGINT NOT NULL,
    failure_category VARCHAR(64) NOT NULL,
    old_locator VARCHAR(1024) NOT NULL,
    new_locator VARCHAR(1024) NOT NULL,
    page_fingerprint_before VARCHAR(128) NOT NULL DEFAULT '',
    page_fingerprint_after VARCHAR(128) NOT NULL DEFAULT '',
    confidence INT NOT NULL DEFAULT 0,
    reason VARCHAR(1024) NOT NULL DEFAULT '',
    verification_json TEXT NOT NULL,
    revision INT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'pending_review',
    reviewed_by VARCHAR(128),
    reviewed_name VARCHAR(255),
    review_comment VARCHAR(1024),
    reviewed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_locator_repair_proposal_id UNIQUE (proposal_id),
    CONSTRAINT fk_ai_locator_repair_proposal_entry FOREIGN KEY (entry_id) REFERENCES ai_locator_knowledge_entries(entry_id),
    CONSTRAINT fk_ai_locator_repair_proposal_project FOREIGN KEY (project_id) REFERENCES projects(id)
);
CREATE INDEX idx_ai_locator_repair_proposals_scope_status ON ai_locator_repair_proposals(project_id, status, created_at);

CREATE TABLE ai_locator_repair_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    audit_id VARCHAR(128) NOT NULL,
    proposal_id VARCHAR(128) NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    before_json TEXT,
    after_json TEXT,
    actor_id VARCHAR(128) NOT NULL DEFAULT 'system-dev',
    actor_name VARCHAR(255) NOT NULL DEFAULT 'Development User',
    comment VARCHAR(1024) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_locator_repair_audit_id UNIQUE (audit_id),
    CONSTRAINT fk_ai_locator_repair_audit_proposal FOREIGN KEY (proposal_id) REFERENCES ai_locator_repair_proposals(proposal_id)
);
CREATE INDEX idx_ai_locator_repair_audit_proposal ON ai_locator_repair_audit(proposal_id, created_at);
