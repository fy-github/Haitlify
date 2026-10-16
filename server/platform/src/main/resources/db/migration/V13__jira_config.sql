CREATE TABLE jira_configs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_key VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    base_url VARCHAR(1024) NOT NULL DEFAULT '',
    auth_mode VARCHAR(64) NOT NULL DEFAULT 'service-account',
    username VARCHAR(255) NOT NULL DEFAULT '',
    token TEXT,
    credential_configured BOOLEAN NOT NULL DEFAULT FALSE,
    project_jql_json TEXT,
    actor_id VARCHAR(128) NOT NULL DEFAULT 'system-dev',
    actor_name VARCHAR(255) NOT NULL DEFAULT 'Development User',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_jira_config_key UNIQUE (config_key)
);
