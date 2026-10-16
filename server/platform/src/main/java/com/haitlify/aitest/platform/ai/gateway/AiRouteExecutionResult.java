package com.haitlify.aitest.platform.ai.gateway;

import java.util.List;

/**
 * Outcome of walking a strategy's route chain for one chunk. {@code attempts} holds every call
 * made (primary plus any fallbacks), in order, so the worker can persist one call record per
 * attempt and so each visible model name is explainable. {@code terminalReason} is non-null when
 * the chunk did not succeed.
 */
public record AiRouteExecutionResult(
    boolean success,
    AiGenerationResult finalResult,
    AiRoute winningRoute,
    List<AiAttemptRecord> attempts,
    AiRetryReason terminalReason
) {

    public String errorMessage() {
        return finalResult() == null ? "" : finalResult().errorMessage();
    }
}
