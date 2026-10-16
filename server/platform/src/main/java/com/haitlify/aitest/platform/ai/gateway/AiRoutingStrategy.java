package com.haitlify.aitest.platform.ai.gateway;

import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * Immutable routing strategy captured for a single run: an ordered list of routes (primary first,
 * then fallbacks), the retry budget, per-call timeout floor, whole-run deadline, and a verbatim
 * copy of the policy that produced it. Serialized into {@code ai_analysis_runs.strategy_snapshot_json}
 * so in-flight runs are insulated from later policy edits.
 */
public record AiRoutingStrategy(
    String strategySnapshotId,
    String taskType,
    List<AiRoute> routes,
    int retryBudget,
    long perCallTimeoutMs,
    long wholeRunDeadlineMs,
    JsonNode policySnapshot
) {
}
