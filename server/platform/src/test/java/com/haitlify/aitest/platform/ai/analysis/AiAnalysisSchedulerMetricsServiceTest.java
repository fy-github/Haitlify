package com.haitlify.aitest.platform.ai.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 调度指标（T-Review-4）：聚合口径正确、项目过滤生效、无数据返回 null 不伪装 0；
 * 同时验证控制器端点的读取路径与策略块暴露。
 */
@SpringBootTest
@ActiveProfiles("test")
class AiAnalysisSchedulerMetricsServiceTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AiAnalysisSchedulerMetricsService metricsService;

    @Autowired
    private AiAnalysisRunController controller;

    @Autowired
    private com.haitlify.aitest.platform.ai.event.AiRunEventService eventService;

    private long projectId;

    @BeforeEach
    void resetData() {
        // 与被清理对象有外键关系的子表先删——顺序与 AiJobSchedulerIntegrationTest.cleanSlate 同口径，
        // 避免其它测试类遗留的 chunks/artifacts 让本类的重置触发外键违约（第三十轮复核方在干净环境抓到）。
        jdbcTemplate.update("delete from ai_artifact_gate_audits");
        jdbcTemplate.update("delete from ai_analysis_worker_leases");
        jdbcTemplate.update("delete from ai_run_events");
        jdbcTemplate.update("delete from ai_run_policies");
        jdbcTemplate.update("delete from ai_unclear_questions");
        jdbcTemplate.update("delete from ai_coverage_summaries");
        jdbcTemplate.update("delete from ai_review_actions");
        jdbcTemplate.update("delete from ai_draft_test_points");
        jdbcTemplate.update("delete from ai_call_records");
        jdbcTemplate.update("delete from ai_analysis_chunks");
        jdbcTemplate.update("delete from ai_runtime_vertical_artifacts");
        jdbcTemplate.update("delete from ai_runtime_vertical_attempts");
        jdbcTemplate.update("delete from ai_runtime_vertical_runs");
        jdbcTemplate.update("delete from ai_analysis_runs");
        jdbcTemplate.update("delete from projects where code = 'AI_SCHED_METRICS'");
        Instant now = Instant.parse("2026-07-03T10:00:00Z");
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, '', true, ?, ?)",
            "AI_SCHED_METRICS", "Scheduler metrics", Timestamp.from(now), Timestamp.from(now));
        projectId = jdbcTemplate.queryForObject("select id from projects where code = 'AI_SCHED_METRICS'", Long.class);
        seedRun("metrics-run-a", 1000L, 4000L, "MODEL_GENERATED");
        seedRun("metrics-run-b", 3000L, 7000L, "BLOCKED");
        jdbcTemplate.update("""
            insert into ai_call_records(
              record_id, run_id, project_id, provider_type, model_name, config_snapshot_id, agent_id,
              skill_ids_json, rule_version, modality, request_id, status, error_code, error_message,
              duration_ms, token_usage_input, token_usage_output, estimated_cost, input_summary_json,
              output_summary_json, raw_payload_json, actor_id, actor_name, created_at, updated_at
            ) values ('metrics-call-1', (select id from ai_analysis_runs where run_id = 'metrics-run-a'), ?, 'openai-compatible',
              'm', 'default', 'a', '[]', 'system-v1', 'text', 'r1', 'succeeded', '', '', 2000, 1, 2, 0, '{}', '{}', null,
              'system-dev', 'Development User', ?, ?)
            """,
            projectId, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        eventService.emit(new com.haitlify.aitest.platform.ai.event.AiRunEvent(
            "metrics-run-b", Instant.now(), "lease_recovered", "warn", "recovered", null, 0, null, "", "", "", 0L, "LEASE_RECOVERED", "", 0L));
        jdbcTemplate.update(
            "insert into ai_analysis_worker_leases(run_id, owner_id, lease_until, fencing_token, updated_at) values (?, ?, ?, 1, ?)",
            "metrics-run-a", "metrics-owner", Timestamp.from(Instant.now().plusSeconds(60)), Timestamp.from(Instant.now()));
    }

    @Test
    void metricsAggregateQueueLeaseAttemptsAndVariability() {
        Map<String, Object> metrics = metricsService.metrics(null);
        @SuppressWarnings("unchecked")
        Map<String, Object> queue = (Map<String, Object>) metrics.get("queue");
        assertEquals(2, queue.get("sampledRuns"));
        assertEquals(3500L, queue.get("averageQueueWaitMillis"));
        assertEquals(4000L, queue.get("maxQueueWaitMillis"));
        @SuppressWarnings("unchecked")
        Map<String, Object> leases = (Map<String, Object>) metrics.get("leases");
        assertEquals(1, leases.get("activeLeases"));
        @SuppressWarnings("unchecked")
        Map<String, Object> attempts = (Map<String, Object>) metrics.get("attempts");
        assertEquals(1, attempts.get("sampledAttempts"));
        assertEquals(2000L, attempts.get("averageAttemptDurationMillis"));
        assertEquals(0, attempts.get("sampledRuntimeAttempts"), "本夹具未播种 Runtime attempt，采样数必须为 0");
        assertNull(attempts.get("averageTtfbMillis"), "无 TTFB 数据必须是 null，不是 0");
        @SuppressWarnings("unchecked")
        Map<String, Object> variability = (Map<String, Object>) metrics.get("variability");
        assertEquals(1, variability.get("blockedRuns"));
        assertEquals(1, variability.get("recoveredRuns"));
        @SuppressWarnings("unchecked")
        Map<String, Object> policyBlock = (Map<String, Object>) metrics.get("policy");
        assertEquals(60L, policyBlock.get("leaseTtlSeconds"));
    }

    @Test
    void metricsRespectProjectFilterAndEmptyProjectReturnsNullAggregates() {
        Map<String, Object> filtered = metricsService.metrics(projectId);
        @SuppressWarnings("unchecked")
        Map<String, Object> queue = (Map<String, Object>) filtered.get("queue");
        assertEquals(2, queue.get("sampledRuns"));

        Map<String, Object> otherProject = metricsService.metrics(projectId + 999_999L);
        @SuppressWarnings("unchecked")
        Map<String, Object> otherQueue = (Map<String, Object>) otherProject.get("queue");
        assertEquals(0, otherQueue.get("sampledRuns"));
        assertNull(otherQueue.get("averageQueueWaitMillis"), "零分母必须是 null，不伪装 0");
        assertNull(otherQueue.get("maxQueueWaitMillis"));
        // 第三十轮 C8：recoveredRuns 同样必须遵守项目过滤。
        @SuppressWarnings("unchecked")
        Map<String, Object> filteredVariability = (Map<String, Object>) filtered.get("variability");
        assertEquals(1, filteredVariability.get("recoveredRuns"));
        @SuppressWarnings("unchecked")
        Map<String, Object> otherVariability = (Map<String, Object>) otherProject.get("variability");
        assertEquals(0, otherVariability.get("recoveredRuns"), "recoveredRuns 必须按项目过滤");
    }

    @Test
    void controllerEndpointServesTheSameShape() {
        Map<String, Object> fromController = controller.schedulerMetrics(null);
        assertThat(fromController).containsKeys("queue", "leases", "attempts", "variability", "policy");
    }

    private void seedRun(String runId, long createdOffsetMs, long startedOffsetMs, String resultOrigin) {
        Instant base = Instant.parse("2026-07-03T10:00:00Z");
        jdbcTemplate.update("""
            insert into ai_analysis_runs(
              run_id, project_id, target_case_kind, template_id, analysis_strategy, execution_mode,
              status, worker_status, coverage_status, total_chunks, completed_chunks, current_chunk_id,
              resume_from_chunk_id, error_summary, config_snapshot_json, rule_snapshot_json,
              skill_snapshot_json, schema_snapshot_json, input_summary_json, output_summary_json,
              actor_id, actor_name, result_origin, acceptance_state, started_at, finished_at, created_at, updated_at
            ) values (?, ?, 'interface', '', 'balanced', 'multi_pass',
              'succeeded', 'idle', 'planned', 0, 0, '', '', '', '{}', '{}', '{}', '{}', '{}', null,
              'system-dev', 'Development User', ?, 'PENDING_HUMAN_CONFIRMATION', ?, ?, ?, ?)
            """,
            runId, projectId, resultOrigin,
            Timestamp.from(base.plusMillis(startedOffsetMs)), Timestamp.from(base.plusMillis(startedOffsetMs)),
            Timestamp.from(base.plusMillis(createdOffsetMs)), Timestamp.from(base.plusMillis(startedOffsetMs)));
    }
}
