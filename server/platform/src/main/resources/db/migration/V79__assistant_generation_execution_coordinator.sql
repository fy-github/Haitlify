-- Internal coordinator contract only; no dispatch, token persistence or physical cleanup inference.
CREATE TABLE assistant_generation_executions (
    generation_id VARCHAR(36) NOT NULL PRIMARY KEY,
    binding_id VARCHAR(36) NOT NULL UNIQUE,
    worker_unit_id VARCHAR(128) NOT NULL,
    lease_generation BIGINT NOT NULL,
    image_digest VARCHAR(80) NOT NULL,
    policy_version VARCHAR(128) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    incarnation VARCHAR(36) NOT NULL UNIQUE,
    cleanup_epoch VARCHAR(36) NULL,
    status VARCHAR(32) NOT NULL,
    CONSTRAINT chk_generation_execution_status CHECK (
        status IN ('CONFIGURING', 'CONFIGURE_SENT', 'GRANT_ISSUED', 'AUTHORIZED',
                   'REVOKING', 'QUARANTINED', 'CLEANED', 'UNKNOWN')
    )
);
