package com.haitlify.aitest.platform.ai.agentic;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

/**
 * Wire-level contracts shared by the resumable Web Case Agent.
 *
 * <p>Durable responses deliberately contain summaries and references only. Credentials,
 * cookies, authorization material, raw HTML, document bodies, and hidden model reasoning are
 * excluded from persisted run/event/artifact contracts. Browser login data is represented only
 * by the request-only {@link BrowserDiscoveryRequest} and is never written to a run snapshot.
 */
public final class WebCaseAgentModels {

    private WebCaseAgentModels() {
    }

    public enum WebCaseAgentStage {
        PREFLIGHT,
        EVIDENCE,
        PLANNING,
        GENERATION,
        VALIDATION,
        DELIVERY
    }

    public enum WebCaseAgentRunStatus {
        QUEUED,
        RUNNING,
        PAUSED,
        SUCCEEDED,
        PARTIAL,
        FAILED,
        BLOCKED,
        CANCELLED
    }

    public enum WebCaseAgentMode {
        PLAN,
        COMPILE
    }

    public enum WebCaseAgentEventType {
        INPUT_SUMMARY,
        ACTION,
        TOOL_RESULT,
        OUTPUT_SUMMARY,
        VALIDATION,
        STATUS,
        ERROR
    }

    public enum AgentMemoryStatus {
        PENDING,
        ACTIVE,
        DISABLED,
        DELETED
    }

