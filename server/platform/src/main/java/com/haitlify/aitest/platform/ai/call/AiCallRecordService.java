package com.haitlify.aitest.platform.ai.call;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
class AiCallRecordService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    AiCallRecordService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    AiCallRecordListResponse listRecords(String runId) {
        if (runId != null && !runId.isBlank()) {
            List<AiCallRecordResponse> items = jdbcTemplate.query("""
                select c.*, r.run_id as external_run_id
                from ai_call_records c
                left join ai_analysis_runs r on r.id = c.run_id
                where r.run_id = ?
                order by c.created_at desc, c.id desc
                """, this::mapRecord, runId);
            return new AiCallRecordListResponse(items);
        }

        List<AiCallRecordResponse> items = jdbcTemplate.query("""
            select c.*, r.run_id as external_run_id
            from ai_call_records c
            left join ai_analysis_runs r on r.id = c.run_id
            order by c.created_at desc, c.id desc
            """, this::mapRecord);
        return new AiCallRecordListResponse(items);
    }

    AiCallRecordResponse getRecord(String recordId) {
        List<AiCallRecordResponse> records = jdbcTemplate.query("""
            select c.*, r.run_id as external_run_id
            from ai_call_records c
            left join ai_analysis_runs r on r.id = c.run_id
            where c.record_id = ?
            """, this::mapRecord, recordId);
        if (records.isEmpty()) {
            throw new IllegalArgumentException("Unknown AI call record: " + recordId);
        }
        return records.getFirst();
    }

    private AiCallRecordResponse mapRecord(ResultSet rs, int rowNum) throws SQLException {
        return new AiCallRecordResponse(
            rs.getString("record_id"),
            rs.getString("external_run_id"),
            nullableLong(rs, "project_id"),
            rs.getString("provider_type"),
            rs.getString("model_name"),
            rs.getString("config_snapshot_id"),
            rs.getString("agent_id"),
            parseArray(rs.getString("skill_ids_json")),
            rs.getString("rule_version"),
            rs.getString("modality"),
            rs.getString("request_id"),
            rs.getString("status"),
            rs.getString("error_code"),
            rs.getString("error_message"),
            rs.getLong("duration_ms"),
            rs.getInt("token_usage_input"),
            rs.getInt("token_usage_output"),
            rs.getBigDecimal("estimated_cost") == null ? "0" : rs.getBigDecimal("estimated_cost").toPlainString(),
            parseObject(rs.getString("input_summary_json")),
            parseObject(rs.getString("output_summary_json")),
            rs.getString("raw_payload_json"),
            rs.getString("actor_id"),
            rs.getString("actor_name"),
            toIsoString(rs.getTimestamp("created_at")),
            toIsoString(rs.getTimestamp("updated_at"))
        );
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private JsonNode parseObject(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        return parse(json);
    }

    private JsonNode parseArray(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createArrayNode();
        }
        return parse(json);
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid AI call record JSON", exception);
        }
    }

    private String toIsoString(Timestamp timestamp) {
        if (timestamp == null) return null;
        Instant instant = timestamp.toInstant();
        return instant.toString();
    }
}

record AiCallRecordListResponse(
    List<AiCallRecordResponse> items
) {
}

record AiCallRecordResponse(
    String recordId,
    String runId,
    Long projectId,
    String providerType,
    String modelName,
    String configSnapshotId,
    String agentId,
    JsonNode skillIds,
    String ruleVersion,
    String modality,
    String requestId,
    String status,
    String errorCode,
    String errorMessage,
    long durationMs,
    int tokenUsageInput,
    int tokenUsageOutput,
    String estimatedCost,
    JsonNode inputSummary,
    JsonNode outputSummary,
    String rawPayload,
    String actorId,
    String actorName,
    String createdAt,
    String updatedAt
) {
}
