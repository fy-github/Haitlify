-- 客观质量基线:对每个已完成的 web_case run,基于其已交付(accepted)用例自动计算六维客观分(1-10,
-- 与人工评分 ai_web_case_quality_ratings 同量纲同对象),供洞察在无人工评分时形成自动基线。
-- 人工评分存在时以人工为准并覆盖客观分(见 WebCaseQualityInsightService 合并逻辑)。
CREATE TABLE ai_web_case_objective_scores (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    score_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    project_id BIGINT NOT NULL,
    overall_score INT NOT NULL DEFAULT 0,
    dimension_scores_json TEXT NOT NULL DEFAULT ('{}'),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_web_case_objective_score_id UNIQUE (score_id),
    CONSTRAINT uk_ai_web_case_objective_score_run UNIQUE (run_id)
);

CREATE INDEX idx_ai_web_case_objective_score_project
    ON ai_web_case_objective_scores(project_id, created_at);
