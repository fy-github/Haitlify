-- T14：历史明文密钥迁移的受控备份。备份内容为加密副本（enc:v1: 密文），
-- 绝不落明文；迁移报告只含计数，不含任何秘密值。
CREATE TABLE ai_secret_backups (
    backup_id VARCHAR(128) PRIMARY KEY,
    source_table VARCHAR(64) NOT NULL,
    source_ref VARCHAR(255) NOT NULL,
    ciphertext MEDIUMTEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE INDEX idx_ai_secret_backups_source ON ai_secret_backups (source_table, source_ref);
