package com.haitlify.aitest.platform.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AiRunPolicyServiceTest {

    @Autowired
    private AiRunPolicyService runPolicyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetTables() {
        jdbcTemplate.update("delete from ai_run_policies");
        jdbcTemplate.update("delete from ai_system_configs");
    }

    @Test
    void seedsThreeDefaultPoliciesFromActiveProviderAndIsIdempotent() {
        seedConfig(providerStateJson("prod-openai", "gpt-5.5", true, "backup-ds", false), true);

        List<AiRunPolicyResponse> first = runPolicyService.listPolicies().items();
        List<AiRunPolicyResponse> second = runPolicyService.listPolicies().items();

        assertThat(first).hasSize(3);
        assertThat(second).hasSize(3);
        assertThat(taskTypesOf(first)).containsExactlyInAnyOrder(
            "requirement_analysis", "web_case_generation", "api_case_generation"
        );
        AiRunPolicyResponse requirement = policyFor(first, "requirement_analysis");
        assertThat(requirement.enabled()).isTrue();
        assertThat(requirement.retryBudget()).isEqualTo(2);
        assertThat(requirement.wholeRunDeadlineMs()).isEqualTo(900000L);
        assertThat(requirement.perCallTimeoutMs()).isEqualTo(180000);
        assertThat(requirement.routes()).hasSize(1);
        assertThat(requirement.routes().get(0).connectionId()).isEqualTo("prod-openai");
        assertThat(requirement.routes().get(0).model()).isEqualTo("gpt-5.5");
        assertThat(warningCodes(requirement)).isEmpty();
    }

    @Test
    void seedsEmptyRoutePoliciesWhenNoConnectionConfigured() {
        // no ai_system_configs row present
        List<AiRunPolicyResponse> policies = runPolicyService.listPolicies().items();
        assertThat(policies).hasSize(3);
        for (AiRunPolicyResponse policy : policies) {
            assertThat(policy.routes()).isEmpty();
            assertThat(warningCodes(policy)).contains("missing_primary_route");
        }
    }

    @Test
    void validateFlagsDisabledProviderAndMissingCredential() {
        seedConfig(providerStateJson("prod-openai", "gpt-5.5", true, "backup-ds", false), true);
        runPolicyService.listPolicies();

        AiRunPolicyResponse saved = runPolicyService.savePolicy(
            "default-requirement_analysis",
            new SaveAiRunPolicyRequest(
                "需求分析默认策略", true, 90000, 600000L, 3, 0,
                List.of(
                    new SaveAiRouteRequest(0, "prod-openai", "gpt-5.5"),
                    new SaveAiRouteRequest(1, "backup-ds", "deepseek-chat")
                )
            )
        );

        Set<String> codes = warningCodes(saved);
        assertThat(codes).contains("disabled_provider", "missing_credential");
        // The healthy primary route produces no warning.
        assertThat(saved.routes()).hasSize(2);
        assertThat(saved.perCallTimeoutMs()).isEqualTo(90000);
        assertThat(saved.retryBudget()).isEqualTo(3);
    }

    @Test
    void validateFlagsUnreachableConnection() {
        seedConfig(providerStateJson("prod-openai", "gpt-5.5", true, "backup-ds", true), true);
        runPolicyService.listPolicies();

        AiRunPolicyResponse saved = runPolicyService.savePolicy(
            "default-requirement_analysis",
            new SaveAiRunPolicyRequest("策略", true, null, null, null, null,
                List.of(new SaveAiRouteRequest(0, "ghost-connection", "ghost-model")))
        );

        assertThat(warningCodes(saved)).contains("unavailable_model");
    }

    @Test
    void enabledTaskOverrideTakesPrecedenceOverDefault() {
        seedConfig(providerStateJson("prod-openai", "gpt-5.5", true, "backup-ds", true), true);
        runPolicyService.listPolicies();

        runPolicyService.saveTaskOverride("requirement_analysis", new SaveAiRunPolicyRequest(
            "需求分析覆盖", true, 60000, 600000L, 1, 0,
            List.of(new SaveAiRouteRequest(0, "backup-ds", "deepseek-chat"))
        ));

        com.haitlify.aitest.platform.ai.gateway.AiRoutingStrategy overridden = runPolicyService.resolveStrategy("requirement_analysis");
        assertThat(overridden.routes()).hasSize(1);
        assertThat(overridden.routes().get(0).model()).isEqualTo("deepseek-chat");
        assertThat(overridden.retryBudget()).isEqualTo(1);

        runPolicyService.saveTaskOverride("requirement_analysis", new SaveAiRunPolicyRequest(
            null, false, null, null, null, null, null
        ));

        com.haitlify.aitest.platform.ai.gateway.AiRoutingStrategy effective = runPolicyService.resolveStrategy("requirement_analysis");
        assertThat(effective.routes().get(0).model()).isEqualTo("gpt-5.5");
    }

    @Test
    void deleteTaskOverrideRestoresDefaultPolicyAndRemovesOverride() {
        seedConfig(providerStateJson("prod-openai", "gpt-5.5", true, "backup-ds", true), true);
        runPolicyService.saveTaskOverride("requirement_analysis", new SaveAiRunPolicyRequest(
            "需求分析覆盖", true, null, null, null, null,
            List.of(new SaveAiRouteRequest(0, "backup-ds", "deepseek-chat"))
        ));

        runPolicyService.deleteTaskOverride("requirement_analysis");

        assertThat(runPolicyService.listPolicies().items())
            .noneMatch(policy -> "task_override".equals(policy.scope()) && "requirement_analysis".equals(policy.taskType()));
        assertThat(runPolicyService.resolveStrategy("requirement_analysis").routes().get(0).model()).isEqualTo("gpt-5.5");
    }

    @Test
    void allowsZeroTimeoutsForHeartbeatMonitoredRuns() {
        seedConfig(providerStateJson("prod-openai", "gpt-5.5", true, "backup-ds", true), true);

        AiRunPolicyResponse saved = runPolicyService.savePolicy(
            "default-requirement_analysis",
            new SaveAiRunPolicyRequest(null, true, 0, 0L, 0, null,
                List.of(new SaveAiRouteRequest(0, "prod-openai", "gpt-5.5")))
        );

        assertThat(saved.perCallTimeoutMs()).isZero();
        assertThat(saved.wholeRunDeadlineMs()).isZero();
        assertThat(saved.retryBudget()).isZero();
        assertThat(runPolicyService.resolveStrategy("requirement_analysis").routes().getFirst().requestTimeoutMs()).isZero();
    }

    @Test
    void syncDefaultPolicyPrimaryRoutePreservesUserFallbacks() {
        seedConfig(providerStateJson("prod-openai", "gpt-5.5", true, "backup-ds", true), true);
        runPolicyService.savePolicy(
            "default-requirement_analysis",
            new SaveAiRunPolicyRequest("策略", true, null, null, null, null,
                List.of(
                    new SaveAiRouteRequest(0, "prod-openai", "gpt-5.5"),
                    new SaveAiRouteRequest(1, "backup-ds", "deepseek-chat")
                )
            )
        );

        runPolicyService.syncDefaultPolicyPrimaryRoute("prod-openai-2", "gpt-5.6", 300000);

        AiRunPolicyResponse requirement = policyFor(runPolicyService.listPolicies().items(), "requirement_analysis");
        assertThat(requirement.routes()).hasSize(2);
        assertThat(requirement.routes().get(0).connectionId()).isEqualTo("prod-openai-2");
        assertThat(requirement.routes().get(0).model()).isEqualTo("gpt-5.6");
        assertThat(requirement.perCallTimeoutMs()).isEqualTo(300000);
        assertThat(requirement.routes().get(1).connectionId()).isEqualTo("backup-ds");
        assertThat(requirement.routes().get(1).model()).isEqualTo("deepseek-chat");
    }

    private void seedConfig(String providerConfigJson, boolean enabled) {
        jdbcTemplate.update("""
            insert into ai_system_configs(
              config_key, enabled, provider_type, base_url, text_model, provider_config_json,
              credential_configured, request_timeout_ms, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "default", enabled, "openai", "https://api.openai.com/v1", "gpt-5.5", providerConfigJson,
            true, 180000, Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
        );
    }

    private String providerStateJson(String primaryId, String primaryModel, boolean primaryEnabled, String fallbackId, boolean fallbackEnabled) {
        return """
            {"version":2,"activeProviderId":"%s","activeModel":"%s","providers":[
              {"id":"%s","providerId":"openai","name":"生产 OpenAI","baseURL":"https://api.openai.com/v1","defaultModel":"%s","apiKey":"sk-prod","enabled":%s,"credentialConfigured":true,"requestTimeoutMs":180000},
              {"id":"%s","providerId":"deepseek","name":"备用 DeepSeek","baseURL":"https://api.deepseek.com/v1","defaultModel":"deepseek-chat","apiKey":"","enabled":%s,"credentialConfigured":false,"requestTimeoutMs":120000}
            ]}
            """.formatted(primaryId, primaryModel, primaryId, primaryModel, primaryEnabled, fallbackId, fallbackEnabled);
    }

    private Set<String> taskTypesOf(List<AiRunPolicyResponse> policies) {
        return policies.stream().map(AiRunPolicyResponse::taskType).collect(Collectors.toSet());
    }

    private Set<String> warningCodes(AiRunPolicyResponse policy) {
        return policy.warnings().stream().map(AiPolicyWarning::code).collect(Collectors.toSet());
    }

    private AiRunPolicyResponse policyFor(List<AiRunPolicyResponse> policies, String taskType) {
        return policies.stream().filter(p -> taskType.equals(p.taskType())).findFirst().orElseThrow();
    }

    @Test
    void taskOverrideWriteRejectsUnknownTypesAndStoresCatalogCanonicalSpelling() {
        seedConfig(providerStateJson("prod-openai", "gpt-5.5", true, "backup-ds", true), true);
        runPolicyService.listPolicies();

        // L5①：未知任务类型 fail-closed，零落库（此前会静默 INSERT 一行永不可达的覆盖）。
        assertThatThrownBy(() -> runPolicyService.saveTaskOverride("mystery_task", new SaveAiRunPolicyRequest(
            "未知", true, 1000, 1000L, 1, null, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown_task_type");
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_run_policies where task_type = 'mystery_task'", Integer.class)).isZero();

        // 策略体系自身的默认类型（不在助手目录内）仍可覆盖——白名单取并集。
        AiRunPolicyResponse webOverride = runPolicyService.saveTaskOverride("web_case_generation",
            new SaveAiRunPolicyRequest("Web 覆盖", true, 45000, 450000L, 3, null, null));
        assertThat(webOverride.taskType()).isEqualTo("web_case_generation");

        // 变体拼写：归一化 → 目录校验 → canonical 拼写落库。
        AiRunPolicyResponse saved = runPolicyService.saveTaskOverride("Bug_Analysis\u200B",
            new SaveAiRunPolicyRequest("缺陷覆盖", true, 30000, 300000L, 4, null, null));
        assertThat(saved.taskType()).isEqualTo("bug_analysis");
        assertThat(jdbcTemplate.queryForList(
            "select task_type from ai_run_policies where scope = 'task_override' order by task_type", String.class))
            .containsExactly("bug_analysis", "web_case_generation");
    }

    @Test
    void taskOverrideKeysAreNormalizedSoVariantSpellingsTakeEffect() {
        seedConfig(providerStateJson("prod-openai", "gpt-5.5", true, "backup-ds", true), true);
        runPolicyService.listPolicies();

        // T-R5 轮 6：'Requirement_Analysis' 变体拼写必须归一到规范键（既生效也不留不可达行）。
        runPolicyService.saveTaskOverride("Requirement_Analysis", new SaveAiRunPolicyRequest(
            "覆盖", true, 60000, 600000L, 5, 0,
            List.of(new SaveAiRouteRequest(0, "backup-ds", "deepseek-chat"))));

        assertThat(jdbcTemplate.queryForList(
            "select task_type from ai_run_policies where scope = 'task_override'", String.class))
            .containsExactly("requirement_analysis");
        assertThat(runPolicyService.resolveStrategy("requirement_analysis").retryBudget()).isEqualTo(5);

        // 补充平面变形（U+101FD）同样归一：命中同一行（UPDATE）而非新增不可达行。
        runPolicyService.saveTaskOverride("requirement_analysis\ud800\uddfd", new SaveAiRunPolicyRequest(
            null, true, null, null, 6, null, null));
        assertThat(jdbcTemplate.queryForList(
            "select task_type from ai_run_policies where scope = 'task_override'", String.class))
            .containsExactly("requirement_analysis");
        assertThat(runPolicyService.resolveStrategy("requirement_analysis").retryBudget()).isEqualTo(6);

        // T-R5 轮 7：删除也必须同口径——变体拼写删除不得静默无效。
        runPolicyService.deleteTaskOverride("Requirement_Analysis\ud800\uddfd");
        assertThat(jdbcTemplate.queryForList(
            "select task_type from ai_run_policies where scope = 'task_override'", String.class))
            .isEmpty();
        assertThat(runPolicyService.resolveStrategy("requirement_analysis").retryBudget()).isEqualTo(2);
    }
}