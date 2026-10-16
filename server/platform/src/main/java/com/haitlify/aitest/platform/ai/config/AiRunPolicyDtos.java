package com.haitlify.aitest.platform.ai.config;

import java.util.List;

/**
 * DTOs for the Platform AI run-policy authoring layer (deliverable 1).
 *
 * <p>A policy defines an ordered route of one primary connection/model followed by zero or more
 * fallbacks, plus per-call timeout, whole-run deadline, retry budget and input-size budget. The
 * route references a stored provider connection by its connection id (providers[].id) together
 * with an explicit model, so task behavior is expressed independently of credentials.
 */
record AiRouteResponse(
    int position,
    String connectionId,
    String model
) {
}

record SaveAiRouteRequest(
    Integer position,
    String connectionId,
    String model
) {
}

record AiPolicyWarning(
    String code,
    String message
) {
}

record AiRunPolicyResponse(
    String policyId,
    String scope,
    String taskType,
    String name,
    boolean enabled,
    int perCallTimeoutMs,
    long wholeRunDeadlineMs,
    int retryBudget,
    int maxInputChars,
    List<AiRouteResponse> routes,
    List<AiPolicyWarning> warnings,
    String updatedAt
) {
}

record SaveAiRunPolicyRequest(
    String name,
    Boolean enabled,
    Integer perCallTimeoutMs,
    Long wholeRunDeadlineMs,
    Integer retryBudget,
    Integer maxInputChars,
    List<SaveAiRouteRequest> routes
) {
}

record AiRunPolicyListResponse(
    List<AiRunPolicyResponse> items
) {
}
