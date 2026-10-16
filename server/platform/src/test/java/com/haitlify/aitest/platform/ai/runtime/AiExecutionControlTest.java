package com.haitlify.aitest.platform.ai.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.haitlify.aitest.platform.ai.event.AiRunEventService;
import com.haitlify.aitest.platform.ai.gateway.AiConnectionTestResult;
import com.haitlify.aitest.platform.ai.gateway.AiGateway;
import com.haitlify.aitest.platform.ai.gateway.AiGatewayConfig;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.ai.gateway.AiRetryReason;
import com.haitlify.aitest.platform.ai.gateway.AiRoute;
import com.haitlify.aitest.platform.ai.gateway.AiRouteExecutionContext;
import com.haitlify.aitest.platform.ai.gateway.AiRouteExecutionResult;
import com.haitlify.aitest.platform.ai.gateway.AiRouteExecutor;
import com.haitlify.aitest.platform.ai.gateway.AiRoutingStrategy;
import com.haitlify.aitest.platform.ai.gateway.AiVisionRequest;
import com.haitlify.aitest.platform.ai.gateway.AiVisionResponse;

/**
 * T05：统一执行控制——假时钟 + 计数 gateway 先行证明：
 * 取消后不再发起调用；整体剩余 8 秒不得发起 300 秒请求（有效超时=最小剩余预算）；
 * 截止已过零调用；等待重试被中断记 INTERRUPTED 而非网络错误。
 */
class AiExecutionControlTest {

    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");

    private final Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void cancelledRunStartsNoFurtherCalls() {
        var registry = new AiCancellationRegistry();
        var ledger = new AiExecutionLedger();
        var gateway = new RecordingGateway();
        var executor = new AiRouteExecutor(gateway, mock(AiRunEventService.class), registry, ledger, fixedClock);
        registry.cancel("run-1");

        AiRouteExecutionResult result = executor.execute(strategy(1, 300_000), "prompt", ctx("run-1"));

        assertThat(gateway.configs).isEmpty();
        assertThat(result.terminalReason()).isEqualTo(AiRetryReason.CANCELLED);
    }

    @Test
    void cancellationDuringACallStopsRetryAndFallback() {
        var registry = new AiCancellationRegistry();
        var ledger = new AiExecutionLedger();
        var gateway = new RecordingGateway();
        gateway.responder = index -> failure(AiRetryReason.UPSTREAM_ERROR);
        gateway.onCall = index -> registry.cancel("run-2");
        var executor = new AiRouteExecutor(gateway, mock(AiRunEventService.class), registry, ledger, fixedClock);

        AiRouteExecutionResult result = executor.execute(strategy(2, 300_000), "prompt", ctx("run-2"));

        // 第一次调用返回后检查到取消：不再重试、不再切换（否则单路由 retryBudget=2 会有第二次调用+退避）。
        assertThat(gateway.configs).hasSize(1);
        assertThat(result.terminalReason()).isEqualTo(AiRetryReason.CANCELLED);
    }

    @Test
    void remainingBudgetCapsThePerCallTimeout() {
        var registry = new AiCancellationRegistry();
        var ledger = new AiExecutionLedger();
        var gateway = new RecordingGateway();
        gateway.responder = index -> success();
        var executor = new AiRouteExecutor(gateway, mock(AiRunEventService.class), registry, ledger, fixedClock);
        ledger.open("run-3", NOW.plusSeconds(8));

        AiRouteExecutionResult result = executor.execute(strategy(1, 300_000), "prompt", ctx("run-3"));

        assertThat(result.success()).isTrue();
        assertThat(gateway.configs).hasSize(1);
        assertThat(gateway.configs.get(0).requestTimeoutMs()).isEqualTo(8_000);
        assertThat(ledger.attemptCount("run-3")).isEqualTo(1);
        ledger.close("run-3");
        assertThat(ledger.openLedgerCount()).isZero();
    }

    @Test
    void expiredDeadlineStartsNoCall() {
        var ledger = new AiExecutionLedger();
        var gateway = new RecordingGateway();
        var executor = new AiRouteExecutor(gateway, mock(AiRunEventService.class),
            new AiCancellationRegistry(), ledger, fixedClock);
        ledger.open("run-4", NOW.minusSeconds(1));

        AiRouteExecutionResult result = executor.execute(strategy(1, 300_000), "prompt", ctx("run-4"));

        assertThat(gateway.configs).isEmpty();
        assertThat(result.terminalReason()).isEqualTo(AiRetryReason.RUN_DEADLINE_EXCEEDED);
    }

    @Test
    void interruptedBackoffIsInterruptedNotANetworkFailure() {
        var registry = new AiCancellationRegistry();
        var ledger = new AiExecutionLedger();
        var gateway = new RecordingGateway();
        gateway.responder = index -> failure(AiRetryReason.UPSTREAM_ERROR);
        var executor = new AiRouteExecutor(gateway, mock(AiRunEventService.class), registry, ledger, fixedClock);
        Thread.currentThread().interrupt();
        try {
            AiRouteExecutionResult result = executor.execute(strategy(1, 300_000), "prompt", ctx("run-5"));

            assertThat(gateway.configs).hasSize(1);
            assertThat(result.terminalReason()).isEqualTo(AiRetryReason.INTERRUPTED);
        } finally {
            Thread.interrupted(); // 清除 waitForRetry 重新设置的标志，避免污染后续用例。
        }
    }

