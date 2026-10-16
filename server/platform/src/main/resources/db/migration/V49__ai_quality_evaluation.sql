-- T-Review-5：Gold Set 质量评测持久化（运行 / 案例结果 / 人工标注 / 分歧裁决）。
-- 规则评分与模型评分分列；人评恒由人工写入，框架不代填（human 状态在案例行内单列）。
CREATE TABLE ai_quality_evaluation_runs (
    evaluation_id VARCHAR(128) NOT NULL PRIMARY KEY,
    package_version VARCHAR(64) NOT NULL,
    profile_ref VARCHAR(128) NOT NULL,
    skill_ref VARCHAR(128) NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    repeats INT NOT NULL,
    versions_json TEXT NOT NULL,
    metrics_json TEXT NOT NULL,
    stability_json TEXT NOT NULL,
    thresholds_json TEXT NOT NULL,
    gate_json TEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE INDEX idx_ai_quality_runs_task ON ai_quality_evaluation_runs (task_type, created_at);

CREATE TABLE ai_quality_evaluation_cases (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    evaluation_id VARCHAR(128) NOT NULL,
    case_id VARCHAR(160) NOT NULL,
    run_index INT NOT NULL,
    category VARCHAR(32) NOT NULL,
    schema_valid BOOLEAN NOT NULL,
    citations_valid BOOLEAN NOT NULL,
    coverage DOUBLE NOT NULL,
    forbidden_hits_json TEXT NOT NULL,
    attempt_succeeded BOOLEAN NOT NULL,
    output_sha256 VARCHAR(64),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_ai_quality_case_run UNIQUE (evaluation_id, case_id, run_index)
);

CREATE INDEX idx_ai_quality_cases_eval ON ai_quality_evaluation_cases (evaluation_id, case_id);

-- 人工标注：标注者只看盲化载荷（无档案名）；人评与规则分数分开存储。
CREATE TABLE ai_quality_annotations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    evaluation_id VARCHAR(128) NOT NULL,
    case_id VARCHAR(160) NOT NULL,
    annotator_ref VARCHAR(128) NOT NULL,
    human_decision VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_ai_quality_annotation UNIQUE (evaluation_id, case_id, annotator_ref)
);

-- 分歧裁决：两名标注不一致时由第三方裁决（adjudicator）。
CREATE TABLE ai_quality_adjudications (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    evaluation_id VARCHAR(128) NOT NULL,
    case_id VARCHAR(160) NOT NULL,
    adjudicator_ref VARCHAR(128) NOT NULL,
    final_decision VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
