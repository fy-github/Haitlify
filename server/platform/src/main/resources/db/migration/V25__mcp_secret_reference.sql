ALTER TABLE ai_mcp_servers ADD COLUMN auth_secret_ref VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE ai_mcp_servers ADD COLUMN auth_secret_ciphertext TEXT;
