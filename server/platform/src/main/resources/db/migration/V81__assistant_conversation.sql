-- Assistant conversation ledger (HTTP entry). One row per user-visible conversation plus its turns.
-- Identity (tenant/project/actor) is derived server-side from the authenticated principal and the
-- project row; request_id only makes conversation creation idempotent per actor+project.
-- stored_session_id is the ONLY restart handle: it never leaves the server (never returned by the API).
CREATE TABLE assistant_conversations (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    title VARCHAR(200),
    stored_session_id VARCHAR(128),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_assistant_conversation_request UNIQUE (actor_id, project_id, request_id)
);

CREATE INDEX idx_assistant_conversation_owner ON assistant_conversations(actor_id, project_id, created_at);

-- Turn ledger. state is the only success signal: SUBMITTED means "protocol accepted, reply pending",
-- never "answered". A turn whose outcome is not provable stays UNKNOWN and must be reconciled.
CREATE TABLE assistant_conversation_messages (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL,
    actor_id BIGINT NOT NULL,
    request_id VARCHAR(64),
    role VARCHAR(16) NOT NULL,
    body_text TEXT NOT NULL,
    state VARCHAR(24) NOT NULL,
    turn_id VARCHAR(128),
    upstream_turn_ref VARCHAR(128),
    failure_detail VARCHAR(500),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_assistant_conversation_message FOREIGN KEY (conversation_id) REFERENCES assistant_conversations(id),
    CONSTRAINT uk_assistant_conversation_message_request UNIQUE (conversation_id, request_id),
    CONSTRAINT chk_assistant_conversation_message_role CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT chk_assistant_conversation_message_state
        CHECK (state IN ('SUBMITTED', 'COMPLETED', 'UNKNOWN', 'FAILED'))
);

CREATE INDEX idx_assistant_conversation_message_conv ON assistant_conversation_messages(conversation_id, created_at);
