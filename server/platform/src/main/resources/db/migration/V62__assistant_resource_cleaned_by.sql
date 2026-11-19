-- 资源清理要能追到人：执行器取值扩展为 USER，并单独记录 cleaned_by。
ALTER TABLE assistant_resource_ledger DROP CONSTRAINT ck_assistant_resource_executor;
ALTER TABLE assistant_resource_ledger ADD CONSTRAINT ck_assistant_resource_executor
    CHECK (cleanup_executor IN ('RUNNER_FINALLY', 'HAITLIFY_WORKER', 'MANUAL', 'USER'));
ALTER TABLE assistant_resource_ledger ADD COLUMN cleaned_by BIGINT NULL;
