CREATE TABLE ai_web_case_quality_ratings (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    rating_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    project_id BIGINT NOT NULL,
    overall_score INT NOT NULL DEFAULT 0,
    dimension_scores_json TEXT NOT NULL DEFAULT ('{}'),
    comment TEXT NOT NULL DEFAULT (''),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_web_case_quality_rating_id UNIQUE (rating_id),
    CONSTRAINT uk_ai_web_case_quality_rating_run UNIQUE (run_id)
);

CREATE INDEX idx_ai_web_case_quality_rating_project
    ON ai_web_case_quality_ratings(project_id, created_at);
