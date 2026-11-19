-- A0 §5.7 受理账本（计划 §5.4）：五张表 + 三条唯一约束。
-- 设计见 docs/superpowers/a0/2026-10-19-a0-request-ledger-design.md（DDL 已在 mysql:8.4 实跑验证）。
-- 类型按仓库既有可移植写法（VARCHAR 存枚举 + CHECK，不用 MySQL ENUM/JSON），H2(MySQL 模式) 与 MySQL 8.4 都能建。

-- 受理记录：一次用户提交一行；先落库再派发。
CREATE TABLE assistant_request_ledger (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    project_id BIGINT NULL,
    request_id CHAR(36) NOT NULL,
    input_digest CHAR(64) NOT NULL,
    session_binding CHAR(36) NULL,
    admission_state VARCHAR(16) NOT NULL,
    operation_id CHAR(36) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_assistant_request UNIQUE (tenant_id, request_id),
    CONSTRAINT ck_assistant_request_state CHECK (admission_state IN ('ADMITTED', 'DISPATCHED', 'UNKNOWN', 'CLOSED'))
);

CREATE INDEX idx_assistant_request_operation ON assistant_request_ledger (operation_id);

-- 业务操作：一次「已授权动作」一行；重复执行必须新建 operation（新授权）。
CREATE TABLE assistant_operation_ledger (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    operation_id CHAR(36) NOT NULL,
    tenant_id BIGINT NOT NULL,
    action_code VARCHAR(64) NOT NULL,
    target_ref VARCHAR(255) NOT NULL,
    target_revision VARCHAR(64) NOT NULL,
    input_digest CHAR(64) NOT NULL,
    idempotency_key CHAR(64) NOT NULL,
    execution_state VARCHAR(16) NOT NULL,
    report_state VARCHAR(16) NOT NULL DEFAULT 'NONE',
    dispatch_generation INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_assistant_operation UNIQUE (operation_id),
    CONSTRAINT uk_assistant_operation_idem UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT ck_assistant_operation_exec CHECK (execution_state IN ('PENDING', 'DISPATCHED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN')),
    CONSTRAINT ck_assistant_operation_report CHECK (report_state IN ('NONE', 'AWAITING', 'ACCEPTED', 'REJECTED'))
);

-- 派发尝试：一次派发一行；迟到回执按 generation 归并，不污染新代次。
CREATE TABLE assistant_attempt_ledger (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    attempt_id CHAR(36) NOT NULL,
    operation_id CHAR(36) NOT NULL,
    generation INT NOT NULL,
    hermes_stored_session_id VARCHAR(64) NULL,
    hermes_turn_ref VARCHAR(128) NULL,
    runner_run_id CHAR(36) NULL,
    dispatched_at TIMESTAMP(3) NULL,
    ended_at TIMESTAMP(3) NULL,
    outcome VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    CONSTRAINT uk_assistant_attempt UNIQUE (attempt_id),
    CONSTRAINT uk_assistant_attempt_gen UNIQUE (operation_id, generation),
    CONSTRAINT ck_assistant_attempt_outcome CHECK (outcome IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN'))
);

CREATE INDEX idx_assistant_attempt_session ON assistant_attempt_ledger (hermes_stored_session_id);

-- 资源账本：分配即登记，必须有人负责回收（UNRECOVERABLE 不得显示为闭环成功）。
CREATE TABLE assistant_resource_ledger (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    resource_id CHAR(36) NOT NULL,
    operation_id CHAR(36) NOT NULL,
    resource_kind VARCHAR(24) NOT NULL,
    owner_actor_id BIGINT NOT NULL,
    cleanup_executor VARCHAR(24) NOT NULL,
    cleanup_state VARCHAR(24) NOT NULL,
    deadline_at TIMESTAMP(3) NOT NULL,
    attempts TINYINT NOT NULL DEFAULT 0,
    evidence_json TEXT NULL,
    CONSTRAINT uk_assistant_resource UNIQUE (resource_id),
    CONSTRAINT ck_assistant_resource_kind CHECK (resource_kind IN ('BROWSER', 'ACCOUNT_LEASE', 'TEST_DATA', 'TEMP_ARTIFACT')),
    CONSTRAINT ck_assistant_resource_executor CHECK (cleanup_executor IN ('RUNNER_FINALLY', 'HAITLIFY_WORKER', 'MANUAL')),
    CONSTRAINT ck_assistant_resource_state CHECK (cleanup_state IN ('ALLOCATED', 'CLEANING', 'CLEANED', 'CLEANUP_FAILED', 'MANUAL_REQUIRED', 'UNRECOVERABLE'))
);

CREATE INDEX idx_assistant_resource_deadline ON assistant_resource_ledger (cleanup_state, deadline_at);

-- 对账事件：只追加，不改写历史。
CREATE TABLE assistant_reconciliation_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    operation_id CHAR(36) NOT NULL,
    attempt_id CHAR(36) NULL,
    event_type VARCHAR(24) NOT NULL,
    actor_id BIGINT NULL,
    detail_json TEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT ck_assistant_reconcile_type CHECK (event_type IN ('LATE_RECEIPT', 'CANCEL_RACE', 'UNKNOWN_RAISED', 'UNKNOWN_RESOLVED', 'MANUAL_DISPOSITION', 'GENERATION_SUPERSEDED'))
);

CREATE INDEX idx_assistant_reconcile_op ON assistant_reconciliation_event (operation_id, created_at);
