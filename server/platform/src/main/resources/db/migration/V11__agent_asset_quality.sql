ALTER TABLE ai_locator_knowledge_entries ADD COLUMN hit_count INT NOT NULL DEFAULT 0;
ALTER TABLE ai_locator_knowledge_entries ADD COLUMN execution_success_count INT NOT NULL DEFAULT 0;
ALTER TABLE ai_locator_knowledge_entries ADD COLUMN execution_failure_count INT NOT NULL DEFAULT 0;
ALTER TABLE ai_locator_knowledge_entries ADD COLUMN confidence_score INT NOT NULL DEFAULT 35;
ALTER TABLE ai_locator_knowledge_entries ADD COLUMN confidence_locked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ai_locator_knowledge_entries ADD COLUMN last_hit_at TIMESTAMP NULL;

ALTER TABLE ai_agent_memories ADD COLUMN hit_count INT NOT NULL DEFAULT 0;
ALTER TABLE ai_agent_memories ADD COLUMN execution_success_count INT NOT NULL DEFAULT 0;
ALTER TABLE ai_agent_memories ADD COLUMN execution_failure_count INT NOT NULL DEFAULT 0;
ALTER TABLE ai_agent_memories ADD COLUMN confidence_score INT NOT NULL DEFAULT 35;
ALTER TABLE ai_agent_memories ADD COLUMN confidence_locked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ai_agent_memories ADD COLUMN last_hit_at TIMESTAMP NULL;

CREATE TABLE ai_agent_asset_usage (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    usage_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    point_id VARCHAR(128),
    asset_type VARCHAR(32) NOT NULL,
    asset_id VARCHAR(128) NOT NULL,
    outcome VARCHAR(32) NOT NULL DEFAULT 'pending',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_agent_asset_usage UNIQUE (run_id, point_id, asset_type, asset_id)
);

CREATE INDEX idx_ai_agent_asset_usage_outcome ON ai_agent_asset_usage(run_id, point_id, asset_type, outcome);
