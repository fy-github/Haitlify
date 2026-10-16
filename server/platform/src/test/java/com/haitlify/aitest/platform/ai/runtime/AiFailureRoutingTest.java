package com.haitlify.aitest.platform.ai.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.haitlify.aitest.platform.ai.event.AiRunEventService;
import com.haitlify.aitest.platform.ai.gateway.AiGateway;
import com.haitlify.aitest.platform.ai.gateway.AiGatewayConfig;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.ai.gateway.AiRetryReason;
import com.haitlify.aitest.platform.ai.gateway.AiRoute;
import com.haitlify.aitest.platform.ai.gateway.AiRouteExecutionContext;
import com.haitlify.aitest.platform.ai.gateway.AiRouteExecutionResult;
import com.haitlify.aitest.platform.ai.gateway.AiRouteExecutor;
import com.haitlify.aitest.platform.ai.gateway.AiRoutingStrategy;

/**
 * T05：失败分类规则版本化 + 重试路由——规则表逐项钉住（502/503/504、429、认证、参数、空输出、
 * incomplete/refusal/no-channel），并验证可重试分类在预算内重试、不可重试分类立即停止。
 */
class AiFailureRoutingTest {

    @Test
    void ruleVersionIsPinned() {
        // 规则改动必须显式提升版本并同步本断言（业务 message 精确规则不许隐性漂移）。
        assertThat(AiFailureClassifier.RULE_VERSION).isEqualTo("v1");
    }

    @Test
    void httpStatusClassificationTable() {
        assertThat(AiFailureClassifier.classify(502, null).reason()).isEqualTo(AiRetryReason.UPSTREAM_ERROR);
        assertThat(AiFailureClassifier.classify(503, null).ruleId()).isEqualTo("v1_http_502_503_504_transient");
        assertThat(AiFailureClassifier.classify(503, null).retryable()).isTrue();
        assertThat(AiFailureClassifier.classify(504, null).reason()).isEqualTo(AiRetryReason.UPSTREAM_ERROR);
        assertThat(AiFailureClassifier.classify(500, null).ruleId()).isEqualTo("v1_http_5xx_upstream");
        assertThat(AiFailureClassifier.classify(429, null).reason()).isEqualTo(AiRetryReason.RATE_LIMITED);
        assertThat(AiFailureClassifier.classify(429, null).retryable()).isTrue();
        assertThat(AiFailureClassifier.classify(401, null).reason()).isEqualTo(AiRetryReason.AUTH_DENIED);
        assertThat(AiFailureClassifier.classify(403, null).retryable()).isFalse();
        assertThat(AiFailureClassifier.classify(404, null).reason()).isEqualTo(AiRetryReason.MODEL_OR_ENDPOINT_NOT_FOUND);
        assertThat(AiFailureClassifier.classify(400, null).reason()).isEqualTo(AiRetryReason.INVALID_REQUEST);
        assertThat(AiFailureClassifier.classify(422, null).reason()).isEqualTo(AiRetryReason.INVALID_REQUEST);
        assertThat(AiFailureClassifier.classify(408, null).reason()).isEqualTo(AiRetryReason.TIMEOUT);
        assertThat(AiFailureClassifier.classify(0, new HttpTimeoutException("t")).reason()).isEqualTo(AiRetryReason.TIMEOUT);
        assertThat(AiFailureClassifier.classify(0, new IOException("io")).reason()).isEqualTo(AiRetryReason.TRANSIENT_NETWORK);
        assertThat(AiFailureClassifier.classify(0, null).reason()).isEqualTo(AiRetryReason.INVALID_REQUEST);
    }

    @Test
    void bodySemanticsClassifyRefusalIncompleteAndEmpty() {
        assertThat(AiFailureClassifier.classifyUnusableBody("stop", true).reason()).isEqualTo(AiRetryReason.REFUSAL);
        assertThat(AiFailureClassifier.classifyUnusableBody("stop", true).retryable()).isFalse();
        assertThat(AiFailureClassifier.classifyUnusableBody("content_filter", false).reason()).isEqualTo(AiRetryReason.REFUSAL);
        assertThat(AiFailureClassifier.classifyUnusableBody("length", false).reason()).isEqualTo(AiRetryReason.INCOMPLETE_OUTPUT);
        assertThat(AiFailureClassifier.classifyUnusableBody("length", false).retryable()).isFalse();
        assertThat(AiFailureClassifier.classifyUnusableBody("stop", false).reason()).isEqualTo(AiRetryReason.EMPTY_OUTPUT);
        assertThat(AiFailureClassifier.classifyUnusableBody("stop", false).retryable()).isTrue();
        assertThat(AiFailureClassifier.classifyUnusableBody("", false).ruleId()).isEqualTo("v1_body_empty_content");
    }

