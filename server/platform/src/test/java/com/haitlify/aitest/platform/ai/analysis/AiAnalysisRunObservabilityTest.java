package com.haitlify.aitest.platform.ai.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.haitlify.aitest.platform.ai.config.AiRunPolicyService;
import com.haitlify.aitest.platform.ai.event.AiRunEventService;
import com.haitlify.aitest.platform.ai.gateway.AiGateway;
import com.haitlify.aitest.platform.ai.gateway.AiGatewayConfig;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.ai.gateway.AiRetryReason;
import com.haitlify.aitest.platform.ai.gateway.AiRoute;
import com.haitlify.aitest.platform.ai.gateway.AiRoutingStrategy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end observability for requirement-analysis runs (spec §Verification): policy strategy
 * materialization, eligible fallback, non-retryable auth failure, per-call timeout, whole-run
 * deadline, per-chunk retry, preservation of successful chunks, and sensitive-data redaction.
 */
@SpringBootTest
@ActiveProfiles("test")
class AiAnalysisRunObservabilityTest {

    @Autowired
    private AiAnalysisWorker worker;

    @Autowired
    private AiRunPolicyService runPolicyService;

    @Autowired
    private AiRunEventService eventService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AiGateway aiGateway;

    @MockitoBean
    private Clock clock;

    private long projectId;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("delete from ai_run_events");
        jdbcTemplate.update("delete from ai_run_policies");
        jdbcTemplate.update("delete from ai_unclear_questions");
        jdbcTemplate.update("delete from ai_coverage_summaries");
        jdbcTemplate.update("delete from ai_analysis_chunks");
        jdbcTemplate.update("delete from ai_call_records");
        jdbcTemplate.update("delete from ai_draft_test_points");
        jdbcTemplate.update("delete from ai_analysis_runs");
        jdbcTemplate.update("delete from ai_source_chunks");
        jdbcTemplate.update("delete from ai_source_documents");
        jdbcTemplate.update("delete from ai_system_configs");
        jdbcTemplate.update("delete from ai_rules");
        jdbcTemplate.update("delete from projects where code = 'AI_OBS'");

