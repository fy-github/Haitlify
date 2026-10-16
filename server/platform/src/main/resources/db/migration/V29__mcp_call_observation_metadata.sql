ALTER TABLE ai_tool_call_records ADD COLUMN provider_call_id VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE ai_tool_call_records ADD COLUMN parameter_hash VARCHAR(128) NOT NULL DEFAULT '';
ALTER TABLE ai_tool_call_records ADD COLUMN evidence_id VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE ai_tool_call_records ADD COLUMN observed_at TIMESTAMP NULL;
