-- V75/V76 are immutable. Retire logical reservations, NEVER physical cleanup or generation work.
-- Run Flyway with execution writers quiesced (the normal schema-upgrade maintenance boundary).
-- Identification is structural, not timestamp/expiry based:
-- V76 reset an existing unit to inactive/dirty, NULL owner/epoch and lease counter zero.
-- V76 registerUnit creates an epoch; initial acknowledgement leaves no binding history;
-- allocation increments the counter before commit, and cleanup/revoke NEVER reset it.
-- Therefore a positive historical lease on a zero-counter unit cannot be produced by the
-- supported V76 lifecycle. Later disabled/cleaned V76 units have a positive counter.
-- Missing/inconsistent evidence is intentionally not guessed. Operator-written/imported
-- rows that mimic this signature cannot be distinguished; audit provenance before upgrade
-- in such installations. No expiry inference, model replay, dispatch, or generation update.

CREATE TABLE IF NOT EXISTS assistant_execution_legacy_retirements (
    binding_id VARCHAR(36) NOT NULL PRIMARY KEY,
    generation_id VARCHAR(36) NOT NULL,
    worker_unit_id VARCHAR(128) NOT NULL,
    lease_generation BIGINT NOT NULL,
    original_state VARCHAR(16) NOT NULL,
    original_active_generation_key VARCHAR(36) NULL,
    original_active_worker_unit_key VARCHAR(128) NULL,
    original_revoked_at TIMESTAMP(6) NULL,
    original_generation_state VARCHAR(32) NULL,
    retired_at TIMESTAMP(6) NOT NULL,
    reason VARCHAR(128) NOT NULL
);

-- Durable target set also preserves original reservation/revocation evidence on retry.
INSERT INTO assistant_execution_legacy_retirements
    (binding_id,generation_id,worker_unit_id,lease_generation,original_state,
     original_active_generation_key,original_active_worker_unit_key,original_revoked_at,
     original_generation_state,retired_at,reason)
SELECT b.id,b.generation_id,b.worker_unit_id,b.lease_generation,b.state,
       b.active_generation_key,b.active_worker_unit_key,b.revoked_at,g.state,
       CURRENT_TIMESTAMP(6),'V76_QUARANTINED_LEGACY_LEASE_NO_CLEANUP_INFERRED'
FROM assistant_execution_bindings b
JOIN assistant_execution_worker_units u ON u.unit_id=b.worker_unit_id
JOIN assistant_generations g ON g.id=b.generation_id
WHERE u.active=false AND u.cleanup_confirmed=false
  AND u.current_binding_id IS NULL AND u.current_lease_generation=0 AND u.cleanup_epoch IS NULL
  AND b.lease_generation>0
  AND (b.active_generation_key IS NULL OR b.active_generation_key=b.generation_id)
  AND (b.active_worker_unit_key IS NULL OR b.active_worker_unit_key=b.worker_unit_id)
  AND NOT EXISTS (SELECT 1 FROM assistant_execution_legacy_retirements r WHERE r.binding_id=b.id);

UPDATE assistant_execution_grants SET revoked=true
WHERE binding_id IN (SELECT binding_id FROM assistant_execution_legacy_retirements);

-- REVOKED means authorization retired, not execution success, rollback, or cleanup.
-- Historical CLEANED/REVOKED states and existing revocation timestamps remain intact.
UPDATE assistant_execution_bindings
SET state=CASE WHEN state='ACTIVE' THEN 'REVOKED' ELSE state END,
    active_generation_key=NULL,active_worker_unit_key=NULL,
    revoked_at=CASE WHEN EXISTS (SELECT 1 FROM assistant_execution_legacy_retirements r
                                 WHERE r.binding_id=assistant_execution_bindings.id AND r.original_state='ACTIVE')
                    THEN COALESCE(revoked_at,(SELECT r.retired_at FROM assistant_execution_legacy_retirements r
                                             WHERE r.binding_id=assistant_execution_bindings.id))
                    ELSE revoked_at END
WHERE id IN (SELECT binding_id FROM assistant_execution_legacy_retirements);
-- Old unit rows remain inactive/dirty with no usable cleanup epoch forever.
-- A caller may explicitly allocate a NEW unit to a still-WAITING_WORKER generation;
-- RUNNING/READY/STOPPED/etc remain unchanged and are NOT made allocatable here.