        Instant now = Instant.parse("2026-07-30T10:00:00Z");
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_OBS", "AI Observability", "observability tests", true, now, now
        );
        projectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_OBS'", Long.class);
        seedTwoConnectionConfig();
        when(clock.instant()).thenReturn(Instant.parse("2026-07-30T10:00:00Z"));
    }

    @Test
    void resolveStrategyMaterializesOrderedPolicyRoutes() {
        Instant now = Instant.parse("2026-07-30T10:00:00Z");
        String routesJson = """
            [{"position":0,"connectionId":"conn-openai","model":"gpt-5.5"},{"position":1,"connectionId":"conn-ds","model":"deepseek-chat"}]
            """.trim();
        jdbcTemplate.update("""
            insert into ai_run_policies(
              policy_id, scope, task_type, name, enabled, per_call_timeout_ms, whole_run_deadline_ms,
              retry_budget, max_input_chars, routes_json, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "default-requirement_analysis", "default", "requirement_analysis", "需求分析默认策略",
            true, 90000, 600000L, 1, 0, routesJson, Timestamp.from(now), Timestamp.from(now)
        );

        AiRoutingStrategy strategy = runPolicyService.resolveStrategy("requirement_analysis");

        assertEquals(2, strategy.routes().size());
        AiRoute primary = strategy.routes().get(0);
        assertEquals("conn-openai", primary.connectionId());
        assertEquals("gpt-5.5", primary.model());
        assertEquals("sk-primary-secret", primary.apiKey());
        assertEquals("deepseek-chat", strategy.routes().get(1).model());
        assertEquals(1, strategy.retryBudget());
    }

    @Test
    void fallbackSucceedsAndRecordsEventsAndSummary() {
        long runPk = insertRunWithStrategy("run-fallback", strategyJson(900000L));
        insertChunk(runPk, "chunk-fallback-1", "planned");
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(failure(AiRetryReason.RATE_LIMITED))
            .thenReturn(success("run-fallback"));

        AiAnalysisWorkerResult result = worker.processRun("run-fallback");

        assertEquals("succeeded", result.status());
        assertEquals(2, callRecordCount("run-fallback"));
        assertTrue(eventStages("run-fallback").contains("fallback_switched"));
        JsonNode summary = outputSummary("run-fallback");
        assertTrue(summary.path("fallbackReasons").toString().contains("RATE_LIMITED"));
        assertEquals("succeeded", summary.path("terminalReason").asText());
        assertEquals("succeeded", chunkStatus(runPk, "chunk-fallback-1"));
    }

    @Test
    void nonRetryableAuthFailureStopsAndMarksRetryUnsafe() {
        long runPk = insertRunWithStrategy("run-auth", strategyJson(900000L));
        insertChunk(runPk, "chunk-auth-1", "planned");
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(failure(AiRetryReason.AUTH_DENIED));

        AiAnalysisWorkerResult result = worker.processRun("run-auth");

        assertEquals("failed", result.status());
        assertEquals(1, callRecordCount("run-auth"));
        verify(aiGateway, times(1)).structuredGenerate(any(AiGatewayConfig.class), any());
        JsonNode summary = outputSummary("run-auth");
        assertEquals("auth_denied", summary.path("terminalReason").asText());
        assertFalse(summary.path("retryCommand").path("safe").asBoolean());
    }

    @Test
    void perCallTimeoutTriggersFallback() {
        long runPk = insertRunWithStrategy("run-timeout", strategyJson(900000L));
        insertChunk(runPk, "chunk-timeout-1", "planned");
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(failure(AiRetryReason.TIMEOUT))
            .thenReturn(success("run-timeout"));

        AiAnalysisWorkerResult result = worker.processRun("run-timeout");

        assertEquals("succeeded", result.status());
        JsonNode summary = outputSummary("run-timeout");
        assertTrue(summary.path("fallbackReasons").toString().contains("TIMEOUT"));
        assertEquals("succeeded", chunkStatus(runPk, "chunk-timeout-1"));
    }

    @Test
    void wholeRunDeadlineStopsRemainingChunks() {
        long runPk = insertRunWithStrategy("run-deadline", strategyJson(1L));
        insertChunk(runPk, "chunk-deadline-1", "planned");
        insertChunk(runPk, "chunk-deadline-2", "planned");
        // Deterministic clock: deadline is computed from t0 (deadline = t0 + 1ms)。时钟在 chunk-1 的
        // 模型调用返回后推进到 t1，因此 chunk-2 的 deadline 检查必须停止它——该写法对租约/调用路径
        // 额外读取时钟的次数保持稳健（不再依赖精确的调用序列）。
        Instant t0 = Instant.parse("2026-07-30T10:00:00Z");
        Instant t1 = t0.plusMillis(2);
        java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(t0);
        when(clock.instant()).thenAnswer(invocation -> now.get());
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any())).thenAnswer(invocation -> {
            AiGenerationResult generated = success("run-deadline");
            now.set(t1);
            return generated;
        });

        AiAnalysisWorkerResult result = worker.processRun("run-deadline");

        assertEquals("failed", result.status());
        JsonNode summary = outputSummary("run-deadline");
        assertEquals("run_deadline_exceeded", summary.path("terminalReason").asText());
        assertEquals(1, summary.path("chunks").path("completed").asInt());
        assertEquals("planned", chunkStatus(runPk, "chunk-deadline-2"));
        assertTrue(eventStages("run-deadline").contains("run_deadline_exceeded"));
    }

    @Test
    void succeededChunksAreNotRegeneratedOnResume() {
        long runPk = insertRunWithStrategy("run-resume", strategyJson(900000L));
        insertChunk(runPk, "chunk-resume-1", "succeeded");
        insertChunk(runPk, "chunk-resume-2", "planned");
        jdbcTemplate.update("""
            update ai_analysis_runs
            set status = ?, worker_status = ?, total_chunks = ?, completed_chunks = ?,
                current_chunk_id = ?, resume_from_chunk_id = ?
            where run_id = ?
            """,
            "failed", "idle", 2, 1, "chunk-resume-2", "chunk-resume-2", "run-resume"
        );
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(success("run-resume"));

        AiAnalysisWorkerResult result = worker.resumeRun("run-resume");

        assertEquals("succeeded", result.status());
        // Only the not-yet-succeeded chunk is processed.
        verify(aiGateway, times(1)).structuredGenerate(any(AiGatewayConfig.class), any());
        assertEquals("succeeded", chunkStatus(runPk, "chunk-resume-2"));
    }

    @Test
    void eventStreamNeverLeaksSecrets() {
        long runPk = insertRunWithStrategy("run-redact", strategyJson(900000L));
        insertChunk(runPk, "chunk-redact-1", "planned");
        when(aiGateway.structuredGenerate(any(AiGatewayConfig.class), any()))
            .thenReturn(failure(AiRetryReason.RATE_LIMITED))
            .thenReturn(success("run-redact"));

        worker.processRun("run-redact");

        String payload = objectMapper.valueToTree(eventService.listByRun("run-redact").items()).toString();
        assertFalse(payload.contains("sk-primary-secret"));
        assertFalse(payload.contains("sk-fallback-secret"));
        assertFalse(payload.contains("apiKey"));
    }

    private AiGenerationResult success(String runId) {
        return new AiGenerationResult(true, """
            { "draftTestPoints": [{ "title": "测试点", "module": "模块", "sourceChunkId": "chunk-%s-1" }] }
            """.formatted(runId), "req-" + runId, 12, 8, null);
    }

    private AiGenerationResult failure(AiRetryReason reason) {
        return new AiGenerationResult(false, "", "req-fail", 0, 0, "fail", reason.retryable(), reason.name());
    }

    private String strategyJson(long wholeRunDeadlineMs) {
        return """
            {"strategySnapshotId":"strategy:obs","taskType":"requirement_analysis","retryBudget":1,
             "perCallTimeoutMs":120000,"wholeRunDeadlineMs":%d,
             "routes":[
               {"connectionId":"conn-openai","providerName":"生产 OpenAI","providerType":"openai-compatible","baseUrl":"https://a","model":"gpt-5.5","apiKey":"sk-primary-secret","requestTimeoutMs":120000},
               {"connectionId":"conn-ds","providerName":"备用 DeepSeek","providerType":"openai-compatible","baseUrl":"https://b","model":"deepseek-chat","apiKey":"sk-fallback-secret","requestTimeoutMs":120000}
             ]}
            """.formatted(wholeRunDeadlineMs);
    }

    private void seedTwoConnectionConfig() {
        Instant now = Instant.parse("2026-07-30T10:00:00Z");
        jdbcTemplate.update("""
            insert into ai_system_configs(
              config_key, enabled, provider_type, base_url, text_model, provider_config_json,
              credential_configured, request_timeout_ms, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "default", true, "openai-compatible", "https://a", "gpt-5.5",
            """
            {"version":2,"activeProviderId":"conn-openai","activeModel":"gpt-5.5","providers":[
              {"id":"conn-openai","providerId":"openai","name":"生产 OpenAI","baseURL":"https://a","defaultModel":"gpt-5.5","apiKey":"sk-primary-secret","enabled":true,"credentialConfigured":true,"requestTimeoutMs":120000},
              {"id":"conn-ds","providerId":"deepseek","name":"备用 DeepSeek","baseURL":"https://b","defaultModel":"deepseek-chat","apiKey":"sk-fallback-secret","enabled":true,"credentialConfigured":true,"requestTimeoutMs":120000}
            ]}
            """,
            true, 120000, Timestamp.from(now), Timestamp.from(now)
        );
    }

    private long insertRunWithStrategy(String runId, String strategyJson) {
        Instant now = Instant.parse("2026-07-30T10:00:00Z");
        jdbcTemplate.update("""
            insert into ai_analysis_runs(
              run_id, project_id, target_case_kind, template_id, analysis_strategy, execution_mode,
              status, worker_status, coverage_status, total_chunks, completed_chunks, current_chunk_id,
              resume_from_chunk_id, error_summary, config_snapshot_json, rule_snapshot_json,
              skill_snapshot_json, schema_snapshot_json, input_summary_json, output_summary_json,
              strategy_snapshot_json, actor_id, actor_name, started_at, finished_at, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            runId, projectId, "interface", "", "balanced", "multi_pass",
            "queued", "idle", "planned", 0, 0, "", "", "",
            "{}", "{}", "{}", "{}", "{\"documentIds\":[]}", null, strategyJson,
            "system-dev", "Development User", null, null, now, now
        );
        long runPk = jdbcTemplate.queryForObject("select id from ai_analysis_runs where run_id = ?", Long.class, runId);
        return runPk;
    }

    private void insertChunk(long runPk, String plannedChunkId, String status) {
        Instant now = Instant.parse("2026-07-30T10:00:00Z");
        jdbcTemplate.update("""
            insert into ai_analysis_chunks(
              planned_chunk_id, run_id, source_chunk_ids_json, section_heading, token_estimate,
              status, skip_reason, content_text, evidence_metadata_json, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            plannedChunkId, runPk, "[]", plannedChunkId, 20, status, "", "内容", "{}", now, now
        );
        jdbcTemplate.update("update ai_analysis_runs set total_chunks = (select count(*) from ai_analysis_chunks where run_id = ?) where id = ?", runPk, runPk);
    }

    private int callRecordCount(String runId) {
        return jdbcTemplate.queryForObject(
            "select count(*) from ai_call_records cr join ai_analysis_runs r on r.id = cr.run_id where r.run_id = ?",
            Integer.class, runId
        );
    }

    private List<String> eventStages(String runId) {
        return eventService.listByRun(runId).items().stream().map(e -> e.stage()).toList();
    }

    private String chunkStatus(long runPk, String plannedChunkId) {
        return jdbcTemplate.queryForObject(
            "select status from ai_analysis_chunks where run_id = ? and planned_chunk_id = ?",
            String.class, runPk, plannedChunkId
        );
    }

    private JsonNode outputSummary(String runId) {
        try {
            String json = jdbcTemplate.queryForObject(
                "select output_summary_json from ai_analysis_runs where run_id = ?", String.class, runId
            );
            return objectMapper.readTree(json == null ? "{}" : json);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }
}
