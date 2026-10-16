package com.haitlify.aitest.platform.ai.runtime;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.ai.gateway.AiGatewaySettingsService;
import com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings;
import com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.AiTaskRequest;
import com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.Capability;
import com.haitlify.aitest.platform.ai.runtime.transport.AiProtocolTransportClient;
import com.haitlify.aitest.platform.ai.runtime.transport.AiTransportRequest;
import com.haitlify.aitest.platform.ai.runtime.transport.AiTransportResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AiRuntimeInvocationService {
    private static final String SAFE_TASK_TYPE = "requirement_analysis";
    private final AiGatewaySettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final AiProtocolTransportClient transportClient;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public AiRuntimeInvocationService(AiGatewaySettingsService settingsService, ObjectMapper objectMapper,
                                      JdbcTemplate jdbcTemplate,
                                      @org.springframework.beans.factory.annotation.Value(
                                          "${app.ai.runtime.disabled-task-types:}") String disabledTaskTypes,
                                      @org.springframework.beans.factory.annotation.Value(
                                          "${app.ai.runtime.disabled-projects:}") String disabledProjects) {
        this(settingsService, objectMapper, new AiProtocolTransportClient(HttpClient.newHttpClient(), objectMapper),
            jdbcTemplate, AiRuntimeSwitches.parse(disabledTaskTypes, disabledProjects));
    }

    AiRuntimeInvocationService(AiGatewaySettingsService settingsService, ObjectMapper objectMapper,
                               JdbcTemplate jdbcTemplate) {
        this(settingsService, objectMapper, new AiProtocolTransportClient(HttpClient.newHttpClient(), objectMapper),
            jdbcTemplate, AiRuntimeSwitches.parse("", ""));
    }

    AiRuntimeInvocationService(AiGatewaySettingsService settingsService, ObjectMapper objectMapper, AiProtocolTransportClient transportClient) {
        this(settingsService, objectMapper, transportClient, null);
    }

    AiRuntimeInvocationService(AiGatewaySettingsService settingsService, ObjectMapper objectMapper, AiProtocolTransportClient transportClient, JdbcTemplate jdbcTemplate) {
        this(settingsService, objectMapper, transportClient, jdbcTemplate, AiRuntimeSwitches.parse("", ""));
    }

    AiRuntimeInvocationService(AiGatewaySettingsService settingsService, ObjectMapper objectMapper, AiProtocolTransportClient transportClient, JdbcTemplate jdbcTemplate, AiRuntimeSwitches.Switches runtimeSwitches) {
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
        this.transportClient = transportClient;
        this.jdbcTemplate = jdbcTemplate;
        this.runtimeSwitches = runtimeSwitches;
    }

    private final AiRuntimeSwitches.Switches runtimeSwitches;

    public AiRuntimeInvocationResult invoke(AiTaskRequest request) {
        if (request == null || !SAFE_TASK_TYPE.equals(request.taskType())) {
            return AiRuntimeInvocationResult.blocked("unsupported_vertical_slice_task");
        }
        // T-R5 轮 4：vertical slice 直连 transport 同样受发布开关约束（发出任何请求前阻断）。
        String gate = runtimeSwitches.reasonFor(SAFE_TASK_TYPE, request.projectId());
        if (gate != null) {
            return AiRuntimeInvocationResult.blocked("runtime_disabled");
        }
        JsonNode input = request.input();
        if (input == null || input.path("requirement").asText("").isBlank() || input.path("evidenceIds").isMissingNode()) {
            return AiRuntimeInvocationResult.blocked("input_contract_invalid");
        }
        try {
            ResolvedAiGatewaySettings settings = settingsService.loadDefaultTextSettings();
            AiTransportRequest transportRequest = new AiTransportRequest(
                protocol(settings.providerType()), settings.baseUrl(), settings.model(), settings.apiKey(),
                verticalSlicePrompt(request.input()), List.of(Capability.TEXT), Map.of(),
                Duration.ofMillis(settings.timeoutMs())
            );
            AiTransportResult transport = transportClient.execute(transportRequest);
            if (transport.httpStatus() < 200 || transport.httpStatus() >= 300) {
                return AiRuntimeInvocationResult.blocked("provider_http_" + transport.httpStatus(), transport.response());
            }
            if (!transport.response().hasUsableText()) {
                return AiRuntimeInvocationResult.blocked("provider_output_not_usable", transport.response());
            }
            JsonNode output = objectMapper.readTree(stripJsonFence(transport.response().text()));
            output = normalizeVerticalSliceOutput(output);
            if (!isValidVerticalSliceOutput(output, request.evidenceIds())) {
                return AiRuntimeInvocationResult.blocked("business_schema_invalid", transport.response());
            }
            return new AiRuntimeInvocationResult(
                "MODEL", "MODEL_GENERATED", "PENDING_HUMAN_CONFIRMATION",
                output, transport.response().providerRequestId(), transport.response().usage(), null,
                transport.durationMillis(), transport.ttfbMillis()
            );
        } catch (Exception exception) {
            return AiRuntimeInvocationResult.blocked("runtime_invocation_failed");
        }
    }

    private String verticalSlicePrompt(JsonNode input) throws Exception {
        return "Return JSON only with exactly two test points. Each item must contain title, steps, expectedResult, and evidenceIds. Use only these evidence IDs: "
            + objectMapper.writeValueAsString(input == null ? objectMapper.nullNode() : input.path("evidenceIds"))
            + ". Requirement: " + compact(input == null ? "" : input.path("requirement").asText(""));
    }

    private JsonNode normalizeVerticalSliceOutput(JsonNode output) {
        if (output != null && output.isArray()) {
            var wrapper = objectMapper.createObjectNode();
            wrapper.set("items", output);
            return wrapper;
        }
        return output;
    }

    private boolean isValidVerticalSliceOutput(JsonNode output, List<String> allowedEvidenceIds) {
        JsonNode items = output == null ? null : output.path("items");
        if (items == null || !items.isArray() || (items.size() != 2 && items.size() < 1)) return false;
        for (JsonNode item : items) {
            if (item.path("title").asText("").isBlank() || !hasSteps(item) || item.path("expectedResult").asText("").isBlank()) return false;
            if (!item.path("evidenceIds").isArray() || item.path("evidenceIds").isEmpty()) return false;
            for (JsonNode evidenceId : item.path("evidenceIds")) if (!allowedEvidenceIds.contains(evidenceId.asText())) return false;
        }
        return true;
    }

    private boolean hasSteps(JsonNode item) {
        JsonNode steps = item.path("steps");
        return (steps.isTextual() && !steps.asText().isBlank()) || (steps.isArray() && !steps.isEmpty());
    }

    private AiRuntimeModels.Protocol protocol(String providerType) {
        return "responses".equalsIgnoreCase(providerType) ? AiRuntimeModels.Protocol.RESPONSES : AiRuntimeModels.Protocol.CHAT_COMPLETIONS;
    }

    private String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String stripJsonFence(String text) {
        String value = text == null ? "" : text.trim();
        if (value.startsWith("```") && value.endsWith("```")) {
            int newline = value.indexOf('\n');
            return newline >= 0 ? value.substring(newline + 1, value.length() - 3).trim() : "";
        }
        return value;
    }

    public record AiRuntimeInvocationResult(
        String executionKind, String resultOrigin, String acceptanceState, JsonNode output,
        String providerRequestId, AiProtocolResponse.Usage usage, String failureCategory,
        Long durationMillis, Long ttfbMillis
    ) {
        static AiRuntimeInvocationResult blocked(String category, AiProtocolResponse response) {
            return new AiRuntimeInvocationResult(
                "MODEL", "BLOCKED", "BLOCKED", null,
                response == null ? null : response.providerRequestId(),
                response == null ? null : response.usage(), category,
                null, null
            );
        }

        static AiRuntimeInvocationResult blocked(String category) {
            return blocked(category, null);
        }
    }
}
