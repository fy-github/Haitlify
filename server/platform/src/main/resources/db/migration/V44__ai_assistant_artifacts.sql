-- T12：QA 助手产物表——revision 不可变、人工确认状态与 actor 审计。
-- run 级状态/事件复用 ai_analysis_runs 与 ai_run_events；本表只承载产物与其评审链。
CREATE TABLE ai_assistant_artifacts (
    artifact_id VARCHAR(128) PRIMARY KEY,
    run_id VARCHAR(128) NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    project_id BIGINT NOT NULL,
    revision INT NOT NULL,
    acceptance_state VARCHAR(32) NOT NULL,
    result_origin VARCHAR(32) NOT NULL,
    payload_json MEDIUMTEXT NULL,
    violations_json MEDIUMTEXT NULL,
    source_manifest_json MEDIUMTEXT NULL,
    created_by VARCHAR(128) NOT NULL DEFAULT '',
    reviewed_by VARCHAR(128) NOT NULL DEFAULT '',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_ai_assistant_artifact_revision UNIQUE (run_id, revision)
);

CREATE INDEX idx_ai_assistant_artifacts_run ON ai_assistant_artifacts (run_id, revision);
CREATE INDEX idx_ai_assistant_artifacts_project ON ai_assistant_artifacts (project_id, task_type, created_at);
