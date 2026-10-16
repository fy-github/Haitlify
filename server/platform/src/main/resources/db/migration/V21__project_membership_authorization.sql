-- Explicit project tenancy membership for non-admin platform users.
CREATE TABLE project_members (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    username VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_project_members_project_username UNIQUE (project_id, username),
    CONSTRAINT fk_project_members_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_project_members_user FOREIGN KEY (username) REFERENCES platform_users(username)
);
CREATE INDEX idx_project_members_username ON project_members(username, project_id);
