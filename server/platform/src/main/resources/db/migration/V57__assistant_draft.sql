-- A1 前半段：用例草稿与人工确认（计划 §4.2 确认节点、§9.2「用户确认」）。
-- 草稿必须经人工确认才能进入编译与执行；确认是一次显式动作，记录确认人与时间。
CREATE TABLE assistant_draft (
    draft_id CHAR(36) NOT NULL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    project_id BIGINT NULL,
    requirement_ref VARCHAR(255) NOT NULL,
    requirement_digest CHAR(64) NOT NULL,
    draft_json TEXT NOT NULL,
    state VARCHAR(16) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    confirmed_by BIGINT NULL,
    confirmed_at TIMESTAMP(3) NULL,
    CONSTRAINT ck_assistant_draft_state CHECK (state IN ('DRAFT', 'CONFIRMED', 'REJECTED'))
);

CREATE INDEX idx_assistant_draft_scope ON assistant_draft (tenant_id, project_id, state);
