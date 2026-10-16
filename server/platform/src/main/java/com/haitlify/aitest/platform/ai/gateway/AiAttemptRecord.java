package com.haitlify.aitest.platform.ai.gateway;

/**
 * One attempt within a route-chain execution: which route was tried, the raw gateway result, how
 * long it took, and why it ended. The worker persists one call record per attempt.
 */
public record AiAttemptRecord(
    int attemptIndex,
    AiRoute route,
    AiGenerationResult result,
    long durationMs,
    AiRetryReason retryReason
) {
}
