-- V75 is immutable. A current owner and one-use cleanup epoch fence every reuse.
ALTER TABLE assistant_execution_worker_units ADD COLUMN current_binding_id VARCHAR(36) NULL;
ALTER TABLE assistant_execution_worker_units ADD COLUMN current_lease_generation BIGINT NOT NULL DEFAULT 0;
ALTER TABLE assistant_execution_worker_units ADD COLUMN cleanup_epoch VARCHAR(36) NULL;
-- Existing V75 registration/cleanup flags are not fenced evidence. Quarantine rather than infer safety.
-- Retire these units and provision fresh unit IDs through the trusted cleanup acknowledgement path.
UPDATE assistant_execution_worker_units SET active=false, cleanup_confirmed=false;
