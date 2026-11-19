-- A2：等待用户（MFA / 人工登录 / 缺输入）与需求图文、表格规模摘要。
-- WAITING_USER 是「非终态但不是执行中」：不能生成报告，也不能被 Runner 回执覆盖；
-- 到期由确定性清扫转 CANCELLED，不交给模型猜。
ALTER TABLE assistant_operation_ledger DROP CONSTRAINT ck_assistant_operation_exec;
ALTER TABLE assistant_operation_ledger ADD CONSTRAINT ck_assistant_operation_exec
    CHECK (execution_state IN ('PENDING', 'DISPATCHED', 'RUNNING', 'WAITING_USER',
                               'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN'));

ALTER TABLE assistant_operation_ledger ADD COLUMN waiting_reason VARCHAR(500) NULL;
ALTER TABLE assistant_operation_ledger ADD COLUMN waiting_requested_at TIMESTAMP(3) NULL;
ALTER TABLE assistant_operation_ledger ADD COLUMN waiting_deadline_at TIMESTAMP(3) NULL;

-- 需求原文不落库（只存 digest），但图文/表格规模是「这次确认覆盖了什么」的事实，
-- 必须随草稿固化，才能在报告里如实统计，而不是事后重算或推断。
ALTER TABLE assistant_draft ADD COLUMN requirement_summary_json TEXT NULL;
