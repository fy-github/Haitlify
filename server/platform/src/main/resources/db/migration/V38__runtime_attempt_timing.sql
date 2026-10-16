alter table ai_runtime_vertical_attempts
    add column duration_ms BIGINT NULL;

alter table ai_runtime_vertical_attempts
    add column ttfb_ms BIGINT NULL;
