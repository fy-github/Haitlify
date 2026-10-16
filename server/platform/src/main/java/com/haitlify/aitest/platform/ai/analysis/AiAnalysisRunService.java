package com.haitlify.aitest.platform.ai.analysis;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.haitlify.aitest.platform.ai.analysis.chunk.AiAnalysisChunkPlanningService;
import com.haitlify.aitest.platform.ai.analysis.chunk.AiAnalysisChunkPlanningService.AiAnalysisChunkPlanRequest;
import com.haitlify.aitest.platform.ai.call.AiCallRecordPayloads;
import com.haitlify.aitest.platform.ai.config.AiRunPolicyService;
import com.haitlify.aitest.platform.ai.gateway.AiRoute;
import com.haitlify.aitest.platform.ai.gateway.AiRoutingStrategy;
import com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactGateAuditService;
import com.haitlify.aitest.platform.auth.ProjectAccessService;
import com.haitlify.aitest.platform.security.SecretCipher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional
class AiAnalysisRunService {

    private static final int MIN_ANALYSIS_TIMEOUT_MS = 120000;
    private static final int DEFAULT_CHUNK_INPUT_TOKENS = 1200;
    private static final int DEFAULT_MAX_ANALYSIS_CHUNKS = 24;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AiAnalysisChunkPlanningService chunkPlanningService;
    private final AiAnalysisRunDispatcher aiAnalysisRunDispatcher;
    private final AiRunPolicyService runPolicyService;
    private final SecretCipher secretCipher;
    private final AiArtifactGateAuditService gateAuditService;
    private final ProjectAccessService projectAccessService;
    private final AiRunOriginSyncService runOriginSyncService;

