ALTER TABLE ai_web_case_agent_runs ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'COMPILE';
ALTER TABLE ai_web_case_agent_runs ADD COLUMN plan_run_id VARCHAR(128) NOT NULL DEFAULT '';
CREATE INDEX idx_ai_web_case_agent_runs_plan_run ON ai_web_case_agent_runs(plan_run_id, mode);
