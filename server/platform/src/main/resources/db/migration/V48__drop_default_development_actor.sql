-- T14-R5：清除历史迁移里的默认开发身份（DDL 默认值）——
-- 新建行若未显式给 actor，必须落显式 system，而不是可冒充的 system-dev/Development User。
-- 历史迁移按纪律不得改写（V3–V47 byte-exact），此处用前向迁移修正默认值。
-- 语法用标准 ALTER COLUMN ... SET DEFAULT（MySQL 8.0.13+ 与 H2 test profile 双兼容）。
ALTER TABLE ai_agent_rule_versions ALTER COLUMN actor_id SET DEFAULT 'system';
ALTER TABLE ai_agent_rule_versions ALTER COLUMN actor_name SET DEFAULT 'System';
ALTER TABLE ai_agent_memory_audit ALTER COLUMN actor_id SET DEFAULT 'system';
ALTER TABLE ai_agent_memory_audit ALTER COLUMN actor_name SET DEFAULT 'System';
ALTER TABLE jira_configs ALTER COLUMN actor_id SET DEFAULT 'system';
ALTER TABLE jira_configs ALTER COLUMN actor_name SET DEFAULT 'System';
ALTER TABLE ai_locator_repair_audit ALTER COLUMN actor_id SET DEFAULT 'system';
ALTER TABLE ai_locator_repair_audit ALTER COLUMN actor_name SET DEFAULT 'System';
ALTER TABLE ai_system_configs ALTER COLUMN actor_id SET DEFAULT 'system';
ALTER TABLE ai_system_configs ALTER COLUMN actor_name SET DEFAULT 'System';
ALTER TABLE ai_project_configs ALTER COLUMN actor_id SET DEFAULT 'system';
ALTER TABLE ai_project_configs ALTER COLUMN actor_name SET DEFAULT 'System';
ALTER TABLE ai_rules ALTER COLUMN actor_id SET DEFAULT 'system';
ALTER TABLE ai_rules ALTER COLUMN actor_name SET DEFAULT 'System';
ALTER TABLE ai_skills ALTER COLUMN actor_id SET DEFAULT 'system';
ALTER TABLE ai_skills ALTER COLUMN actor_name SET DEFAULT 'System';
ALTER TABLE ai_skill_overrides ALTER COLUMN actor_id SET DEFAULT 'system';
ALTER TABLE ai_skill_overrides ALTER COLUMN actor_name SET DEFAULT 'System';
ALTER TABLE ai_mcp_servers ALTER COLUMN actor_id SET DEFAULT 'system';
ALTER TABLE ai_mcp_servers ALTER COLUMN actor_name SET DEFAULT 'System';
ALTER TABLE ai_source_documents ALTER COLUMN actor_id SET DEFAULT 'system';
ALTER TABLE ai_source_documents ALTER COLUMN actor_name SET DEFAULT 'System';
ALTER TABLE ai_analysis_runs ALTER COLUMN actor_id SET DEFAULT 'system';
ALTER TABLE ai_analysis_runs ALTER COLUMN actor_name SET DEFAULT 'System';
ALTER TABLE ai_draft_test_points ALTER COLUMN actor_id SET DEFAULT 'system';
ALTER TABLE ai_draft_test_points ALTER COLUMN actor_name SET DEFAULT 'System';
ALTER TABLE ai_review_actions ALTER COLUMN actor_id SET DEFAULT 'system';
ALTER TABLE ai_review_actions ALTER COLUMN actor_name SET DEFAULT 'System';
ALTER TABLE ai_call_records ALTER COLUMN actor_id SET DEFAULT 'system';
ALTER TABLE ai_call_records ALTER COLUMN actor_name SET DEFAULT 'System';
ALTER TABLE ai_tool_call_records ALTER COLUMN actor_id SET DEFAULT 'system';
ALTER TABLE ai_skill_versions ALTER COLUMN actor_id SET DEFAULT 'system';
ALTER TABLE ai_skill_versions ALTER COLUMN actor_name SET DEFAULT 'System';
ALTER TABLE ai_visual_assets ALTER COLUMN actor_id SET DEFAULT 'system';
ALTER TABLE ai_visual_assets ALTER COLUMN actor_name SET DEFAULT 'System';
ALTER TABLE ai_figma_files ALTER COLUMN actor_id SET DEFAULT 'system';
ALTER TABLE ai_figma_files ALTER COLUMN actor_name SET DEFAULT 'System';
ALTER TABLE ai_agent_definitions ALTER COLUMN actor_id SET DEFAULT 'system';
ALTER TABLE ai_agent_definitions ALTER COLUMN actor_name SET DEFAULT 'System';
ALTER TABLE ai_run_policies ALTER COLUMN actor_id SET DEFAULT 'system';
ALTER TABLE ai_run_policies ALTER COLUMN actor_name SET DEFAULT 'System';
