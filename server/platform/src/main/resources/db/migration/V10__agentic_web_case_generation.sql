-- Agentic Web case generation persistence boundary.
--
-- These tables hold resumable run state, redacted event summaries, browser evidence references,
-- versioned rule snapshots, and human-controlled memory assets. Credentials, cookies, raw HTML,
-- and hidden model reasoning are intentionally absent from every persisted schema here.

CREATE TABLE ai_agent_rule_versions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    rule_set_id VARCHAR(128) NOT NULL,
    scope VARCHAR(32) NOT NULL DEFAULT 'global',
    project_id BIGINT,
    version INT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'draft',
    content_text TEXT NOT NULL,
    phase_rules_json TEXT NOT NULL,
    content_hash VARCHAR(128) NOT NULL DEFAULT '',
    published_at TIMESTAMP NULL,
    actor_id VARCHAR(128) NOT NULL DEFAULT 'system-dev',
    actor_name VARCHAR(255) NOT NULL DEFAULT 'Development User',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_agent_rule_version_scope UNIQUE (rule_set_id, scope, project_id, version),
    CONSTRAINT fk_ai_agent_rule_version_project FOREIGN KEY (project_id) REFERENCES projects (id)
);

CREATE INDEX idx_ai_agent_rule_versions_scope_status
    ON ai_agent_rule_versions(rule_set_id, scope, project_id, status);

CREATE TABLE ai_web_case_agent_runs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id VARCHAR(128) NOT NULL,
    project_id BIGINT NOT NULL,
    analysis_run_id VARCHAR(128) NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL DEFAULT 'queued',
    current_stage VARCHAR(32) NOT NULL DEFAULT 'preflight',
    requested_point_ids_json TEXT NOT NULL,
    completed_point_ids_json TEXT NOT NULL,
    blocked_point_ids_json TEXT NOT NULL,
    rules_snapshot_json TEXT NOT NULL,
    input_summary_json TEXT NOT NULL,
    output_summary_json TEXT,
    last_heartbeat_at TIMESTAMP NULL,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    error_summary VARCHAR(1024) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP NULL,
    finished_at TIMESTAMP NULL,
    CONSTRAINT uk_ai_web_case_agent_run_id UNIQUE (run_id),
    CONSTRAINT fk_ai_web_case_agent_run_project FOREIGN KEY (project_id) REFERENCES projects (id)
);

CREATE INDEX idx_ai_web_case_agent_runs_project_status
    ON ai_web_case_agent_runs(project_id, status, updated_at);

CREATE TABLE ai_web_case_agent_events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    sequence_no BIGINT NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    stage VARCHAR(32) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL DEFAULT 'info',
    summary VARCHAR(1024) NOT NULL DEFAULT '',
    point_id VARCHAR(128),
    tool_name VARCHAR(255),
    duration_ms BIGINT NOT NULL DEFAULT 0,
    rule_version INT,
    knowledge_hit_ids_json TEXT,
    error_code VARCHAR(128),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_web_case_agent_event_sequence UNIQUE (run_id, sequence_no)
);

CREATE INDEX idx_ai_web_case_agent_events_run_sequence
    ON ai_web_case_agent_events(run_id, sequence_no);

CREATE TABLE ai_web_case_agent_artifacts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    artifact_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    point_id VARCHAR(128) NOT NULL,
    artifact_type VARCHAR(64) NOT NULL DEFAULT 'web_case',
    candidate_json TEXT NOT NULL,
    evidence_ids_json TEXT NOT NULL,
    validation_errors_json TEXT NOT NULL,
    accepted BOOLEAN NOT NULL DEFAULT FALSE,
    repair_round INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_web_case_agent_artifact_id UNIQUE (artifact_id)
);

CREATE INDEX idx_ai_web_case_agent_artifacts_run_point
    ON ai_web_case_agent_artifacts(run_id, point_id, accepted, repair_round);