    public enum AgentMemoryScopeType {
        PROJECT,
        PROJECT_SET,
        GLOBAL
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LoginDiscoveryContext(
        String discoveryId,
        String status,
        String successType,
        String successTarget
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WebCaseAgentStartRequest(
        Long projectId,
        String analysisRunId,
        List<String> pointIds,
        String environmentId,
        String baseUrl,
        String browser,
        String viewport,
        LoginDiscoveryContext loginDiscovery,
        BrowserDiscoveryRequest browserDiscovery,
        Long batchTotalDurationMs,
        WebCaseAgentMode mode,
        String planRunId,
        String authenticationMode
    ) {
        public WebCaseAgentStartRequest {
            pointIds = immutableList(pointIds);
            batchTotalDurationMs = batchTotalDurationMs == null ? null : Math.max(0L, batchTotalDurationMs);
            mode = mode == null ? WebCaseAgentMode.COMPILE : mode;
            planRunId = planRunId == null ? "" : planRunId;
            authenticationMode = authenticationMode == null ? "" : authenticationMode;
        }

        public WebCaseAgentStartRequest(
            Long projectId,
            String analysisRunId,
            List<String> pointIds,
            String environmentId,
            String baseUrl,
            String browser,
            String viewport,
            LoginDiscoveryContext loginDiscovery,
            BrowserDiscoveryRequest browserDiscovery
        ) {
            this(projectId, analysisRunId, pointIds, environmentId, baseUrl, browser, viewport, loginDiscovery, browserDiscovery, null, WebCaseAgentMode.COMPILE, "", "");
        }

        public WebCaseAgentStartRequest(
            Long projectId,
            String analysisRunId,
            List<String> pointIds,
            String environmentId,
            String baseUrl,
            String browser,
            String viewport,
            LoginDiscoveryContext loginDiscovery,
            BrowserDiscoveryRequest browserDiscovery,
            WebCaseAgentMode mode,
            String planRunId
        ) {
            this(projectId, analysisRunId, pointIds, environmentId, baseUrl, browser, viewport, loginDiscovery, browserDiscovery, null, mode, planRunId, "");
        }

        public WebCaseAgentStartRequest(
            Long projectId, String analysisRunId, List<String> pointIds, String environmentId, String baseUrl,
            String browser, String viewport, LoginDiscoveryContext loginDiscovery, BrowserDiscoveryRequest browserDiscovery,
            Long batchTotalDurationMs, WebCaseAgentMode mode, String planRunId
        ) {
            this(projectId, analysisRunId, pointIds, environmentId, baseUrl, browser, viewport, loginDiscovery,
                browserDiscovery, batchTotalDurationMs, mode, planRunId, "");
        }
    }

    /**
     * Ephemeral request data used only while a browser-grounded agent task is running.  It is
     * intentionally not included in the durable run response or database snapshot.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BrowserDiscoveryRequest(
        Boolean headless,
        Integer timeoutMs,
        String waitUntil,
        String authToken,
        Map<String, String> extraHeaders,
        List<String> cookieOrigins,
        @JsonProperty("allowedOrigins")
        @JsonAlias("allowedRequestOrigins")
        List<String> allowedRequestOrigins,
        String sessionId,
        Boolean reuseSession,
        BrowserLoginRequest login,
        String diagnosticMode,
        Boolean tracingEnabled,
        String authenticationMode,
        String networkPolicyMode
    ) {
        public BrowserDiscoveryRequest(
            Boolean headless,
            Integer timeoutMs,
            String waitUntil,
            String authToken,
            Map<String, String> extraHeaders,
            List<String> cookieOrigins,
            BrowserLoginRequest login
        ) {
            this(headless, timeoutMs, waitUntil, authToken, extraHeaders, cookieOrigins, null, null, null, login, null, null, "", null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BrowserLoginRequest(
        boolean enabled,
        String url,
        String usernameLocator,
        String passwordLocator,
        String submitLocator,
        String username,
        String password,
        String successType,
        String successTarget
    ) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WebCaseAgentRunResponse(
        String runId,
        Long projectId,
        String analysisRunId,
        WebCaseAgentRunStatus status,
        WebCaseAgentStage currentStage,
        List<String> requestedPointIds,
        List<String> completedPointIds,
        List<String> blockedPointIds,
        Integer ruleVersion,
        String startedAt,
        String updatedAt,
        String errorSummary,
        WebCaseAgentMode mode,
        String planRunId
    ) {
        public WebCaseAgentRunResponse {
            requestedPointIds = immutableList(requestedPointIds);
            completedPointIds = immutableList(completedPointIds);
            blockedPointIds = immutableList(blockedPointIds);
        }

        public WebCaseAgentRunResponse(
            String runId,
            WebCaseAgentRunStatus status,
            WebCaseAgentStage currentStage
        ) {
            this(runId, null, null, status, currentStage, List.of(), List.of(), List.of(), null, null, null, null, WebCaseAgentMode.COMPILE, "");
        }

        public WebCaseAgentRunResponse(
            String runId,
            Long projectId,
            String analysisRunId,
            WebCaseAgentRunStatus status,
            WebCaseAgentStage currentStage,
            List<String> requestedPointIds,
            List<String> completedPointIds,
            List<String> blockedPointIds,
            Integer ruleVersion,
            String startedAt,
            String updatedAt,
            String errorSummary
        ) {
            this(runId, projectId, analysisRunId, status, currentStage, requestedPointIds, completedPointIds, blockedPointIds, ruleVersion, startedAt, updatedAt, errorSummary, WebCaseAgentMode.COMPILE, "");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WebCaseAgentEventResponse(
        String eventId,
        String runId,
        Long sequenceNo,
        String occurredAt,
        WebCaseAgentStage stage,
        WebCaseAgentEventType eventType,
        String severity,
        String summary,
        String pointId,
        String toolName,
        Long durationMs,
        Integer ruleVersion,
        List<String> knowledgeHitIds,
        String errorCode,
        JsonNode details
    ) {
        public WebCaseAgentEventResponse(
            String eventId,
            String runId,
            Long sequenceNo,
            String occurredAt,
            WebCaseAgentStage stage,
            WebCaseAgentEventType eventType,
            String severity,
            String summary,
            String pointId,
            String toolName,
            Long durationMs,
            Integer ruleVersion,
            List<String> knowledgeHitIds,
            String errorCode
        ) {
            this(eventId, runId, sequenceNo, occurredAt, stage, eventType, severity, summary, pointId, toolName,
                durationMs, ruleVersion, knowledgeHitIds, errorCode, null);
        }

        public WebCaseAgentEventResponse {
            knowledgeHitIds = immutableList(knowledgeHitIds);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LocatorEvidence(
        String evidenceId,
        String pageUrl,
        String pageFingerprint,
        String naturalName,
        String locator,
        String role,
        String accessibleName,
        String elementType,
        boolean visible,
        String collectedAt
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LocatorKnowledgeEntry(
        String entryId,
        Long projectId,
        String environmentId,
        String moduleName,
        String pageName,
        String pageUrl,
        String pageFingerprint,
        String naturalName,
        String locator,
        String roleName,
        String accessibleName,
        String elementType,
        String source,
        String status,
        Integer evidenceVersion,
        String collectedAt,
        String lastVerifiedAt,
        JsonNode metadata
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentRuleSnapshot(
        String ruleSetId,
        String scope,
        Long projectId,
        Integer version,
        String contentHash,
        Map<String, String> phaseRules
    ) {
        public AgentRuleSnapshot {
            phaseRules = immutableMap(phaseRules);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentMemoryResponse(
        String memoryId,
        String sourceRunId,
        Long projectId,
        String moduleName,
        WebCaseAgentStage stage,
        String category,
        String content,
        AgentMemoryStatus status,
        AgentMemoryScopeType scopeType,
        List<Long> scopeProjectIds,
        Integer rulesVersion,
        String createdBy,
        Integer revision,
        String confirmedBy,
        String confirmedAt,
        String createdAt,
        String updatedAt,
        int hitCount,
        int executionSuccessCount,
        int executionFailureCount,
        int confidenceScore,
        boolean confidenceLocked,
        String lastHitAt
    ) {
        public AgentMemoryResponse(String memoryId, String sourceRunId, Long projectId, String moduleName, WebCaseAgentStage stage, String category, String content, AgentMemoryStatus status, AgentMemoryScopeType scopeType, List<Long> scopeProjectIds, Integer rulesVersion, String createdBy, String confirmedBy, String confirmedAt, String createdAt, String updatedAt) {
            this(memoryId, sourceRunId, projectId, moduleName, stage, category, content, status, scopeType, scopeProjectIds, rulesVersion, createdBy, 1, confirmedBy, confirmedAt, createdAt, updatedAt, 0, 0, 0, 35, false, null);
        }
        public AgentMemoryResponse {
            scopeProjectIds = immutableList(scopeProjectIds);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GeneratedCaseArtifact(
        String artifactId,
        String runId,
        String sourcePointId,
        String artifactType,
        JsonNode candidateJson,
        List<String> evidenceIds,
        List<String> validationErrors,
        boolean accepted,
        int repairRound,
        String resultOrigin,
        String acceptanceState,
        int revision,
        String parentArtifactId,
        String idempotencyKey,
        String modelProfileVersion,
        List<String> fieldOrigins,
        String createdAt,
        String updatedAt
    ) {
        public GeneratedCaseArtifact {
            evidenceIds = immutableList(evidenceIds);
            validationErrors = immutableList(validationErrors);
            fieldOrigins = immutableList(fieldOrigins);
        }

        public GeneratedCaseArtifact(
            String artifactId,
            String runId,
            String sourcePointId,
            String artifactType,
            JsonNode candidateJson,
            List<String> evidenceIds,
            List<String> validationErrors,
            boolean accepted,
            int repairRound,
            String createdAt,
            String updatedAt
        ) {
            this(artifactId, runId, sourcePointId, artifactType, candidateJson, evidenceIds, validationErrors,
                accepted, repairRound, "RULE_EXTRACTED", "PENDING_HUMAN_CONFIRMATION", 1, "", artifactId, "", List.of(), createdAt, updatedAt);
        }

        public GeneratedCaseArtifact(
            String sourcePointId,
            JsonNode candidateJson,
            List<String> evidenceIds,
            List<String> validationErrors,
            boolean accepted
        ) {
            this(null, null, sourcePointId, "web_case", candidateJson, evidenceIds, validationErrors,
                accepted, 0, "RULE_EXTRACTED", "PENDING_HUMAN_CONFIRMATION", 1, "", sourcePointId, "", List.of(), null, null);
        }
    }

    public record PlanDecisionRequest(boolean accepted, Integer revision) {

        /** 兼容旧调用方：不带 revision 时不做过期校验。 */
        public PlanDecisionRequest(boolean accepted) {
            this(accepted, null);
        }
    }

    public record WebCaseAgentArtifactRevisionResponse(
        String artifactId,
        String runId,
        String sourcePointId,
        int revision,
        String parentArtifactId,
        String idempotencyKey,
        String resultOrigin,
        String acceptanceState,
        String modelProfileVersion,
        List<String> fieldOrigins
    ) {}


    public record PlanUpdateRequest(JsonNode candidateJson, Integer revision) {

        /** 兼容旧调用方：不带 revision 时不做过期校验。 */
        public PlanUpdateRequest(JsonNode candidateJson) {
            this(candidateJson, null);
        }
    }

    private static <T> List<T> immutableList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }
}
