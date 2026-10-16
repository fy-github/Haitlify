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
 * T11（后端半部）：连接/档案编辑器 API——版本化读写、409 冲突、有效配置溯源。
 */
@SpringBootTest
@ActiveProfiles("test")
class AiConnectionProfileControllerTest {

    @Autowired
    private AiConnectionProfileController controller;

    @Autowired
    private AiRunPolicyService runPolicyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanState() {
        jdbcTemplate.update("delete from ai_model_profiles");
        jdbcTemplate.update("delete from ai_connections");
        jdbcTemplate.update("delete from ai_run_policies");
        jdbcTemplate.update("delete from ai_system_configs");
    }

    private void seedLegacyConfig() {
        String providerStateJson = """
            {"version":2,"activeProviderId":"legacy","activeModel":"gpt-legacy","providers":[
              {"id":"legacy","providerId":"openai","name":"Legacy","baseURL":"https://legacy.test/v1",
               "defaultModel":"gpt-legacy","enabled":true,"credentialConfigured":true,"requestTimeoutMs":300000}
            ]}
            """;
        jdbcTemplate.update("""
            insert into ai_system_configs(
              config_key, enabled, provider_type, base_url, text_model, provider_config_json,
              credential_configured, request_timeout_ms, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "default", true, "openai", "https://legacy.test/v1", "gpt-legacy", providerStateJson,
            true, 300000, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    @Test
    void connectionsAreVersionedAndReadableByPin() {
        var first = controller.registerConnection(new RegisterConnectionRequest(
            "editor-conn", "https://editor.test/v1", "alias:editor", "chat_completions", true,
            List.of(1L), null));
        assertThat(first.version()).isEqualTo(1);

        var second = controller.registerConnection(new RegisterConnectionRequest(
            "editor-conn", "https://editor.test/v2", "alias:editor", "CHAT_COMPLETIONS", true,
            List.of(1L, 2L), 1));
        assertThat(second.version()).isEqualTo(2);

        assertThat(controller.getConnection("editor-conn", null).baseUrl()).isEqualTo("https://editor.test/v2");
        assertThat(controller.getConnection("editor-conn", 1).baseUrl()).isEqualTo("https://editor.test/v1");
        assertThat(controller.listConnections()).hasSize(1);

        assertThatThrownBy(() -> controller.registerConnection(new RegisterConnectionRequest(
            "editor-conn", "https://editor.test/v3", "alias:editor", "CHAT_COMPLETIONS", true, null, 1)))
            .isInstanceOf(ConfigurationVersionConflictException.class);
    }

    @Test
    void profilesValidateCapabilitiesAndExposeVerificationState() {
        controller.registerConnection(new RegisterConnectionRequest(
            "editor-conn", "https://editor.test/v1", "alias:editor", "CHAT_COMPLETIONS", true, null, null));

        var profile = controller.registerProfile(new RegisterProfileRequest(
            "editor-profile", "editor-conn", "gpt-x", List.of("TEXT", "JSON_OBJECT"), "{\"temperature\":0}",
            null, null));
        assertThat(profile.version()).isEqualTo(1);
        assertThat(profile.capabilities()).containsExactly("TEXT", "JSON_OBJECT");
        assertThat(controller.getProfile("editor-profile", null).modelId()).isEqualTo("gpt-x");
        assertThat(controller.listProfiles()).hasSize(1);

        assertThatThrownBy(() -> controller.registerProfile(new RegisterProfileRequest(
            "editor-profile", "editor-conn", "gpt-x", List.of("UNKNOWN_CAP"), "{}", null, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown capability");
    }

    @Test
    void effectiveOptionsResolveTimeoutWithProvenance() {
        seedLegacyConfig();
        controller.registerConnection(new RegisterConnectionRequest(
            "editor-conn", "https://editor.test/v1", "alias:editor", "CHAT_COMPLETIONS", true, null, null));
        controller.registerProfile(new RegisterProfileRequest(
            "editor-profile", "editor-conn", "gpt-x", List.of("TEXT"), "{}", null, null));

        // 无任务覆盖 ⇒ 连接默认（legacy 全局超时）来源。
        var inherited = controller.effectiveOptions("requirement_analysis", "editor-profile");
        assertThat(inherited.timeout().value()).isEqualTo(300000);
        assertThat(inherited.timeout().source()).isEqualTo(AiEffectiveOptionsResolver.SOURCE_CONNECTION_DEFAULT);
        assertThat(inherited.declaredCapabilities()).containsExactly("TEXT");
        assertThat(inherited.taskTypes()).contains("requirement_analysis", "bug_analysis");
        assertThat(inherited.notes().get("taskTypes")).contains("不代表");

        // 显式任务覆盖 ⇒ task_override 来源且值优先。
        runPolicyService.saveTaskOverride("requirement_analysis", new SaveAiRunPolicyRequest(
            "显式覆盖", true, 180000, null, null, null, null));
        var explicit = controller.effectiveOptions("requirement_analysis", "editor-profile");
        assertThat(explicit.timeout().value()).isEqualTo(180000);
        assertThat(explicit.timeout().source()).isEqualTo(AiEffectiveOptionsResolver.SOURCE_TASK_OVERRIDE);
    }
}
