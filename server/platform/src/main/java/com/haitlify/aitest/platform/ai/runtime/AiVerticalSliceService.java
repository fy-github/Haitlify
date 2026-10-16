package com.haitlify.aitest.platform.ai.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.haitlify.aitest.platform.ai.gateway.AiGatewaySettingsService;
import com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings;
import com.haitlify.aitest.platform.ai.runtime.AiRuntimeModels.AiTaskRequest;
import com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactAcceptanceRequest;
import com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactAcceptanceResult;
import com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactAcceptanceService;
import com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactAcceptanceState;
import com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactOrigin;
import tools.jackson.databind.ObjectMapper;

@Service
public class AiVerticalSliceService {
    private final AiRuntimeInvocationService runtime;
    private final AiGatewaySettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final AiArtifactAcceptanceService acceptanceService;
    private final com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactGateAuditService gateAuditService;

    public AiVerticalSliceService(
        AiRuntimeInvocationService runtime,
        AiGatewaySettingsService settingsService,
        ObjectMapper objectMapper,
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
        AiArtifactAcceptanceService acceptanceService,
        com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactGateAuditService gateAuditService
    ) {
        this.runtime = runtime;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.acceptanceService = acceptanceService;
        this.gateAuditService = gateAuditService;
    }

    @Transactional
    public AiVerticalSliceResult execute(AiTaskRequest request) {
        String runId = request.runId() == null || request.runId().isBlank() ? "vertical-" + UUID.randomUUID() : request.runId();
        ResolvedAiGatewaySettings settings = settingsService.loadDefaultTextSettings();
        String connectionId = "legacy-default";
        String profileId = "legacy-default-profile";
        ensureProfile(connectionId, profileId, settings, request.projectId());
        String inputHash = sha256(request.input() == null ? "" : request.input().toString());
        Instant now = Instant.now();
        insertRun(runId, request, connectionId, profileId, settings, inputHash, now);
        AiRuntimeInvocationService.AiRuntimeInvocationResult invocation = runtime.invoke(request);
        AiArtifactAcceptanceResult acceptance = acceptanceService.accept(new AiArtifactAcceptanceRequest(
            "MODEL_GENERATED".equals(invocation.resultOrigin()) ? AiArtifactOrigin.MODEL_GENERATED : AiArtifactOrigin.HUMAN_EDITED,
            AiArtifactAcceptanceState.PENDING_HUMAN_CONFIRMATION,
            invocation.output(), request.projectId(), request.projectId(), request.evidenceIds(), request.evidenceIds(),
            null, 1, request.idempotencyKey(), invocation
        ));
        if (!acceptance.accepted()) {
            insertAttempt("attempt-" + UUID.randomUUID(), runId, invocation, now);
            return new AiVerticalSliceResult(runId, null, invocation, null);
        }
        String attemptId = "attempt-" + UUID.randomUUID();
        insertAttempt(attemptId, runId, invocation, now);
        if (!"MODEL_GENERATED".equals(invocation.resultOrigin())) {
            return new AiVerticalSliceResult(runId, null, invocation, null);
        }
        String artifactId = "artifact-" + UUID.randomUUID();
        List<String> evidenceIds = request.evidenceIds() == null ? List.of() : request.evidenceIds();
        insertArtifact(artifactId, runId, request.projectId(), invocation, evidenceIds, now);
        gateAuditService.record(new com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactGateAuditService.GateRecord(
            artifactId, runId, request.projectId(), "MODEL_GENERATED", "PENDING_HUMAN_CONFIRMATION", 1, "", request.idempotencyKey(), evidenceIds, List.of(), request.actorId()));
        updateRun(runId, invocation, now);
        return new AiVerticalSliceResult(runId, artifactId, invocation, readArtifact(artifactId));
    }

