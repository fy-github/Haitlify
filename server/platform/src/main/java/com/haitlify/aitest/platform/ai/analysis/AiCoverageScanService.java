package com.haitlify.aitest.platform.ai.analysis;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional
class AiCoverageScanService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    AiCoverageScanService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    AiCoverageScanResult scan(String runId) {
        Map<String, Object> run = jdbcTemplate.queryForMap("select id, project_id from ai_analysis_runs where run_id = ?", runId);
        Long runPk = ((Number) run.get("ID")).longValue();
        Long projectId = ((Number) run.get("PROJECT_ID")).longValue();

        List<Map<String, Object>> points = jdbcTemplate.queryForList(
            "select id, title, module_name, precondition_text, test_objective, suggested_steps, expected_result from ai_draft_test_points where run_id = ? and acceptance_state = 'ACCEPTED' and review_status in ('confirmed', 'modified') order by id",
            runPk
        );
        Map<String, Integer> moduleCounts = new LinkedHashMap<>();
        Map<String, Integer> operationCounts = new LinkedHashMap<>();
        for (Map<String, Object> point : points) {
            String module = blankToDefault((String) point.get("MODULE_NAME"), "未分类");
            moduleCounts.merge(module, 1, Integer::sum);
            extractOperation(point).forEach(operation -> operationCounts.merge(operation, 1, Integer::sum));
        }

        jdbcTemplate.update("delete from ai_coverage_summaries where run_id = ?", runPk);
        jdbcTemplate.update("delete from ai_unclear_questions where run_id = ?", runPk);
        for (Map<String, Object> point : points) {
            if (((String) point.get("PRECONDITION_TEXT")) == null || ((String) point.get("PRECONDITION_TEXT")).isBlank()) {
                insertUnclearQuestion(runPk, projectId, point);
            }
        }

        List<String> warnings = skippedChunkWarnings(runPk);
        ObjectNode summary = objectMapper.createObjectNode()
            .put("totalDraftPoints", points.size());
        summary.set("moduleCounts", objectMapper.valueToTree(moduleCounts));
        summary.set("operationCounts", objectMapper.valueToTree(operationCounts));
        insertCoverageSummary(runPk, summary, warnings);
        jdbcTemplate.update("update ai_analysis_runs set coverage_status = ?, updated_at = ? where id = ?",
            "completed",
            Timestamp.from(Instant.now()),
            runPk
        );
        return new AiCoverageScanResult(points.size(), moduleCounts, operationCounts, warnings);
    }

    private List<String> extractOperation(Map<String, Object> point) {
        String text = String.join(" ",
            blankToDefault((String) point.get("TITLE"), ""),
            blankToDefault((String) point.get("TEST_OBJECTIVE"), ""),
            blankToDefault((String) point.get("SUGGESTED_STEPS"), ""),
            blankToDefault((String) point.get("EXPECTED_RESULT"), "")
        );
        List<String> operations = new ArrayList<>();
        for (String candidate : List.of("保存项目", "删除项目", "新增项目", "编辑项目", "查询项目", "导出项目", "提交项目")) {
            if (text.contains(candidate)) {
                operations.add(candidate);
            }
        }
        return operations;
    }

    private void insertUnclearQuestion(Long runPk, Long projectId, Map<String, Object> point) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_unclear_questions(
              question_id, run_id, project_id, severity, question_text, source_ref, status, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "question-" + UUID.randomUUID(),
            runPk,
            projectId,
            "medium",
            "测试点缺少前置条件: " + blankToDefault((String) point.get("TITLE"), "未命名测试点"),
            String.valueOf(point.get("ID")),
            "open",
            Timestamp.from(now),
            Timestamp.from(now)
        );
    }

    private List<String> skippedChunkWarnings(Long runPk) {
        return jdbcTemplate.query(
            "select planned_chunk_id, skip_reason from ai_analysis_chunks where run_id = ? and status = 'skipped' order by id",
            (rs, rowNum) -> "Skipped chunk " + rs.getString("planned_chunk_id") + ": " + rs.getString("skip_reason"),
            runPk
        );
    }

    private void insertCoverageSummary(Long runPk, ObjectNode summary, List<String> warnings) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_coverage_summaries(run_id, summary_json, warnings_json, created_at, updated_at)
            values (?, ?, ?, ?, ?)
            """,
            runPk,
            stringify(summary),
            stringify(objectMapper.valueToTree(warnings)),
            Timestamp.from(now),
            Timestamp.from(now)
        );
    }

    private String stringify(tools.jackson.databind.JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid coverage summary JSON", exception);
        }
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

record AiCoverageScanResult(
    int totalDraftPoints,
    Map<String, Integer> moduleCounts,
    Map<String, Integer> operationCounts,
    List<String> warnings
) {
}
