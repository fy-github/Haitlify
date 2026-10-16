package com.haitlify.aitest.platform.ai.agentic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.haitlify.aitest.platform.ai.draft.AgentWebCaseGenerationFacade;

/**
 * Opt-in live gate for the Web PLAN path. Run only against the configured MySQL and authorized provider
 * profile: {@code -Dai.live.gate=true}.
 *
 * Why this gate exists: the plan artifact origin is derived from model evidence, so "plan artifacts are
 * MODEL_GENERATED" can only be proven by a real model call that returns a real provider request id —
 * mocks and fixtures cannot establish it. It must never be part of the deterministic suite.
 */
@Tag("live")
@SpringBootTest
class AiWebPlanLiveGateTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WebCaseAgentRunService runService;

    @Autowired
    private AgentWebCaseGenerationFacade facade;

    @Test
    void realPlanCallProducesModelGeneratedArtifactWithProviderRequestId() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("ai.live.gate"), "Set -Dai.live.gate=true to run the paid live gate");

        String suffix = String.valueOf(System.currentTimeMillis());
        String pointId = "live-plan-point-" + suffix;
        String runId = "live-plan-run-" + suffix;
        String projectCode = "LIVE_PLAN_" + suffix;
        Instant now = Instant.now();
        boolean inserted = false;
        try {
            jdbcTemplate.update(
                "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
                projectCode, "Live Plan Gate", "opt-in live gate fixture", true, Timestamp.from(now), Timestamp.from(now));
            long projectId = jdbcTemplate.queryForObject("select id from projects where code = ?", Long.class, projectCode);

            jdbcTemplate.update("""
                insert into ai_analysis_runs(
                  run_id, project_id, target_case_kind, template_id, analysis_strategy, execution_mode,
                  status, worker_status, coverage_status, total_chunks, completed_chunks, current_chunk_id,
                  resume_from_chunk_id, error_summary, config_snapshot_json, rule_snapshot_json,
                  skill_snapshot_json, schema_snapshot_json, input_summary_json, output_summary_json,
                  actor_id, actor_name, started_at, finished_at, created_at, updated_at
                ) values (?, ?, 'web_automation', '', 'balanced', 'single_pass', 'succeeded', 'idle', 'planned',
                  0, 0, '', '', '', '{}', '{}', '{}', '{}', '{}', '{}', 'live-gate', 'Live Gate', ?, ?, ?, ?)
                """,
                "live-plan-analysis-" + suffix, projectId, Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now));
            long runPk = jdbcTemplate.queryForObject(
                "select id from ai_analysis_runs where run_id = ?", Long.class, "live-plan-analysis-" + suffix);

            jdbcTemplate.update("""
                insert into ai_draft_test_points(
                  point_id, run_id, project_id, title, module_name, page_or_feature, case_kind, test_type,
                  priority_level, test_objective, expected_result, source_file, source_section, source_page,
                  source_text, review_status, template_id, field_origins_json, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, 'web_automation', 'functional', 'P2', ?, ?, 'live-gate-requirement.md',
                  '设备管理', '1', ?, 'confirmed', '', '[\"RULE_COMPUTED\"]', ?, ?)
                """,
                pointId, runPk, projectId, "设备列表按名称搜索并展示筛选结果", "设备管理", "设备列表页",
                "验证设备列表支持按名称关键字搜索，搜索后只展示匹配的设备",
                "在设备列表输入设备名称关键字后，列表只展示匹配的设备",
                "设备管理页面提供「名称」搜索框，输入关键字点击查询后，列表仅展示名称匹配的设备，未匹配的设备不再显示。",
                Timestamp.from(now), Timestamp.from(now));

            jdbcTemplate.update("""
                insert into ai_web_case_agent_runs(
                  run_id, project_id, analysis_run_id, status, current_stage, requested_point_ids_json,
                  completed_point_ids_json, blocked_point_ids_json, rules_snapshot_json, input_summary_json,
                  output_summary_json, last_heartbeat_at, cancel_requested, error_summary, mode, plan_run_id,
                  created_at, updated_at, started_at, finished_at
                ) values (?, ?, ?, 'running', 'planning', ?, '[]', '[]', '{}', '{}', '{}', ?, false, '', 'PLAN', '', ?, ?, ?, null)
                """,
                runId, projectId, "live-plan-analysis-" + suffix, "[\"" + pointId + "\"]",
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
            inserted = true;

            long startedAt = System.currentTimeMillis();
            AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch plan = facade.plan(List.of(pointId), null, List.of());
            long elapsedMs = System.currentTimeMillis() - startedAt;

            assertNotNull(plan, "plan call returned nothing");
            assertFalse(plan.plans().isEmpty(), "real plan call produced no plans");
            assertNotNull(plan.providerRequestId(), "provider request id is null");
            assertFalse(plan.providerRequestId().isBlank(), "real plan call returned a blank provider request id");

            runService.saveNaturalPlanArtifacts(runId, plan.plans(), plan.providerRequestId());

            int artifacts = jdbcTemplate.queryForObject(
                "select count(*) from ai_web_case_agent_artifacts where run_id = ? and result_origin = 'MODEL_GENERATED' "
                    + "and acceptance_state = 'PENDING_HUMAN_CONFIRMATION' and accepted = false",
                Integer.class, runId);
            assertEquals(plan.plans().size(), artifacts, "every plan artifact must be MODEL_GENERATED with a real provider request id");

            int audited = jdbcTemplate.queryForObject(
                "select count(*) from ai_artifact_gate_audits a join ai_web_case_agent_artifacts w on a.artifact_id = w.artifact_id "
                    + "where w.run_id = ? and a.result_origin = 'MODEL_GENERATED'",
                Integer.class, runId);
            assertTrue(audited >= 1, "gate audit must record the same model origin as the artifact");

            String storedRequestId = jdbcTemplate.queryForObject(
                "select candidate_json from ai_web_case_agent_artifacts where run_id = ? limit 1", String.class, runId);
            assertTrue(storedRequestId.contains(plan.providerRequestId()),
                "the artifact must persist the provider request id it was accepted on");

            System.out.printf(
                "LIVE_PLAN_GATE plans=%d providerRequestId=%s elapsedMs=%d%n",
                plan.plans().size(), plan.providerRequestId(), elapsedMs);
        } finally {
            // Ordered cleanup: the real call inserts an ai_call_records row with an FK to the fixture
            // project, so the project can only go last.
            jdbcTemplate.update("delete from ai_artifact_gate_audits where run_id = ?", runId);
            jdbcTemplate.update("delete from ai_web_case_agent_artifacts where run_id = ?", runId);
            jdbcTemplate.update("delete from ai_web_case_agent_runs where run_id = ?", runId);
            jdbcTemplate.update("delete from ai_draft_test_points where point_id = ?", pointId);
            jdbcTemplate.update("delete from ai_call_records where project_id = (select id from projects where code = ?)", projectCode);
            jdbcTemplate.update("delete from ai_analysis_runs where run_id = ?", "live-plan-analysis-" + suffix);
            jdbcTemplate.update("delete from projects where code = ?", projectCode);
            if (!inserted) {
                System.out.println("live plan gate fixture was not created; nothing to clean up beyond the statements above");
            }
        }
    }
}
