CREATE TABLE ai_agent_definitions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    agent_id VARCHAR(128) NOT NULL,
    project_id BIGINT,
    scope VARCHAR(32) NOT NULL DEFAULT 'system',
    name_override VARCHAR(255),
    description_override VARCHAR(1024),
    agent_role_override VARCHAR(64),
    prompt_template_override TEXT,
    output_json_schema_override TEXT,
    allowed_tools_override_json TEXT,
    enabled_override BOOLEAN,
    version INT NOT NULL DEFAULT 1,
    overridden_fields_json TEXT,
    actor_id VARCHAR(128) NOT NULL DEFAULT 'system-dev',
    actor_name VARCHAR(255) NOT NULL DEFAULT 'Development User',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_agent_definition_scope UNIQUE (agent_id, project_id, scope),
    CONSTRAINT fk_ai_agent_definition_project FOREIGN KEY (project_id) REFERENCES projects (id)
);

CREATE TABLE ai_agent_run_graphs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    graph_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    project_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'queued',
    input_snapshot_json TEXT,
    output_snapshot_json TEXT,
    error_summary VARCHAR(1024) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_agent_run_graph_id UNIQUE (graph_id),
    CONSTRAINT uk_ai_agent_run_graph_run UNIQUE (run_id),
    CONSTRAINT fk_ai_agent_run_graph_project FOREIGN KEY (project_id) REFERENCES projects (id)
);

CREATE TABLE ai_agent_run_nodes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    node_id VARCHAR(128) NOT NULL,
    graph_id BIGINT NOT NULL,
    agent_id VARCHAR(128) NOT NULL,
    agent_role VARCHAR(64) NOT NULL DEFAULT '',
    execution_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'queued',
    input_snapshot_json TEXT,
    output_snapshot_json TEXT,
    error_summary VARCHAR(1024) NOT NULL DEFAULT '',
    started_at TIMESTAMP NULL,
    finished_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_agent_run_node_id UNIQUE (node_id),
    CONSTRAINT fk_ai_agent_run_node_graph FOREIGN KEY (graph_id) REFERENCES ai_agent_run_graphs (id)
);

CREATE TABLE ai_agent_handoffs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    handoff_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    from_agent_id VARCHAR(128) NOT NULL DEFAULT '',
    to_agent_id VARCHAR(128) NOT NULL DEFAULT '',
    source_run_node_id VARCHAR(128) NOT NULL DEFAULT '',
    target_run_node_id VARCHAR(128) NOT NULL DEFAULT '',
    payload_summary VARCHAR(1024) NOT NULL DEFAULT '',
    payload_json TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_agent_handoff_id UNIQUE (handoff_id)
);

CREATE TABLE ai_agent_critiques (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    critique_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    reviewer_agent_id VARCHAR(128) NOT NULL DEFAULT '',
    target_agent_id VARCHAR(128) NOT NULL DEFAULT '',
    target_run_node_id VARCHAR(128) NOT NULL DEFAULT '',
    critique_type VARCHAR(64) NOT NULL DEFAULT '',
    severity VARCHAR(32) NOT NULL DEFAULT '',
    critique_json TEXT,
    repair_requested BOOLEAN NOT NULL DEFAULT FALSE,
    repair_pass INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_agent_critique_id UNIQUE (critique_id)
);

CREATE TABLE ai_agent_conflicts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conflict_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    conflict_type VARCHAR(64) NOT NULL DEFAULT '',
    source_agent_ids_json TEXT,
    target_key VARCHAR(512) NOT NULL DEFAULT '',
    conflict_json TEXT,
    resolution_status VARCHAR(32) NOT NULL DEFAULT 'unresolved',
    resolution_json TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_agent_conflict_id UNIQUE (conflict_id)
);

CREATE TABLE ai_agent_consensus_records (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    consensus_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'draft',
    merged_output_json TEXT,
    source_agent_ids_json TEXT,
    created_draft_point_ids_json TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_agent_consensus_id UNIQUE (consensus_id)
);

CREATE TABLE ai_quality_scores (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    score_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    quality_dimension VARCHAR(64) NOT NULL DEFAULT '',
    score DECIMAL(5,2) NOT NULL DEFAULT 0,
    score_level VARCHAR(32) NOT NULL DEFAULT '',
    score_reason VARCHAR(1024) NOT NULL DEFAULT '',
    evidence_json TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_quality_score_id UNIQUE (score_id)
);

CREATE TABLE ai_automation_suggestions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    suggestion_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    draft_point_id VARCHAR(128) NOT NULL DEFAULT '',
    case_kind VARCHAR(64) NOT NULL DEFAULT '',
    automation_readiness VARCHAR(32) NOT NULL DEFAULT '',
    required_data_json TEXT,
    required_environment_json TEXT,
    suggested_steps_json TEXT,
    suggested_assertions_json TEXT,
    risks_json TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_automation_suggestion_id UNIQUE (suggestion_id)
);

CREATE INDEX idx_ai_agent_nodes_graph_order ON ai_agent_run_nodes(graph_id, execution_order);
CREATE INDEX idx_ai_agent_handoffs_run ON ai_agent_handoffs(run_id);
CREATE INDEX idx_ai_agent_conflicts_run_status ON ai_agent_conflicts(run_id, resolution_status);
CREATE INDEX idx_ai_quality_scores_run ON ai_quality_scores(run_id);
CREATE INDEX idx_ai_automation_suggestions_run ON ai_automation_suggestions(run_id);
