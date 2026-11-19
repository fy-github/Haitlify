-- 资源审计：记录收尾时间（谁在何时把资源判成 CLEANED/UNRECOVERABLE）。
ALTER TABLE assistant_resource_ledger ADD COLUMN cleaned_at TIMESTAMP NULL;
