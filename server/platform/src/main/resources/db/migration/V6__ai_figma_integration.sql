CREATE TABLE ai_figma_files (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    figma_file_id VARCHAR(128) NOT NULL,
    project_id BIGINT NOT NULL,
    file_key VARCHAR(255) NOT NULL,
    selected_node_id VARCHAR(255) NOT NULL DEFAULT '',
    file_name VARCHAR(512) NOT NULL DEFAULT '',
    snapshot_hash VARCHAR(128) NOT NULL DEFAULT '',
    raw_summary_json TEXT,
    actor_id VARCHAR(128) NOT NULL DEFAULT 'system-dev',
    actor_name VARCHAR(255) NOT NULL DEFAULT 'Development User',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_figma_file_id UNIQUE (figma_file_id),
    CONSTRAINT uk_ai_figma_file_project_node UNIQUE (project_id, file_key, selected_node_id),
    CONSTRAINT fk_ai_figma_file_project FOREIGN KEY (project_id) REFERENCES projects (id)
);

CREATE TABLE ai_figma_nodes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    figma_node_id VARCHAR(128) NOT NULL,
    figma_file_pk BIGINT NOT NULL,
    node_id VARCHAR(255) NOT NULL,
    node_type VARCHAR(64) NOT NULL DEFAULT '',
    node_name VARCHAR(512) NOT NULL DEFAULT '',
    parent_node_id VARCHAR(255) NOT NULL DEFAULT '',
    component_key VARCHAR(255) NOT NULL DEFAULT '',
    component_name VARCHAR(512) NOT NULL DEFAULT '',
    variant_properties_json TEXT,
    style_json TEXT,
    token_json TEXT,
    layout_mode VARCHAR(64) NOT NULL DEFAULT '',
    constraints_json TEXT,
    absolute_bounds_json TEXT,
    prototype_link_json TEXT,
    source_snapshot_hash VARCHAR(128) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_figma_node_id UNIQUE (figma_node_id),
    CONSTRAINT uk_ai_figma_node_file_node UNIQUE (figma_file_pk, node_id),
    CONSTRAINT fk_ai_figma_node_file FOREIGN KEY (figma_file_pk) REFERENCES ai_figma_files (id)
);

CREATE TABLE ai_figma_components (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    figma_file_pk BIGINT NOT NULL,
    node_id VARCHAR(255) NOT NULL DEFAULT '',
    component_key VARCHAR(255) NOT NULL DEFAULT '',
    component_name VARCHAR(512) NOT NULL DEFAULT '',
    metadata_json TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_ai_figma_component_file FOREIGN KEY (figma_file_pk) REFERENCES ai_figma_files (id)
);

CREATE TABLE ai_figma_variants (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    figma_file_pk BIGINT NOT NULL,
    node_id VARCHAR(255) NOT NULL DEFAULT '',
    component_key VARCHAR(255) NOT NULL DEFAULT '',
    variant_properties_json TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_ai_figma_variant_file FOREIGN KEY (figma_file_pk) REFERENCES ai_figma_files (id)
);

CREATE TABLE ai_figma_styles (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    figma_file_pk BIGINT NOT NULL,
    node_id VARCHAR(255) NOT NULL DEFAULT '',
    style_key VARCHAR(255) NOT NULL DEFAULT '',
    style_json TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_ai_figma_style_file FOREIGN KEY (figma_file_pk) REFERENCES ai_figma_files (id)
);

CREATE TABLE ai_figma_tokens (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    figma_file_pk BIGINT NOT NULL,
    token_id VARCHAR(128) NOT NULL,
    token_category VARCHAR(64) NOT NULL DEFAULT '',
    token_name VARCHAR(512) NOT NULL DEFAULT '',
    token_json TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_figma_token_id UNIQUE (token_id),
    CONSTRAINT fk_ai_figma_token_file FOREIGN KEY (figma_file_pk) REFERENCES ai_figma_files (id)
);

CREATE TABLE ai_figma_prototype_links (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    figma_file_pk BIGINT NOT NULL,
    prototype_link_id VARCHAR(128) NOT NULL,
    source_node_id VARCHAR(255) NOT NULL DEFAULT '',
    target_node_id VARCHAR(255) NOT NULL DEFAULT '',
    prototype_link_json TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_figma_prototype_link_id UNIQUE (prototype_link_id),
    CONSTRAINT fk_ai_figma_prototype_file FOREIGN KEY (figma_file_pk) REFERENCES ai_figma_files (id)
);

CREATE TABLE ai_figma_change_sets (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    change_set_id VARCHAR(128) NOT NULL,
    figma_file_pk BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'created',
    changes_json TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_figma_change_set_id UNIQUE (change_set_id),
    CONSTRAINT fk_ai_figma_change_set_file FOREIGN KEY (figma_file_pk) REFERENCES ai_figma_files (id)
);

CREATE TABLE ai_figma_element_candidates (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    candidate_id VARCHAR(128) NOT NULL,
    figma_file_pk BIGINT NOT NULL,
    component_key VARCHAR(255) NOT NULL DEFAULT '',
    component_name VARCHAR(512) NOT NULL DEFAULT '',
    node_id VARCHAR(255) NOT NULL DEFAULT '',
    node_name VARCHAR(512) NOT NULL DEFAULT '',
    role VARCHAR(64) NOT NULL DEFAULT '',
    suggested_locator_name VARCHAR(512) NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    evidence_json TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_figma_element_candidate_id UNIQUE (candidate_id),
    CONSTRAINT fk_ai_figma_element_candidate_file FOREIGN KEY (figma_file_pk) REFERENCES ai_figma_files (id)
);

CREATE INDEX idx_ai_figma_nodes_file_parent ON ai_figma_nodes(figma_file_pk, parent_node_id);
CREATE INDEX idx_ai_figma_candidates_file_status ON ai_figma_element_candidates(figma_file_pk, status);