CREATE TABLE ai_locator_knowledge_entries (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    entry_id VARCHAR(128) NOT NULL,
    project_id BIGINT NOT NULL,
    environment_id VARCHAR(128) NOT NULL DEFAULT '',
    module_name VARCHAR(255) NOT NULL DEFAULT '',
    page_name VARCHAR(255) NOT NULL DEFAULT '',
    page_url VARCHAR(2048) NOT NULL DEFAULT '',
    page_fingerprint VARCHAR(128) NOT NULL DEFAULT '',
    natural_name VARCHAR(512) NOT NULL DEFAULT '',
    locator VARCHAR(1024) NOT NULL,
    role_name VARCHAR(128) NOT NULL DEFAULT '',
    accessible_name VARCHAR(512) NOT NULL DEFAULT '',
    element_type VARCHAR(128) NOT NULL DEFAULT '',
    source VARCHAR(64) NOT NULL DEFAULT 'browser',
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    evidence_version INT NOT NULL DEFAULT 1,
    collected_at TIMESTAMP NOT NULL,
    last_verified_at TIMESTAMP NULL,
    metadata_json TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_locator_knowledge_entry_id UNIQUE (entry_id),
    CONSTRAINT fk_ai_locator_knowledge_project FOREIGN KEY (project_id) REFERENCES projects (id)
);

CREATE INDEX idx_ai_locator_knowledge_scope
    ON ai_locator_knowledge_entries(project_id, environment_id, module_name, page_name, status);
CREATE INDEX idx_ai_locator_knowledge_page_fingerprint
    ON ai_locator_knowledge_entries(page_fingerprint, last_verified_at);

CREATE TABLE ai_locator_knowledge_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    history_id VARCHAR(128) NOT NULL,
    entry_id VARCHAR(128) NOT NULL,
    snapshot_json TEXT NOT NULL,
    changed_reason VARCHAR(255) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_locator_knowledge_history_id UNIQUE (history_id),
    CONSTRAINT fk_ai_locator_knowledge_history_entry FOREIGN KEY (entry_id)
        REFERENCES ai_locator_knowledge_entries (entry_id)
);

CREATE INDEX idx_ai_locator_knowledge_history_entry
    ON ai_locator_knowledge_history(entry_id, created_at);

CREATE TABLE ai_agent_memories (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    memory_id VARCHAR(128) NOT NULL,
    source_run_id VARCHAR(128),
    project_id BIGINT,
    module_name VARCHAR(255) NOT NULL DEFAULT '',
    stage VARCHAR(32) NOT NULL DEFAULT 'delivery',
    category VARCHAR(64) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    scope_type VARCHAR(32) NOT NULL DEFAULT 'project',
    scope_project_ids_json TEXT,
    rules_version INT,
    created_by VARCHAR(128) NOT NULL DEFAULT 'agent',
    confirmed_by VARCHAR(128),
    confirmed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_agent_memory_id UNIQUE (memory_id),
    CONSTRAINT fk_ai_agent_memory_project FOREIGN KEY (project_id) REFERENCES projects (id)
);

CREATE INDEX idx_ai_agent_memories_scope_status
    ON ai_agent_memories(project_id, scope_type, status, updated_at);
CREATE INDEX idx_ai_agent_memories_source_run
    ON ai_agent_memories(source_run_id, created_at);

CREATE TABLE ai_agent_memory_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    audit_id VARCHAR(128) NOT NULL,
    memory_id VARCHAR(128) NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    before_json TEXT,
    after_json TEXT,
    actor_id VARCHAR(128) NOT NULL DEFAULT 'system-dev',
    actor_name VARCHAR(255) NOT NULL DEFAULT 'Development User',
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_agent_memory_audit_id UNIQUE (audit_id),
    CONSTRAINT fk_ai_agent_memory_audit_memory FOREIGN KEY (memory_id)
        REFERENCES ai_agent_memories (memory_id)
);

CREATE INDEX idx_ai_agent_memory_audit_memory
    ON ai_agent_memory_audit(memory_id, created_at);
