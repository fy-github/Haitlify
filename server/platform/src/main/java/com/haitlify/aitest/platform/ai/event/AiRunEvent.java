package com.haitlify.aitest.platform.ai.event;

import java.time.Instant;

/**
 * One normalized, sensitive-payload-free event in a run's visible stream (deliverable 3).
 *
 * <p>Per the platform AI event contract: {@code summary} is a short Chinese user-facing statement.
 * Request text, API keys, authorization headers and response bodies are never written here; the
 * technical detail lives in {@code ai_call_records}, linked by {@code strategySnapshotId}.
 */
public record AiRunEvent(
    String runId,
    Instant occurredAt,
    String stage,
    String severity,
    String summary,
    Integer chunkIndex,
    Integer chunkTotal,
    Integer attemptIndex,
    String providerId,
    String providerType,
    String model,
    long durationMs,
    String retryReason,
    String strategySnapshotId,
    long eventSeq
) {
}
