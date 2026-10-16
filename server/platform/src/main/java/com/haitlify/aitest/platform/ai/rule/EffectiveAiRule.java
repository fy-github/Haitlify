package com.haitlify.aitest.platform.ai.rule;

import tools.jackson.databind.JsonNode;

public record EffectiveAiRule(
    Long projectId,
    int version,
    int systemVersion,
    Integer projectVersion,
    String effectiveVersion,
    boolean forcedHumanReview,
    boolean sourceEvidenceRequired,
    boolean inferredRequiresConfirmation,
    JsonNode allowedCaseKinds,
    JsonNode formalGenerationStatuses,
    JsonNode forbiddenPatterns,
    JsonNode coverageDimensions,
    JsonNode rules,
    String updatedAt
) {
}
