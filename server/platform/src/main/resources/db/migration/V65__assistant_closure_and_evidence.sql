-- 未结事项不能被记成「完整闭环」：请求收尾区分 CLOSED / CLOSED_WITH_UNRESOLVED。
ALTER TABLE assistant_request_ledger DROP CONSTRAINT ck_assistant_request_state;
ALTER TABLE assistant_request_ledger ADD CONSTRAINT ck_assistant_request_state
    CHECK (admission_state IN ('ADMITTED', 'DISPATCHED', 'UNKNOWN', 'CLOSED', 'CLOSED_WITH_UNRESOLVED'));

-- 报告要能自证：Runner 的 summary 与断言计数必须落库。
ALTER TABLE assistant_attempt_ledger ADD COLUMN run_total INT NULL;
ALTER TABLE assistant_attempt_ledger ADD COLUMN run_passed INT NULL;
ALTER TABLE assistant_attempt_ledger ADD COLUMN run_failed INT NULL;
ALTER TABLE assistant_attempt_ledger ADD COLUMN run_error INT NULL;

-- 驳回与确认分开留痕，审计不再混淆。
ALTER TABLE assistant_draft ADD COLUMN rejected_by BIGINT NULL;
ALTER TABLE assistant_draft ADD COLUMN rejected_at TIMESTAMP NULL;
