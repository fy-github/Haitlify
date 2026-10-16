package com.haitlify.aitest.platform.ai.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.haitlify.aitest.platform.ai.gateway.AiGateway;
import com.haitlify.aitest.platform.ai.gateway.AiGatewayConfig;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.ai.runtime.AiProtocolResponse;
import com.haitlify.aitest.platform.ai.runtime.AiRuntimeInvocationService.AiRuntimeInvocationResult;
import com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactAcceptanceRequest;
import com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactAcceptanceResult;
import com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactAcceptanceService;
import com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactAcceptanceState;
import com.haitlify.aitest.platform.ai.runtime.artifact.AiArtifactOrigin;
import tools.jackson.databind.ObjectMapper;

/**
 * T-Review-3 acceptance: THE SAME negative fixture must produce THE SAME state semantics at every
 * artifact entry point. Negative fixture: a success-looking model/tool result without auditable
 * provider evidence (blank provider request id / placeholder output).
 *
 * Required semantics at every entry: the artifact must be BLOCKED or carry a non-model origin; it
 * must NEVER be recorded as MODEL_GENERATED / TOOL_OBSERVED, and never ACCEPTED without human action.
 *
 * The worker entry lives in this package because {@link AiAnalysisWorker} is package-private; the
 * shared gate and the multi-agent endpoint are exercised through their public seams.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiArtifactAcceptanceCrossEntryTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AiAnalysisWorker analysisWorker;

    @Autowired
    private AiArtifactAcceptanceService acceptanceService;

    @MockitoBean
    private AiGateway aiGateway;

    private long projectId;
    private long runPk;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("delete from ai_analysis_worker_leases");
        jdbcTemplate.update("delete from ai_artifact_gate_audits");
        jdbcTemplate.update("delete from ai_run_events");
        jdbcTemplate.update("delete from ai_run_policies");
        jdbcTemplate.update("delete from ai_unclear_questions");
        jdbcTemplate.update("delete from ai_coverage_summaries");
        jdbcTemplate.update("delete from ai_analysis_chunks");
        jdbcTemplate.update("delete from ai_call_records");
        jdbcTemplate.update("delete from ai_review_actions");
        jdbcTemplate.update("delete from ai_draft_test_points");
        jdbcTemplate.update("delete from ai_web_case_agent_artifacts");
        jdbcTemplate.update("delete from ai_web_case_agent_runs");
        jdbcTemplate.update("delete from ai_agent_consensus_records");
        jdbcTemplate.update("delete from ai_agent_conflicts");
        jdbcTemplate.update("delete from ai_agent_critiques");
        jdbcTemplate.update("delete from ai_agent_handoffs");
        jdbcTemplate.update("delete from ai_agent_run_nodes");
        jdbcTemplate.update("delete from ai_agent_run_graphs");
        jdbcTemplate.update("delete from ai_analysis_runs");
        jdbcTemplate.update("delete from ai_source_chunks");
        jdbcTemplate.update("delete from ai_source_documents");
        jdbcTemplate.update("delete from ai_system_configs");
        jdbcTemplate.update("delete from ai_rules");
        jdbcTemplate.update("delete from projects where code in ('X_ENTRY_NEG', 'X_ENTRY_NEG_AGENT')");

        Instant now = Instant.parse("2026-07-03T10:00:00Z");
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "X_ENTRY_NEG", "Cross Entry Negative", "T-Review-3 cross entry negative fixture", true, now, now);
        projectId = jdbcTemplate.queryForObject("select id from projects where code = 'X_ENTRY_NEG'", Long.class);
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "X_ENTRY_NEG_AGENT", "Cross Entry Negative Agent", "T-Review-3 cross entry negative fixture", true, now, now);
        jdbcTemplate.update("""
            insert into ai_system_configs(
              config_key, enabled, provider_type, base_url, text_model, vision_model, embedding_model,
              api_key_ciphertext, credential_configured, request_timeout_ms, max_input_length, max_output_length,
              retry_count, request_logging_enabled, external_document_send_allowed, provider_config_json,
              masked_config_json, actor_id, actor_name, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "default", true, "openai-compatible", "https://api.example.com/v1", "gpt-test", "", "",
            "sk-test", true, 30000, 0, 0, 0, false, true, null,
            "****test", "system-dev", "Development User", now, now);
        insertQueuedRun(now);
        runPk = jdbcTemplate.queryForObject("select id from ai_analysis_runs where run_id = 'x-entry-run'", Long.class);
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, "{\"draftTestPoints\":[]}", "", 10, 5, null));
    }

    /**
     * The negative fixture: the provider answered "success" but returned no auditable request id,
     * so the platform must treat the result as unverifiable at every entry.
     */
    @Test
    void sameNegativeFixtureFailsClosedAtEveryEntryPoint() throws Exception {
        // Entry 1: shared acceptance gate — model claim with blank provider request id.
        AiRuntimeInvocationResult unverifiableRuntime = new AiRuntimeInvocationResult(
            "MODEL", "MODEL_GENERATED", "PENDING_HUMAN_CONFIRMATION",
            objectMapper.readTree("{\"draftTestPoints\":[]}"), "", new AiProtocolResponse.Usage(1, 1), null, null, null);
        AiArtifactAcceptanceResult gateResult = acceptanceService.accept(new AiArtifactAcceptanceRequest(
            AiArtifactOrigin.MODEL_GENERATED, AiArtifactAcceptanceState.PENDING_HUMAN_CONFIRMATION,
            unverifiableRuntime.output(), projectId, projectId, List.of(), List.of(), null, 1, "x-entry-idem", unverifiableRuntime));
        assertFalse(gateResult.accepted(), "gate must fail closed without provider evidence");
        assertEquals(AiArtifactAcceptanceState.BLOCKED, gateResult.acceptanceState());
        assertTrue(gateResult.errors().contains("provider_request_id_missing"));

        // Entry 2: requirement analysis worker (draft merge path) — same fixture through the real worker.
        jdbcTemplate.update("""
            insert into ai_analysis_chunks(
              planned_chunk_id, run_id, source_chunk_ids_json, section_heading, token_estimate,
              status, skip_reason, content_text, evidence_metadata_json, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "x-entry-planned-1", runPk, "[]", "登录", 20, "planned", "", "用户输入正确账号密码后进入工作台", "{}",
            Instant.now(), Instant.now());
        jdbcTemplate.update("update ai_analysis_runs set total_chunks = 1 where id = ?", runPk);
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {"draftTestPoints":[{"title":"登录成功","module":"登录","expectedResult":"进入工作台","sourceChunkId":"x-entry-planned-1"}]}
                """, "", 10, 5, null));
        var workerResult = analysisWorker.processNextQueuedRun();
        assertTrue(workerResult.processed());

        // Entry 3: multi-agent consensus merge — same fixture through the real endpoint.
        long agentProjectId = jdbcTemplate.queryForObject("select id from projects where code = 'X_ENTRY_NEG_AGENT'", Long.class);
        String agentRunId = objectMapper.readTree(mockMvc.perform(post("/api/ai/multi-agent-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    { "projectId": %d, "targetCaseKind": "web_automation", "inputSummary": { "source": "x-entry" } }
                    """.formatted(agentProjectId)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("runId").asText();
        mockMvc.perform(post("/api/ai/multi-agent-runs/{runId}/merge", agentRunId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "candidates": [
                        {
                          "title": "补充登录失败场景",
                          "targetKey": "login-failure",
                          "caseKind": "web_automation",
                          "priority": "P2",
                          "expectedResult": "展示失败提示",
                          "sourceEvidence": ["review:1"]
                        }
                      ]
                    }
                    """))
            .andExpect(status().isOk());

        // Shared invariant: no entry recorded the unverifiable fixture as model/tool achieved.
        assertEquals(0, jdbcTemplate.queryForObject(
            "select count(*) from ai_draft_test_points where result_origin in ('MODEL_GENERATED', 'TOOL_OBSERVED')",
            Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
            "select count(*) from ai_draft_test_points where acceptance_state in ('ACCEPTED', 'EXECUTED')",
            Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
            "select count(*) from ai_draft_test_points where field_origins_json like '%MODEL_PROPOSED%'",
            Integer.class));
        // Every artifact from the negative fixture is either BLOCKED or rule-origin, never model-claimed.
        assertEquals(2, jdbcTemplate.queryForObject(
            "select count(*) from ai_draft_test_points where result_origin in ('BLOCKED', 'RULE_EXTRACTED')",
            Integer.class));
        assertTrue(jdbcTemplate.queryForObject(
            "select count(*) from ai_artifact_gate_audits where decision = 'BLOCKED'", Integer.class) >= 1);
    }

    /**
     * Web artifact entry: the same expectation — nothing is deliverable/exportable until a human
     * confirmation transition recorded an ACCEPTED gate decision.
     */
    @Test
    void webArtifactsAreNotExportableBeforeHumanConfirmation() throws Exception {
        Instant now = Instant.now();
        jdbcTemplate.update("""
            insert into ai_web_case_agent_runs(
              run_id, project_id, analysis_run_id, status, current_stage, requested_point_ids_json,
              completed_point_ids_json, blocked_point_ids_json, rules_snapshot_json, input_summary_json,
              output_summary_json, last_heartbeat_at, cancel_requested, error_summary, mode, plan_run_id,
              created_at, updated_at, started_at, finished_at
            ) values (?, ?, '', 'waiting_review', 'delivery', '["point-1"]', '[]', '[]', '{}', '{}', '{}', ?, false, '', 'PLAN', '', ?, ?, ?, null)
            """,
            "x-entry-plan-run", projectId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        for (String artifactId : List.of("x-entry-artifact-pending", "x-entry-artifact-second")) {
            jdbcTemplate.update("""
                insert into ai_web_case_agent_artifacts(
                  artifact_id, run_id, point_id, artifact_type, candidate_json, evidence_ids_json,
                  validation_errors_json, accepted, repair_round, revision, parent_artifact_id,
                  idempotency_key, result_origin, acceptance_state, created_at, updated_at
                ) values (?, ?, ?, 'natural_web_case_plan', ?, '[]', '[]', false, 0, 1, '', ?, 'MODEL_GENERATED', 'PENDING_HUMAN_CONFIRMATION', ?, ?)
                """,
                artifactId, "x-entry-plan-run", "point-1",
                "{\"sourcePointId\":\"point-1\",\"title\":\"列表\",\"module\":\"用户\",\"pageName\":\"用户列表\",\"startUrl\":\"/users\",\"naturalSteps\":[\"打开列表\"],\"naturalExpectedResult\":\"列表可见\",\"objective\":\"验证列表\",\"revision\":1,\"idempotencyKey\":\"" + artifactId + "\"}",
                artifactId, Timestamp.from(now), Timestamp.from(now));
        }

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/ai/web-case-agent-runs/{runId}/artifacts/export", "x-entry-plan-run"))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(0));

        // 人工确认必须带认证主体：Gate 审计要记录真实操作人（此前这里是无主体调用，掩盖了 actor 被写成固定值的问题）。
        var humanSession = new MockHttpSession();
        var humanContext = SecurityContextHolder.createEmptyContext();
        humanContext.setAuthentication(new UsernamePasswordAuthenticationToken("fyli", "n/a", List.of()));
        humanSession.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, humanContext);

        mockMvc.perform(post("/api/ai/web-case-agent-runs/{runId}/artifacts/{artifactId}/decision",
                "x-entry-plan-run", "x-entry-artifact-pending")
                .session(humanSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accepted\": true}"))
            .andExpect(status().isOk());

        String exported = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/ai/web-case-agent-runs/{runId}/artifacts/export", "x-entry-plan-run"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertEquals(1, objectMapper.readTree(exported).size(), "only the human-confirmed artifact is exportable");
        assertEquals("x-entry-artifact-pending", objectMapper.readTree(exported).path(0).path("artifactId").asText());
        assertEquals("HUMAN_EDITED", objectMapper.readTree(exported).path(0).path("resultOrigin").asText());
        assertEquals("ACCEPTED", objectMapper.readTree(exported).path(0).path("acceptanceState").asText());
        assertTrue(jdbcTemplate.queryForObject(
            "select count(*) from ai_artifact_gate_audits where artifact_id = 'x-entry-artifact-pending' and decision = 'ACCEPTED'",
            Integer.class) >= 1);
        assertEquals("fyli", jdbcTemplate.queryForObject(
            "select actor_id from ai_artifact_gate_audits where artifact_id = 'x-entry-artifact-pending' and decision = 'ACCEPTED'",
            String.class), "Gate 审计必须记录真实操作人");
        assertEquals("PENDING_HUMAN_CONFIRMATION", jdbcTemplate.queryForObject(
            "select acceptance_state from ai_web_case_agent_artifacts where artifact_id = 'x-entry-artifact-second'", String.class));
        // Artifact-level origin vocabulary has no "proposed" state: a row is either backed by runtime
        // evidence, rule-derived, tool-observed, human-edited, or BLOCKED. "MODEL_PROPOSED" is a
        // field-level label only and must never appear as an artifact origin.
        assertEquals(0, jdbcTemplate.queryForObject(
            "select count(*) from ai_web_case_agent_artifacts where result_origin = 'MODEL_PROPOSED'", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
            "select count(*) from ai_web_case_agent_artifacts where accepted = true and acceptance_state <> 'ACCEPTED'", Integer.class));
    }

    private void insertQueuedRun(Instant now) {
        jdbcTemplate.update("""
            insert into ai_analysis_runs(
              run_id, project_id, target_case_kind, template_id, analysis_strategy, execution_mode,
              status, worker_status, coverage_status, total_chunks, completed_chunks, current_chunk_id,
              resume_from_chunk_id, error_summary, config_snapshot_json, rule_snapshot_json,
              skill_snapshot_json, schema_snapshot_json, input_summary_json, output_summary_json,
              actor_id, actor_name, started_at, finished_at, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "x-entry-run", projectId, "interface", "", "balanced", "multi_pass",
            "queued", "idle", "planned", 0, 0, "", "", "",
            "{}", "{}", "{}", "{}", "{\"documentIds\":[]}", null,
            "system-dev", "Development User", null, null, now, now);
    }
}
