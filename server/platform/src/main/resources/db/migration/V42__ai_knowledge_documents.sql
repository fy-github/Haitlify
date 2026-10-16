-- T08: project-scoped knowledge documents for requirement/evidence retrieval.
-- Only rows in PUBLISHED status are retrievable; drafts and archives must never
-- surface in citations. source_ref links back to the originating evidence
-- document for location tracing.
CREATE TABLE ai_knowledge_documents (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    knowledge_id VARCHAR(128) NOT NULL,
    project_id BIGINT NOT NULL,
    title VARCHAR(512) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version INT NOT NULL DEFAULT 1,
    content TEXT NOT NULL,
    source_ref VARCHAR(255) NOT NULL DEFAULT '',
    published_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_ai_knowledge_documents UNIQUE (knowledge_id),
    CONSTRAINT fk_ai_knowledge_documents_project FOREIGN KEY (project_id) REFERENCES projects (id)
);

CREATE INDEX idx_ai_knowledge_documents_scope
    ON ai_knowledge_documents (project_id, status);
