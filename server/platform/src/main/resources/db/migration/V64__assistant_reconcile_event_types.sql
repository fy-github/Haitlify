-- 资源收尾与人工处置需要独立事件类型（原来只能落在 MANUAL_DISPOSITION 里，审计不可区分）。
ALTER TABLE assistant_reconciliation_event DROP CONSTRAINT ck_assistant_reconcile_type;
ALTER TABLE assistant_reconciliation_event ADD CONSTRAINT ck_assistant_reconcile_type
    CHECK (event_type IN ('LATE_RECEIPT', 'CANCEL_RACE', 'UNKNOWN_RAISED', 'UNKNOWN_RESOLVED',
                          'MANUAL_DISPOSITION', 'GENERATION_SUPERSEDED',
                          'RESOURCE_CLEANED', 'RESOURCE_UNRECOVERABLE', 'UNKNOWN_REPAIRED', 'MANUAL_RESOLUTION'));
