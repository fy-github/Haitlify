-- 从等待用户恢复到 PENDING 的 operation 必须重新派发，不能拿旧 attempt 的 runId 续跑。
-- 等待字段在恢复时会被清空，所以需要一个独立的事实标记；真正派发（PENDING→DISPATCHED）时清掉它。
ALTER TABLE assistant_operation_ledger ADD COLUMN awaiting_redispatch BOOLEAN NOT NULL DEFAULT FALSE;
