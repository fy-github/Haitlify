package com.haitlify.aitest.platform.ai.quota;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiQuotaServiceTest {

    @Autowired
    private AiQuotaService quotaService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long otherProjectId;
    private long runPk;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("delete from ai_unclear_questions");
        jdbcTemplate.update("delete from ai_coverage_summaries");
        jdbcTemplate.update("delete from ai_analysis_chunks");
        jdbcTemplate.update("delete from ai_call_records");
        jdbcTemplate.update("delete from ai_review_actions");
        jdbcTemplate.update("delete from ai_draft_test_points");
        jdbcTemplate.update("delete from ai_analysis_runs");
        jdbcTemplate.update("delete from projects where code in ('AI_QUOTA', 'AI_QUOTA_OTHER')");

        Instant now = Instant.parse("2026-07-03T10:00:00Z");
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_QUOTA",
            "AI Quota",
            "AI quota tests",
            true,
            now,
            now
        );
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_QUOTA_OTHER",
            "AI Quota Other",
            "AI quota tests",
            true,
            now,
            now
        );
        projectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_QUOTA'", Long.class);
        otherProjectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_QUOTA_OTHER'", Long.class);
        insertRun(now);
        runPk = jdbcTemplate.queryForObject("select id from ai_analysis_runs where run_id = 'run-quota'", Long.class);
        insertCall("call-in-window-1", projectId, runPk, 100, 40, "0.0123", now.minusSeconds(60));
        insertCall("call-in-window-2", projectId, runPk, 30, 20, "0.0040", now);
        insertCall("call-outside-window", projectId, runPk, 900, 900, "9.0000", now.minusSeconds(86_400 * 3));
        insertCall("call-other-project", otherProjectId, null, 500, 500, "5.0000", now);
    }

    @Test
    void aggregatesProjectTokenUsageWithinTimeWindow() {
        AiQuotaUsageResponse usage = quotaService.getUsage(
            projectId,
            Instant.parse("2026-07-03T09:00:00Z"),
            Instant.parse("2026-07-03T11:00:00Z")
        );

        assertEquals(projectId, usage.projectId());
        assertEquals(2, usage.callCount());
        assertEquals(130, usage.inputTokens());
        assertEquals(60, usage.outputTokens());
        assertEquals(190, usage.totalTokens());
        assertEquals("0.0163", usage.estimatedCost());
    }

    @Test
    void exposesQuotaUsageEndpoint() throws Exception {
        mockMvc.perform(get("/api/projects/{projectId}/ai-quota-usage", projectId)
                .param("from", "2026-07-03T09:00:00Z")
                .param("to", "2026-07-03T11:00:00Z"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.projectId").value(projectId))
            .andExpect(jsonPath("$.callCount").value(2))
            .andExpect(jsonPath("$.inputTokens").value(130))
            .andExpect(jsonPath("$.outputTokens").value(60))
            .andExpect(jsonPath("$.totalTokens").value(190))
            .andExpect(jsonPath("$.estimatedCost").value("0.0163"));
    }

    private void insertRun(Instant now) {
        jdbcTemplate.update("""
            insert into ai_analysis_runs(
              run_id, project_id, target_case_kind, template_id, analysis_strategy, execution_mode,
              status, worker_status, coverage_status, total_chunks, completed_chunks, current_chunk_id,
              resume_from_chunk_id, error_summary, config_snapshot_json, rule_snapshot_json,
              skill_snapshot_json, schema_snapshot_json, input_summary_json, output_summary_json,
              actor_id, actor_name, started_at, finished_at, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "run-quota", projectId, "interface", "", "balanced", "multi_pass",
            "succeeded", "idle", "completed", 1, 1, "", "", "",
            "{}", "{}", "{}", "{}", "{}", null,
            "system-dev", "Development User", now, now, now, now
        );
    }

    private void insertCall(String recordId, long callProjectId, Long callRunPk, int inputTokens, int outputTokens, String cost, Instant createdAt) {
        jdbcTemplate.update("""
            insert into ai_call_records(
              record_id, run_id, project_id, provider_type, model_name, config_snapshot_id, agent_id,
              skill_ids_json, rule_version, modality, request_id, status, error_code, error_message,
              duration_ms, token_usage_input, token_usage_output, estimated_cost, input_summary_json,
              output_summary_json, raw_payload_json, actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            recordId, callRunPk, callProjectId, "openai-compatible", "gpt-test", "default", "requirement-test-point-analysis",
            "[\"requirement-test-point-analysis\"]", "1", "text", "req-" + recordId, "succeeded", "", "",
            50, inputTokens, outputTokens, cost, "{}", "{}", "{}", "system-dev", "Development User", createdAt, createdAt
        );
    }
}
