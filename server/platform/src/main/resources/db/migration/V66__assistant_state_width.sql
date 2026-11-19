-- CLOSED_WITH_UNRESOLVED 是 22 字符，原列宽 16 写不进去（本轮复核发现的 P1）。
ALTER TABLE assistant_request_ledger MODIFY COLUMN admission_state VARCHAR(32) NOT NULL;

-- Runner 的 runId 不保证是 UUID：留足长度，避免换实现就被截断。
ALTER TABLE assistant_attempt_ledger MODIFY COLUMN runner_run_id VARCHAR(128) NULL;

-- 报告正文不再携带决策态快照（决策在 operation/report 轴），旧正文保留不动。
