package com.haitlify.aitest.platform.ai.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.ai.gateway.AiGatewaySettingsService;
import com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings;
import com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.Capability;
import com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.Protocol;
import com.haitlify.aitest.platform.ai.runtime.transport.AiProtocolTransportClient;
import com.haitlify.aitest.platform.ai.runtime.transport.AiTransportRequest;
import com.haitlify.aitest.platform.ai.runtime.transport.AiTransportResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AiProviderCompatibilityProbeService {
    private final AiGatewaySettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final AiProtocolTransportClient transportClient;
    private final JdbcTemplate jdbcTemplate;

    public AiProviderCompatibilityProbeService(
        AiGatewaySettingsService settingsService,
        ObjectMapper objectMapper,
        AiProtocolTransportClient transportClient
    ) {
        this(settingsService, objectMapper, transportClient, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AiProviderCompatibilityProbeService(
        AiGatewaySettingsService settingsService,
        ObjectMapper objectMapper,
        AiProtocolTransportClient transportClient,
        JdbcTemplate jdbcTemplate
    ) {
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
        this.transportClient = transportClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    public AiProviderCompatibilityMatrix.Entry probe(
        String profileId,
        int profileVersion,
        String modelId,
        String input,
        List<String> evidenceIds,
        String configVersion
    ) {
        Instant observedAt = Instant.now();
        String prompt = prompt(input, evidenceIds);
        String inputHash = sha256(prompt);
        try {
            ResolvedAiGatewaySettings settings = settingsService.loadDefaultTextSettings();
            ensureProfileReference(profileId, profileVersion, modelId, configVersion);
            Protocol protocol = protocol(settings.providerType());
            long started = System.nanoTime();
            AiTransportResult result = transportClient.execute(new AiTransportRequest(
                protocol,
                settings.baseUrl(),
                modelId,
                settings.apiKey(),
                prompt,
                List.of(Capability.TEXT),
                java.util.Map.of(),
                Duration.ofMillis(settings.timeoutMs())
            ));
            long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            AiProtocolResponse response = result.response();
            String schemaOutcome = "UNKNOWN";
            String evidenceOutcome = "UNKNOWN";
            String conclusion = "LIVE_UNKNOWN";
            String blockingReason = "";
            if (result.httpStatus() < 200 || result.httpStatus() >= 300) {
                conclusion = "BLOCKED";
                blockingReason = "provider_http_" + result.httpStatus();
            } else if (!response.hasUsableText()) {
                conclusion = "BLOCKED";
                blockingReason = "provider_output_not_usable";
            } else {
                JsonNode output = normalize(objectMapper.readTree(stripFence(response.text())));
                schemaOutcome = hasValidSchema(output) ? "PASS" : "FAIL";
                evidenceOutcome = hasValidEvidence(output, evidenceIds) ? "PASS" : "FAIL";
                if ("PASS".equals(schemaOutcome) && "PASS".equals(evidenceOutcome)
                    && response.providerRequestId() != null && !response.providerRequestId().isBlank()
                    && "stop".equalsIgnoreCase(response.finishReason())) {
                    conclusion = "LIVE_BUSINESS_PASS";
                } else {
                    conclusion = "BLOCKED";
                    blockingReason = "schema_or_evidence_invalid";
                }
            }
            AiProtocolResponse.Usage usage = response.usage();
            boolean usageKnown = usage != null && usage.inputTokens() != null && usage.outputTokens() != null;
            return new AiProviderCompatibilityMatrix.Entry(
                protocol.name(), profileId, profileVersion, modelId, "text", inputHash, observedAt,
                configVersion, response.providerRequestId(), result.httpStatus(), response.finishReason(),
                usageKnown, usage == null ? null : usage.inputTokens(), usage == null ? null : usage.outputTokens(),
                schemaOutcome, evidenceOutcome, latencyMs, conclusion, blockingReason, "probe-" + inputHash.substring(0, 12)
            );
        } catch (Exception exception) {
            return new AiProviderCompatibilityMatrix.Entry(
                "UNKNOWN", profileId, profileVersion, modelId, "text", inputHash, observedAt,
                configVersion, "", null, "", false, null, null, "UNKNOWN", "UNKNOWN", 0,
                "BLOCKED", "probe_exception", ""
            );
        }
    }

    private void ensureProfileReference(String profileId, int profileVersion, String modelId, String configVersion) {
        if (jdbcTemplate == null) return;
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from ai_model_profiles where profile_id = ? and version = ? and model_id = ?",
            Integer.class, profileId, profileVersion, modelId
        );
        if (count == null || count != 1) {
            throw new IllegalArgumentException("profile reference is not registered: " + profileId + "@" + profileVersion + "/" + modelId);
        }
        if (configVersion == null || configVersion.isBlank()) {
            throw new IllegalArgumentException("config version required");
        }
    }

    private String prompt(String input, List<String> evidenceIds) {
        try {
            return "Return JSON only with exactly two test points. Each item must contain title, steps, expectedResult, and evidenceIds. Use only these evidence IDs: "
                + objectMapper.writeValueAsString(evidenceIds) + ". Requirement: " + input;
        } catch (Exception exception) {
            throw new IllegalArgumentException("cannot build compatibility probe prompt", exception);
        }
    }

    private JsonNode normalize(JsonNode output) {
        if (output != null && output.isArray()) {
            var wrapper = objectMapper.createObjectNode();
            wrapper.set("items", output);
            return wrapper;
        }
        return output;
    }

    private boolean hasValidSchema(JsonNode output) {
        JsonNode items = output == null ? null : output.path("items");
        if (items == null || !items.isArray() || items.size() != 2) return false;
        for (JsonNode item : items) {
            JsonNode steps = item.path("steps");
            if (item.path("title").asText("").isBlank()
                || item.path("expectedResult").asText("").isBlank()
                || (steps.isTextual() && steps.asText().isBlank())
                || (!steps.isTextual() && (!steps.isArray() || steps.isEmpty()))) return false;
        }
        return true;
    }

    private boolean hasValidEvidence(JsonNode output, List<String> allowedEvidenceIds) {
        for (JsonNode item : output.path("items")) {
            JsonNode evidenceIds = item.path("evidenceIds");
            if (!evidenceIds.isArray() || evidenceIds.isEmpty()) return false;
            for (JsonNode evidenceId : evidenceIds) if (!allowedEvidenceIds.contains(evidenceId.asText())) return false;
        }
        return true;
    }

    private Protocol protocol(String providerType) {
        return "responses".equalsIgnoreCase(providerType) ? Protocol.RESPONSES : Protocol.CHAT_COMPLETIONS;
    }

    private String stripFence(String text) {
        String value = text == null ? "" : text.trim();
        if (value.startsWith("```") && value.endsWith("```")) {
            int newline = value.indexOf('\n');
            return newline < 0 ? "" : value.substring(newline + 1, value.length() - 3).trim();
        }
        return value;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot hash compatibility probe input", exception);
        }
    }
}
