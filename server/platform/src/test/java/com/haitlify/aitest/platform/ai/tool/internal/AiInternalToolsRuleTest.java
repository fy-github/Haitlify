package com.haitlify.aitest.platform.ai.tool.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import tools.jackson.databind.ObjectMapper;

/**
 * T09 bullet 4：两个业务工具读取真实模板/映射并以 RULE_COMPUTED 标注——
 * 确定性算法、不命中不编造、空数据即全缺口。
 */
class AiInternalToolsRuleTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MatchCaseTemplateTool matchTool = new MatchCaseTemplateTool(jdbc, objectMapper);
    private final CheckCoverageGapsTool gapsTool = new CheckCoverageGapsTool(jdbc, objectMapper);

    private ResultSet templateRow(String kind, int schemaVersion, String caseTypes, String fields) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("kind")).thenReturn(kind);
        when(rs.getInt("schema_version")).thenReturn(schemaVersion);
        when(rs.getString("case_types_json")).thenReturn(caseTypes);
        when(rs.getString("interface_fields_json")).thenReturn(fields);
        return rs;
    }

    private ResultSet pointRow(String pointId, String title, String caseKind, String testType) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("point_id")).thenReturn(pointId);
        when(rs.getString("title")).thenReturn(title);
        when(rs.getString("case_kind")).thenReturn(caseKind);
        when(rs.getString("test_type")).thenReturn(testType);
        return rs;
    }

    @SuppressWarnings("unchecked")
    private void stubTemplateRows(ResultSet... rows) throws Exception {
        when(jdbc.query(contains("case_template_configs"), any(RowMapper.class), eq(42L)))
            .thenAnswer(invocation -> {
                RowMapper<Object> mapper = (RowMapper<Object>) invocation.getArgument(1, RowMapper.class);
                List<Object> mapped = new java.util.ArrayList<>();
                for (int index = 0; index < rows.length; index++) {
                    mapped.add(mapper.mapRow(rows[index], index));
                }
                return mapped;
            });
    }

    @SuppressWarnings("unchecked")
    private void stubPointRows(boolean runScoped, String expectedRunId, ResultSet... rows) throws Exception {
        if (runScoped) {
            when(jdbc.query(contains("ai_draft_test_points"), any(RowMapper.class), eq(42L), eq(expectedRunId)))
                .thenAnswer(answer(rows));
        } else {
            when(jdbc.query(contains("ai_draft_test_points"), any(RowMapper.class), eq(42L)))
                .thenAnswer(answer(rows));
        }
    }

    private static org.mockito.stubbing.Answer<List<Object>> answer(ResultSet[] rows) {
        return invocation -> {
            RowMapper<Object> mapper = invocation.getArgument(1, RowMapper.class);
            List<Object> mapped = new java.util.ArrayList<>();
            for (int index = 0; index < rows.length; index++) {
                mapped.add(mapper.mapRow(rows[index], index));
            }
            return mapped;
        };
    }

    @Test
    void matchCaseTemplateReadsRealTemplatesAndRanksByOverlap() throws Exception {
        stubTemplateRows(
            templateRow("auth-login", 2, "[\"正向\",\"异常\"]", "[\"username\",\"password\"]"),
            templateRow("device-limit", 1, "[\"边界\"]", "[\"maxDevices\"]"));

        var result = matchTool.dryRun(objectMapper.createObjectNode()
            .put("text", "登录正向和异常场景需要覆盖")
            .put("projectId", 42));

        assertThat(result.path("resultOrigin").asText()).isEqualTo("RULE_COMPUTED");
        assertThat(result.path("algorithm").asText()).isEqualTo("keyword-overlap-v1");
        assertThat(result.path("matches")).isNotEmpty();
        assertThat(result.path("matches").get(0).path("kind").asText()).isEqualTo("auth-login");
        assertThat(result.path("matches").get(0).path("schemaVersion").asInt()).isEqualTo(2);
        // 无重叠的模板不得被编造进结果。
        for (var match : result.path("matches")) {
            assertThat(match.path("kind").asText()).isEqualTo("auth-login");
        }
    }

    @Test
    void matchCaseTemplateWithNoOverlapReturnsEmptyHonestly() throws Exception {
        stubTemplateRows(templateRow("device-limit", 1, "[\"边界\"]", "[\"maxDevices\"]"));

        var result = matchTool.dryRun(objectMapper.createObjectNode()
            .put("text", "完全无关的内容主题")
            .put("projectId", 42));

        assertThat(result.path("matches")).isEmpty();
        assertThat(result.path("resultOrigin").asText()).isEqualTo("RULE_COMPUTED");
    }

    @Test
    void matchCaseTemplateRequiresProjectId() {
        assertThatThrownBy(() -> matchTool.dryRun(objectMapper.createObjectNode().put("text", "x")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("projectId");
    }

    @Test
    void checkCoverageGapsReadsDraftPointsAndListsMissingDimensions() throws Exception {
        stubPointRows(false, null,
            pointRow("p-1", "登录成功正向验证", "functional", "正向"),
            pointRow("p-2", "错误密码提示异常", "functional", "异常"),
            pointRow("p-3", "token 状态切换校验", "state", "状态"));

        var result = gapsTool.dryRun(objectMapper.createObjectNode().put("projectId", 42));

        assertThat(result.path("resultOrigin").asText()).isEqualTo("RULE_COMPUTED");
        assertThat(result.path("algorithm").asText()).isEqualTo("coverage-dimensions-v1");
        assertThat(result.path("totalPoints").asInt()).isEqualTo(3);
        assertThat(result.path("gaps")).extracting(node -> node.asText())
            .contains("boundary", "permission", "compatibility")
            .doesNotContain("positive", "negative", "state");
        assertThat(result.path("unclassifiedPointCount").asInt()).isZero();
    }

    @Test
    void emptyProjectYieldsEveryDimensionAsGap() throws Exception {
        stubPointRows(false, null);

        var result = gapsTool.dryRun(objectMapper.createObjectNode().put("projectId", 42));

        assertThat(result.path("totalPoints").asInt()).isZero();
        assertThat(result.path("gaps")).hasSize(6);
    }

    @Test
    void runScopedCoverageUsesTheRunJoin() throws Exception {
        stubPointRows(true, "run-9",
            pointRow("p-9", "权限越权拒绝", "functional", "权限"));

        var result = gapsTool.dryRun(objectMapper.createObjectNode().put("projectId", 42).put("runId", "run-9"));

        assertThat(result.path("runScoped").asBoolean()).isTrue();
        assertThat(result.path("gaps")).extracting(node -> node.asText())
            .doesNotContain("permission");
    }
}
