package com.haitlify.aitest.platform.ai.analysis;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.haitlify.aitest.platform.ai.event.AiRunEventListResponse;
import com.haitlify.aitest.platform.ai.event.AiRunEventService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
class AiAnalysisRunDetailService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AiRunEventService aiRunEventService;

    AiAnalysisRunDetailService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, AiRunEventService aiRunEventService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.aiRunEventService = aiRunEventService;
    }

    AiCoverageSummaryResponse coverageSummary(String runId) {
        Long runPk = runPk(runId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select summary_json, warnings_json
            from ai_coverage_summaries
            where run_id = ?
            order by created_at desc, id desc
            limit 1
            """, runPk);
        if (rows.isEmpty()) {
            return new AiCoverageSummaryResponse(runId, objectMapper.createObjectNode(), objectMapper.createArrayNode());
        }
        Map<String, Object> row = rows.getFirst();
        return new AiCoverageSummaryResponse(
            runId,
            parseObject((String) row.get("SUMMARY_JSON")),
            parseArray((String) row.get("WARNINGS_JSON"))
        );
    }

    AiUnclearQuestionListResponse unclearQuestions(String runId) {
        Long runPk = runPk(runId);
        List<AiUnclearQuestionResponse> items = jdbcTemplate.query("""
            select question_id, severity, question_text, source_ref, status, created_at, updated_at
            from ai_unclear_questions
            where run_id = ?
            order by id asc
            """, this::mapQuestion, runPk);
        return new AiUnclearQuestionListResponse(items);
    }

    AiRunEventListResponse runEvents(String runId, Long afterSeq) {
        return aiRunEventService.listByRun(runId, afterSeq);
    }

    AiToolCallListResponse toolCalls(String runId) {
        List<AiToolCallResponse> items = jdbcTemplate.query("""
            select call_id, run_id, project_id, server_id, tool_name, read_only, status,
                   request_summary_json, response_summary_json, error_message, actor_id, created_at
            from ai_tool_call_records
            where run_id = ?
            order by created_at desc, id desc
            """, this::mapToolCall, runId);
        return new AiToolCallListResponse(items);
    }

    private AiUnclearQuestionResponse mapQuestion(ResultSet rs, int rowNum) throws SQLException {
        return new AiUnclearQuestionResponse(
            rs.getString("question_id"),
            rs.getString("severity"),
            rs.getString("question_text"),
            rs.getString("source_ref"),
            rs.getString("status"),
            toIso(rs.getTimestamp("created_at")),
            toIso(rs.getTimestamp("updated_at"))
        );
    }

    private AiToolCallResponse mapToolCall(ResultSet rs, int rowNum) throws SQLException {
        return new AiToolCallResponse(
            rs.getString("call_id"),
            rs.getString("run_id"),
            nullableLong(rs, "project_id"),
            rs.getString("server_id"),
            rs.getString("tool_name"),
            rs.getBoolean("read_only"),
            rs.getString("status"),
            parseObject(rs.getString("request_summary_json")),
            parseObject(rs.getString("response_summary_json")),
            rs.getString("error_message"),
            rs.getString("actor_id"),
            toIso(rs.getTimestamp("created_at"))
        );
    }

    private Long runPk(String runId) {
        return jdbcTemplate.queryForObject("select id from ai_analysis_runs where run_id = ?", Long.class, runId);
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
            throw new IllegalArgumentException("Invalid AI analysis detail JSON", exception);
        }
    }

    private String toIso(Timestamp timestamp) {
        if (timestamp == null) return null;
        Instant instant = timestamp.toInstant();
        return instant.toString();
    }
}

record AiCoverageSummaryResponse(
    String runId,
    JsonNode summary,
    JsonNode warnings
) {
}

record AiUnclearQuestionListResponse(
    List<AiUnclearQuestionResponse> items
) {
}

record AiUnclearQuestionResponse(
    String questionId,
    String severity,
    String questionText,
    String sourceRef,
    String status,
    String createdAt,
    String updatedAt
) {
}

record AiToolCallListResponse(
    List<AiToolCallResponse> items
) {
}

record AiToolCallResponse(
    String callId,
    String runId,
    Long projectId,
    String serverId,
    String toolName,
    boolean readOnly,
    String status,
    JsonNode requestSummary,
    JsonNode responseSummary,
    String errorMessage,
    String actorId,
    String createdAt
) {
}
