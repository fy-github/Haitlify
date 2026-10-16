package com.haitlify.aitest.platform.ai.automation;

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

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiAutomationSuggestionServiceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private long projectId;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("delete from ai_automation_suggestions");
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
        jdbcTemplate.update("delete from ai_coverage_summaries");
        jdbcTemplate.update("delete from ai_unclear_questions");
        jdbcTemplate.update("delete from ai_analysis_runs");
        jdbcTemplate.update("delete from projects where code = 'AI_AUTOMATION_SUGGEST'");

        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_AUTOMATION_SUGGEST",
            "AI Automation Suggest Project",
            "Automation suggestion tests",
            true,
            now,
            now
        );
        projectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_AUTOMATION_SUGGEST'", Long.class);
    }

    @Test
    void webAndApiSuggestionsAttachToDraftPointsWithoutBypassingReview() throws Exception {
        String runId = createRunAndMerge();

        mockMvc.perform(get("/api/ai/multi-agent-runs/{runId}/automation-suggestions", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].draftPointId").isNotEmpty())
            .andExpect(jsonPath("$.items[?(@.caseKind=='web_automation')].requiredData.page").value(org.hamcrest.Matchers.contains("login-submit")))
            .andExpect(jsonPath("$.items[?(@.caseKind=='web_automation')].suggestedAssertions[0]").value(org.hamcrest.Matchers.contains("进入首页")))
            .andExpect(jsonPath("$.items[?(@.caseKind=='interface')].requiredEnvironment.method").value(org.hamcrest.Matchers.contains("POST")))
            .andExpect(jsonPath("$.items[?(@.caseKind=='interface')].suggestedAssertions[0]").value(org.hamcrest.Matchers.contains("状态码和响应体符合预期")));

        Integer reviewedCount = jdbcTemplate.queryForObject(
            "select count(*) from ai_draft_test_points where review_status <> 'ai_generated'",
            Integer.class
        );
        org.junit.jupiter.api.Assertions.assertEquals(0, reviewedCount);
    }

    private String createRunAndMerge() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/ai/multi-agent-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "projectId": %d,
                      "targetCaseKind": "web_automation",
                      "inputSummary": { "source": "prd-automation" }
                    }
                    """.formatted(projectId)))
            .andExpect(status().isOk())
            .andReturn();
        String runId = objectMapper.readTree(result.getResponse().getContentAsString()).get("runId").asText();

        mockMvc.perform(post("/api/ai/multi-agent-runs/{runId}/merge", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "candidates": [
                        {
                          "title": "登录成功",
                          "targetKey": "login-submit",
                          "caseKind": "web_automation",
                          "priority": "P1",
                          "expectedResult": "进入首页",
                          "sourceEvidence": ["doc:1"],
                          "suggestedSteps": ["打开登录页", "输入账号密码", "点击登录"]
                        },
                        {
                          "title": "创建项目接口",
                          "targetKey": "POST /api/projects",
                          "caseKind": "interface",
                          "priority": "P1",
                          "expectedResult": "状态码和响应体符合预期",
                          "sourceEvidence": ["doc:2"],
                          "suggestedSteps": ["准备请求体", "发送 POST 请求", "校验响应"]
                        }
                      ]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("merged"))
            .andExpect(jsonPath("$.aiEnrichmentFallback").value(true));

        return runId;
    }
}