    AiAnalysisRunService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        AiAnalysisChunkPlanningService chunkPlanningService,
        AiAnalysisRunDispatcher aiAnalysisRunDispatcher,
        AiRunPolicyService runPolicyService,
        SecretCipher secretCipher,
        AiArtifactGateAuditService gateAuditService,
        ProjectAccessService projectAccessService,
        AiRunOriginSyncService runOriginSyncService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.chunkPlanningService = chunkPlanningService;
        this.aiAnalysisRunDispatcher = aiAnalysisRunDispatcher;
        this.runPolicyService = runPolicyService;
        this.secretCipher = secretCipher;
        this.gateAuditService = gateAuditService;
        this.projectAccessService = projectAccessService;
        this.runOriginSyncService = runOriginSyncService;
    }

    AiAnalysisRunResponse createRun(CreateAiAnalysisRunRequest request) {
        projectAccessService.requireAccess(request.projectId());
        ObjectNode inputSummary = objectMapper.createObjectNode();
        inputSummary.put("projectId", request.projectId());
        inputSummary.put("targetCaseKind", blankToDefault(request.targetCaseKind(), "interface"));
        inputSummary.put("templateId", blankToDefault(request.templateId(), ""));
        inputSummary.put("analysisStrategy", blankToDefault(request.analysisStrategy(), "balanced"));
        inputSummary.set("documentIds", objectMapper.valueToTree(request.documentIds()));
        return executeChunkedRun(request.projectId(), request.documentIds(), inputSummary);
    }

    AiAnalysisRunResponse getRun(String runId) {
        Map<String, Object> run = findRun(runId);
        projectAccessService.requireAccess(((Number) run.get("PROJECT_ID")).longValue());
        return toRunResponse(run);
    }

    AiAnalysisRunListResponse listProjectRuns(Long projectId) {
        projectAccessService.requireAccess(projectId);
        List<AiAnalysisRunResponse> items = jdbcTemplate.queryForList(
                "select * from ai_analysis_runs where project_id = ? order by created_at desc",
                projectId
            )
            .stream()
            .map(this::toRunResponse)
            .toList();
        return new AiAnalysisRunListResponse(items);
    }

    AiAnalysisRunResponse retryRun(String runId) {
        Map<String, Object> sourceRun = findRun(runId);
        projectAccessService.requireAccess(((Number) sourceRun.get("PROJECT_ID")).longValue());
        JsonNode inputSummary = parse((String) sourceRun.get("INPUT_SUMMARY_JSON"));
        List<String> documentIds = objectMapper.convertValue(
            inputSummary.path("documentIds"),
            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
        );
        ObjectNode retryInput = (ObjectNode) inputSummary.deepCopy();
        retryInput.put("retryOfRunId", runId);
        return executeChunkedRun(((Number) sourceRun.get("PROJECT_ID")).longValue(), documentIds, retryInput);
    }

    AiAnalysisRunResponse cancelRun(String runId) {
        Map<String, Object> run = findRun(runId);
        projectAccessService.requireAccess(((Number) run.get("PROJECT_ID")).longValue());
        String status = (String) run.get("STATUS");
        if (!List.of("succeeded", "failed", "cancelled").contains(status)) {
            jdbcTemplate.update(
                "update ai_analysis_runs set status = ?, worker_status = ?, error_summary = ?, finished_at = ?, updated_at = ? where run_id = ?",
                "cancelled",
                "cancelled",
                "用户取消",
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                runId
            );
            aiAnalysisRunDispatcher.cancelAfterCommit(runId);
        }
        return getRun(runId);
    }

    private AiAnalysisRunResponse executeChunkedRun(Long projectId, List<String> documentIds, ObjectNode inputSummary) {
        String runId = "run-" + UUID.randomUUID();
        Instant now = Instant.now();
        GatewaySettings config = loadGatewaySettingsOrNull();
        AiRoutingStrategy strategy = stampStrategySnapshotId(
            runPolicyService.resolveStrategy(deriveTaskType(inputSummary)), runId
        );
        insertRun(runId, projectId, inputSummary, 0, now, config, serializeStrategy(strategy));
        chunkPlanningService.planChunks(runId, new AiAnalysisChunkPlanRequest(
            DEFAULT_CHUNK_INPUT_TOKENS,
            DEFAULT_MAX_ANALYSIS_CHUNKS,
            inputSummary.path("analysisStrategy").asText("all")
        ));
        aiAnalysisRunDispatcher.dispatchAfterCommit(runId);
        return getRun(runId);
    }

    private String deriveTaskType(ObjectNode inputSummary) {
        return "requirement_analysis";
    }

    private AiRoutingStrategy stampStrategySnapshotId(AiRoutingStrategy strategy, String runId) {
        return new AiRoutingStrategy(
            "strategy:" + runId,
            strategy.taskType(),
            strategy.routes(),
            strategy.retryBudget(),
            strategy.perCallTimeoutMs(),
            strategy.wholeRunDeadlineMs(),
            strategy.policySnapshot()
        );
    }

    private String serializeStrategy(AiRoutingStrategy strategy) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("strategySnapshotId", strategy.strategySnapshotId());
        root.put("taskType", strategy.taskType());
        root.put("retryBudget", strategy.retryBudget());
        root.put("perCallTimeoutMs", strategy.perCallTimeoutMs());
        root.put("wholeRunDeadlineMs", strategy.wholeRunDeadlineMs());
        if (strategy.policySnapshot() != null) {
            root.set("policy", strategy.policySnapshot());
        }
        ArrayNode routes = root.putArray("routes");
        for (AiRoute route : strategy.routes()) {
            ObjectNode item = routes.addObject();
            item.put("connectionId", route.connectionId());
            item.put("providerName", route.providerName());
            item.put("providerType", route.providerType());
            item.put("baseUrl", route.baseUrl());
            item.put("model", route.model());
            item.put("apiKey", secretCipher.encrypt(route.apiKey()));
            item.put("requestTimeoutMs", route.requestTimeoutMs());
        }
        return root.toString();
    }

    private Long insertRun(String runId, Long projectId, ObjectNode inputSummary, int totalChunks, Instant now, GatewaySettings config, String strategySnapshotJson) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                insert into ai_analysis_runs(
                  run_id, project_id, target_case_kind, template_id, analysis_strategy, execution_mode,
                  status, worker_status, coverage_status, total_chunks, completed_chunks, current_chunk_id,
                  resume_from_chunk_id, error_summary, config_snapshot_json, rule_snapshot_json,
                  skill_snapshot_json, schema_snapshot_json, input_summary_json, output_summary_json,
                  actor_id, actor_name, started_at, finished_at, created_at, updated_at, strategy_snapshot_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, runId);
            statement.setLong(2, projectId);
            statement.setString(3, inputSummary.path("targetCaseKind").asText("interface"));
            statement.setString(4, inputSummary.path("templateId").asText(""));
            statement.setString(5, inputSummary.path("analysisStrategy").asText("balanced"));
            statement.setString(6, "multi_pass");
            statement.setString(7, "queued");
            statement.setString(8, "idle");
            statement.setString(9, "pending");
            statement.setInt(10, totalChunks);
            statement.setInt(11, 0);
            statement.setString(12, "");
            statement.setString(13, "");
            statement.setString(14, "");
            statement.setString(15, stringifyGatewaySettings(config));
            statement.setString(16, "{}");
            statement.setString(17, "{}");
            statement.setString(18, "{}");
            statement.setString(19, AiCallRecordPayloads.compactJson(objectMapper, inputSummary));
            statement.setString(20, null);
            statement.setString(21, com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem());
            statement.setString(22, com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName());
            statement.setTimestamp(23, Timestamp.from(now));
            statement.setTimestamp(24, null);
            statement.setTimestamp(25, Timestamp.from(now));
            statement.setTimestamp(26, Timestamp.from(now));
            statement.setString(27, strategySnapshotJson);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private GatewaySettings loadGatewaySettingsOrNull() {
        try {
            return loadGatewaySettings();
        } catch (Exception exception) {
            // Missing/incomplete connection config: createRun still returns a runId promptly;
            // the async worker fails the run gracefully with the config-incomplete message.
            return null;
        }
    }

    private String stringifyGatewaySettings(GatewaySettings settings) {
        if (settings == null) {
            return "{}";
        }
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("providerType", settings.providerType());
        snapshot.put("baseUrl", settings.baseUrl());
        snapshot.put("model", settings.model());
        snapshot.put("apiKey", secretCipher.encrypt(settings.apiKey()));
        snapshot.put("timeoutMs", settings.timeoutMs());
        return snapshot.toString();
    }

    private GatewaySettings loadGatewaySettings() {
        List<GatewaySettings> settings = jdbcTemplate.query(
            "select enabled, provider_type, base_url, text_model, api_key_ciphertext, request_timeout_ms from ai_system_configs where config_key = 'default'",
            (rs, rowNum) -> {
                if (!rs.getBoolean("enabled")) {
                    throw new IllegalStateException("AI 配置未启用，请先在 AI 配置页启用并保存配置");
                }
                String providerType = blankToDefault(rs.getString("provider_type"), "openai-compatible");
                String baseUrl = blankToDefault(rs.getString("base_url"), "");
                String model = blankToDefault(rs.getString("text_model"), "");
                String apiKey = secretCipher.decrypt(blankToDefault(rs.getString("api_key_ciphertext"), ""));
                if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) {
                    throw new IllegalStateException("AI 配置未完成，请先在 AI 配置页保存 Base URL、模型和 API Key");
                }
                return new GatewaySettings(
                    providerType,
                    baseUrl,
                    model,
                    apiKey,
                    Math.max(rs.getInt("request_timeout_ms"), MIN_ANALYSIS_TIMEOUT_MS)
                );
            }
        );
        if (settings.isEmpty()) {
            throw new IllegalStateException("AI 配置未完成，请先在 AI 配置页保存 Base URL、模型和 API Key");
        }
        return settings.get(0);
    }

    private void markRunFailed(String runId, String errorSummary, int completedChunks) {
        jdbcTemplate.update("""
            update ai_analysis_runs
            set status = ?, worker_status = ?, coverage_status = ?, completed_chunks = ?, error_summary = ?,
                finished_at = ?, updated_at = ?
            where run_id = ?
            """,
            "failed",
            "idle",
            "failed",
            completedChunks,
            blankToDefault(errorSummary, "AI 分析失败"),
            Timestamp.from(Instant.now()),
            Timestamp.from(Instant.now()),
            runId
        );
    }

    private Map<String, Object> findRun(String runId) {
        return jdbcTemplate.queryForMap("select * from ai_analysis_runs where run_id = ?", runId);
    }

    private AiAnalysisRunResponse toRunResponse(Map<String, Object> row) {
        return new AiAnalysisRunResponse(
            (String) row.get("RUN_ID"),
            ((Number) row.get("PROJECT_ID")).longValue(),
            (String) row.get("TARGET_CASE_KIND"),
            (String) row.get("TEMPLATE_ID"),
            (String) row.get("ANALYSIS_STRATEGY"),
            (String) row.get("STATUS"),
            (String) row.get("WORKER_STATUS"),
            (String) row.get("COVERAGE_STATUS"),
            ((Number) row.get("TOTAL_CHUNKS")).intValue(),
            ((Number) row.get("COMPLETED_CHUNKS")).intValue(),
            ((String) row.get("ERROR_SUMMARY")),
            (String) row.get("RESULT_ORIGIN"),
            (String) row.get("ACCEPTANCE_STATE"),
            parse((String) row.get("INPUT_SUMMARY_JSON")),
            parse((String) row.get("OUTPUT_SUMMARY_JSON")),
            toIso(row.get("STARTED_AT")),
            toIso(row.get("HEARTBEAT_AT")),
            toIso(row.get("FINISHED_AT")),
            toIso(row.get("CREATED_AT")),
            toIso(row.get("UPDATED_AT"))
        );
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }

    private String stringify(JsonNode node) {
        if (node == null) return null;
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid AI analysis payload", exception);
        }
    }

    private String toIso(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().toString();
        if (value instanceof Instant instant) return instant.toString();
        return String.valueOf(value);
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

record CreateAiAnalysisRunRequest(
    Long projectId,
    List<String> documentIds,
    String targetCaseKind,
    String templateId,
    String analysisStrategy
) {
}

record AiAnalysisRunResponse(
    String runId,
    Long projectId,
    String targetCaseKind,
    String templateId,
    String analysisStrategy,
    String status,
    String workerStatus,
    String coverageStatus,
    int totalChunks,
    int completedChunks,
    String errorSummary,
    String resultOrigin,
    String acceptanceState,
    JsonNode inputSummary,
    JsonNode outputSummary,
    String startedAt,
    String heartbeatAt,
    String finishedAt,
    String createdAt,
    String updatedAt
) {
}

record AiAnalysisRunListResponse(
    List<AiAnalysisRunResponse> items
) {
}

record GatewaySettings(
    String providerType,
    String baseUrl,
    String model,
    String apiKey,
    int timeoutMs
) {
}
