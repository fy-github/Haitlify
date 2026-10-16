package com.haitlify.aitest.platform.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * T01：旧 schema → 新 schema 数据迁移样例（H2 执行；生产 MySQL 侧由 Flyway V22 建表并在部署演练中验证）。
 * 覆盖：非破坏性导入、幂等、旧 policy timeout 作为 EXPLICIT、回滚读取窗口、关系完整性。
 */
@SpringBootTest
@ActiveProfiles("test")
class AiRuntimeMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AiLegacyConfigurationImporter importer;

    @Autowired
    private AiConnectionService connectionService;

    @Autowired
    private AiModelProfileService profileService;

    @Autowired
    private AiEffectiveOptionsResolver resolver;

    @BeforeEach
    void cleanState() {
        jdbcTemplate.update("delete from ai_model_profiles");
        jdbcTemplate.update("delete from ai_connections");
        jdbcTemplate.update("delete from ai_run_policies");
        jdbcTemplate.update("delete from ai_system_configs");
    }

    private void seedLegacyConfig(String baseUrl) {
        String providerStateJson = """
            {"version":2,"activeProviderId":"legacy","activeModel":"gpt-legacy","providers":[
              {"id":"legacy","providerId":"openai","name":"Legacy","baseURL":"%s","defaultModel":"gpt-legacy",
               "enabled":true,"credentialConfigured":true,"requestTimeoutMs":180000}
            ]}
            """.formatted(baseUrl);
        jdbcTemplate.update("""
            insert into ai_system_configs(
              config_key, enabled, provider_type, base_url, text_model, provider_config_json,
              credential_configured, request_timeout_ms, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "default", true, "openai", baseUrl, "gpt-legacy", providerStateJson,
            true, 180000, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    @Test
    void legacyImportIsNonDestructiveIdempotentAndKeepsARollbackWindow() {
        seedLegacyConfig("https://legacy.test/v1");

        var first = importer.importLegacyDefault();

        // 连接：CHAT_COMPLETIONS + opaque 引用；档案：未声明能力（UNKNOWN 不假设）。
        assertThat(first.connectionVersion()).isEqualTo(1);
        assertThat(first.connectionAppended()).isTrue();
        assertThat(first.protocol()).isEqualTo("CHAT_COMPLETIONS");
        assertThat(first.capabilities()).isEmpty();
        assertThat(first.capabilitiesState()).isEqualTo("UNKNOWN");
        assertThat(connectionService.latest("legacy-default").credentialRef())
            .isEqualTo("legacy-ai-system-config");
        assertThat(profileService.latest("legacy-default-profile").connectionId()).isEqualTo("legacy-default");
        assertThat(profileService.latest("legacy-default-profile").modelId()).isEqualTo("gpt-legacy");
        assertThat(profileService.latest("legacy-default-profile").verificationJson()).contains("UNKNOWN");

        // 旧 policy timeout 作为 EXPLICIT 覆盖导入。
        assertThat(first.policyTimeoutImported()).isTrue();
        Integer overrideTimeout = jdbcTemplate.queryForObject(
            "select per_call_timeout_ms from ai_run_policies where scope = 'task_override' and task_type = ?",
            Integer.class, "requirement_analysis");
        assertThat(overrideTimeout).isEqualTo(180000);
        // 解析器口径：显式覆盖 = task_override 来源（不是连接默认）。
        var effective = resolver.resolveTimeout(overrideTimeout, "policy@task_override",
            300000, "connection@v1", null, null);
        assertThat(effective.source()).isEqualTo(AiEffectiveOptionsResolver.SOURCE_TASK_OVERRIDE);
        assertThat(effective.value()).isEqualTo(180000);

        // 幂等：重复导入不追加任何版本、不重复落策略。
        var second = importer.importLegacyDefault();
        assertThat(second.connectionAppended()).isFalse();
        assertThat(second.profileAppended()).isFalse();
        assertThat(second.policyTimeoutImported()).isFalse();
        assertThat(second.connectionVersion()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_connections where connection_id = 'legacy-default'", Integer.class))
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_model_profiles where profile_id = 'legacy-default-profile'",
            Integer.class)).isEqualTo(1);

        // 回滚读取窗口：导入后追加新档案版本，旧版本仍可按版本复读；旧配置行原样保留。
        profileService.register("legacy-default-profile", "legacy-default", "gpt-legacy-next",
            List.of("TEXT"), "{}", null);
        assertThat(profileService.at("legacy-default-profile", 1).modelId()).isEqualTo("gpt-legacy");
        assertThat(profileService.latest("legacy-default-profile").modelId()).isEqualTo("gpt-legacy-next");
        assertThat(jdbcTemplate.queryForObject("select count(*) from ai_system_configs", Integer.class))
            .isEqualTo(1);

        // 旧配置变更 ⇒ 追加新连接版本（非破坏性），历史版本保持；
        // 连接保存绝不无条件覆盖显式策略——override 超时与来源保持不变。
        jdbcTemplate.update("update ai_system_configs set base_url = 'https://legacy.test/v2'"
            + " where config_key = 'default'");
        var third = importer.importLegacyDefault();
        assertThat(third.connectionAppended()).isTrue();
        assertThat(third.connectionVersion()).isEqualTo(2);
        assertThat(connectionService.at("legacy-default", 1).baseUrl()).isEqualTo("https://legacy.test/v1");
        assertThat(connectionService.latest("legacy-default").baseUrl()).isEqualTo("https://legacy.test/v2");
        Integer stillExplicit = jdbcTemplate.queryForObject(
            "select per_call_timeout_ms from ai_run_policies where scope = 'task_override' and task_type = ?",
            Integer.class, "requirement_analysis");
        assertThat(stillExplicit).isEqualTo(180000);
        var composedAfterConnectionSave = resolver.resolveTimeout(stillExplicit, "policy@task_override",
            300000, "connection@v2", null, null);
        assertThat(composedAfterConnectionSave.source())
            .isEqualTo(AiEffectiveOptionsResolver.SOURCE_TASK_OVERRIDE);
        assertThat(composedAfterConnectionSave.value()).isEqualTo(180000);
    }

    @Test
    void missingLegacyConfigFailsClosedWithoutWritingAnything() {
        assertThatThrownBy(importer::importLegacyDefault)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("legacy configuration missing");
        assertThat(jdbcTemplate.queryForObject("select count(*) from ai_connections", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from ai_model_profiles", Integer.class)).isZero();
    }
}
