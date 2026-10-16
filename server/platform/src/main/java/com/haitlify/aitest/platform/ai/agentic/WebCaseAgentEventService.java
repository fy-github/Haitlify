package com.haitlify.aitest.platform.ai.agentic;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.WebCaseAgentEventResponse;
import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.WebCaseAgentEventType;
import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.WebCaseAgentStage;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

@Service
@Transactional
public class WebCaseAgentEventService {
    private static final int MAX_SUMMARY_CODE_POINTS = 1024;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    WebCaseAgentEventService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) { this.jdbcTemplate = jdbcTemplate; this.objectMapper = objectMapper; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WebCaseAgentEventResponse append(String runId, WebCaseAgentStage stage, WebCaseAgentEventType type, String severity, String summary, String pointId, String toolName, long durationMs, Integer ruleVersion, List<String> knowledgeHitIds, String errorCode) {
        return appendDetailed(runId, stage, type, severity, summary, pointId, toolName, durationMs, ruleVersion, knowledgeHitIds, errorCode, null);
    }

    public WebCaseAgentEventResponse appendDetailed(String runId, WebCaseAgentStage stage, WebCaseAgentEventType type, String severity, String summary, String pointId, String toolName, long durationMs, Integer ruleVersion, List<String> knowledgeHitIds, String errorCode, JsonNode details) {
        Long sequence = jdbcTemplate.queryForObject("select coalesce(max(sequence_no), 0) + 1 from ai_web_case_agent_events where run_id = ?", Long.class, runId);
        Instant now = Instant.now();
        jdbcTemplate.update("insert into ai_web_case_agent_events(event_id, run_id, sequence_no, occurred_at, stage, event_type, severity, summary, point_id, tool_name, duration_ms, rule_version, knowledge_hit_ids_json, error_code, details_json, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "evt-" + UUID.randomUUID(), runId, sequence, Timestamp.from(now), stage.name(), type.name(), severity == null ? "info" : severity, normalizeSummary(summary), pointId, toolName, durationMs, ruleVersion, write(knowledgeHitIds == null ? List.of() : knowledgeHitIds), errorCode, writeDetails(details), Timestamp.from(now));
        return list(runId, sequence - 1).stream().filter(item -> item.sequenceNo().equals(sequence)).findFirst().orElseThrow();
    }

    static String normalizeSummary(String summary) {
        String value = summary == null ? "" : summary;
        int codePointCount = value.codePointCount(0, value.length());
        if (codePointCount <= MAX_SUMMARY_CODE_POINTS) return value;
        int retainedCodePoints = MAX_SUMMARY_CODE_POINTS - 3;
        return value.substring(0, value.offsetByCodePoints(0, retainedCodePoints)) + "...";
    }

    public List<WebCaseAgentEventResponse> list(String runId, long afterSequence) {
        return jdbcTemplate.query("select * from ai_web_case_agent_events where run_id = ? and sequence_no > ? order by sequence_no", (rs, rowNum) -> new WebCaseAgentEventResponse(
            rs.getString("event_id"), rs.getString("run_id"), rs.getLong("sequence_no"), rs.getTimestamp("occurred_at").toInstant().toString(),
            WebCaseAgentStage.valueOf(rs.getString("stage").toUpperCase()), WebCaseAgentEventType.valueOf(rs.getString("event_type").toUpperCase()), rs.getString("severity"), rs.getString("summary"), rs.getString("point_id"), rs.getString("tool_name"), rs.getLong("duration_ms"), (Integer) rs.getObject("rule_version"), read(rs.getString("knowledge_hit_ids_json")), rs.getString("error_code"), readDetails(rs.getString("details_json"))
        ), runId, afterSequence);
    }

    private String write(List<String> values) { try { return objectMapper.writeValueAsString(values); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private List<String> read(String json) { try { return objectMapper.readValue(json == null ? "[]" : json, new TypeReference<>() {}); } catch (Exception exception) { return List.of(); } }
    private String writeDetails(JsonNode details) { try { return details == null || details.isNull() ? null : objectMapper.writeValueAsString(details); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private JsonNode readDetails(String json) {
        try {
            if (json == null || json.isBlank()) return null;
            JsonNode value = objectMapper.readTree(json);
            return value != null && value.isObject() ? value : null;
        } catch (Exception exception) { return null; }
    }
}
