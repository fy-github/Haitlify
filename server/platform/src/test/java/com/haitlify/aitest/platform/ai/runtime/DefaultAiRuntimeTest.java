package com.haitlify.aitest.platform.ai.runtime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.haitlify.aitest.platform.ai.event.AiRunEventService;
import com.haitlify.aitest.platform.ai.gateway.AiGatewaySettingsService;
import com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings;
import com.haitlify.aitest.platform.ai.runtime.transport.AiProtocolTransportClient;
import com.haitlify.aitest.platform.ai.runtime.transport.AiTransportResult;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T06 固定执行序列钉住测试：attempt 先于 transport 落库并跨传输失败存活；预算
 * 尽时不发请求；项目不匹配在任何写入前拒绝；成功路径 finalize usage 并记 TTFB；
 * HTTP 失败按分类器归类。
 */
@SpringBootTest
@ActiveProfiles("test")
class DefaultAiRuntimeTest {

    private static final long PROJECT_ID = 987002L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AiAttemptRepository attemptsBean;

    @Autowired
    private AiExecutionLedger ledgerBean;

    @Autowired
    private AiJobService jobsBean;

    @Autowired
    private AiRunEventService eventServiceBean;

    private AiGatewaySettingsService settingsService;

    private AiProtocolTransportClient transportClient;

    private DefaultAiRuntime runtime;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from ai_runtime_attempts");
        jdbcTemplate.update("delete from ai_runtime_job_nodes");
        jdbcTemplate.update("delete from ai_runtime_jobs");
        jdbcTemplate.update("delete from projects where id = ?", PROJECT_ID);
        jdbcTemplate.update(
            "insert into projects(id, code, name, description, active, created_at, updated_at)"
                + " values (?, ?, ?, '', true, ?, ?)",
            PROJECT_ID, "RTIT-" + PROJECT_ID, "runtime-test", new java.sql.Timestamp(System.currentTimeMillis()),
            new java.sql.Timestamp(System.currentTimeMillis()));

