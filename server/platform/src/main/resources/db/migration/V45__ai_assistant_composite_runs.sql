-- T12：组合任务（依赖 DAG）——首个案例 Bug→日志→回归。
-- 每个节点是独立的助手运行：各自状态、各自产物 revision、各自人工评审；
-- 组合运行持有共享的 evidence manifest（依赖节点复用同一清单）。
CREATE TABLE ai_assistant_composite_runs (
    composite_id VARCHAR(128) PRIMARY KEY,
    project_id BIGINT NOT NULL,
    actor_id VARCHAR(128) NOT NULL DEFAULT '',
    template VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    manifest_json MEDIUMTEXT NULL,
    inputs_json MEDIUMTEXT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE ai_assistant_composite_nodes (
    node_id VARCHAR(128) PRIMARY KEY,
    composite_id VARCHAR(128) NOT NULL,
    position INT NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    depends_on_json VARCHAR(512) NOT NULL DEFAULT '[]',
    status VARCHAR(32) NOT NULL,
    run_id VARCHAR(128) NOT NULL DEFAULT '',
    artifact_id VARCHAR(160) NOT NULL DEFAULT '',
    skip_reason VARCHAR(512) NOT NULL DEFAULT '',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_ai_assistant_composite_node UNIQUE (composite_id, node_id)
);

CREATE INDEX idx_ai_assistant_composite_nodes ON ai_assistant_composite_nodes (composite_id, position);
CREATE INDEX idx_ai_assistant_composite_status ON ai_assistant_composite_runs (project_id, status, created_at);