    @Test
    void messageRulesPrecedeStatusCodesAndDoNotOverMatch() {
        assertThat(AiFailureClassifier.classifyMessage("no available channel for model x").reason()).isEqualTo(AiRetryReason.NO_CHANNEL);
        assertThat(AiFailureClassifier.classifyMessage("上游返回：无可用渠道").reason()).isEqualTo(AiRetryReason.NO_CHANNEL);
        assertThat(AiFailureClassifier.classifyMessage("no channel available").retryable()).isFalse();
        assertThat(AiFailureClassifier.classifyMessage("blocked by content policy").reason()).isEqualTo(AiRetryReason.REFUSAL);
        assertThat(AiFailureClassifier.classifyMessage("safety filter triggered").reason()).isEqualTo(AiRetryReason.REFUSAL);
        // 近似文案不得误伤：普通 channel 或 policy 词不命中规则。
        assertThat(AiFailureClassifier.classifyMessage("channel capacity: 3 of 8")).isNull();
        assertThat(AiFailureClassifier.classifyMessage("privacy policy page updated")).isNull();
        assertThat(AiFailureClassifier.classifyMessage("")).isNull();
        assertThat(AiFailureClassifier.classifyMessage(null)).isNull();
    }

    @Test
    void retryableEmptyOutputRetriesWithinBudgetThenSucceeds() {
        AiGateway gateway = mock(AiGateway.class);
        when(gateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(false, "", "req-1", 0, 0, "empty", true, AiRetryReason.EMPTY_OUTPUT.name()))
            .thenReturn(new AiGenerationResult(true, "{\"ok\":true}", "req-2", 5, 5, null));
        var executor = new AiRouteExecutor(gateway, mock(AiRunEventService.class),
            new AiCancellationRegistry(), new AiExecutionLedger(),
            Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"), ZoneOffset.UTC));

        AiRouteExecutionResult result = executor.execute(strategy(1, 2), "prompt", ctx("run-retry-empty"));

        assertThat(result.success()).isTrue();
        assertThat(result.attempts()).hasSize(2);
        assertThat(result.attempts().get(0).retryReason()).isEqualTo(AiRetryReason.EMPTY_OUTPUT);
        verify(gateway, times(2)).structuredGenerate(any(AiGatewayConfig.class), any());
    }

    @Test
    void refusalAndNoChannelStopImmediatelyWithoutRetry() {
        for (AiRetryReason terminal : List.of(AiRetryReason.REFUSAL, AiRetryReason.NO_CHANNEL)) {
            AiGateway gateway = mock(AiGateway.class);
            when(gateway.structuredGenerate(any(AiGatewayConfig.class), any()))
                .thenReturn(new AiGenerationResult(false, "", "req-x", 0, 0, "blocked", terminal.retryable(), terminal.name()));
            var executor = new AiRouteExecutor(gateway, mock(AiRunEventService.class),
                new AiCancellationRegistry(), new AiExecutionLedger(),
                Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"), ZoneOffset.UTC));

            AiRouteExecutionResult result = executor.execute(strategy(2, 2), "prompt", ctx("run-terminal"));

            assertThat(result.terminalReason()).isEqualTo(terminal);
            verify(gateway, times(1)).structuredGenerate(any(AiGatewayConfig.class), any());
        }
    }

    // ------------------------------------------------------------------

    @Test
    void fallbackSelectionFiltersByProjectCapabilityAndBudget() {
        org.springframework.jdbc.core.JdbcTemplate jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(jdbc.queryForList(any(String.class))).thenReturn(List.of(
            selectionRow("conn-a", "prof-a", "{\"capabilities\":[\"TEXT\",\"JSON_SCHEMA\"]}", "[42]", true),
            selectionRow("conn-b", "prof-b", "{\"capabilities\":[\"TEXT\"]}", "[42]", true),
            selectionRow("conn-c", "prof-c", "{\"capabilities\":[\"TEXT\"]}", "[7]", true),
            selectionRow("conn-d", "prof-d", "{\"capabilities\":[\"TEXT\"]}", "", false),
            selectionRow("conn-e", "prof-e", "{\"capabilities\":[\"TEXT\",\"JSON_SCHEMA\"]}", "", true),
            selectionRow("conn-f", "prof-f", "{\"capabilities\":[\"TEXT\"]}", "not-json", true),
            selectionRow("conn-g", "prof-g", "{\"capabilities\":[\"TEXT\",\"JSON_SCHEMA\"]}", "[42]", true),
            selectionRow("conn-h", "prof-h", "{\"capabilities\":[\"TEXT\",\"JSON_SCHEMA\"]}", "{\"projects\":[42]}", true),
            // R2-1：生产写入方的裸数组形态也必须被解析（否则 live 数据下筛选停用）。
            selectionRow("conn-i", "prof-i", "[\"TEXT\",\"JSON_SCHEMA\"]", "[42]", true)));
        var cipher = mock(com.haitlify.aitest.platform.security.SecretCipher.class);
        when(cipher.decrypt(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(invocation -> "materialized-" + invocation.getArgument(0));
        // M1：占位符引用（解密结果等于引用本身）不可材料化 ⇒ 拒绝。
        when(cipher.decrypt(org.mockito.ArgumentMatchers.eq("key-conn-g"))).thenReturn("key-conn-g");
        var selector = new AiFallbackRouteSelector(jdbc, new tools.jackson.databind.ObjectMapper(), cipher);

        var selection = selector.select(42L, List.of("TEXT", "JSON_SCHEMA"), 60_000, 30_000);

        // 入选：a（能力全 + 授权）、e（空授权=不限制）、i（裸数组能力形态）；密钥为材料化后的值。
        assertThat(selection.routes()).hasSize(3);
        assertThat(selection.routes().get(0).connectionId()).isEqualTo("conn-a");
        assertThat(selection.routes().get(0).apiKey()).isEqualTo("materialized-key-conn-a");
        assertThat(selection.routes().get(1).connectionId()).isEqualTo("conn-e");
        assertThat(selection.routes().get(2).connectionId()).isEqualTo("conn-i");
        assertThat(selection.rejected()).hasSize(6);
        assertThat(selection.rejected()).extracting("rejection").containsExactlyInAnyOrder(
            "missing_capability:JSON_SCHEMA", "project_not_authorized", "connection_disabled",
            "project_not_authorized", "credential_unresolved", "project_not_authorized");
        assertThat(AiFallbackRouteSelector.RULE_VERSION).isEqualTo("v1");

        // 预算前提：整体截止已到（budget<=0）⇒ 不产出任何路由。
        assertThat(selector.select(42L, List.of("TEXT"), 0, 30_000).routes()).isEmpty();
    }

    private java.util.Map<String, Object> selectionRow(String connectionId, String profileId,
                                                       String capabilitiesJson, String allowedProjectsJson, boolean enabled) {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("connection_id", connectionId);
        row.put("profile_id", profileId);
        row.put("version", 1);
        row.put("model_id", "model-" + connectionId);
        row.put("capabilities_json", capabilitiesJson);
        row.put("base_url", "https://" + connectionId + ".test/v1");
        row.put("protocol", "openai");
        row.put("credential_ref", "key-" + connectionId);
        row.put("allowed_projects_json", allowedProjectsJson);
        row.put("enabled", enabled);
        return row;
    }

    private AiRoutingStrategy strategy(int retryBudget, int perCallTimeoutMs) {
        AiRoute route = new AiRoute("conn-1", "prov", "openai", "https://provider.test/v1", "model-1", "key-1", perCallTimeoutMs);
        return new AiRoutingStrategy("snap-1", "requirement_analysis", List.of(route), retryBudget, perCallTimeoutMs, 900_000, null);
    }

    private AiRouteExecutionContext ctx(String runId) {
        return new AiRouteExecutionContext(runId, "snap-1", 0, 1, "chunk-1");
    }
}
