package com.haitlify.aitest.platform.ai.automation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional
class AiAutomationSuggestionService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    AiAutomationSuggestionService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    AiAutomationSuggestionListResponse listSuggestions(String runId) {
        ensureSuggestions(runId);
        return new AiAutomationSuggestionListResponse(jdbcTemplate.query(
            "select * from ai_automation_suggestions where run_id = ? order by id asc",
            (rs, rowNum) -> mapResponse(rs),
            runId
        ));
    }

    private void ensureSuggestions(String runId) {
        Integer existing = jdbcTemplate.queryForObject(
            "select count(*) from ai_automation_suggestions where run_id = ?",
            Integer.class,
            runId
        );
        if (existing != null && existing > 0) {
            return;
        }
        List<DraftPoint> points = jdbcTemplate.query(
            """
            select p.point_id, p.case_kind, p.page_or_feature, p.suggested_steps, p.expected_result, p.metadata_json
            from ai_draft_test_points p
            join ai_analysis_runs r on p.run_id = r.id
            where r.run_id = ?
            order by p.id asc
            """,
            (rs, rowNum) -> new DraftPoint(
                rs.getString("point_id"),
                rs.getString("case_kind"),
                rs.getString("page_or_feature"),
                rs.getString("suggested_steps"),
                rs.getString("expected_result"),
                parse(rs.getString("metadata_json"))
            ),
            runId
        );
        Instant now = Instant.now();
        for (DraftPoint point : points) {
            SuggestionPayload payload = buildPayload(point);
            jdbcTemplate.update(
                """
                insert into ai_automation_suggestions(
                  suggestion_id, run_id, draft_point_id, case_kind, automation_readiness,
                  required_data_json, required_environment_json, suggested_steps_json,
                  suggested_assertions_json, risks_json, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "automation-suggestion-" + UUID.randomUUID(),
                runId,
                point.pointId(),
                point.caseKind(),
                payload.automationReadiness(),
                stringify(payload.requiredData()),
                stringify(payload.requiredEnvironment()),
                stringify(payload.suggestedSteps()),
                stringify(payload.suggestedAssertions()),
                stringify(payload.risks()),
                now,
                now
            );
        }
    }

    private SuggestionPayload buildPayload(DraftPoint point) {
        String targetKey = point.metadata().path("targetKey").asText(point.pageOrFeature());
        ArrayNode steps = objectMapper.createArrayNode();
        Arrays.stream((point.suggestedSteps() == null ? "" : point.suggestedSteps()).split("\\R"))
            .filter(step -> !step.isBlank())
            .forEach(steps::add);
        ArrayNode assertions = objectMapper.createArrayNode().add(textOrDefault(point.expectedResult(), "状态码和响应体符合预期"));
        ArrayNode risks = objectMapper.createArrayNode();
        ObjectNode requiredData = objectMapper.createObjectNode();
        ObjectNode requiredEnvironment = objectMapper.createObjectNode();

        if ("interface".equals(point.caseKind())) {
            String[] parts = targetKey.split(" ", 2);
            requiredEnvironment.put("method", parts.length > 0 ? parts[0] : "POST");
            requiredEnvironment.put("endpoint", parts.length > 1 ? parts[1] : targetKey);
            requiredData.put("requestData", "按接口契约准备有效请求体和边界数据");
            assertions.removeAll().add("状态码和响应体符合预期");
        } else {
            requiredData.put("page", targetKey);
            requiredData.set("locatorNeeds", objectMapper.valueToTree(List.of(targetKey)));
            requiredEnvironment.put("browser", "chromium");
            requiredEnvironment.set("waits", objectMapper.valueToTree(List.of("networkidle", "visible")));
        }

        return new SuggestionPayload(
            steps.isEmpty() ? "partial" : "ready",
            requiredData,
            requiredEnvironment,
            steps,
            assertions,
            risks
        );
    }

    private AiAutomationSuggestionResponse mapResponse(ResultSet rs) throws SQLException {
        return new AiAutomationSuggestionResponse(
            rs.getString("suggestion_id"),
            rs.getString("run_id"),
            rs.getString("draft_point_id"),
            rs.getString("case_kind"),
            rs.getString("automation_readiness"),
            parse(rs.getString("required_data_json")),
            parse(rs.getString("required_environment_json")),
            parse(rs.getString("suggested_steps_json")),
            parse(rs.getString("suggested_assertions_json")),
            parse(rs.getString("risks_json")),
            rs.getTimestamp("created_at").toInstant().toString()
        );
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid automation suggestion JSON", exception);
        }
    }

    private String stringify(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node == null ? objectMapper.createObjectNode() : node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid automation suggestion payload", exception);
        }
    }

    private String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

record DraftPoint(
    String pointId,
    String caseKind,
    String pageOrFeature,
    String suggestedSteps,
    String expectedResult,
    JsonNode metadata
) {
}

record SuggestionPayload(
    String automationReadiness,
    JsonNode requiredData,
    JsonNode requiredEnvironment,
    JsonNode suggestedSteps,
    JsonNode suggestedAssertions,
    JsonNode risks
) {
}

record AiAutomationSuggestionListResponse(
    List<AiAutomationSuggestionResponse> items
) {
}

record AiAutomationSuggestionResponse(
    String suggestionId,
    String runId,
    String draftPointId,
    String caseKind,
    String automationReadiness,
    JsonNode requiredData,
    JsonNode requiredEnvironment,
    JsonNode suggestedSteps,
    JsonNode suggestedAssertions,
    JsonNode risks,
    String createdAt
) {
}
