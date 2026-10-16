-- 项 2：report_draft 的确定性事实（passRate 等）随产物落库，供批准/修改/导出全路径复检。
ALTER TABLE ai_assistant_artifacts ADD COLUMN deterministic_facts_json TEXT NULL;