        settingsService = mock(AiGatewaySettingsService.class);
        when(settingsService.loadDefaultTextSettings()).thenReturn(
            new ResolvedAiGatewaySettings("chat_completions", "http://127.0.0.1:1/v1", "model-x", "key-x", 5_000));
        transportClient = mock(AiProtocolTransportClient.class);
        runtime = new DefaultAiRuntime(settingsService, transportClient, attemptsBean, ledgerBean, jobsBean,
            eventServiceBean, objectMapper);
        runtime.useClock(new MutableClock(Instant.parse("2026-10-09T03:00:00Z")));
    }

    @Test
    void attemptIsPersistedBeforeTransportAndSurvivesTransportFailure() throws Exception {
        String runId = "run-transport-fail";
        AiJobService.AiJob job = claimedJob(runId);
        when(transportClient.execute(any())).thenThrow(new java.io.IOException("boom"));

        AiRuntime.RuntimeExecution result = runtime.execute(job, "node-1", request(runId, PROJECT_ID));

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.terminalReason()).isNotBlank();
        List<AiAttemptRepository.Attempt> attempts = attemptsBean.listByJob(job.jobId());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).status()).isEqualTo(AiAttemptRepository.STATUS_FAILED);
        assertThat(attempts.get(0).errorCategory()).isNotBlank();
        verify(transportClient, org.mockito.Mockito.times(1)).execute(any());
    }

    @Test
    void deadlineExhaustedSkipsTransportEntirely() throws Exception {
        String runId = "run-deadline";
        AiJobService.AiJob job = claimedJob(runId);
        // 相对 runtime 的冻结时钟（2026-10-09T03:00:00Z）已过去的整体截止。
        ledgerBean.open(runId, Instant.parse("2026-10-09T02:59:00Z"));

        AiRuntime.RuntimeExecution result = runtime.execute(job, "node-1", request(runId, PROJECT_ID));

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.terminalReason()).isEqualTo("run_deadline_exceeded");
        verify(transportClient, never()).execute(any());
        List<AiAttemptRepository.Attempt> attempts = attemptsBean.listByJob(job.jobId());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).errorCategory()).isEqualTo("RUN_DEADLINE_EXCEEDED");
        assertThat(attempts.get(0).status()).isEqualTo(AiAttemptRepository.STATUS_FAILED);
    }

    @Test
    void projectMismatchIsBlockedBeforeAnyWrite() throws Exception {
        String runId = "run-mismatch";
        AiJobService.AiJob job = claimedJob(runId);

        AiRuntime.RuntimeExecution result = runtime.execute(job, "node-1", request(runId, 999_999L));

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.terminalReason()).isEqualTo("project_mismatch");
        verify(transportClient, never()).execute(any());
        assertThat(attemptsBean.listByJob(job.jobId())).isEmpty();
    }

    @Test
    void successPathFinalizesUsageAndRecordsFirstToken() throws Exception {
        String runId = "run-success";
        AiJobService.AiJob job = claimedJob(runId);
        when(transportClient.execute(any())).thenReturn(new AiTransportResult(
            200,
            new AiProtocolResponse("{\"ok\":true}", "stop", null, List.of(),
                new AiProtocolResponse.Usage(11, 22), "pr-9", false),
            300L, 120L));

        AiRuntime.RuntimeExecution result = runtime.execute(job, "node-1", request(runId, PROJECT_ID));

        assertThat(result.status()).isEqualTo("succeeded");
        assertThat(result.outputJson()).contains("ok");
        assertThat(result.providerRequestId()).isEqualTo("pr-9");
        AiAttemptRepository.Attempt attempt = attemptsBean.listByJob(job.jobId()).get(0);
        assertThat(attempt.status()).isEqualTo(AiAttemptRepository.STATUS_SUCCEEDED);
        assertThat(attempt.providerRequestId()).isEqualTo("pr-9");
        assertThat(attempt.usageKnown()).isTrue();
        assertThat(attempt.inputTokens()).isEqualTo(11);
        assertThat(attempt.outputTokens()).isEqualTo(22);
        assertThat(attempt.ttfbMs()).isEqualTo(120L);
        assertThat(jobsBean.find(job.jobId()).orElseThrow().firstTokenAt()).isNotNull();
    }

    @Test
    void httpErrorIsClassifiedAndAttemptFailed() throws Exception {
        String runId = "run-http-503";
        AiJobService.AiJob job = claimedJob(runId);
        when(transportClient.execute(any())).thenReturn(new AiTransportResult(
            503,
            new AiProtocolResponse(null, null, null, List.of(), null, "", false),
            15L, null));

        AiRuntime.RuntimeExecution result = runtime.execute(job, "node-1", request(runId, PROJECT_ID));

        assertThat(result.status()).isEqualTo("failed");
        AiAttemptRepository.Attempt attempt = attemptsBean.listByJob(job.jobId()).get(0);
        assertThat(attempt.httpStatus()).isEqualTo(503);
        assertThat(attempt.errorCategory()).isEqualTo("UPSTREAM_ERROR");
        assertThat(attempt.status()).isEqualTo(AiAttemptRepository.STATUS_FAILED);
    }

    // ------------------------------------------------------------------ helpers

    private AiJobService.AiJob claimedJob(String runId) {
        jobsBean.submit("requirement_analysis", PROJECT_ID, "actor-1", "idem-" + runId, "hash-" + runId,
            List.of("node-1"), "rev-1");
        return jobsBean.claim("worker-runtime", Duration.ofSeconds(120)).orElseThrow();
    }

    private AiRuntimeModels.AiTaskRequest request(String runId, long projectId) {
        try {
            return new AiRuntimeModels.AiTaskRequest("requirement_analysis", projectId, "actor-1", runId,
                null, null, null, List.of("ev-1"),
                objectMapper.readTree("{\"requirement\":\"需求文本\",\"evidenceIds\":[\"ev-1\"]}"),
                "idem-" + UUID.randomUUID());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
