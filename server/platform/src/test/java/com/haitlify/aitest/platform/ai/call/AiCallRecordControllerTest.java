package com.haitlify.aitest.platform.ai.call;

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
class AiCallRecordControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
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
        jdbcTemplate.update("delete from projects where code = 'AI_CALL_RECORDS'");

        Instant now = Instant.parse("2026-07-03T10:00:00Z");
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_CALL_RECORDS",
            "AI Call Records",
            "AI call records tests",
            true,
            now,
            now
        );
        projectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_CALL_RECORDS'", Long.class);
        jdbcTemplate.update("""
            insert into ai_analysis_runs(
              run_id, project_id, target_case_kind, template_id, analysis_strategy, execution_mode,
              status, worker_status, coverage_status, total_chunks, completed_chunks, error_summary,
              config_snapshot_json, input_summary_json, output_summary_json, rule_snapshot_json, skill_snapshot_json,
              schema_snapshot_json, actor_id, actor_name, started_at, finished_at, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "run-call-records-1", projectId, "interface", "builtin-interface", "balanced", "inline",
            "succeeded", "idle", "covered", 1, 1, "",
            "{}", "{}", "{}", "{}", "{}", "{}",
            "system-dev", "Development User", now, now, now, now
        );
        runPk = jdbcTemplate.queryForObject("select id from ai_analysis_runs where run_id = 'run-call-records-1'", Long.class);

        insertCallRecord("call-old", "failed", "HTTP 500", 80, now.minusSeconds(60));
        insertCallRecord("call-new", "succeeded", "", 42, now);
    }

    @Test
    void listsCallRecordsNewestFirst() throws Exception {
        mockMvc.perform(get("/api/ai/call-records"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].recordId").value("call-new"))
            .andExpect(jsonPath("$.items[0].runId").value("run-call-records-1"))
            .andExpect(jsonPath("$.items[0].providerType").value("openai-compatible"))
            .andExpect(jsonPath("$.items[0].modelName").value("gpt-test"))
            .andExpect(jsonPath("$.items[0].status").value("succeeded"))
            .andExpect(jsonPath("$.items[0].durationMs").value(42))
            .andExpect(jsonPath("$.items[1].recordId").value("call-old"))
            .andExpect(jsonPath("$.items[1].errorMessage").value("HTTP 500"));
    }

    @Test
    void filtersCallRecordsByRunId() throws Exception {
        mockMvc.perform(get("/api/ai/call-records").param("runId", "run-call-records-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].runId").value("run-call-records-1"));

        mockMvc.perform(get("/api/ai/call-records").param("runId", "missing-run"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void returnsCallRecordDetailByRecordId() throws Exception {
        mockMvc.perform(get("/api/ai/call-records/{recordId}", "call-old"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recordId").value("call-old"))
            .andExpect(jsonPath("$.requestId").value("req-call-old"))
            .andExpect(jsonPath("$.tokenUsageInput").value(12))
            .andExpect(jsonPath("$.tokenUsageOutput").value(8))
            .andExpect(jsonPath("$.inputSummary.module").value("登录"))
            .andExpect(jsonPath("$.rawPayload").value("{\"draftTestPoints\":[]}"));
    }

    private void insertCallRecord(String recordId, String status, String errorMessage, long durationMs, Instant createdAt) {
        jdbcTemplate.update("""
            insert into ai_call_records(
              record_id, run_id, project_id, provider_type, model_name, config_snapshot_id, agent_id,
              skill_ids_json, rule_version, modality, request_id, status, error_code, error_message,
              duration_ms, token_usage_input, token_usage_output, estimated_cost, input_summary_json,
              output_summary_json, raw_payload_json, actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            recordId, runPk, projectId, "openai-compatible", "gpt-test", "default", "requirement-test-point-analysis",
            "[\"requirement-test-point-analysis\"]", "1", "text", "req-" + recordId, status, "", errorMessage,
            durationMs, 12, 8, 0, "{\"module\":\"登录\"}",
            "{\"draftTestPointCount\":1}", "{\"draftTestPoints\":[]}", "system-dev", "Development User", createdAt, createdAt
        );
    }
}
