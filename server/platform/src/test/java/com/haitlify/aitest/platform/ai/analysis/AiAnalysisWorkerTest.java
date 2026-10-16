package com.haitlify.aitest.platform.ai.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.haitlify.aitest.platform.ai.gateway.AiGateway;
import com.haitlify.aitest.platform.ai.gateway.AiGatewayConfig;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import tools.jackson.databind.JsonNode;

@SpringBootTest
@ActiveProfiles("test")
class AiAnalysisWorkerTest {

    @Autowired
    private AiAnalysisWorker worker;

    @Autowired
    private com.haitlify.aitest.platform.ai.runtime.AiCancellationRegistry cancellationRegistry;

    @Autowired
    private AiRunOriginSyncService runOriginSyncService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AiGateway aiGateway;

    private long projectId;
    private long runPk;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("delete from ai_analysis_worker_leases");
        jdbcTemplate.update("delete from ai_run_events");
        jdbcTemplate.update("delete from ai_run_policies");
        jdbcTemplate.update("delete from ai_unclear_questions");
        jdbcTemplate.update("delete from ai_coverage_summaries");
        jdbcTemplate.update("delete from ai_analysis_chunks");
        jdbcTemplate.update("delete from ai_call_records");
        jdbcTemplate.update("delete from ai_review_actions");
        jdbcTemplate.update("delete from ai_artifact_gate_audits");
        jdbcTemplate.update("delete from ai_draft_test_points");
        jdbcTemplate.update("delete from ai_analysis_runs");
        jdbcTemplate.update("delete from ai_source_chunks");
        jdbcTemplate.update("delete from ai_source_documents");
        jdbcTemplate.update("delete from ai_system_configs");
        jdbcTemplate.update("delete from ai_rules");
        jdbcTemplate.update("delete from projects where code = 'AI_WORKER'");