    @Transactional
    public AiVerticalSliceArtifact confirm(String artifactId, String reviewerActorId) {
        int updated = jdbcTemplate.update(
            "update ai_runtime_vertical_artifacts set acceptance_state = 'ACCEPTED', reviewer_actor_id = ?, reviewed_at = ?, updated_at = ? where artifact_id = ? and acceptance_state = 'PENDING_HUMAN_CONFIRMATION'",
            reviewerActorId, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), artifactId
        );
        if (updated != 1) throw new IllegalArgumentException("artifact is not pending human confirmation: " + artifactId);
        AiVerticalSliceArtifact accepted = readArtifact(artifactId);
        gateAuditService.record(new com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactGateAuditService.GateRecord(
            accepted.artifactId(), accepted.runId(), accepted.projectId(), "HUMAN_EDITED", "ACCEPTED", 1, accepted.artifactId(), accepted.runId() + ":" + accepted.artifactId(), readEvidenceIds(accepted.evidenceIdsJson()), List.of(), reviewerActorId));
        return accepted;
    }

    public AiVerticalSliceArtifact readArtifact(String artifactId) {
        return jdbcTemplate.queryForObject(
            "select artifact_id, run_id, project_id, origin, acceptance_state, content_json, evidence_ids_json, reviewer_actor_id, reviewed_at from ai_runtime_vertical_artifacts where artifact_id = ?",
            (rs, rowNum) -> new AiVerticalSliceArtifact(
                rs.getString("artifact_id"), rs.getString("run_id"), rs.getLong("project_id"),
                rs.getString("origin"), rs.getString("acceptance_state"), rs.getString("content_json"),
                rs.getString("evidence_ids_json"), rs.getString("reviewer_actor_id"), rs.getTimestamp("reviewed_at")
            ), artifactId
        );
    }

    private void ensureProfile(String connectionId, String profileId, ResolvedAiGatewaySettings settings, Long projectId) {
        Integer connectionCount = jdbcTemplate.queryForObject("select count(*) from ai_connections where connection_id = ? and version = 1", Integer.class, connectionId);
        if (connectionCount == null || connectionCount == 0) {
            jdbcTemplate.update("insert into ai_connections(connection_id, version, base_url, credential_ref, protocol, enabled, allowed_projects_json, created_at, updated_at) values (?, 1, ?, ?, ?, true, ?, ?, ?)",
                connectionId, settings.baseUrl(), "legacy-ai-system-config", protocol(settings.providerType()), "[" + projectId + "]", Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        }
        Integer profileCount = jdbcTemplate.queryForObject("select count(*) from ai_model_profiles where profile_id = ? and version = 1", Integer.class, profileId);
        if (profileCount == null || profileCount == 0) {
            jdbcTemplate.update("insert into ai_model_profiles(profile_id, version, connection_id, model_id, capabilities_json, parameter_policy_json, verification_json, created_at, updated_at) values (?, 1, ?, ?, ?, '{}', ?, ?, ?)",
                profileId, connectionId, settings.model(), "[\"TEXT\",\"JSON_OBJECT\"]", "{\"source\":\"legacy-ai-system-config\",\"status\":\"LIVE_INVOCATION\"}", Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        }
    }

    private void insertRun(String runId, AiTaskRequest request, String connectionId, String profileId, ResolvedAiGatewaySettings settings, String inputHash, Instant now) {
        jdbcTemplate.update("insert into ai_runtime_vertical_runs(run_id, project_id, actor_id, connection_id, connection_version, profile_id, profile_version, protocol, input_hash, status, result_origin, acceptance_state, schema_outcome, evidence_ids_json, created_at, updated_at) values (?, ?, ?, ?, 1, ?, 1, ?, ?, 'RUNNING', 'MODEL', 'PENDING', 'PENDING', ?, ?, ?)",
            runId, request.projectId(), request.actorId(), connectionId, profileId, protocol(settings.providerType()), inputHash,
            objectMapper.valueToTree(request.evidenceIds()).toString(), Timestamp.from(now), Timestamp.from(now));
    }

    private void insertAttempt(String attemptId, String runId, AiRuntimeInvocationService.AiRuntimeInvocationResult result, Instant now) {
        var usage = result.usage();
        jdbcTemplate.update("insert into ai_runtime_vertical_attempts(attempt_id, run_id, provider_request_id, status, error_category, usage_known, input_tokens, output_tokens, duration_ms, ttfb_ms, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            attemptId, runId, result.providerRequestId(), result.resultOrigin(), result.failureCategory(), usage != null && usage.inputTokens() != null && usage.outputTokens() != null,
            usage == null ? null : usage.inputTokens(), usage == null ? null : usage.outputTokens(),
            result.durationMillis(), result.ttfbMillis(), Timestamp.from(now));
    }

    private void insertArtifact(String artifactId, String runId, Long projectId, AiRuntimeInvocationService.AiRuntimeInvocationResult result, List<String> evidenceIds, Instant now) {
        jdbcTemplate.update("insert into ai_runtime_vertical_artifacts(artifact_id, run_id, project_id, origin, acceptance_state, content_json, evidence_ids_json, created_at, updated_at) values (?, ?, ?, ?, 'PENDING_HUMAN_CONFIRMATION', ?, ?, ?, ?)",
            artifactId, runId, projectId, result.resultOrigin(), result.output().toString(), objectMapper.valueToTree(evidenceIds).toString(), Timestamp.from(now), Timestamp.from(now));
    }

    private void updateRun(String runId, AiRuntimeInvocationService.AiRuntimeInvocationResult result, Instant now) {
        jdbcTemplate.update("update ai_runtime_vertical_runs set status = 'WAITING_REVIEW', result_origin = ?, acceptance_state = 'PENDING_HUMAN_CONFIRMATION', schema_outcome = 'PASS', updated_at = ? where run_id = ?",
            result.resultOrigin(), Timestamp.from(now), runId);
    }

    private List<String> readEvidenceIds(String value) {
        try {
            if (value == null || value.isBlank()) return List.of();
            return objectMapper.readValue(value, new tools.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception exception) {
            return List.of();
        }
    }
    private String protocol(String providerType) {
        return "responses".equalsIgnoreCase(providerType) ? "RESPONSES" : "CHAT_COMPLETIONS";
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot hash vertical slice input", exception);
        }
    }

    public record AiVerticalSliceResult(String runId, String artifactId, AiRuntimeInvocationService.AiRuntimeInvocationResult invocation, AiVerticalSliceArtifact artifact) {}
    public record AiVerticalSliceArtifact(String artifactId, String runId, Long projectId, String origin, String acceptanceState, String contentJson, String evidenceIdsJson, String reviewerActorId, java.sql.Timestamp reviewedAt) {}
}
