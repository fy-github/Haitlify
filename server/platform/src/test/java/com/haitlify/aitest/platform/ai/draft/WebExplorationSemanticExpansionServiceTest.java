package com.haitlify.aitest.platform.ai.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;

import com.haitlify.aitest.platform.ai.gateway.AiGateway;
import com.haitlify.aitest.platform.ai.gateway.AiGatewayConfig;
import com.haitlify.aitest.platform.ai.gateway.AiGatewaySettingsService;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.webautomation.WebAutomationLocatorDiscoveryService;

import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

class WebExplorationSemanticExpansionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * facade 以「直通 mock gateway」方式注入：既有 gateway stub / verify 逐字保留生效。
     */
    private static com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade facadeFor(AiGateway gateway) {
        var facade = Mockito.mock(com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade.class);
        Mockito.when(facade.generate(Mockito.any())).thenAnswer(invocation -> {
            var task = (com.haitlify.aitest.platform.ai.runtime.AiTaskExecutionFacade.TaskExecution) invocation.getArgument(0);
            return gateway.structuredGenerate(new AiGatewayConfig(
                "test-provider", "http://test.local", "test-model", "", "test-key", 30_000),
                task.prompt());
        });
        return facade;
    }

    @Test
    void flagOffDoesNotCallGatewayAndKeepsDeterministicIntent() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        AiGateway gateway = Mockito.mock(AiGateway.class);
        var service = new WebExplorationSemanticExpansionService(jdbc, objectMapper, facadeFor(gateway));
        var intent = intent();

        assertThat(service.expand(intent, List.of("恢复服务资格"))).isEqualTo(intent);
        verify(gateway, never()).structuredGenerate(any(), any());
    }

    @Test
    void explicitDisableBypassesGatewayEvenWhenConfigWouldEnableIt() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        AiGateway gateway = Mockito.mock(AiGateway.class);
        AiGatewaySettingsService settings = Mockito.mock(AiGatewaySettingsService.class);
        var service = new WebExplorationSemanticExpansionService(jdbc, objectMapper, facadeFor(gateway));

        var result = service.expandWithDiagnostics(intent(), List.of("目标页面"), false);

        assertThat(result.intent()).isEqualTo(intent());
        assertThat(result.fallbackReason()).isEqualTo("disabled");
        verify(gateway, never()).structuredGenerate(any(), any());
    }

    @Test
    void flagOnMakesBoundedJsonCallAndMergesValidatedTerms() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        AiGateway gateway = Mockito.mock(AiGateway.class);
        AiGatewaySettingsService settings = Mockito.mock(AiGatewaySettingsService.class);
        when(jdbc.queryForObject(any(String.class), any(Class.class))).thenReturn("{\"webExplorationSemanticExpansionEnabled\":true}");
        when(settings.loadDefaultTextSettings()).thenReturn(new com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings("test", "https://ai.test", "model", "key", 100));
        when(gateway.structuredGenerate(any(AiGatewayConfig.class), any())).thenReturn(new AiGenerationResult(true,
            "{\"pageAliases\":[\"资格详情\"],\"elementTerms\":[\"恢复服务资格\",\"状态列\"],\"stateTerms\":[\"灰度中\"],\"actionTerms\":[\"查看\"]}", "r1", 1, 1, null));

        var result = new WebExplorationSemanticExpansionService(jdbc, objectMapper, facadeFor(gateway)).expand(intent(), List.of(
            "https://example.test/detail?token=SECRET#frag", "Authorization: Bearer SECRET", "rowContext=权益已失效"
        ));

        assertThat(result.pageAliases()).contains("资格详情");
        assertThat(result.elementTerms()).contains("恢复服务资格", "状态列").doesNotContain("删除");
        assertThat(result.stateTerms()).contains("灰度中");
        verify(gateway).structuredGenerate(any(AiGatewayConfig.class), any());
    }

    @Test
    void validatesAndReturnsOnlySafeRepairSuggestions() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        AiGateway gateway = Mockito.mock(AiGateway.class);
        AiGatewaySettingsService settings = Mockito.mock(AiGatewaySettingsService.class);
        when(jdbc.queryForObject(any(String.class), any(Class.class))).thenReturn("{\"webExplorationSemanticExpansionEnabled\":true}");
        when(settings.loadDefaultTextSettings()).thenReturn(new com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings("test", "https://ai.test", "model", "key", 100));
        when(gateway.structuredGenerate(any(AiGatewayConfig.class), any())).thenReturn(new AiGenerationResult(true,
            "{\"pageAliases\":[\"资格详情\"],\"elementTerms\":[\"恢复服务资格\"],\"stateTerms\":[\"灰度中\"],\"actionTerms\":[\"查看\"],\"repairSuggestion\":{\"category\":\"wait_ready\",\"reason\":\"等待业务容器\"}}",
            "r-safe", 1, 1, null));

        var result = new WebExplorationSemanticExpansionService(jdbc, objectMapper, facadeFor(gateway))
            .expandWithDiagnostics(intent(), List.of("目标页面"));
        assertThat(result.repairSuggestion()).isNotNull();
        assertThat(result.repairSuggestion().category()).isEqualTo("wait_ready");
        assertThat(result.intent().pageAliases()).contains("资格详情");
        assertThat(result.fallbackReason()).isEqualTo("");

        when(gateway.structuredGenerate(any(AiGatewayConfig.class), any())).thenReturn(new AiGenerationResult(true,
            "{\"pageAliases\":[\"资格详情\"],\"elementTerms\":[\"恢复服务资格\"],\"stateTerms\":[\"灰度中\"],\"actionTerms\":[\"查看\"],\"repairSuggestion\":{\"category\":\"execute_javascript\",\"reason\":\"unsafe\"}}",
            "r-unsafe", 1, 1, null));
        var rejected = new WebExplorationSemanticExpansionService(jdbc, objectMapper, facadeFor(gateway))
            .expandWithDiagnostics(intent(), List.of("另一个页面"));
        assertThat(rejected.repairSuggestion()).isNull();
        assertThat(rejected.intent().pageAliases()).contains("资格详情");
        assertThat(rejected.intent().elementTerms()).contains("恢复服务资格");
        assertThat(rejected.intent().stateTerms()).contains("灰度中");
        assertThat(rejected.fallbackReason()).isEqualTo("unsafe_repair_suggestion");
    }

    @Test
    void malformedProviderFailureAndTimeoutFallBackWithoutThrowing() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        AiGateway gateway = Mockito.mock(AiGateway.class);
        AiGatewaySettingsService settings = Mockito.mock(AiGatewaySettingsService.class);
        when(jdbc.queryForObject(any(String.class), any(Class.class))).thenReturn("{\"webExplorationSemanticExpansionEnabled\":true}");
        when(settings.loadDefaultTextSettings()).thenReturn(new com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings("test", "https://ai.test", "model", "key", 100));
        when(gateway.structuredGenerate(any(AiGatewayConfig.class), any())).thenReturn(new AiGenerationResult(true, "not-json", "r1", 1, 1, null));

        var intent = intent();
        var result = new WebExplorationSemanticExpansionService(jdbc, objectMapper, facadeFor(gateway))
            .expandWithDiagnostics(intent, List.of("x"));
        assertThat(result.intent()).isEqualTo(intent);
        assertThat(result.fallbackReason()).isEqualTo("malformed_output");
    }

    @Test
    void configMissingFallsBackWithoutCallingGateway() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        AiGateway gateway = Mockito.mock(AiGateway.class);
        AiGatewaySettingsService settings = Mockito.mock(AiGatewaySettingsService.class);
        when(jdbc.queryForObject(any(String.class), any(Class.class))).thenReturn("{\"webExplorationSemanticExpansionEnabled\":true}");
        when(settings.loadDefaultTextSettings()).thenThrow(new IllegalStateException("AI 配置未完成"));

        var facade = facadeFor(gateway);
        // 配置缺失在真实链路里由 facade 内的设置加载抛出（IllegalStateException）——
        // mock facade 需复现同一异常类型，服务侧 config_missing 映射才被触发。
        // doThrow 风格：避免 when(...) 触发 mock 上既有的直通 answer。
        Mockito.doThrow(new IllegalStateException("AI 配置未完成")).when(facade).generate(Mockito.any());
        var service = new WebExplorationSemanticExpansionService(jdbc, objectMapper, facade);
        var result = service.expandWithDiagnostics(intent(), List.of("x"));

        assertThat(result.intent()).isEqualTo(intent());
        assertThat(result.fallbackReason()).isEqualTo("config_missing");
        verify(gateway, never()).structuredGenerate(any(), any());
    }

    @Test
    void providerFailureFallsBackWithoutMutatingIntent() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        AiGateway gateway = Mockito.mock(AiGateway.class);
        AiGatewaySettingsService settings = Mockito.mock(AiGatewaySettingsService.class);
        when(jdbc.queryForObject(any(String.class), any(Class.class))).thenReturn("{\"webExplorationSemanticExpansionEnabled\":true}");
        when(settings.loadDefaultTextSettings()).thenReturn(new com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings("test", "https://ai.test", "model", "key", 100));
        when(gateway.structuredGenerate(any(AiGatewayConfig.class), any())).thenReturn(new AiGenerationResult(false, "", "r-failure", 0, 0, "upstream_error"));

        var service = new WebExplorationSemanticExpansionService(jdbc, objectMapper, facadeFor(gateway));
        var result = service.expandWithDiagnostics(intent(), List.of("x"));

        assertThat(result.intent()).isEqualTo(intent());
        assertThat(result.fallbackReason()).isEqualTo("provider_failure");
    }

    @Test
    void providerFailureCanBeRetriedWithSameIntent() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        AiGateway gateway = Mockito.mock(AiGateway.class);
        AiGatewaySettingsService settings = Mockito.mock(AiGatewaySettingsService.class);
        when(jdbc.queryForObject(any(String.class), any(Class.class))).thenReturn("{\"webExplorationSemanticExpansionEnabled\":true}");
        when(settings.loadDefaultTextSettings()).thenReturn(new com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings("test", "https://ai.test", "model", "key", 100));
        when(gateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(false, "", "r-failure-1", 0, 0, "upstream_error"))
            .thenReturn(new AiGenerationResult(true,
                "{\"pageAliases\":[\"资格详情\"],\"elementTerms\":[\"恢复服务资格\"],\"stateTerms\":[\"灰度中\"],\"actionTerms\":[\"查看\"]}",
                "r-success-1", 1, 1, null));

        var service = new WebExplorationSemanticExpansionService(jdbc, objectMapper, facadeFor(gateway));
        var first = service.expandWithDiagnostics(intent(), List.of("x"));
        var second = service.expandWithDiagnostics(intent(), List.of("x"));

        assertThat(first.fallbackReason()).isEqualTo("provider_failure");
        assertThat(first.intent()).isEqualTo(intent());
        assertThat(second.fallbackReason()).isEqualTo("");
        assertThat(second.intent().pageAliases()).contains("资格详情");
        verify(gateway, Mockito.times(2)).structuredGenerate(any(AiGatewayConfig.class), any());
    }

    @Test
    void concurrentRequestsShareTheSameInFlightExpansion() throws Exception {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        AiGateway gateway = Mockito.mock(AiGateway.class);
        AiGatewaySettingsService settings = Mockito.mock(AiGatewaySettingsService.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(jdbc.queryForObject(any(String.class), any(Class.class))).thenReturn("{\"webExplorationSemanticExpansionEnabled\":true}");
        when(settings.loadDefaultTextSettings()).thenReturn(new com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings("test", "https://ai.test", "model", "key", 100));
        when(gateway.structuredGenerate(any(AiGatewayConfig.class), any())).thenAnswer(invocation -> {
            started.countDown();
            release.await(2, TimeUnit.SECONDS);
            return new AiGenerationResult(true,
                "{\"pageAliases\":[\"资格详情\"],\"elementTerms\":[\"恢复服务资格\"],\"stateTerms\":[\"已启用\"],\"actionTerms\":[\"查看\"]}",
                "r-shared", 1, 1, null);
        });

        var service = new WebExplorationSemanticExpansionService(jdbc, objectMapper, facadeFor(gateway));
        var first = CompletableFuture.supplyAsync(() -> service.expandWithDiagnostics(intent(), List.of("x")));
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        var second = CompletableFuture.supplyAsync(() -> service.expandWithDiagnostics(intent(), List.of("x")));
        release.countDown();

        assertThat(first.get(3, TimeUnit.SECONDS).intent().pageAliases()).contains("资格详情");
        assertThat(second.get(3, TimeUnit.SECONDS).intent().pageAliases()).contains("资格详情");
        verify(gateway, Mockito.times(1)).structuredGenerate(any(AiGatewayConfig.class), any());
    }

    @Test
    void cachedExpansionReturnsTheExpandedIntentInsteadOfTheOriginalIntent() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        AiGateway gateway = Mockito.mock(AiGateway.class);
        AiGatewaySettingsService settings = Mockito.mock(AiGatewaySettingsService.class);
        when(jdbc.queryForObject(any(String.class), any(Class.class))).thenReturn("{\"webExplorationSemanticExpansionEnabled\":true}");
        when(settings.loadDefaultTextSettings()).thenReturn(new com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings("test", "https://ai.test", "model", "key", 100));
        when(gateway.structuredGenerate(any(AiGatewayConfig.class), any())).thenReturn(new AiGenerationResult(true,
            "{\"pageAliases\":[\"资格详情\"],\"elementTerms\":[\"恢复服务资格\"],\"stateTerms\":[\"已启用\"],\"actionTerms\":[\"查看\"]}",
            "r-cache-result", 1, 1, null));

        var service = new WebExplorationSemanticExpansionService(jdbc, objectMapper, facadeFor(gateway));
        service.expandWithDiagnostics(intent(), List.of("cache-source"));
        var cached = service.expandWithDiagnostics(intent(), List.of("cache-source"));

        assertThat(cached.fallbackReason()).isEqualTo("");
        assertThat(cached.intent().pageAliases()).contains("资格详情");
        verify(gateway, Mockito.times(1)).structuredGenerate(any(AiGatewayConfig.class), any());
    }

    @Test
    void rejectsSemanticOutputsWithForbiddenFields() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        AiGateway gateway = Mockito.mock(AiGateway.class);
        AiGatewaySettingsService settings = Mockito.mock(AiGatewaySettingsService.class);
        when(jdbc.queryForObject(any(String.class), any(Class.class))).thenReturn("{\"webExplorationSemanticExpansionEnabled\":true}");
        when(settings.loadDefaultTextSettings()).thenReturn(new com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings("test", "https://ai.test", "model", "key", 100));
        when(gateway.structuredGenerate(any(AiGatewayConfig.class), any())).thenReturn(new AiGenerationResult(true,
            "{\"pageAliases\":[\"资格详情\"],\"origin\":\"https://example.test/detail?token=SECRET\",\"elementTerms\":[\"恢复服务资格\"],\"stateTerms\":[\"灰度中\"],\"actionTerms\":[\"查看\"]}",
            "r-forbidden", 1, 1, null));

        var service = new WebExplorationSemanticExpansionService(jdbc, objectMapper, facadeFor(gateway));
        var result = service.expandWithDiagnostics(intent(), List.of("x"));

        assertThat(result.intent()).isEqualTo(intent());
        assertThat(result.repairSuggestion()).isNull();
        assertThat(result.fallbackReason()).isEqualTo("malformed_output");
    }

    @Test
    void keepsSafeStateTermsAndRejectsSubmissionOrDeletionSemantics() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        AiGateway gateway = Mockito.mock(AiGateway.class);
        AiGatewaySettingsService settings = Mockito.mock(AiGatewaySettingsService.class);
        when(jdbc.queryForObject(any(String.class), any(Class.class))).thenReturn("{\"webExplorationSemanticExpansionEnabled\":true}");
        when(settings.loadDefaultTextSettings()).thenReturn(new com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings("test", "https://ai.test", "model", "key", 100));
        when(gateway.structuredGenerate(any(AiGatewayConfig.class), any())).thenReturn(new AiGenerationResult(true,
            "{\"pageAliases\":[\"账号详情\"],\"elementTerms\":[\"状态列\"],\"stateTerms\":[\"已启用\",\"已停用\",\"在线\",\"离线\",\"可见\",\"启用状态\"],\"actionTerms\":[\"查看\"]}",
            "r-state-safe", 1, 1, null));

        var intent = new WebAutomationLocatorDiscoveryService.TargetIntent(
            List.of("账号详情"), List.of("/account"), List.of("状态列"), List.of("查看"),
            List.of(), List.of("账号正常"), List.of()
        );
        var result = new WebExplorationSemanticExpansionService(jdbc, objectMapper, facadeFor(gateway))
            .expandWithDiagnostics(intent, List.of("账号详情"));

        assertThat(result.intent().stateTerms())
            .contains("已启用", "已停用", "在线", "离线", "可见", "启用状态")
            .doesNotContain("已删除", "待提交", "已保存");
    }

    @Test
    void rejectsStateTermsThatEncodeDeletionOrSubmissionSemantics() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        AiGateway gateway = Mockito.mock(AiGateway.class);
        AiGatewaySettingsService settings = Mockito.mock(AiGatewaySettingsService.class);
        when(jdbc.queryForObject(any(String.class), any(Class.class))).thenReturn("{\"webExplorationSemanticExpansionEnabled\":true}");
        when(settings.loadDefaultTextSettings()).thenReturn(new com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings("test", "https://ai.test", "model", "key", 100));
        when(gateway.structuredGenerate(any(AiGatewayConfig.class), any())).thenReturn(new AiGenerationResult(true,
            "{\"pageAliases\":[\"账号详情\"],\"elementTerms\":[\"状态列\"],\"stateTerms\":[\"已删除\",\"待提交\",\"已保存\"],\"actionTerms\":[\"查看\"]}",
            "r-state-reject", 1, 1, null));

        var intent = new WebAutomationLocatorDiscoveryService.TargetIntent(
            List.of("账号详情"), List.of("/account"), List.of("状态列"), List.of("查看"),
            List.of(), List.of("账号正常"), List.of()
        );
        var result = new WebExplorationSemanticExpansionService(jdbc, objectMapper, facadeFor(gateway))
            .expandWithDiagnostics(intent, List.of("账号详情"));

        assertThat(result.intent()).isEqualTo(intent);
        assertThat(result.fallbackReason()).isEqualTo("malformed_output");
    }

    @Test
    void malformedOutputCanBeRetriedWithSameIntent() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        AiGateway gateway = Mockito.mock(AiGateway.class);
        AiGatewaySettingsService settings = Mockito.mock(AiGatewaySettingsService.class);
        when(jdbc.queryForObject(any(String.class), any(Class.class))).thenReturn("{\"webExplorationSemanticExpansionEnabled\":true}");
        when(settings.loadDefaultTextSettings()).thenReturn(new com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings("test", "https://ai.test", "model", "key", 100));
        when(gateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, "not-json", "r-malformed-1", 1, 1, null))
            .thenReturn(new AiGenerationResult(true,
                "{\"pageAliases\":[\"资格详情\"],\"elementTerms\":[\"恢复服务资格\"],\"stateTerms\":[\"灰度中\"],\"actionTerms\":[\"查看\"]}",
                "r-success-2", 1, 1, null));

        var service = new WebExplorationSemanticExpansionService(jdbc, objectMapper, facadeFor(gateway));
        var first = service.expandWithDiagnostics(intent(), List.of("x"));
        var second = service.expandWithDiagnostics(intent(), List.of("x"));

        assertThat(first.fallbackReason()).isEqualTo("malformed_output");
        assertThat(first.intent()).isEqualTo(intent());
        assertThat(second.fallbackReason()).isEqualTo("");
        assertThat(second.intent().pageAliases()).contains("资格详情");
        verify(gateway, Mockito.times(2)).structuredGenerate(any(AiGatewayConfig.class), any());
    }

    @Test
    void sanitizesUrlsSecretsAndCapsSources() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        var service = new WebExplorationSemanticExpansionService(jdbc, objectMapper, facadeFor(Mockito.mock(AiGateway.class)));
        String longText = "a".repeat(3000);
        String sanitized = service.sanitizeExpansionInput(List.of(
            "https://example.test/path?q=secret#frag Authorization: Bearer SECRET password=PW token=TK apiKey=AK secretKey=SK",
            longText,
            "rowContext=恢复服务资格"
        ));
        assertThat(sanitized).doesNotContain("secret", "SECRET", "PW", "TK", "AK", "SK", "?q=", "#frag");
        assertThat(sanitized.length()).isLessThanOrEqualTo(8000);
        assertThat(sanitized).doesNotContain("a".repeat(2401));
    }

    @Test
    void removesSensitiveRoutePartsFromSemanticExpansionPrompt() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        AiGateway gateway = Mockito.mock(AiGateway.class);
        AiGatewaySettingsService settings = Mockito.mock(AiGatewaySettingsService.class);
        when(jdbc.queryForObject(any(String.class), any(Class.class)))
            .thenReturn("{\"webExplorationSemanticExpansionEnabled\":true}");
        when(settings.loadDefaultTextSettings()).thenReturn(new com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings(
            "test", "https://ai.test", "model", "key", 100
        ));
        when(gateway.structuredGenerate(any(AiGatewayConfig.class), any())).thenReturn(new AiGenerationResult(
            true, "{}", "r-route", 1, 1, null
        ));
        var service = new WebExplorationSemanticExpansionService(jdbc, objectMapper, facadeFor(gateway));
        var intent = new WebAutomationLocatorDiscoveryService.TargetIntent(
            List.of("详情"), List.of("https://example.test/detail?access_token=SECRET#private"),
            List.of("状态"), List.of("查看"), List.of("在线"), List.of("状态正常"), List.of()
        );

        service.expand(intent, List.of("目标详情"));

        ArgumentCaptor<tools.jackson.databind.JsonNode> prompts = ArgumentCaptor.forClass(tools.jackson.databind.JsonNode.class);
        verify(gateway).structuredGenerate(any(AiGatewayConfig.class), prompts.capture());
        assertThat(prompts.getValue().toString()).doesNotContain("access_token", "SECRET", "private", "?", "#");
    }

    @Test
    void keepsIntentValidWhenNegativeTermsAreNull() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        AiGateway gateway = Mockito.mock(AiGateway.class);
        var service = new WebExplorationSemanticExpansionService(
            jdbc, objectMapper, facadeFor(gateway)
        );
        var intent = new WebAutomationLocatorDiscoveryService.TargetIntent(
            List.of("冻结资格"), List.of("/freeze"), List.of("恢复服务资格"), List.of("查看"),
            List.of("冻结"), List.of("资格已失效"), null
        );

        assertThat(service.expand(intent, List.of("冻结资格详情"))).isEqualTo(intent);
    }

    @Test
    void mergesExpansionWhenNegativeTermsAreNull() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        AiGateway gateway = Mockito.mock(AiGateway.class);
        AiGatewaySettingsService settings = Mockito.mock(AiGatewaySettingsService.class);
        when(jdbc.queryForObject(any(String.class), any(Class.class)))
            .thenReturn("{\"webExplorationSemanticExpansionEnabled\":true}");
        when(settings.loadDefaultTextSettings()).thenReturn(new com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings(
            "test", "https://ai.test", "model", "key", 100
        ));
        when(gateway.structuredGenerate(any(AiGatewayConfig.class), any())).thenReturn(new AiGenerationResult(
            true, "{\"pageAliases\":[\"资格详情\"],\"elementTerms\":[\"恢复服务资格\"]}", "r-null-negative", 1, 1, null
        ));
        var service = new WebExplorationSemanticExpansionService(jdbc, objectMapper, facadeFor(gateway));
        var intent = new WebAutomationLocatorDiscoveryService.TargetIntent(
            List.of("冻结资格"), List.of("/freeze"), List.of("恢复服务资格"), List.of("查看"),
            List.of("冻结"), List.of("资格已失效"), null
        );

        var expanded = service.expand(intent, List.of("冻结资格详情"));
        assertThat(expanded.pageAliases()).contains("资格详情");
        assertThat(expanded.elementTerms()).contains("恢复服务资格");
        assertThat(expanded.negativeTerms()).isEmpty();
    }

    @Test
    void boundsExpansionDeduplicationCache() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        AiGateway gateway = Mockito.mock(AiGateway.class);
        AiGatewaySettingsService settings = Mockito.mock(AiGatewaySettingsService.class);
        when(jdbc.queryForObject(any(String.class), any(Class.class)))
            .thenReturn("{\"webExplorationSemanticExpansionEnabled\":true}");
        when(settings.loadDefaultTextSettings()).thenReturn(new com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings(
            "test", "https://ai.test", "model", "key", 100
        ));
        when(gateway.structuredGenerate(any(AiGatewayConfig.class), any())).thenReturn(new AiGenerationResult(
            true, "{}", "r-cache", 1, 1, null
        ));
        var service = new WebExplorationSemanticExpansionService(jdbc, objectMapper, facadeFor(gateway));

        for (int index = 0; index < 1_100; index++) {
            var intent = new WebAutomationLocatorDiscoveryService.TargetIntent(
                List.of("页面" + index), List.of("/page-" + index), List.of("目标" + index),
                List.of("查看"), List.of("状态"), List.of("结果"), List.of()
            );
            service.expand(intent, List.of("source-" + index));
        }

        assertThat(service.expansionCacheSize()).isLessThanOrEqualTo(1_024);
    }

    @Test
    void boundedExpansionKeepsOnlySafeTerms() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        AiGateway gateway = Mockito.mock(AiGateway.class);
        AiGatewaySettingsService settings = Mockito.mock(AiGatewaySettingsService.class);
        when(jdbc.queryForObject(any(String.class), any(Class.class))).thenReturn("{\"webExplorationSemanticExpansionEnabled\":true}");
        when(settings.loadDefaultTextSettings()).thenReturn(new com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings("test", "https://ai.test", "model", "key", 100));
        when(gateway.structuredGenerate(any(AiGatewayConfig.class), any())).thenReturn(new AiGenerationResult(true, """
            {"pageAliases":["资格详情","资格详情","页面1","页面2","页面3","页面4","页面5","页面6","页面7","页面8","页面9"],
             "elementTerms":["恢复服务资格","恢复服务资格","状态列","入口","按钮","提示","详情","列表","操作列"],
             "stateTerms":["灰度中","灰度中","冻结","失效","在线","离线","成功"],
             "actionTerms":["查看","点击","切换","筛选"],
             "repairSuggestion":{"category":"wait_ready","reason":"等待页面稳定后重试"}}
            """, "r-bounded", 1, 1, null));

        var service = new WebExplorationSemanticExpansionService(jdbc, objectMapper, facadeFor(gateway));
        var result = service.expandWithDiagnostics(intent(), List.of("目标页面"));

        assertThat(result.intent().pageAliases()).contains("冻结资格", "资格详情");
        assertThat(result.intent().pageAliases().size()).isLessThanOrEqualTo(8);
        assertThat(result.intent().elementTerms()).contains("恢复服务资格", "状态列", "入口", "按钮", "提示", "详情", "操作列");
        assertThat(result.intent().elementTerms()).doesNotContain("删除");
        assertThat(result.intent().elementTerms().size()).isLessThanOrEqualTo(8);
        assertThat(result.intent().stateTerms()).contains("灰度中", "冻结", "失效");
        assertThat(result.intent().stateTerms().size()).isLessThanOrEqualTo(6);
        assertThat(result.intent().actionTerms()).contains("查看", "点击", "切换", "筛选");
        assertThat(result.intent().actionTerms().size()).isLessThanOrEqualTo(4);
        assertThat(result.repairSuggestion()).isNotNull();
        assertThat(result.repairSuggestion().category()).isEqualTo("wait_ready");
    }

    @Test
    void keepsStateTermsThatDescribeEnabledOrDisabledStatus() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        AiGateway gateway = Mockito.mock(AiGateway.class);
        AiGatewaySettingsService settings = Mockito.mock(AiGatewaySettingsService.class);
        when(jdbc.queryForObject(any(String.class), any(Class.class))).thenReturn("{\"webExplorationSemanticExpansionEnabled\":true}");
        when(settings.loadDefaultTextSettings()).thenReturn(new com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings("test", "https://ai.test", "model", "key", 100));
        when(gateway.structuredGenerate(any(AiGatewayConfig.class), any())).thenReturn(new AiGenerationResult(true,
            "{\"pageAliases\":[\"账号详情\"],\"elementTerms\":[\"状态列\"],\"stateTerms\":[\"已启用\",\"已停用\"],\"actionTerms\":[\"查看\"]}",
            "r-state", 1, 1, null));

        var result = new WebExplorationSemanticExpansionService(jdbc, objectMapper, facadeFor(gateway)).expand(intent(), List.of("账号详情"));

        assertThat(result.stateTerms()).contains("已启用", "已停用");
        assertThat(result.elementTerms()).contains("状态列");
    }

    @Test
    void keepsDistinctBracketedListsFromCollapsingIntoOneSemanticCacheEntry() throws Exception {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        AiGateway gateway = Mockito.mock(AiGateway.class);
        AiGatewaySettingsService settings = Mockito.mock(AiGatewaySettingsService.class);
        when(jdbc.queryForObject(any(String.class), any(Class.class))).thenReturn("{\"webExplorationSemanticExpansionEnabled\":true}");
        when(settings.loadDefaultTextSettings()).thenReturn(new com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings("test", "https://ai.test", "model", "key", 100));
        when(gateway.structuredGenerate(any(AiGatewayConfig.class), any())).thenReturn(new AiGenerationResult(true, "{}", "r-cache", 1, 1, null));
        var service = new WebExplorationSemanticExpansionService(jdbc, objectMapper, facadeFor(gateway));

        var firstIntent = new WebAutomationLocatorDiscoveryService.TargetIntent(
            List.of("a, b"), List.of("/logs"), List.of("告警日志"), List.of("查看"),
            List.of("在线"), List.of("告警日志可见"), List.of("项目")
        );
        var secondIntent = new WebAutomationLocatorDiscoveryService.TargetIntent(
            List.of("a", "b"), List.of("/logs"), List.of("告警日志"), List.of("查看"),
            List.of("在线"), List.of("告警日志可见"), List.of("项目")
        );

        service.expand(firstIntent, List.of("目标页面"));
        service.expand(secondIntent, List.of("目标页面"));

        // rememberExpansion 在 whenComplete 异步回调中执行：慢机上 expand 返回后缓存可能尚未落盘，
        // 直接断言 size 会与回调竞态（CI/全量曾命中 expected 2 but was 1）；这里做有界等待。
        long deadline = System.currentTimeMillis() + 5_000;
        while (service.expansionCacheSize() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(service.expansionCacheSize()).isEqualTo(2);
        verify(gateway, Mockito.times(2)).structuredGenerate(any(AiGatewayConfig.class), any());
    }

    @Test
    void timeoutCancelsBackgroundProviderAndCanBeRetried() throws Exception {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        AiGateway gateway = Mockito.mock(AiGateway.class);
        AiGatewaySettingsService settings = Mockito.mock(AiGatewaySettingsService.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean interruptedFlag = new AtomicBoolean(false);
        when(jdbc.queryForObject(any(String.class), any(Class.class))).thenReturn("{\"webExplorationSemanticExpansionEnabled\":true}");
        when(settings.loadDefaultTextSettings()).thenReturn(new com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings("test", "https://ai.test", "model", "key", 100));
        when(gateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenAnswer(invocation -> {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException exception) {
                    interruptedFlag.set(true);
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return new AiGenerationResult(true, "{}", "r-timeout-1", 1, 1, null);
            })
            .thenReturn(new AiGenerationResult(true,
                "{\"pageAliases\":[\"资格详情\"],\"elementTerms\":[\"恢复服务资格\"],\"stateTerms\":[\"已启用\"],\"actionTerms\":[\"查看\"]}",
                "r-timeout-2", 1, 1, null));

        var service = new WebExplorationSemanticExpansionService(jdbc, objectMapper, facadeFor(gateway));
        var first = service.expandWithDiagnostics(intent(), List.of("x"));
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        var second = service.expandWithDiagnostics(intent(), List.of("x"));

        assertThat(first.intent()).isEqualTo(intent());
        assertThat(first.fallbackReason()).isEqualTo("timeout");
        assertThat(second.fallbackReason()).isEqualTo("");
        assertThat(second.intent().pageAliases()).contains("资格详情");
        assertThat(interruptedFlag.get()).isTrue();
        verify(gateway, Mockito.times(2)).structuredGenerate(any(AiGatewayConfig.class), any());
    }

    @Test
    void interruptedWaiterDoesNotCancelSharedProviderAndAnotherWaiterStillSucceeds() throws Exception {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        AiGateway gateway = Mockito.mock(AiGateway.class);
        AiGatewaySettingsService settings = Mockito.mock(AiGatewaySettingsService.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(jdbc.queryForObject(any(String.class), any(Class.class))).thenReturn("{\"webExplorationSemanticExpansionEnabled\":true}");
        when(settings.loadDefaultTextSettings()).thenReturn(new com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings("test", "https://ai.test", "model", "key", 100));
        when(gateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenAnswer(invocation -> {
                started.countDown();
                try {
                    assertThat(releaseProvider.await(1, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return new AiGenerationResult(true,
                    "{\"pageAliases\":[\"资格详情\"],\"elementTerms\":[\"恢复服务资格\"],\"stateTerms\":[\"已启用\"],\"actionTerms\":[\"查看\"]}",
                    "r-interrupted-1", 1, 1, null);
            })
            .thenReturn(new AiGenerationResult(true,
                "{\"pageAliases\":[\"资格详情\"],\"elementTerms\":[\"恢复服务资格\"],\"stateTerms\":[\"已启用\"],\"actionTerms\":[\"查看\"]}",
                "r-interrupted-2", 1, 1, null));

        var service = new WebExplorationSemanticExpansionService(jdbc, objectMapper, facadeFor(gateway));
        ExecutorService waiterExecutor = Executors.newFixedThreadPool(2);
        try {
            AtomicReference<Thread> firstWaiterThread = new AtomicReference<>();
            AtomicReference<Thread> secondWaiterThread = new AtomicReference<>();
            CompletableFuture<WebExplorationSemanticExpansionService.ExpansionResult> first =
                CompletableFuture.supplyAsync(() -> {
                    firstWaiterThread.set(Thread.currentThread());
                    return service.expandWithDiagnostics(intent(), List.of("x"));
                }, waiterExecutor);

            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<WebExplorationSemanticExpansionService.ExpansionResult> second =
                CompletableFuture.supplyAsync(() -> {
                    secondWaiterThread.set(Thread.currentThread());
                    return service.expandWithDiagnostics(intent(), List.of("x"));
                }, waiterExecutor);

            assertThat(awaitWaiterCount(service, 2, 1, TimeUnit.SECONDS)).isTrue();
            assertThat(firstWaiterThread.get()).isNotNull();
            assertThat(secondWaiterThread.get()).isNotNull();
            firstWaiterThread.get().interrupt();
            releaseProvider.countDown();

            var firstResult = first.get(1, TimeUnit.SECONDS);
            var secondResult = second.get(1, TimeUnit.SECONDS);
            assertThat(firstResult.fallbackReason()).isEqualTo("provider_failure");
            assertThat(secondResult.fallbackReason()).isEqualTo("");
            assertThat(secondResult.intent().pageAliases()).contains("资格详情");
            verify(gateway, Mockito.times(1)).structuredGenerate(any(AiGatewayConfig.class), any());
        } finally {
            waiterExecutor.shutdownNow();
        }
    }

    private boolean awaitWaiterCount(WebExplorationSemanticExpansionService service, int expected, long timeout, TimeUnit unit) throws Exception {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadlineNanos) {
            if (currentWaiterCount(service) == expected) return true;
            Thread.sleep(10);
        }
        return currentWaiterCount(service) == expected;
    }

    private int currentWaiterCount(WebExplorationSemanticExpansionService service) throws Exception {
        Field inFlightField = WebExplorationSemanticExpansionService.class.getDeclaredField("inFlightExpansions");
        inFlightField.setAccessible(true);
        Map<?, ?> inFlight = (Map<?, ?>) inFlightField.get(service);
        if (inFlight.isEmpty()) return 0;
        Object state = inFlight.values().iterator().next();
        Field waiterField = state.getClass().getDeclaredField("waiterCount");
        waiterField.setAccessible(true);
        AtomicInteger waiters = (AtomicInteger) waiterField.get(state);
        return waiters.get();
    }

    @Test
    void sanitizeRouteValueKeepsHashRoutesAndStripsQueryNoise() throws Exception {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        var service = new WebExplorationSemanticExpansionService(
            jdbc, objectMapper, facadeFor(Mockito.mock(AiGateway.class))
        );
        Method method = WebExplorationSemanticExpansionService.class.getDeclaredMethod("sanitizeRouteValue", String.class);
        method.setAccessible(true);

        assertThat(method.invoke(service, "#/logs?from=nav")).isEqualTo("#/logs");
        assertThat(method.invoke(service, "#/alerts")).isEqualTo("#/alerts");
        assertThat(method.invoke(service, "https://example.test/#/alerts?from=nav")).isEqualTo("#/alerts");
    }

    private WebAutomationLocatorDiscoveryService.TargetIntent intent() {
        return new WebAutomationLocatorDiscoveryService.TargetIntent(
            List.of("冻结资格"), List.of("/freeze"), List.of("恢复服务资格"), List.of("查看"),
            List.of("冻结"), List.of("资格已失效"), List.of("项目")
        );
    }
}