        Instant now = Instant.parse("2026-07-03T10:00:00Z");
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_WORKER",
            "AI Worker",
            "AI worker tests",
            true,
            now,
            now
        );
        projectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_WORKER'", Long.class);
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
            "****test", "system-dev", "Development User", now, now
        );
        insertQueuedRun("run-worker");
        runPk = jdbcTemplate.queryForObject("select id from ai_analysis_runs where run_id = 'run-worker'", Long.class);
    }

    @Test
    void leaseMigrationIsAvailableForCleanSchema() {
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from information_schema.tables where table_name = 'ai_analysis_worker_leases'", Integer.class))
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from information_schema.tables where table_name = 'ai_draft_test_points'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void runOriginDerivationMirrorsArtifactsForSucceededRuns() {
        // The V31 backfill migration must apply cleanly on a fresh schema, and the derivation must never
        // upgrade a run whose artifacts are not model-backed.
        jdbcTemplate.update("delete from ai_draft_test_points");
        jdbcTemplate.update("""
            update ai_analysis_runs set status = 'succeeded', result_origin = 'MODEL_GENERATED',
                acceptance_state = 'PENDING_HUMAN_CONFIRMATION' where id = ?
            """, runPk);
        jdbcTemplate.update("""
            insert into ai_draft_test_points(
              point_id, run_id, project_id, title, module_name, page_or_feature, case_kind, test_type,
              priority_level, test_objective, suggested_steps, expected_result, source_file, source_section,
              source_page, source_text, extraction_method, confidence_score, inferred, review_status, review_comment,
              result_origin, acceptance_state, revision, parent_artifact_id, idempotency_key, template_id,
              metadata_json, actor_id, actor_name, created_at, updated_at
            ) values ('point-rule-1', ?, ?, '规则点', '', '', 'functional', 'functional', 'P2', '', '', '',
              '', '', '', '', 'rule', 0.5, false, 'ai_generated', '', 'RULE_EXTRACTED', 'PENDING_HUMAN_CONFIRMATION',
              1, '', 'rule:1', '', '{}', 'system-dev', 'Development User', ?, ?)
            """, runPk, projectId,
            java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()));

        runOriginSyncService.syncFromArtifacts(runPk);

        assertThat(jdbcTemplate.queryForObject(
            "select result_origin from ai_analysis_runs where id = ?", String.class, runPk))
            .isEqualTo("RULE_EXTRACTED");
        assertThat(jdbcTemplate.queryForObject(
            "select acceptance_state from ai_analysis_runs where id = ?", String.class, runPk))
            .isEqualTo("PENDING_HUMAN_CONFIRMATION");
    }

    @Test
    void queuedRunIsNotProcessedWhenAnotherWorkerOwnsTheLease() {
        jdbcTemplate.update("insert into ai_analysis_worker_leases(run_id, owner_id, lease_until, fencing_token, updated_at) values (?, ?, ?, ?, ?)",
            "run-worker", "other-worker", java.sql.Timestamp.from(Instant.now().plusSeconds(300)), 7L, java.sql.Timestamp.from(Instant.now()));

        AiAnalysisWorkerResult result = worker.processNextQueuedRun();

        assertFalse(result.processed());
        assertEquals("busy", result.status());
        assertEquals("other-worker", jdbcTemplate.queryForObject("select owner_id from ai_analysis_worker_leases where run_id = ?", String.class, "run-worker"));
        assertEquals(7L, jdbcTemplate.queryForObject("select fencing_token from ai_analysis_worker_leases where run_id = ?", Long.class, "run-worker"));
    }

    @Test
    void expiredLeaseCanBeTakenOverWithNextFencingToken() {
        jdbcTemplate.update("insert into ai_analysis_worker_leases(run_id, owner_id, lease_until, fencing_token, updated_at) values (?, ?, ?, ?, ?)",
            "run-worker", "dead-worker", java.sql.Timestamp.from(Instant.now().minusSeconds(1)), 7L, java.sql.Timestamp.from(Instant.now().minusSeconds(2)));
        insertChunk("run-worker-planned-1", "登录", "用户输入正确账号密码后进入工作台", "planned");
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(false, "", "worker-req-failed", 20, 0, "model timeout"));

        AiAnalysisWorkerResult result = worker.processNextQueuedRun();

        assertTrue(result.processed());
        assertEquals(0, jdbcTemplate.queryForObject("select count(*) from ai_analysis_worker_leases where run_id = ?", Integer.class, "run-worker"));
    }

    @Test
    void completedWorkDoesNotContinueAfterLeaseIsFencedOut() {
        insertChunk("run-worker-planned-1", "登录", "用户输入正确账号密码后进入工作台", "planned");
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenAnswer(invocation -> {
                jdbcTemplate.update("update ai_analysis_worker_leases set fencing_token = fencing_token + 1 where run_id = ?", "run-worker");
                return new AiGenerationResult(true, "{\"draftTestPoints\":[]}", "worker-req-fenced", 20, 10, null);
            });

        AiAnalysisWorkerResult result = worker.processNextQueuedRun();

        assertTrue(result.processed());
        // 租约被围栏时该 worker 放弃写入（preempted），运行终态留给新 owner；已完成工作不得继续。
        assertEquals("preempted", result.status());
        assertEquals("running", jdbcTemplate.queryForObject(
            "select status from ai_analysis_runs where run_id = ?", String.class, "run-worker"));
        assertEquals(0, jdbcTemplate.queryForObject("select count(*) from ai_draft_test_points where run_id = ?", Integer.class, runPk));
        assertEquals(0, jdbcTemplate.queryForObject(
            "select count(*) from ai_run_events where run_id = ? and stage = 'run_failed'", Integer.class, "run-worker"));
    }

    @Test
    void draftPointsAreBlockedWhenProviderRequestIdIsMissing() {
        insertChunk("run-worker-planned-1", "登录", "用户输入正确账号密码后进入工作台", "planned");
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {
                  "draftTestPoints": [
                    {
                      "title": "登录成功",
                      "module": "登录",
                      "expectedResult": "进入工作台",
                      "sourceChunkId": "run-worker-planned-1"
                    }
                  ]
                }
                """, "", 30, 15, null));

        worker.processNextQueuedRun();

        assertEquals("BLOCKED", jdbcTemplate.queryForObject(
            "select result_origin from ai_draft_test_points where run_id = ?", String.class, runPk));
        assertEquals("BLOCKED", jdbcTemplate.queryForObject(
            "select acceptance_state from ai_draft_test_points where run_id = ?", String.class, runPk));
        assertEquals(0, jdbcTemplate.queryForObject(
            "select count(*) from ai_draft_test_points where run_id = ? and result_origin = 'MODEL_GENERATED'", Integer.class, runPk));
        assertEquals("BLOCKED", jdbcTemplate.queryForObject(
            "select result_origin from ai_analysis_runs where id = ?", String.class, runPk));
    }

    @Test
    void draftPointsCarryModelGeneratedOriginWithProviderEvidence() {
        insertChunk("run-worker-planned-1", "登录", "用户输入正确账号密码后进入工作台", "planned");
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {
                  "draftTestPoints": [
                    {
                      "title": "登录成功",
                      "module": "登录",
                      "expectedResult": "进入工作台",
                      "sourceChunkId": "run-worker-planned-1"
                    }
                  ]
                }
                """, "worker-req-evidence", 30, 15, null));

        worker.processNextQueuedRun();

        assertEquals("MODEL_GENERATED", jdbcTemplate.queryForObject(
            "select result_origin from ai_draft_test_points where run_id = ?", String.class, runPk));
        assertEquals("PENDING_HUMAN_CONFIRMATION", jdbcTemplate.queryForObject(
            "select acceptance_state from ai_draft_test_points where run_id = ?", String.class, runPk));
        assertEquals("MODEL_GENERATED", jdbcTemplate.queryForObject(
            "select result_origin from ai_analysis_runs where id = ?", String.class, runPk));
        assertEquals("PENDING_HUMAN_CONFIRMATION", jdbcTemplate.queryForObject(
            "select acceptance_state from ai_analysis_runs where id = ?", String.class, runPk));
    }

    @Test
    void longModelExpectedResultStillMergesWithoutGateKeyTruncation() {
        // 用户实测：模型给出的 expectedResult 是一整句文本（非散列），拼进
        // draft-point:<runPk>:<chunkId>:<expectedResult> 后超过 gate 审计 artifact_id 的 128 列宽，
        // 整个合并事务被截断异常炸掉、分析任务失败。长句子输入下合并必须成功。
        insertChunk("run-worker-planned-1", "登录", "用户输入正确账号密码后进入工作台", "planned");
        String longExpected = "进入工作台并校验账号信息、权限范围、数据加载与页面报错状态".repeat(12);
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {
                  "draftTestPoints": [
                    {
                      "title": "登录成功",
                      "module": "登录",
                      "expectedResult": "%s",
                      "sourceChunkId": "run-worker-planned-1"
                    }
                  ]
                }
                """.formatted(longExpected), "worker-req-long-expected", 30, 15, null));

        worker.processNextQueuedRun();

        assertEquals(1, jdbcTemplate.queryForObject(
            "select count(*) from ai_draft_test_points where run_id = ?", Integer.class, runPk));
        String artifactId = jdbcTemplate.queryForObject(
            "select artifact_id from ai_artifact_gate_audits where run_id = ? order by id desc limit 1",
            String.class, String.valueOf(runPk));
        assertThat(artifactId).isNotNull();
        assertThat(artifactId.length()).isLessThanOrEqualTo(128);
        assertEquals("PENDING_HUMAN_CONFIRMATION", jdbcTemplate.queryForObject(
            "select decision from ai_artifact_gate_audits where run_id = ? order by id desc limit 1",
            String.class, String.valueOf(runPk)));
    }

    @Test
    void hugeFailureMessageIsClampedAndRunStillReachesFailedTerminalState() {
        // 失败上报链路自身必须扛住超长异常文本：event.summary 与 run.error_summary 均为
        // varchar(1024)，修复前超长 SQL 原文会把事件写入也顶爆（截断异常盖住原始失败根因）。
        insertChunk("run-worker-planned-1", "登录", "用户输入正确账号密码后进入工作台", "planned");
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenThrow(new RuntimeException("E".repeat(5000)));

        worker.processNextQueuedRun();

        assertEquals("failed", runValue("status"));
        String summary = jdbcTemplate.queryForObject(
            "select summary from ai_run_events where run_id = ? and stage = 'run_failed' order by id desc limit 1",
            String.class, "run-worker");
        assertThat(summary).isNotNull();
        assertThat(summary.length()).isLessThanOrEqualTo(1024);
        assertThat(summary).contains("(truncated)");
        String errorSummary = jdbcTemplate.queryForObject(
            "select error_summary from ai_analysis_runs where id = ?", String.class, runPk);
        assertThat(errorSummary.length()).isLessThanOrEqualTo(1024);
    }

    @Test
    void queuedRunBecomesRunningThenSucceeded() {
        insertChunk("run-worker-planned-1", "登录", "用户输入正确账号密码后进入工作台", "planned");
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                {
                  "draftTestPoints": [
                    {
                      "title": "登录成功",
                      "module": "登录",
                      "caseKind": "interface",
                      "objective": "验证登录",
                      "expectedResult": "进入工作台",
                      "sourceChunkId": "run-worker-planned-1"
                    }
                  ]
                }
                """, "worker-req-1", 30, 15, null));

        AiAnalysisWorkerResult result = worker.processNextQueuedRun();

        assertTrue(result.processed());
        assertEquals("run-worker", result.runId());
        assertEquals("succeeded", runValue("status"));
        assertEquals("idle", runValue("worker_status"));
        assertTrue(jdbcTemplate.queryForObject("select heartbeat_at from ai_analysis_runs where run_id = ?", java.sql.Timestamp.class, "run-worker") != null);
        assertEquals(0, jdbcTemplate.queryForObject("select count(*) from ai_analysis_worker_leases where run_id = ?", Integer.class, "run-worker"));
        assertEquals(1, numberRunValue("completed_chunks"));
        assertEquals("", runValue("current_chunk_id"));
        assertEquals(1, jdbcTemplate.queryForObject("select count(*) from ai_draft_test_points where run_id = ?", Integer.class, runPk));
        assertEquals("succeeded", jdbcTemplate.queryForObject("select status from ai_analysis_chunks where planned_chunk_id = ?", String.class, "run-worker-planned-1"));

        ArgumentCaptor<JsonNode> promptCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(aiGateway).structuredGenerate(any(AiGatewayConfig.class), promptCaptor.capture());
        JsonNode prompt = promptCaptor.getValue();
        assertEquals("requirement-test-point-analysis", prompt.path("skill").path("skillId").asText());
        assertTrue(prompt.path("skill").path("promptTemplate").asText().contains("来源证据"));
        assertEquals("system-v1", prompt.path("effectiveRules").path("effectiveVersion").asText());
        assertEquals(
            "system-v1",
            jdbcTemplate.queryForObject("select rule_version from ai_call_records where run_id = ?", String.class, runPk)
        );
    }

    @Test
    void failedChunkStoresResumePoint() {
        insertChunk("run-worker-planned-1", "登录", "用户输入正确账号密码后进入工作台", "planned");
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(false, "", "worker-req-failed", 20, 0, "model timeout"));

        AiAnalysisWorkerResult result = worker.processNextQueuedRun();

        assertTrue(result.processed());
        assertEquals("failed", runValue("status"));
        assertEquals("idle", runValue("worker_status"));
        assertEquals("run-worker-planned-1", runValue("current_chunk_id"));
        assertEquals("run-worker-planned-1", runValue("resume_from_chunk_id"));
        assertEquals("failed", jdbcTemplate.queryForObject("select status from ai_analysis_chunks where planned_chunk_id = ?", String.class, "run-worker-planned-1"));
        assertEquals("model timeout", runValue("error_summary"));
    }

    @Test
    void truncatedModelJsonIsRepairedBeforeTheChunkIsMarkedFailed() {
        insertChunk("run-worker-planned-1", "登录", "用户输入正确账号密码后进入工作台", "planned");
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true,
                "{\"draftTestPoints\":[{\"title\":\"登录成功\",\"module\":\"登录\",\"expectedResult\":\"进入工作台",
                "worker-req-truncated", 30, 15, null))
            .thenReturn(new AiGenerationResult(true, """
                {"draftTestPoints":[{"title":"登录成功","module":"登录","expectedResult":"进入工作台","sourceChunkId":"run-worker-planned-1"}]}
                """, "worker-req-repaired", 35, 20, null));

        AiAnalysisWorkerResult result = worker.processNextQueuedRun();

        assertEquals("succeeded", result.status(), runValue("error_summary"));
        assertEquals("succeeded", runValue("status"));
        assertTrue(runValue("output_summary_json").contains("\"repairTriggered\":true"));
        assertEquals(1, jdbcTemplate.queryForObject("select count(*) from ai_draft_test_points where run_id = ?", Integer.class, runPk));
        verify(aiGateway, times(2)).structuredGenerate(any(AiGatewayConfig.class), any());
    }

    @Test
    void structurallyInvalidModelJsonIsRepairedBeforeTheChunkIsMarkedFailed() {
        insertChunk("run-worker-planned-1", "登录", "用户输入正确账号密码后进入工作台", "planned");
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true,
                "{\"draftTestPoints\":{\"title\":\"登录成功\"}}",
                "worker-req-invalid-shape", 30, 15, null))
            .thenReturn(new AiGenerationResult(true, """
                {"draftTestPoints":[{"title":"登录成功","module":"登录","expectedResult":"进入工作台","sourceChunkId":"run-worker-planned-1"}]}
                """, "worker-req-repaired-shape", 35, 20, null));

        AiAnalysisWorkerResult result = worker.processNextQueuedRun();

        assertEquals("succeeded", result.status());
        assertEquals("succeeded", runValue("status"));
        assertEquals(1, jdbcTemplate.queryForObject("select count(*) from ai_draft_test_points where run_id = ?", Integer.class, runPk));
        assertTrue(runValue("output_summary_json").contains("\"repairTriggered\":true"));
        verify(aiGateway, times(2)).structuredGenerate(any(AiGatewayConfig.class), any());
    }

    @Test
    void resumeContinuesFromFailedChunk() {
        insertChunk("run-worker-planned-1", "登录", "用户输入正确账号密码后进入工作台", "succeeded");
        insertChunk("run-worker-planned-2", "权限", "管理员查看用户列表", "failed");
        insertChunk("run-worker-planned-3", "项目", "项目保存后为草稿", "planned");
        jdbcTemplate.update("""
            update ai_analysis_runs
            set status = ?, worker_status = ?, total_chunks = ?, completed_chunks = ?, current_chunk_id = ?,
                resume_from_chunk_id = ?, error_summary = ?
            where run_id = ?
            """,
            "failed", "idle", 3, 1, "run-worker-planned-2", "run-worker-planned-2", "model timeout", "run-worker"
        );
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(new AiGenerationResult(true, """
                { "draftTestPoints": [{ "title": "查看用户列表", "module": "权限", "sourceChunkId": "run-worker-planned-2" }] }
                """, "worker-req-resume-1", 25, 10, null))
            .thenReturn(new AiGenerationResult(true, """
                { "draftTestPoints": [{ "title": "保存项目", "module": "项目", "sourceChunkId": "run-worker-planned-3" }] }
                """, "worker-req-resume-2", 28, 12, null));

        AiAnalysisWorkerResult result = worker.resumeRun("run-worker");

        assertTrue(result.processed());
        assertEquals("succeeded", runValue("status"));
        assertEquals("", runValue("resume_from_chunk_id"));
        assertEquals("", runValue("current_chunk_id"));
        assertEquals(3, numberRunValue("completed_chunks"));
        assertEquals("succeeded", jdbcTemplate.queryForObject("select status from ai_analysis_chunks where planned_chunk_id = ?", String.class, "run-worker-planned-2"));
        assertEquals("succeeded", jdbcTemplate.queryForObject("select status from ai_analysis_chunks where planned_chunk_id = ?", String.class, "run-worker-planned-3"));
    }

    @Test
    void cancelledRunStartsNoModelCallAtAll() {
        // T05：取消注册表在 worker 层生效——任何模型调用发起之前即停止（计数为 0）。
        insertChunk("run-worker-cancel-1", "登录", "用户输入正确账号密码后进入工作台", "planned");
        cancellationRegistry.cancel("run-worker");

        AiAnalysisWorkerResult result = worker.processNextQueuedRun();

        assertEquals("cancelled", result.status());
        org.mockito.Mockito.verify(aiGateway, org.mockito.Mockito.never())
            .structuredGenerate(org.mockito.ArgumentMatchers.any(com.haitlify.aitest.platform.ai.gateway.AiGatewayConfig.class),
                org.mockito.ArgumentMatchers.any());
    }

    private void insertQueuedRun(String runId) {
        Instant now = Instant.parse("2026-07-03T10:00:00Z");
        jdbcTemplate.update("""
            insert into ai_analysis_runs(
              run_id, project_id, target_case_kind, template_id, analysis_strategy, execution_mode,
              status, worker_status, coverage_status, total_chunks, completed_chunks, current_chunk_id,
              resume_from_chunk_id, error_summary, config_snapshot_json, rule_snapshot_json,
              skill_snapshot_json, schema_snapshot_json, input_summary_json, output_summary_json,
              actor_id, actor_name, started_at, finished_at, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            runId, projectId, "interface", "", "balanced", "multi_pass",
            "queued", "idle", "planned", 0, 0, "", "", "",
            "{}", "{}", "{}", "{}", "{\"documentIds\":[]}", null,
            "system-dev", "Development User", null, null, now, now
        );
    }

    private void insertChunk(String plannedChunkId, String section, String content, String status) {
        Instant now = Instant.parse("2026-07-03T10:00:00Z");
        jdbcTemplate.update("""
            insert into ai_analysis_chunks(
              planned_chunk_id, run_id, source_chunk_ids_json, section_heading, token_estimate,
              status, skip_reason, content_text, evidence_metadata_json, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            plannedChunkId, runPk, "[]", section, 20, status, "", content, "{}", now, now
        );
        jdbcTemplate.update("update ai_analysis_runs set total_chunks = (select count(*) from ai_analysis_chunks where run_id = ?) where id = ?", runPk, runPk);
    }

    private String runValue(String column) {
        return jdbcTemplate.queryForObject("select " + column + " from ai_analysis_runs where run_id = 'run-worker'", String.class);
    }

    private int numberRunValue(String column) {
        return jdbcTemplate.queryForObject("select " + column + " from ai_analysis_runs where run_id = 'run-worker'", Integer.class);
    }
}
