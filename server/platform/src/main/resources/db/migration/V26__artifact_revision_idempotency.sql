ALTER TABLE ai_web_case_agent_artifacts ADD COLUMN revision INT NOT NULL DEFAULT 1;
ALTER TABLE ai_web_case_agent_artifacts ADD COLUMN parent_artifact_id VARCHAR(128) NOT NULL DEFAULT '';
ALTER TABLE ai_web_case_agent_artifacts ADD COLUMN idempotency_key VARCHAR(255) DEFAULT NULL;

ALTER TABLE ai_draft_test_points ADD COLUMN revision INT NOT NULL DEFAULT 1;
ALTER TABLE ai_draft_test_points ADD COLUMN parent_artifact_id VARCHAR(128) NOT NULL DEFAULT '';
ALTER TABLE ai_draft_test_points ADD COLUMN idempotency_key VARCHAR(255) DEFAULT NULL;

UPDATE ai_web_case_agent_artifacts SET idempotency_key = CONCAT('legacy:web:', id) WHERE idempotency_key IS NULL OR idempotency_key = '';
UPDATE ai_draft_test_points SET idempotency_key = CONCAT('legacy:draft:', id) WHERE idempotency_key IS NULL OR idempotency_key = '';

CREATE UNIQUE INDEX uk_ai_web_case_agent_artifact_idempotency
    ON ai_web_case_agent_artifacts(run_id, idempotency_key);
CREATE UNIQUE INDEX uk_ai_draft_test_point_idempotency
    ON ai_draft_test_points(run_id, idempotency_key);
