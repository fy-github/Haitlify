package com.haitlify.aitest.platform.ai.tool.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * T09 bullet 4：读取真实 case 模板（case_template_configs），按需求文本做确定性
 * 关键词重叠匹配（resultOrigin=RULE_COMPUTED）。不命中就如实返回空——绝不编造模板。
 */
@Component
class MatchCaseTemplateTool extends StaticInternalAiTool {

    private static final int MAX_MATCHES = 5;

    private final JdbcTemplate jdbcTemplate;

    MatchCaseTemplateTool(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        super(objectMapper, "match-case-template", "Match Case Template",
            "Matches requirement text to configured case templates of a project (rule-based).");
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("text").put("type", "string");
        properties.putObject("projectId").put("type", "integer");
        schema.putArray("required").add("text").add("projectId");
        return schema;
    }

    @Override
    public JsonNode outputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("matches").put("type", "array");
        properties.putObject("resultOrigin").put("type", "string");
        properties.putObject("algorithm").put("type", "string");
        schema.putArray("required").add("matches").add("resultOrigin");
        return schema;
    }

    @Override
    public JsonNode dryRun(JsonNode input) {
        String text = inputText(input);
        long projectId = requireProjectId(input);
        var terms = InternalToolText.terms(text);

        List<Match> matches = new ArrayList<>();
        jdbcTemplate.query("""
            select kind, schema_version, case_types_json, interface_fields_json
            from case_template_configs
            where project_id = ?
            order by kind asc
            """,
            (rs, rowNum) -> new TemplateRow(
                rs.getString("kind"),
                rs.getInt("schema_version"),
                rs.getString("case_types_json"),
                rs.getString("interface_fields_json")),
            projectId)
            .forEach(row -> {
                int score = InternalToolText.overlapScore(row.kind(), terms) * 3
                    + InternalToolText.overlapScore(row.caseTypesJson(), terms)
                    + InternalToolText.overlapScore(row.interfaceFieldsJson(), terms);
                if (score > 0) {
                    matches.add(new Match(row.kind(), row.schemaVersion(), parse(row.caseTypesJson()), score));
                }
            });
        matches.sort(Comparator.comparingInt(Match::score).reversed().thenComparing(Match::kind));

        ObjectNode response = objectMapper.createObjectNode();
        response.put("resultOrigin", "RULE_COMPUTED");
        response.put("algorithm", InternalToolText.MATCH_ALGORITHM);
        response.put("templateCount", matches.size());
        ArrayNode array = response.putArray("matches");
        for (Match match : matches.stream().limit(MAX_MATCHES).toList()) {
            ObjectNode item = array.addObject();
            item.put("kind", match.kind());
            item.put("schemaVersion", match.schemaVersion());
            item.put("score", match.score());
            item.set("caseTypes", match.caseTypes());
        }
        return response;
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createArrayNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception malformed) {
            return objectMapper.createArrayNode();
        }
    }

    private long requireProjectId(JsonNode input) {
        JsonNode projectId = input == null ? null : input.get("projectId");
        if (projectId == null || !projectId.isNumber()) {
            throw new IllegalArgumentException("Tool input projectId is required");
        }
        return projectId.asLong();
    }

    private record TemplateRow(String kind, int schemaVersion, String caseTypesJson, String interfaceFieldsJson) {}

    private record Match(String kind, int schemaVersion, JsonNode caseTypes, int score) {}
}
