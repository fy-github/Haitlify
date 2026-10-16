CREATE TABLE case_template_configs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    kind VARCHAR(32) NOT NULL,
    schema_version INT NOT NULL,
    interface_fields_json TEXT NOT NULL,
    case_types_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_case_template_project_kind UNIQUE (project_id, kind),
    CONSTRAINT fk_case_template_project FOREIGN KEY (project_id) REFERENCES projects (id)
);

CREATE TABLE interface_cases (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    library_id VARCHAR(512) NOT NULL,
    case_id VARCHAR(64) NOT NULL,
    module_name VARCHAR(255) NOT NULL DEFAULT '',
    case_type VARCHAR(128) NOT NULL DEFAULT '',
    title VARCHAR(512) NOT NULL DEFAULT '',
    source_file VARCHAR(512) NOT NULL DEFAULT '',
    source_operation_id VARCHAR(255) NOT NULL DEFAULT '',
    run_order INT NOT NULL DEFAULT 0,
    row_json TEXT NOT NULL,
    metadata_json TEXT NOT NULL,
    saved_at VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_interface_case_project_library UNIQUE (project_id, library_id),
    CONSTRAINT fk_interface_case_project FOREIGN KEY (project_id) REFERENCES projects (id)
);