    // ------------------------------------------------------------------

    @Test
    void retryAfterIsHonoredWithAnInjectedSleeper() {
        var gateway = new RecordingGateway();
        gateway.responder = index -> index == 0
            ? new AiGenerationResult(false, "", "req-429", 0, 0, "rate limited", true, AiRetryReason.RATE_LIMITED.name(), 1_500)
            : success();
        var slept = new ArrayList<Long>();
        var policy = new AiRetryPolicy(slept::add, fixedClock, 1_000, 30_000, 0.0, () -> 0);
        var executor = new AiRouteExecutor(gateway, mock(AiRunEventService.class),
            new AiCancellationRegistry(), new AiExecutionLedger(), fixedClock, policy);

        AiRouteExecutionResult result = executor.execute(strategy(1, 300_000), "prompt", ctx("run-policy-1"));

        assertThat(result.success()).isTrue();
        assertThat(slept).containsExactly(1_500L); // Retry-After 优先于本地退避（1000），且零真实等待。
        assertThat(gateway.configs).hasSize(2);
    }

    @Test
    void policyWaitInterruptionYieldsInterrupted() {
        var gateway = new RecordingGateway();
        gateway.responder = index -> failure(AiRetryReason.UPSTREAM_ERROR);
        var policy = new AiRetryPolicy(millis -> {
            throw new InterruptedException("stop");
        }, fixedClock, 1_000, 30_000, 0.0, () -> 0);
        var executor = new AiRouteExecutor(gateway, mock(AiRunEventService.class),
            new AiCancellationRegistry(), new AiExecutionLedger(), fixedClock, policy);

        AiRouteExecutionResult result = executor.execute(strategy(1, 300_000), "prompt", ctx("run-policy-2"));

        assertThat(result.terminalReason()).isEqualTo(AiRetryReason.INTERRUPTED);
        Thread.interrupted(); // 清除策略重新设置的标志，避免污染后续用例。
    }

    @Test
    void policyBackoffJitterAndRetryAfterParsing() {
        var jittered = new AiRetryPolicy(Thread::sleep, fixedClock, 1_000, 4_000, 0.5, () -> 1_000);
        assertThat(jittered.backoffMs(0)).isEqualTo(1_500);
        assertThat(jittered.backoffMs(2)).isEqualTo(6_000); // 封顶 4000 + 50% 抖动
        var noJitter = new AiRetryPolicy(Thread::sleep, fixedClock, 1_000, 30_000, 0.0, () -> 1_000);
        assertThat(noJitter.backoffMs(3)).isEqualTo(8_000);
        assertThat(noJitter.backoffMs(10)).isEqualTo(30_000); // 指数封顶
        assertThat(noJitter.retryAfterMs(java.util.Map.of("retry-after", "5"))).isEqualTo(5_000);
        assertThat(noJitter.retryAfterMs(java.util.Map.of("retry-after", "-3"))).isEqualTo(-1);
        assertThat(noJitter.retryAfterMs(java.util.Map.of("retry-after", "Wed, 01 Jan 2031 00:00:00 GMT"))).isGreaterThan(0);
        assertThat(noJitter.retryAfterMs(java.util.Map.of("retry-after", "2030-01-01T00:00:00Z"))).isEqualTo(-1);
        assertThat(noJitter.retryAfterMs(null)).isEqualTo(-1);
        assertThat(AiRetryPolicy.RULE_VERSION).isEqualTo("v1");
    }

    private AiRoutingStrategy strategy(int retryBudget, int perCallTimeoutMs) {
        return new AiRoutingStrategy("snap-1", "requirement_analysis",
            List.of(route(perCallTimeoutMs)), retryBudget, perCallTimeoutMs, 900_000, null);
    }

    private AiRoute route(int timeoutMs) {
        return new AiRoute("conn-1", "prov", "openai", "https://provider.test/v1", "model-1", "key-1", timeoutMs);
    }

    private AiRouteExecutionContext ctx(String runId) {
        return new AiRouteExecutionContext(runId, "snap-1", 0, 1, "chunk-1");
    }

    private AiGenerationResult failure(AiRetryReason reason) {
        return new AiGenerationResult(false, "", "req-fail", 0, 0, "boom", true, reason.name());
    }

    private AiGenerationResult success() {
        return new AiGenerationResult(true, "{\"ok\":true}", "req-ok", 5, 5, null);
    }

    private static final class RecordingGateway implements AiGateway {

        final List<AiGatewayConfig> configs = new ArrayList<>();
        Function<Integer, AiGenerationResult> responder = index -> successStatic();
        Consumer<Integer> onCall;

        @Override
        public AiConnectionTestResult testConnection(AiGatewayConfig config) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AiGenerationResult structuredGenerate(AiGatewayConfig config, Object prompt) {
            int index = configs.size();
            configs.add(config);
            if (onCall != null) {
                onCall.accept(index);
            }
            return responder.apply(index);
        }

        @Override
        public AiVisionResponse visionAnalyze(AiGatewayConfig config, AiVisionRequest request) {
            throw new UnsupportedOperationException();
        }

        private static AiGenerationResult successStatic() {
            return new AiGenerationResult(true, "{\"ok\":true}", "req-static", 1, 1, null);
        }
    }
}
