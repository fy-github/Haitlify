package com.haitlify.aitest.platform.ai.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

public final class AiRuntimeModels {
    private AiRuntimeModels() {}

    public enum Protocol { CHAT_COMPLETIONS, RESPONSES }
    public enum Capability { TEXT, JSON_OBJECT, JSON_SCHEMA, REASONING, TOOL_CALLS, STREAMING, IMAGE_INPUT }

    public record AiConnection(String id, int version, String baseUrl, String credentialRef,
                               Protocol protocol, boolean enabled, List<Long> allowedProjects) {
        public AiConnection {
            if (id == null || id.isBlank() || baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("connection id/baseUrl required");
            allowedProjects = allowedProjects == null ? List.of() : List.copyOf(allowedProjects);
        }
    }

    public record AiModelProfile(String id, int version, String connectionId, String modelId,
                                 List<Capability> capabilities, Map<String, Object> parameterPolicy) {
        public AiModelProfile {
            if (id == null || id.isBlank() || connectionId == null || connectionId.isBlank() || modelId == null || modelId.isBlank()) throw new IllegalArgumentException("profile fields required");
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            parameterPolicy = parameterPolicy == null ? Map.of() : Map.copyOf(parameterPolicy);
        }
    }

    public record AiRunSnapshot(String runId, String connectionId, int connectionVersion,
                                String profileId, int profileVersion, Protocol protocol,
                                String actorId, Long projectId, String inputHash, Instant createdAt) {}

    public record AiTaskRequest(String taskType, Long projectId, String actorId, String runId,
                                String skillVersionId, String schemaId, String schemaVersion,
                                List<String> evidenceIds, JsonNode input, String idempotencyKey) {}
}
