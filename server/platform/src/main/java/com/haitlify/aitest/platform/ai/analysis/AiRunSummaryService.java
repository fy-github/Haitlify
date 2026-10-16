package com.haitlify.aitest.platform.ai.analysis;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.haitlify.aitest.platform.ai.gateway.AiRetryReason;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds and persists the durable run completion summary (deliverable 3) from backend events and
 * call records — never from model narrative. Extends {@code output_summary_json} additively: the
 * legacy {@code draftTestPointCount}/{@code toolCallCount} keys are preserved alongside the new
 * structured fields, so existing readers keep working.
 */
@Service
@Transactional
class AiRunSummaryService {

    private static final Set<AiRetryReason> NON_RETRYABLE = Set.of(
        AiRetryReason.AUTH_DENIED,
        AiRetryReason.MODEL_OR_ENDPOINT_NOT_FOUND,
        AiRetryReason.INVALID_REQUEST,
        AiRetryReason.OUTPUT_VALIDATION,
        AiRetryReason.NO_ROUTE
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    AiRunSummaryService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    void writeSummary(String runId, AiRetryReason terminalReason) {
        JsonNode summary = computeSummary(runId, terminalReason);
        jdbcTemplate.update(
            "update ai_analysis_runs set output_summary_json = ?, updated_at = ? where run_id = ?",
            writeJson(summary), Timestamp.from(Instant.now()), runId
        );
    }

    JsonNode computeSummary(String runId, AiRetryReason terminalReason) {
        Map<String, Object> run = jdbcTemplate.queryForMap(
            "select id, total_chunks from ai_analysis_runs where run_id = ?", runId
        );
        Long runPk = ((Number) run.get("ID")).longValue();
        int totalChunks = number(run.get("TOTAL_CHUNKS")).intValue();
        int completed = chunkCount(runPk, "succeeded") + chunkCount(runPk, "skipped");
        int failed = chunkCount(runPk, "failed");
        int skipped = chunkCount(runPk, "skipped");
        int retried = eventCount(runId, "fallback_switched");
        int generatedTestPoints = jdbcTemplate.queryForObject(
            "select count(*) from ai_draft_test_points where run_id = ?", Integer.class, runPk
        );
        List<Map<String, Object>> calls = jdbcTemplate.queryForList(
            "select record_id, provider_type, model_name, duration_ms, status from ai_call_records where run_id = ? order by id asc",
            runPk
        );
        ArrayNode attempts = objectMapper.createArrayNode();
        for (Map<String, Object> call : calls) {
            ObjectNode attempt = attempts.addObject();
            attempt.put("callRecordId", str(call, "RECORD_ID"));
            attempt.put("providerType", str(call, "PROVIDER_TYPE"));
            attempt.put("model", str(call, "MODEL_NAME"));
            attempt.put("durationMs", number(call.get("DURATION_MS")).longValue());
            attempt.put("status", str(call, "STATUS"));
        }
        Set<String> fallbackReasons = new LinkedHashSet<>(jdbcTemplate.queryForList(
            "select distinct retry_reason from ai_run_events where run_id = ? and retry_reason <> ''",
            String.class, runId
        ));
        String strategySnapshotId = jdbcTemplate.queryForList(
            "select strategy_snapshot_id from ai_run_events where run_id = ? and strategy_snapshot_id <> '' limit 1",
            String.class, runId
        ).stream().findFirst().orElse("");
        String terminalLabel = terminalReason == null ? "succeeded" : terminalReason.name().toLowerCase();
        boolean retrySafe = terminalReason == null || !NON_RETRYABLE.contains(terminalReason);

        ObjectNode summary = objectMapper.createObjectNode();
        ObjectNode inputSize = summary.putObject("inputSize");
        inputSize.put("chunkCount", totalChunks);
        ObjectNode chunks = summary.putObject("chunks");
        chunks.put("total", totalChunks);
        chunks.put("completed", completed);
        chunks.put("retried", retried);
        chunks.put("failed", failed);
        chunks.put("skipped", skipped);
        summary.set("attempts", attempts);
        summary.set("fallbackReasons", objectMapper.valueToTree(fallbackReasons));
        summary.put("generatedTestPoints", generatedTestPoints);
        ObjectNode validation = summary.putObject("validationResult");
        validation.put("valid", terminalReason == null);
        validation.put("repairTriggered", eventCount(runId, "output_repair_started") > 0);
        if (!strategySnapshotId.isBlank()) {
            summary.put("strategySnapshotId", strategySnapshotId);
        }
        summary.put("terminalReason", terminalLabel);
        // Legacy keys preserved for existing readers.
        summary.put("draftTestPointCount", generatedTestPoints);
        summary.put("toolCallCount", calls.size());
        ObjectNode retryCommand = summary.putObject("retryCommand");
        retryCommand.put("safe", retrySafe);
        if (retrySafe) {
            retryCommand.put("method", "POST");
            retryCommand.put("endpoint", "/api/ai/analysis-runs/" + runId + "/retry");
        }
        return summary;
    }

    private int chunkCount(Long runPk, String status) {
        return jdbcTemplate.queryForObject(
            "select count(*) from ai_analysis_chunks where run_id = ? and status = ?",
            Integer.class, runPk, status
        );
    }

    private int eventCount(String runId, String stage) {
        return jdbcTemplate.queryForObject(
            "select count(*) from ai_run_events where run_id = ? and stage = ?",
            Integer.class, runId, stage
        );
    }

    private String str(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? "" : value.toString();
    }

    private Number number(Object value) {
        return value instanceof Number number ? number : 0;
    }

    private String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid AI run summary payload", exception);
        }
    }
}
