package com.haitlify.aitest.platform.ai.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.haitlify.aitest.platform.support.TestDataReset;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiQualityScoreServiceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private long projectId;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("delete from ai_quality_scores");
        jdbcTemplate.update("delete from ai_review_actions");
        jdbcTemplate.update("delete from ai_draft_test_points");
        jdbcTemplate.update("delete from ai_call_records");
        jdbcTemplate.update("delete from ai_agent_consensus_records");
        jdbcTemplate.update("delete from ai_agent_conflicts");
        jdbcTemplate.update("delete from ai_agent_critiques");
        jdbcTemplate.update("delete from ai_agent_handoffs");
        jdbcTemplate.update("delete from ai_agent_run_nodes");
        jdbcTemplate.update("delete from ai_agent_run_graphs");
        jdbcTemplate.update("delete from ai_analysis_chunks");
        TestDataReset.clearAiAnalysisRuns(jdbcTemplate);
        jdbcTemplate.update("delete from projects where code = 'AI_QUALITY'");

        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_QUALITY",
            "AI Quality Project",
            "Quality tests",
            true,
            now,
            now
        );
        projectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_QUALITY'", Long.class);
    }

    @Test
    void missingSourceEvidenceLowersTraceabilityScore() throws Exception {
        String runId = createRun();
        merge(runId, """
            [
              {
                "title": "新增项目",
                "targetKey": "project-create",
                "caseKind": "interface",
                "priority": "P1",
                "expectedResult": "创建成功",
                "sourceEvidence": []
              }
            ]
            """);

        mockMvc.perform(get("/api/ai/multi-agent-runs/{runId}/quality-scores", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[?(@.qualityDimension=='source_traceability')].score").value(org.hamcrest.Matchers.contains(0.4)))
            .andExpect(jsonPath("$.items[?(@.qualityDimension=='source_traceability')].scoreLevel").value(org.hamcrest.Matchers.contains("warning")));
    }

    @Test
    void duplicateTargetsLowerDuplicateRiskScore() throws Exception {
        String runId = createRun();
        merge(runId, """
            [
              {
                "title": "登录成功",
                "targetKey": "login-submit",
                "caseKind": "web_automation",
                "priority": "P1",
                "expectedResult": "进入首页",
                "sourceEvidence": ["doc:1"]
              },
              {
                "title": "登录成功",
                "targetKey": "login-submit",
                "caseKind": "web_automation",
                "priority": "P1",
                "expectedResult": "进入首页",
                "sourceEvidence": ["doc:1"]
              }
            ]
            """);

        mockMvc.perform(get("/api/ai/multi-agent-runs/{runId}/quality-scores", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[?(@.qualityDimension=='duplicate_risk')].score").value(org.hamcrest.Matchers.contains(0.6)));
    }

    @Test
    void missingExpectedResultLowersTestabilityScoreAndStructuredStepsImproveAutomationReadiness() throws Exception {
        String runId = createRun();
        merge(runId, """
            [
              {
                "title": "列表筛选",
                "targetKey": "project-filter",
                "caseKind": "web_automation",
                "priority": "P2",
                "expectedResult": "",
                "sourceEvidence": ["doc:2"],
                "suggestedSteps": ["打开项目列表", "输入筛选条件", "点击查询"]
              }
            ]
            """);

        mockMvc.perform(get("/api/ai/multi-agent-runs/{runId}/quality-scores", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[?(@.qualityDimension=='testability')].score").value(org.hamcrest.Matchers.contains(0.5)))
            .andExpect(jsonPath("$.items[?(@.qualityDimension=='automation_readiness')].score").value(org.hamcrest.Matchers.contains(0.9)));
    }

    @Test
    void qualityScoresPersistArtifactLevelRuleOriginAndPendingAcceptance() throws Exception {
        String runId = createRun();
        merge(runId, """
            [
              {
                "title": "列表筛选",
                "targetKey": "project-filter",
                "caseKind": "web_automation",
                "priority": "P2",
                "expectedResult": "筛选生效",
                "sourceEvidence": ["doc:2"]
              }
            ]
            """);

        mockMvc.perform(get("/api/ai/multi-agent-runs/{runId}/quality-scores", runId)).andExpect(status().isOk());

        // RULE_COMPUTED is a field-level label; the artifact-level origin of a rule-derived score is
        // RULE_EXTRACTED, and a computed score is pending human consideration, not accepted.
        assertEquals(6, jdbcTemplate.queryForObject(
            "select count(*) from ai_quality_scores where run_id = ? and result_origin = 'RULE_EXTRACTED' "
                + "and acceptance_state = 'PENDING_HUMAN_CONFIRMATION'", Integer.class, runId));
        assertEquals(0, jdbcTemplate.queryForObject(
            "select count(*) from ai_quality_scores where result_origin = 'RULE_COMPUTED' "
                + "or acceptance_state not in ('PENDING_HUMAN_CONFIRMATION', 'ACCEPTED', 'EXECUTED', 'BLOCKED', 'DEGRADED')",
            Integer.class));
    }

    private String createRun() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/ai/multi-agent-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "projectId": %d,
                      "targetCaseKind": "web_automation",
                      "inputSummary": { "source": "prd-quality" }
                    }
                    """.formatted(projectId)))
            .andExpect(status().isOk())
            .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).get("runId").asText();
    }

    private void merge(String runId, String candidatesJson) throws Exception {
        mockMvc.perform(post("/api/ai/multi-agent-runs/{runId}/merge", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    { "candidates": %s }
                    """.formatted(candidatesJson)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("merged"));
    }
}
