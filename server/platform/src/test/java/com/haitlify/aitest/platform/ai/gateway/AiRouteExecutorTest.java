package com.haitlify.aitest.platform.ai.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.haitlify.aitest.platform.ai.event.AiRunEvent;
import com.haitlify.aitest.platform.ai.event.AiRunEventService;

class AiRouteExecutorTest {

    private final AiGateway gateway = mock(AiGateway.class);
    private final AiRunEventService eventService = mock(AiRunEventService.class);
    private final AiRouteExecutor executor = new AiRouteExecutor(gateway, eventService);

    private static final AiRoute PRIMARY = new AiRoute("conn-openai", "生产 OpenAI", "openai-compatible", "https://a", "gpt-5.5", "key", 120000);
    private static final AiRoute FALLBACK = new AiRoute("conn-ds", "备用 DeepSeek", "openai-compatible", "https://b", "deepseek-chat", "key2", 120000);
    private static final AiRouteExecutionContext CTX = new AiRouteExecutionContext("run-1", "strategy:test", 0, 1, "chunk-1");

    private static AiGenerationResult success(String content) {
        return new AiGenerationResult(true, content, "req", 10, 5, null);
    }

    private static AiGenerationResult failure(AiRetryReason reason) {
        return new AiGenerationResult(false, "", "", 0, 0, "fail", reason.retryable(), reason.name());
    }

    private static AiRoutingStrategy strategy(List<AiRoute> routes, int retryBudget) {
        return new AiRoutingStrategy("strategy:test", "requirement_analysis", routes, retryBudget, 120000L, 900000L, null);
    }

    @Test
    void singleRouteSuccessReturnsImmediately() {
        when(gateway.structuredGenerate(any(), any())).thenReturn(success("{}"));

        AiRouteExecutionResult result = executor.execute(strategy(List.of(PRIMARY), 2), new Object(), CTX);

        assertTrue(result.success());
        assertEquals(PRIMARY, result.winningRoute());
        assertEquals(1, result.attempts().size());
        assertNull(result.terminalReason());
        verify(gateway, times(1)).structuredGenerate(any(), any());
    }

    @Test
    void fallbackOnRateLimitThenSucceeds() {
        when(gateway.structuredGenerate(any(), any()))
            .thenReturn(failure(AiRetryReason.RATE_LIMITED))
            .thenReturn(success("{}"));

        AiRouteExecutionResult result = executor.execute(strategy(List.of(PRIMARY, FALLBACK), 1), new Object(), CTX);

        assertTrue(result.success());
        assertEquals(FALLBACK, result.winningRoute());
        assertEquals(2, result.attempts().size());
        verify(gateway, times(2)).structuredGenerate(any(), any());
    }

    @Test
    void timeoutTriggersFallback() {
        when(gateway.structuredGenerate(any(), any()))
            .thenReturn(failure(AiRetryReason.TIMEOUT))
            .thenReturn(success("{}"));

        AiRouteExecutionResult result = executor.execute(strategy(List.of(PRIMARY, FALLBACK), 1), new Object(), CTX);

        assertTrue(result.success());
        assertEquals(FALLBACK, result.winningRoute());
    }

    @Test
    void singleRouteUpstream503RetriesWithinTheConfiguredBudget() {
        when(gateway.structuredGenerate(any(), any()))
            .thenReturn(failure(AiRetryReason.UPSTREAM_ERROR))
            .thenReturn(success("{}"));

        AiRouteExecutionResult result = executor.execute(strategy(List.of(PRIMARY), 1), new Object(), CTX);

        assertTrue(result.success());
        assertEquals(PRIMARY, result.winningRoute());
        assertEquals(2, result.attempts().size());
        verify(gateway, times(2)).structuredGenerate(any(), any());
    }

    @Test
    void singleRouteTimeoutRetriesTheCurrentModelWithoutClaimingAFallback() {
        when(gateway.structuredGenerate(any(), any())).thenReturn(failure(AiRetryReason.TIMEOUT));

        AiRouteExecutionResult result = executor.execute(strategy(List.of(PRIMARY), 2), new Object(), CTX);

        assertFalse(result.success());
        assertEquals(AiRetryReason.TIMEOUT, result.terminalReason());
        ArgumentCaptor<AiRunEvent> events = ArgumentCaptor.forClass(AiRunEvent.class);
        verify(gateway, times(3)).structuredGenerate(any(), any());
        verify(eventService, times(8)).emit(events.capture());
        String firstFailure = events.getAllValues().get(1).summary();
        String finalFailure = events.getAllValues().get(7).summary();
        assertTrue(firstFailure.contains("准备重试当前模型"));
        assertTrue(finalFailure.contains("已达到当前策略重试上限"));
        assertFalse(firstFailure.contains("准备切换备用路由"));
    }

    @Test
    void nonRetryableAuthFailureStopsImmediately() {
        when(gateway.structuredGenerate(any(), any())).thenReturn(failure(AiRetryReason.AUTH_DENIED));

        AiRouteExecutionResult result = executor.execute(strategy(List.of(PRIMARY, FALLBACK), 1), new Object(), CTX);

        assertFalse(result.success());
        assertEquals(AiRetryReason.AUTH_DENIED, result.terminalReason());
        assertEquals(1, result.attempts().size());
        // A fallback route was available, but auth failures must not blindly retry.
        verify(gateway, times(1)).structuredGenerate(any(), any());
    }

    @Test
    void budgetExhaustedWhenAllRetryableRoutesFail() {
        when(gateway.structuredGenerate(any(), any())).thenReturn(failure(AiRetryReason.UPSTREAM_ERROR));

        AiRouteExecutionResult result = executor.execute(strategy(List.of(PRIMARY, FALLBACK), 1), new Object(), CTX);

        assertFalse(result.success());
        assertEquals(AiRetryReason.BUDGET_EXHAUSTED, result.terminalReason());
        assertEquals(2, result.attempts().size());
    }

    @Test
    void retryBudgetCapsAttemptsBelowRouteCount() {
        // 3 routes but retry budget 1 => maxAttempts = min(3, 2) = 2; third route is never tried.
        when(gateway.structuredGenerate(any(), any())).thenReturn(failure(AiRetryReason.RATE_LIMITED));

        AiRoute third = new AiRoute("conn-3", "第三路由", "openai-compatible", "https://c", "third-model", "key3", 120000);
        AiRouteExecutionResult result = executor.execute(strategy(List.of(PRIMARY, FALLBACK, third), 1), new Object(), CTX);

        assertFalse(result.success());
        assertEquals(2, result.attempts().size());
        verify(gateway, times(2)).structuredGenerate(any(), any());
    }

    @Test
    void emptyStrategyReturnsNoRouteWithoutCallingGateway() {
        AiRouteExecutionResult result = executor.execute(strategy(List.of(), 2), new Object(), CTX);

        assertFalse(result.success());
        assertEquals(AiRetryReason.NO_ROUTE, result.terminalReason());
        assertTrue(result.attempts().isEmpty());
        verify(gateway, never()).structuredGenerate(any(), any());
    }
}
