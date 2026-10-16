ALTER TABLE ai_system_configs
    ADD COLUMN vision_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE ai_system_configs
    ADD COLUMN max_image_bytes BIGINT NOT NULL DEFAULT 10485760;

ALTER TABLE ai_system_configs
    ADD COLUMN allowed_image_types_json TEXT;

CREATE TABLE ai_visual_assets (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    asset_id VARCHAR(128) NOT NULL,
    project_id BIGINT NOT NULL,
    source_document_id BIGINT,
    file_name VARCHAR(512) NOT NULL,
    content_type VARCHAR(128) NOT NULL DEFAULT '',
    content_hash VARCHAR(128) NOT NULL,
    storage_location VARCHAR(1024) NOT NULL DEFAULT '',
    byte_size BIGINT NOT NULL DEFAULT 0,
    width INT NOT NULL DEFAULT 0,
    height INT NOT NULL DEFAULT 0,
    content_base64 TEXT,
    actor_id VARCHAR(128) NOT NULL DEFAULT 'system-dev',
    actor_name VARCHAR(255) NOT NULL DEFAULT 'Development User',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_visual_asset_id UNIQUE (asset_id),
    CONSTRAINT fk_ai_visual_asset_project FOREIGN KEY (project_id) REFERENCES projects (id)
);

CREATE INDEX idx_ai_visual_assets_project_created ON ai_visual_assets(project_id, created_at);
CREATE INDEX idx_ai_visual_assets_hash ON ai_visual_assets(content_hash);

CREATE TABLE ai_visual_analysis_results (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    analysis_id VARCHAR(128) NOT NULL,
    asset_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    ocr_status VARCHAR(32) NOT NULL DEFAULT 'unavailable',
    layout_json TEXT,
    components_json TEXT,
    styles_json TEXT,
    interactions_json TEXT,
    states_json TEXT,
    risks_json TEXT,
    suggested_test_points_json TEXT,
    unclear_questions_json TEXT,
    warnings_json TEXT,
    raw_output_json TEXT,
    request_id VARCHAR(255) NOT NULL DEFAULT '',
    token_usage_input INT NOT NULL DEFAULT 0,
    token_usage_output INT NOT NULL DEFAULT 0,
    error_message VARCHAR(1024) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_visual_analysis_id UNIQUE (analysis_id),
    CONSTRAINT fk_ai_visual_analysis_asset FOREIGN KEY (asset_id) REFERENCES ai_visual_assets (id)
);

CREATE INDEX idx_ai_visual_analysis_asset_created ON ai_visual_analysis_results(asset_id, created_at);

ALTER TABLE ai_source_documents
    ADD COLUMN visual_asset_id BIGINT;

ALTER TABLE ai_source_documents
    ADD CONSTRAINT fk_ai_source_document_visual_asset FOREIGN KEY (visual_asset_id) REFERENCES ai_visual_assets (id);

CREATE TABLE ai_visual_regions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    region_id VARCHAR(128) NOT NULL,
    asset_id BIGINT NOT NULL,
    region_type VARCHAR(64) NOT NULL DEFAULT 'unknown',
    x INT NOT NULL DEFAULT 0,
    y INT NOT NULL DEFAULT 0,
    width INT NOT NULL DEFAULT 0,
    height INT NOT NULL DEFAULT 0,
    text TEXT,
    component_hint VARCHAR(1024) NOT NULL DEFAULT '',
    style_hint_json TEXT,
    confidence DOUBLE NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_visual_region_id UNIQUE (region_id),
    CONSTRAINT fk_ai_visual_region_asset FOREIGN KEY (asset_id) REFERENCES ai_visual_assets (id)
);

CREATE INDEX idx_ai_visual_regions_asset_type ON ai_visual_regions(asset_id, region_type);

ALTER TABLE ai_draft_test_points
    ADD COLUMN source_region_id BIGINT;

ALTER TABLE ai_draft_test_points
    ADD CONSTRAINT fk_ai_draft_test_point_visual_region FOREIGN KEY (source_region_id) REFERENCES ai_visual_regions (id);
