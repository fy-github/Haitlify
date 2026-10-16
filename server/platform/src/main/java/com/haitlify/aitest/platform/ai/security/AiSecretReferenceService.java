package com.haitlify.aitest.platform.ai.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.security.SecretCipher;

/**
 * T14：秘密引用服务。
 *
 * <ul>
 *   <li>存储只产生引用（{@code enc:v1:…} 密文形态）；</li>
 *   <li>生产环境（未开 {@code app.security.allow-plaintext-secrets} 且无根密钥）拒绝新增明文密钥；</li>
 *   <li>历史明文迁移：先备份到受控加密存储（密文副本）、替换为密文、逐条验证解密可用——
 *       迁移报告只有计数，任何日志/报告都不含秘密值。</li>
 * </ul>
 */
@Service
public class AiSecretReferenceService {

    public static final String CIPHER_PREFIX = "enc:v1:";

    public record MigrationReport(
        int scanned,
        int migrated,
        int skippedAlreadyEncrypted,
        int blockedNoRootKey,
        boolean decryptionVerified,
        String note
    ) {
    }

    public static class PlaintextSecretRefusedException extends RuntimeException {
        public PlaintextSecretRefusedException(String message) {
            super(message);
        }
    }

    private final JdbcTemplate jdbcTemplate;
    private final SecretCipher secretCipher;
    private final boolean allowPlaintextSecrets;

    public AiSecretReferenceService(JdbcTemplate jdbcTemplate, SecretCipher secretCipher,
                                    @Value("${app.security.allow-plaintext-secrets:false}") boolean allowPlaintextSecrets) {
        this.jdbcTemplate = jdbcTemplate;
        this.secretCipher = secretCipher;
        this.allowPlaintextSecrets = allowPlaintextSecrets;
    }

    /** 新增秘密 ⇒ 只返回引用。无根密钥时按配置拒绝（默认拒绝），绝不静默存明文。 */
    public String storeSecret(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("secret required");
        }
        String stored = secretCipher.encrypt(plaintext);
        if (!stored.startsWith(CIPHER_PREFIX) && !allowPlaintextSecrets) {
            throw new PlaintextSecretRefusedException(
                "no_root_key: 未配置根密钥，拒绝新增明文密钥（设置 APP_SECRET_KEY 或显式开启 allow-plaintext-secrets）");
        }
        return stored;
    }

    public boolean isEncryptedRef(String ref) {
        return ref != null && ref.startsWith(CIPHER_PREFIX);
    }

    /** 材料化：仅用于合法 transport 组装；密文无法解密一律异常（fail-closed）。 */
    public String materialize(String ref) {
        return secretCipher.decrypt(ref);
    }

    /**
     * 迁移历史明文：{@code ai_system_configs.api_key_ciphertext} 中非 enc:v1: 前缀的非空值
     * 视为历史明文。逐条加密回写 + 加密备份 + 解密验证；报告不含任何秘密值。
     */
    public MigrationReport migrateLegacyPlaintext() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select id, config_key, api_key_ciphertext from ai_system_configs"
                + " where api_key_ciphertext is not null and api_key_ciphertext <> ''");
        int scanned = 0;
        int migrated = 0;
        int skipped = 0;
        int blocked = 0;
        boolean verified = true;
        for (Map<String, Object> row : rows) {
            String value = String.valueOf(row.get("api_key_ciphertext"));
            if (value.startsWith(CIPHER_PREFIX)) {
                skipped++;
                continue;
            }
            scanned++;
            String ciphertext;
            try {
                ciphertext = storeSecret(value);
            } catch (PlaintextSecretRefusedException refused) {
                blocked++;
                continue;
            }
            jdbcTemplate.update("""
                insert into ai_secret_backups(backup_id, source_table, source_ref, ciphertext, created_at)
                values (?, ?, ?, ?, ?)
                """,
                "backup-" + UUID.randomUUID(), "ai_system_configs",
                String.valueOf(row.get("config_key")), ciphertext, java.sql.Timestamp.from(Instant.now()));
            jdbcTemplate.update("update ai_system_configs set api_key_ciphertext = ? where id = ?",
                ciphertext, row.get("id"));
            // 验证解密可用：迁移后的引用必须能还原出原值。
            if (!value.equals(secretCipher.decrypt(ciphertext))) {
                verified = false;
            }
            migrated++;
        }
        return new MigrationReport(scanned, migrated, skipped, blocked, verified,
            blocked > 0 ? "存在因缺少根密钥而未迁移的明文（保持原样，未做任何替换）" : "迁移完成");
    }
}
