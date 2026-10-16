package com.haitlify.aitest.platform.ai.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.haitlify.aitest.platform.security.SecretCipher;

/**
 * T14 批次 3：历史明文密钥迁移——先备份（加密副本）→ 替换引用 → 验证解密可用；
 * 报告不含秘密值；无根密钥时拒绝新增明文并保持数据原样。
 */
class AiLegacySecretMigrationTest {

    /** 迁移测试用的历史明文（测试常量，不是真实凭据）。 */
    private static final String LEGACY_PLAINTEXT = "legacy-plain-key-3d8f2a9c1b7e5d4f";

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:secret-migration;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("""
            create table if not exists ai_system_configs(
              id bigint auto_increment primary key, config_key varchar(128) not null,
              api_key_ciphertext varchar(512))
            """);
        jdbc.execute("""
            create table if not exists ai_secret_backups(
              backup_id varchar(128) primary key, source_table varchar(64) not null,
              source_ref varchar(255) not null, ciphertext clob not null, created_at timestamp)
            """);
        jdbc.execute("delete from ai_secret_backups");
        jdbc.execute("delete from ai_system_configs");
    }

    private SecretCipher cipherWithKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new SecretCipher(Base64.getEncoder().encodeToString(key));
    }

    private AiSecretReferenceService service(SecretCipher cipher, boolean allowPlaintext) {
        return new AiSecretReferenceService(jdbc, cipher, allowPlaintext);
    }

    @Test
    void migrationBacksUpEncryptedThenReplacesWithReferencesAndVerifiesDecryption() {
        jdbc.update("insert into ai_system_configs(config_key, api_key_ciphertext) values ('legacy-default', ?)",
            LEGACY_PLAINTEXT);
        jdbc.update("insert into ai_system_configs(config_key, api_key_ciphertext) values ('already-enc', ?)",
            "enc:v1:already-migrated");
        jdbc.update("insert into ai_system_configs(config_key, api_key_ciphertext) values ('no-secret', '')");
        SecretCipher cipher = cipherWithKey();
        AiSecretReferenceService referenceService = service(cipher, false);

        AiSecretReferenceService.MigrationReport report = referenceService.migrateLegacyPlaintext();
        assertThat(report.scanned()).isEqualTo(1);
        assertThat(report.migrated()).isEqualTo(1);
        assertThat(report.skippedAlreadyEncrypted()).isEqualTo(1);
        assertThat(report.blockedNoRootKey()).isZero();
        assertThat(report.decryptionVerified()).isTrue();

        // 数据行替换为引用，且可解密还原。
        String stored = jdbc.queryForObject(
            "select api_key_ciphertext from ai_system_configs where config_key = 'legacy-default'", String.class);
        assertThat(stored).startsWith(AiSecretReferenceService.CIPHER_PREFIX);
        assertThat(stored).doesNotContain(LEGACY_PLAINTEXT);
        assertThat(referenceService.materialize(stored)).isEqualTo(LEGACY_PLAINTEXT);

        // 备份是加密副本：可解密还原，且不含明文。
        Map<String, Object> backup = jdbc.queryForMap(
            "select * from ai_secret_backups where source_ref = 'legacy-default'");
        String backupCiphertext = String.valueOf(backup.get("CIPHERTEXT"));
        assertThat(backupCiphertext).startsWith(AiSecretReferenceService.CIPHER_PREFIX);
        assertThat(backupCiphertext).doesNotContain(LEGACY_PLAINTEXT);
        assertThat(cipher.decrypt(backupCiphertext)).isEqualTo(LEGACY_PLAINTEXT);

        // 报告与迁移产物中不含任何秘密值。
        assertThat(report.toString()).doesNotContain(LEGACY_PLAINTEXT);
        assertThat(jdbc.queryForObject("select count(*) from ai_secret_backups", Integer.class)).isEqualTo(1);
    }

    @Test
    void migrationIsIdempotentAndSecondRunChangesNothing() {
        jdbc.update("insert into ai_system_configs(config_key, api_key_ciphertext) values ('legacy-default', ?)",
            LEGACY_PLAINTEXT);
        AiSecretReferenceService referenceService = service(cipherWithKey(), false);
        referenceService.migrateLegacyPlaintext();

        AiSecretReferenceService.MigrationReport second = referenceService.migrateLegacyPlaintext();
        assertThat(second.scanned()).isZero();
        assertThat(second.migrated()).isZero();
        assertThat(second.skippedAlreadyEncrypted()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ai_secret_backups", Integer.class)).isEqualTo(1);
    }

    @Test
    void withoutARootKeyNewPlaintextIsRefusedAndLegacyDataStaysUntouched() {
        jdbc.update("insert into ai_system_configs(config_key, api_key_ciphertext) values ('legacy-default', ?)",
            LEGACY_PLAINTEXT);
        SecretCipher keyless = new SecretCipher("");
        AiSecretReferenceService referenceService = service(keyless, false);

        assertThatThrownBy(() -> referenceService.storeSecret("brand-new-secret-value"))
            .isInstanceOf(AiSecretReferenceService.PlaintextSecretRefusedException.class)
            .hasMessageContaining("no_root_key");

        AiSecretReferenceService.MigrationReport report = referenceService.migrateLegacyPlaintext();
        assertThat(report.blockedNoRootKey()).isEqualTo(1);
        assertThat(report.migrated()).isZero();
        // 数据保持原样：没有静默替换、没有明文备份。
        assertThat(jdbc.queryForObject(
            "select api_key_ciphertext from ai_system_configs where config_key = 'legacy-default'", String.class))
            .isEqualTo(LEGACY_PLAINTEXT);
        assertThat(jdbc.queryForObject("select count(*) from ai_secret_backups", Integer.class)).isZero();
    }

    @Test
    void storingWithARootKeyReturnsAReferenceThatMaterializesBack() {
        AiSecretReferenceService referenceService = service(cipherWithKey(), false);
        String ref = referenceService.storeSecret("rotated-secret-42");
        assertThat(referenceService.isEncryptedRef(ref)).isTrue();
        assertThat(ref).doesNotContain("rotated-secret-42");
        assertThat(referenceService.materialize(ref)).isEqualTo("rotated-secret-42");
    }

    @Test
    void tamperedCiphertextFailsClosedInsteadOfReturningGarbage() {
        AiSecretReferenceService referenceService = service(cipherWithKey(), false);
        String ref = referenceService.storeSecret("original");
        // 确定性篡改：翻转最后一个字符（保证与原文不同）。
        String tampered = ref.substring(0, ref.length() - 1)
            + (ref.endsWith("A") ? "B" : "A");
        assertThatThrownBy(() -> referenceService.materialize(tampered))
            .isInstanceOf(IllegalStateException.class);
    }
}
