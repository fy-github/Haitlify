package com.haitlify.aitest.platform.ai.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.haitlify.aitest.platform.ai.event.AiRunEventService;
import com.haitlify.aitest.platform.ai.gateway.AiConnectionTestResult;
import com.haitlify.aitest.platform.ai.gateway.AiGateway;
import com.haitlify.aitest.platform.ai.gateway.AiGatewayConfig;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.ai.gateway.AiRoute;
import com.haitlify.aitest.platform.ai.gateway.AiRouteExecutionContext;
import com.haitlify.aitest.platform.ai.gateway.AiRouteExecutionResult;
import com.haitlify.aitest.platform.ai.gateway.AiRouteExecutor;
import com.haitlify.aitest.platform.ai.gateway.AiRoutingStrategy;
import com.haitlify.aitest.platform.ai.gateway.AiVisionRequest;
import com.haitlify.aitest.platform.ai.gateway.AiVisionResponse;

/**
 * T07/T13 收敛的行为证明——与
 * {@link com.haitlify.aitest.platform.ai.runtime.AiRuntimeConsumerCoverageTest} 的源码扫描互补：
 * 扫描证明「没有旁路」（业务层不得出现 gateway 直连调用点），本类证明「旁路被替换成了什么」：
 *
 * <p>路由执行器（AiRouteExecutor）每一次 provider 调用都经统一 Runtime facade——
 * 台账时间上限（T05）、两阶段 attempt 落库（T06）、token 预算 reserve/settle（T13）
 * 与失败分类对路由调用逐项生效；路由选择/重试/取消编排仍由执行器负责。
 */
class AiModelCallConvergenceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void everyRouteCallIsRecordedAuditedAndBudgetedThroughTheFacade() {
        StubGateway gateway = new StubGateway();
        AiAttemptRepository attempts = mock(AiAttemptRepository.class);
        when(attempts.begin(any(), any(), any(), any(), any(), any())).thenReturn("attempt-converge");
        com.haitlify.aitest.platform.ai.quota.AiBudgetService budget =
            mock(com.haitlify.aitest.platform.ai.quota.AiBudgetService.class);
        when(budget.reserve(anyString(), any(), any(), anyLong(), anyInt(), anyString()))
            .thenReturn(new com.haitlify.aitest.platform.ai.quota.AiBudgetService.Reservation(
                "res-1", "scope", java.time.LocalDate.of(2026, 10, 12), 100, 1));

        AiExecutionLedger ledger = new AiExecutionLedger();
        ledger.open("run-converge", NOW.plusSeconds(300));
        AiTaskExecutionFacade facade = new AiTaskExecutionFacade(
            null, gateway, ledger, attempts, budget, 1_000_000L, 1000, "", "");
        facade.useClock(FIXED); // 台账剩余必须与执行器使用同一时钟
        AiRouteExecutor executor = new AiRouteExecutor(facade, mock(AiRunEventService.class),
            new AiCancellationRegistry(), ledger, FIXED,
            new AiRetryPolicy(millis -> { }, FIXED, 1_000, 30_000, 0.0, () -> 0), "", "");

        AiRouteExecutionResult result = executor.execute(
            new AiRoutingStrategy("snap-1", "requirement_analysis", List.of(route()), 0, 300_000, 900_000, null),
            "提示词", new AiRouteExecutionContext("run-converge", "snap-1", 0, 1, "chunk-1",
                "requirement_analysis", 1L));

        assertThat(result.success()).isTrue();
        // 路由语义不变：请求到达网关一次，且携带路由配置（模型来自路由，不是默认档案）
        assertThat(gateway.configs).hasSize(1);
        assertThat(gateway.configs.get(0).model()).isEqualTo("model-1");
        assertThat(gateway.configs.get(0).baseUrl()).isEqualTo("https://provider.test/v1");

        // T06：两阶段 attempt 落库（begin + 终态）——这正是旁路路径此前缺失的审计
        verify(attempts).begin(eq(""), eq("requirement_analysis"), eq("run-converge"), anyString(), anyString(), eq(""));
        verify(attempts).finalize(eq("attempt-converge"), any(AiAttemptRepository.AttemptOutcome.class));

        // T13：token 预算 reserve（global/project/task 三 scope）与成功后的 usage 结算
        verify(budget, times(3)).reserve(anyString(), any(), any(), anyLong(), eq(1), eq("attempt-converge"));
        verify(budget, times(3)).settle(eq("res-1"), anyLong(), eq(1), eq(true), any());
    }

    @Test
    void routeBudgetBlockFailsClosedWithoutCallingTheProvider() {
        StubGateway gateway = new StubGateway();
        AiAttemptRepository attempts = mock(AiAttemptRepository.class);
        when(attempts.begin(any(), any(), any(), any(), any(), any())).thenReturn("attempt-blocked");
        com.haitlify.aitest.platform.ai.quota.AiBudgetService budget =
            mock(com.haitlify.aitest.platform.ai.quota.AiBudgetService.class);
        when(budget.reserve(anyString(), any(), any(), anyLong(), anyInt(), anyString()))
            .thenThrow(new com.haitlify.aitest.platform.ai.quota.AiBudgetService.BudgetExceededException("daily limit"));

        AiExecutionLedger ledger = new AiExecutionLedger();
        ledger.open("run-budget", NOW.plusSeconds(300));
        AiTaskExecutionFacade facade = new AiTaskExecutionFacade(
            null, gateway, ledger, attempts, budget, 1_000_000L, 1000, "", "");
        facade.useClock(FIXED); // 台账剩余必须与执行器使用同一时钟
        AiRouteExecutor executor = new AiRouteExecutor(facade, mock(AiRunEventService.class),
            new AiCancellationRegistry(), ledger, FIXED,
            new AiRetryPolicy(millis -> { }, FIXED, 1_000, 30_000, 0.0, () -> 0), "", "");

        AiRouteExecutionResult result = executor.execute(
            new AiRoutingStrategy("snap-1", "requirement_analysis", List.of(route()), 0, 300_000, 900_000, null),
            "提示词", new AiRouteExecutionContext("run-budget", "snap-1", 0, 1, "chunk-1",
                "requirement_analysis", 1L));

        // 预算不足：零 provider 请求，终态不可重试（fail-closed，绝不重试/切换放大消耗）
        assertThat(result.success()).isFalse();
        assertThat(gateway.configs).isEmpty();
        assertThat(result.terminalReason()).isNotNull();
        assertThat(result.terminalReason().retryable()).isFalse();
    }

    private AiRoute route() {
        return new AiRoute("conn-1", "prov", "openai", "https://provider.test/v1", "model-1", "key-1", 300_000);
    }

    private static final class StubGateway implements AiGateway {

        final List<AiGatewayConfig> configs = new ArrayList<>();

        @Override
        public AiConnectionTestResult testConnection(AiGatewayConfig config) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AiGenerationResult structuredGenerate(AiGatewayConfig config, Object prompt) {
            configs.add(config);
            return new AiGenerationResult(true, "{\"draftTestPoints\": []}", "req-converge", 5, 7, null, false, "", 0);
        }

        @Override
        public AiVisionResponse visionAnalyze(AiGatewayConfig config, AiVisionRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
