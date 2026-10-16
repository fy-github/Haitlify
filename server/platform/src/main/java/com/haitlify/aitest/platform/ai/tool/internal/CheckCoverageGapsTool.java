package com.haitlify.aitest.platform.ai.tool.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * T09 bullet 4：按 requirement→case 映射检查覆盖缺口——读取项目真实草稿测试点
 * （可选运行范围），对六类覆盖维度做确定性关键词判定（resultOrigin=RULE_COMPUTED）。
 * 无点即全缺口；绝不虚构已覆盖。
 */
@Component
class CheckCoverageGapsTool extends StaticInternalAiTool {

    private static final Map<String, String[]> DIMENSIONS = new LinkedHashMap<>();

    static {
        DIMENSIONS.put("positive", new String[] {"正向", "正常", "成功", "positive", "success"});
        DIMENSIONS.put("negative", new String[] {"异常", "错误", "失败", "拒绝", "非法", "negative", "error", "fail"});
        DIMENSIONS.put("boundary", new String[] {"边界", "极限", "最大", "最小", "范围", "boundary", "limit"});
        DIMENSIONS.put("state", new String[] {"状态", "切换", "并发", "时序", "重试", "state", "concurrency"});
        DIMENSIONS.put("permission", new String[] {"权限", "越权", "角色", "鉴权", "permission", "role", "auth"});
        DIMENSIONS.put("compatibility", new String[] {"兼容", "多端", "版本", "浏览器", "compatibility", "browser"});
    }

    private final JdbcTemplate jdbcTemplate;

    CheckCoverageGapsTool(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        super(objectMapper, "check-coverage-gaps", "Check Coverage Gaps",
            "Checks coverage dimensions of a project's draft test points against the requirement-to-case map (rule-based).");
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("projectId").put("type", "integer");
        properties.putObject("runId").put("type", "string");
        schema.putArray("required").add("projectId");
        return schema;
    }

    @Override
    public JsonNode outputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("dimensionCoverage").put("type", "array");
        properties.putObject("gaps").put("type", "array");
        properties.putObject("resultOrigin").put("type", "string");
        schema.putArray("required").add("dimensionCoverage").add("gaps").add("resultOrigin");
        return schema;
    }

    @Override
    public JsonNode dryRun(JsonNode input) {
        long projectId = requireProjectId(input);
        String runId = input.hasNonNull("runId") ? input.path("runId").asText("") : "";

        List<DraftPoint> points = runId.isBlank()
            ? jdbcTemplate.query("""
                select point_id, title, case_kind, test_type from ai_draft_test_points
                where project_id = ? order by id asc
                """,
                (rs, rowNum) -> new DraftPoint(rs.getString("point_id"), rs.getString("title"),
                    rs.getString("case_kind"), rs.getString("test_type")),
                projectId)
            : jdbcTemplate.query("""
                select p.point_id, p.title, p.case_kind, p.test_type from ai_draft_test_points p
                join ai_analysis_runs r on r.id = p.run_id
                where p.project_id = ? and r.run_id = ? order by p.id asc
                """,
                (rs, rowNum) -> new DraftPoint(rs.getString("point_id"), rs.getString("title"),
                    rs.getString("case_kind"), rs.getString("test_type")),
                projectId, runId);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("resultOrigin", "RULE_COMPUTED");
        response.put("algorithm", InternalToolText.COVERAGE_ALGORITHM);
        response.put("totalPoints", points.size());
        response.put("runScoped", !runId.isBlank());

        ArrayNode coverage = response.putArray("dimensionCoverage");
        ArrayNode gaps = response.putArray("gaps");
        List<String> unclassified = new ArrayList<>();
        for (Map.Entry<String, String[]> dimension : DIMENSIONS.entrySet()) {
            List<String> matchedTitles = new ArrayList<>();
            for (DraftPoint point : points) {
                String haystack = point.haystack();
                if (InternalToolText.mentionsAny(haystack, dimension.getValue())) {
                    if (matchedTitles.size() < 3) {
                        matchedTitles.add(point.title());
                    }
                }
            }
            ObjectNode item = coverage.addObject();
            item.put("dimension", dimension.getKey());
            item.put("covered", !matchedTitles.isEmpty());
            item.put("matchingPointCount", matchedTitles.isEmpty() ? 0 : matchedTitles.size());
            ArrayNode samples = item.putArray("sampleTitles");
            matchedTitles.forEach(samples::add);
            if (matchedTitles.isEmpty()) {
                gaps.add(dimension.getKey());
            }
        }
        for (DraftPoint point : points) {
            boolean classified = false;
            for (String[] keywords : DIMENSIONS.values()) {
                if (InternalToolText.mentionsAny(point.haystack(), keywords)) {
                    classified = true;
                    break;
                }
            }
            if (!classified) {
                unclassified.add(point.pointId());
            }
        }
        response.put("unclassifiedPointCount", unclassified.size());
        ArrayNode unclassifiedIds = response.putArray("unclassifiedPointIds");
        unclassified.forEach(unclassifiedIds::add);
        return response;
    }

    private long requireProjectId(JsonNode input) {
        JsonNode projectId = input == null ? null : input.get("projectId");
        if (projectId == null || !projectId.isNumber()) {
            throw new IllegalArgumentException("Tool input projectId is required");
        }
        return projectId.asLong();
    }

    private record DraftPoint(String pointId, String title, String caseKind, String testType) {
        String haystack() {
            return title + " " + caseKind + " " + testType;
        }
    }
}
