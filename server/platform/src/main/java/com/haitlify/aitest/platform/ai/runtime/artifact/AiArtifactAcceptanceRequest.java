package com.haitlify.aitest.platform.ai.runtime.artifact;

import java.util.List;

import com.haitlify.aitest.platform.ai.runtime.AiRuntimeInvocationService.AiRuntimeInvocationResult;
import tools.jackson.databind.JsonNode;

public record AiArtifactAcceptanceRequest(
    AiArtifactOrigin origin,
    AiArtifactAcceptanceState requestedState,
    JsonNode content,
    Long projectId,
    Long sourceProjectId,
    List<String> evidenceIds,
    List<String> allowedEvidenceIds,
    String parentArtifactId,
    int revision,
    String idempotencyKey,
    AiRuntimeInvocationResult runtimeResult
) {}
