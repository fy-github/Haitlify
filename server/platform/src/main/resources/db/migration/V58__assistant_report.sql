-- A1 报告：按 operation 追加修订（只追加，旧修订不改写）。
-- 报告正文与执行事实分离：执行事实在 assistant_operation_ledger / assistant_attempt_ledger，
-- 报告只是「某一时刻的事实快照 + 证据引用」，人工接受/驳回落在 report_state。
CREATE TABLE assistant_report (
    report_id CHAR(36) NOT NULL PRIMARY KEY,
    operation_id CHAR(36) NOT NULL,
    revision INT NOT NULL,
    body_json TEXT NOT NULL,
    generated_by BIGINT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_assistant_report_rev UNIQUE (operation_id, revision)
);

CREATE INDEX idx_assistant_report_op ON assistant_report (operation_id, revision);
