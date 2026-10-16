-- Phase 7 increment 2: platform-side persistence of WebAutomation execution results.
-- The run snapshot (status/summary/per-case durations/session) is woven through this table so the
-- stability metrics can aggregate execution success rate and average duration per project.
-- project_id is captured when the run is created (optional on the wire; rows created without it
-- are excluded from project-scoped metrics).
CREATE TABLE ai_web_automation_run_reports (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id VARCHAR(128) NOT NULL,
    project_id BIGINT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'queued',
    mode VARCHAR(32) NOT NULL DEFAULT '',
    selected_browser VARCHAR(64) NOT NULL DEFAULT '',
    total_cases INT NOT NULL DEFAULT 0,
    passed_cases INT NOT NULL DEFAULT 0,
    failed_cases INT NOT NULL DEFAULT 0,
    error_cases INT NOT NULL DEFAULT 0,
    total_case_duration_ms BIGINT NOT NULL DEFAULT 0,
    session_id VARCHAR(128) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_web_automation_run_report_run UNIQUE (run_id)
);

CREATE INDEX idx_ai_web_automation_run_reports_project
    ON ai_web_automation_run_reports(project_id, status, updated_at);
