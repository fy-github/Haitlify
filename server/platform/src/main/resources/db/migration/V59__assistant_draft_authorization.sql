-- A1：把「授权上下文」绑定到草稿，避免执行时用调用方自填的 action/target/revision 冒充已确认授权。
ALTER TABLE assistant_draft ADD COLUMN action_code VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE assistant_draft ADD COLUMN target_ref VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE assistant_draft ADD COLUMN target_revision VARCHAR(64) NOT NULL DEFAULT '';
