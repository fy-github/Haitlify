-- Trusted cleanup receipt audit only. This table does not perform physical cleanup.
CREATE TABLE assistant_worker_cleanup_receipts (
    receipt_id VARCHAR(36) NOT NULL PRIMARY KEY,
    generation_id VARCHAR(36) NOT NULL,
    binding_id VARCHAR(36) NOT NULL,
    worker_unit_id VARCHAR(128) NOT NULL,
    lease_generation BIGINT NOT NULL,
    cleanup_epoch VARCHAR(36) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    incarnation VARCHAR(36) NOT NULL,
    container_id VARCHAR(255) NOT NULL,
    network_id VARCHAR(255) NOT NULL,
    resource_labels TEXT NOT NULL,
    container_absent BOOLEAN NOT NULL,
    network_absent BOOLEAN NOT NULL,
    evidence_ref VARCHAR(1024) NOT NULL,
    evidence_digest VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL,
    verifier_result VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_cleanup_receipt_ticket UNIQUE (binding_id, lease_generation, cleanup_epoch),
    CONSTRAINT chk_cleanup_receipt_status CHECK (status IN ('QUARANTINED', 'UNKNOWN', 'VERIFIED', 'CLEANED')),
    CONSTRAINT chk_cleanup_verifier_result CHECK (verifier_result IN ('DISABLED', 'VERIFIED', 'UNKNOWN', 'FAILED')),
    CONSTRAINT chk_cleanup_absent CHECK (container_absent = TRUE AND network_absent = TRUE)
);

CREATE INDEX idx_cleanup_receipt_generation ON assistant_worker_cleanup_receipts(generation_id, created_at);
