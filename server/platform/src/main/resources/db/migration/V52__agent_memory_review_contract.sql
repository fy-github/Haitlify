-- 全链路人工确认 Exit Gate（知识经验/AgentMemory 契约）：修订号。
ALTER TABLE ai_agent_memories ADD COLUMN revision INT NOT NULL DEFAULT 1;
