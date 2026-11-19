-- A2：operation 记录它执行的是哪一份确认草稿（审计可回溯；也用于判断草稿是否已被执行）。
ALTER TABLE assistant_operation_ledger ADD COLUMN draft_id VARCHAR(36) NULL;
CREATE INDEX idx_assistant_operation_draft ON assistant_operation_ledger(draft_id);
