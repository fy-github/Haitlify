-- A2：资产修改/重新确认 —— 草稿可以有修订链，旧修订被取代后不能再执行。
ALTER TABLE assistant_draft ADD COLUMN revision INT NOT NULL DEFAULT 1;
ALTER TABLE assistant_draft ADD COLUMN superseded_by VARCHAR(36) NULL;
ALTER TABLE assistant_draft ADD COLUMN root_draft_id VARCHAR(36) NULL;

ALTER TABLE assistant_draft DROP CONSTRAINT ck_assistant_draft_state;
ALTER TABLE assistant_draft ADD CONSTRAINT ck_assistant_draft_state
    CHECK (state IN ('DRAFT', 'CONFIRMED', 'REJECTED', 'SUPERSEDED'));

UPDATE assistant_draft SET root_draft_id = draft_id WHERE root_draft_id IS NULL;
