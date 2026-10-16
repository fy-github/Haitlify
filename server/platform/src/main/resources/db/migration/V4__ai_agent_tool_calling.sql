CREATE TABLE ai_tool_call_records (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    call_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(128) NOT NULL DEFAULT '',
    project_id BIGINT,
    server_id VARCHAR(128) NOT NULL DEFAULT '',
    tool_name VARCHAR(255) NOT NULL DEFAULT '',
    read_only BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    request_summary_json TEXT,
    response_summary_json TEXT,
    error_message VARCHAR(1024) NOT NULL DEFAULT '',
    actor_id VARCHAR(128) NOT NULL DEFAULT 'system-dev',
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_tool_call_record_id UNIQUE (call_id)
);

CREATE INDEX idx_ai_tool_call_records_run_created ON ai_tool_call_records(run_id, created_at);
CREATE INDEX idx_ai_tool_call_records_server_status ON ai_tool_call_records(server_id, status, created_at);

ALTER TABLE ai_skills ADD COLUMN skill_kind VARCHAR(64) NOT NULL DEFAULT 'analysis';
ALTER TABLE ai_skills ADD COLUMN is_custom BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE ai_skill_versions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    skill_id VARCHAR(128) NOT NULL,
    version INT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1024) NOT NULL DEFAULT '',
    tags_json TEXT,
    applicable_case_kinds_json TEXT,
    applicable_file_types_json TEXT,
    prompt_template TEXT NOT NULL,
    output_json_schema TEXT NOT NULL,
    available_tools_json TEXT,
    examples_json TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    actor_id VARCHAR(128) NOT NULL DEFAULT 'system-dev',
    actor_name VARCHAR(255) NOT NULL DEFAULT 'Development User',
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_skill_version UNIQUE (skill_id, version)
);

CREATE INDEX idx_ai_skill_versions_skill_created ON ai_skill_versions(skill_id, created_at);

CREATE TABLE ai_analysis_chunks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    planned_chunk_id VARCHAR(128) NOT NULL,
    run_id BIGINT NOT NULL,
    source_chunk_ids_json TEXT,
    section_heading VARCHAR(512) NOT NULL DEFAULT '',
    token_estimate INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'planned',
    skip_reason VARCHAR(255) NOT NULL DEFAULT '',
    content_text TEXT,
    evidence_metadata_json TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_analysis_chunk_id UNIQUE (planned_chunk_id),
    CONSTRAINT fk_ai_analysis_chunk_run FOREIGN KEY (run_id) REFERENCES ai_analysis_runs (id)
);

CREATE INDEX idx_ai_analysis_chunks_run_status ON ai_analysis_chunks(run_id, status, id);

CREATE TABLE ai_coverage_summaries (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    summary_json TEXT,
    warnings_json TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_ai_coverage_summary_run FOREIGN KEY (run_id) REFERENCES ai_analysis_runs (id)
);

CREATE TABLE ai_unclear_questions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    question_id VARCHAR(128) NOT NULL,
    run_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    severity VARCHAR(32) NOT NULL DEFAULT 'medium',
    question_text VARCHAR(1024) NOT NULL DEFAULT '',
    source_ref VARCHAR(255) NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL DEFAULT 'open',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_unclear_question_id UNIQUE (question_id),
    CONSTRAINT fk_ai_unclear_question_run FOREIGN KEY (run_id) REFERENCES ai_analysis_runs (id),
    CONSTRAINT fk_ai_unclear_question_project FOREIGN KEY (project_id) REFERENCES projects (id)
);

CREATE INDEX idx_ai_coverage_summaries_run_created ON ai_coverage_summaries(run_id, created_at);
CREATE INDEX idx_ai_unclear_questions_run_status ON ai_unclear_questions(run_id, status, id);
