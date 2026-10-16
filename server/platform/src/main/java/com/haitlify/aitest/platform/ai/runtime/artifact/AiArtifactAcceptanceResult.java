package com.haitlify.aitest.platform.ai.runtime.artifact;

import java.util.List;

import tools.jackson.databind.JsonNode;

public record AiArtifactAcceptanceResult(
    boolean accepted,
    AiArtifactOrigin origin,
    AiArtifactAcceptanceState acceptanceState,
    JsonNode content,
    List<String> errors
) {
    public static AiArtifactAcceptanceResult blocked(AiArtifactOrigin origin, List<String> errors) {
        return new AiArtifactAcceptanceResult(false, origin, AiArtifactAcceptanceState.BLOCKED, null, List.copyOf(errors));
    }
}
