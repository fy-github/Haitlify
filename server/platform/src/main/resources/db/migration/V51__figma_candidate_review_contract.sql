-- 全链路人工确认 Exit Gate（Figma element candidate 契约）：修订号 + 人工动作持久审计。
ALTER TABLE ai_figma_element_candidates ADD COLUMN revision INT NOT NULL DEFAULT 1;
ALTER TABLE ai_figma_element_candidates ADD COLUMN reviewed_by VARCHAR(128) NOT NULL DEFAULT '';
ALTER TABLE ai_figma_element_candidates ADD COLUMN reviewed_at TIMESTAMP NULL;
