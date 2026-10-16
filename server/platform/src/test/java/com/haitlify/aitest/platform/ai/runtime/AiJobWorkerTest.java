package com.haitlify.aitest.platform.ai.runtime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.haitlify.aitest.platform.ai.event.AiRunEventService;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T06 worker 测试：全节点执行与重启跳过已完成节点、节点边界取消停机、节点失败
 * 快速收尾、恢复时未知副作用节点 BLOCKED（绝不重放）与无副作用节点重排队。
 */
@SpringBootTest
@ActiveProfiles("test")
class AiJobWorkerTest {

    private static final long PROJECT_ID = 987003L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AiRunEventService eventService;

    private MutableClock clock;

    private AiJobService jobs;

    private AiRuntime runtime;

    private AiJobWorker worker;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-09T04:00:00Z"));
        jobs = new AiJobService(jdbcTemplate, objectMapper, clock);
        runtime = mock(AiRuntime.class);
        worker = new AiJobWorker(jobs, runtime, eventService, job -> null, false, 120);
        jdbcTemplate.update("delete from ai_runtime_attempts");
        jdbcTemplate.update("delete from ai_runtime_job_nodes");
        jdbcTemplate.update("delete from ai_runtime_jobs");
        jdbcTemplate.update("delete from projects where id = ?", PROJECT_ID);
        jdbcTemplate.update(
            "insert into projects(id, code, name, description, active, created_at, updated_at)"
                + " values (?, ?, ?, '', true, ?, ?)",
            PROJECT_ID, "WKIT-" + PROJECT_ID, "job-worker-test", new java.sql.Timestamp(System.currentTimeMillis()),
            new java.sql.Timestamp(System.currentTimeMillis()));
    }

    @Test
    void happyPathCompletesAllNodesAndThenThereIsNothingToClaim() {
        String jobId = submit(List.of("step-1", "step-2"));
        when(runtime.execute(any(), eq("step-1"), any())).thenReturn(success("attempt-1"));
        when(runtime.execute(any(), eq("step-2"), any())).thenReturn(success("attempt-2"));

        AiJobWorker.WorkerResult result = worker.processOne();

        assertThat(result.outcome()).isEqualTo("succeeded");
        assertThat(result.jobId()).isEqualTo(jobId);
        assertThat(jobs.find(jobId).orElseThrow().status()).isEqualTo(AiJobService.STATUS_SUCCEEDED);
        assertThat(jobs.nodes(jobId)).extracting(AiJobService.AiJobNode::status)
            .containsExactly(AiJobService.NODE_SUCCEEDED, AiJobService.NODE_SUCCEEDED);
        assertThat(jobs.nodes(jobId)).extracting(AiJobService.AiJobNode::outputRef)
            .containsExactly("attempt-1", "attempt-2");
        assertThat(worker.processOne().outcome()).isEqualTo("none");
    }

    @Test
    void resumeSkipsCompletedNodesAndOnlyRunsTheRemainder() {
        String jobId = submit(List.of("step-1", "step-2"));
        long fenceOld = jobs.claim("worker-old", Duration.ofSeconds(30)).orElseThrow().fencingToken();
        jobs.startNode(jobId, "step-1", "attempt-old", AiJobService.SIDE_EFFECT_NONE, fenceOld);
        jobs.completeNode(jobId, "step-1", "attempt-old", fenceOld);
        // 模拟重启：旧持有者消失，租约过期后接管。
        clock.advance(Duration.ofSeconds(31));
        worker.recoverExpiredJobs();
        when(runtime.execute(any(), eq("step-2"), any())).thenReturn(success("attempt-2"));

        AiJobWorker.WorkerResult result = worker.processOne();

        assertThat(result.outcome()).isEqualTo("succeeded");
        verify(runtime, times(1)).execute(any(), eq("step-2"), any());
        verify(runtime, org.mockito.Mockito.never()).execute(any(), eq("step-1"), any());
        assertThat(jobs.nodes(jobId)).extracting(AiJobService.AiJobNode::status)
            .containsExactly(AiJobService.NODE_SUCCEEDED, AiJobService.NODE_SUCCEEDED);
    }

    @Test
    void cancelBetweenNodesStopsBeforeTheNextNode() {
        String jobId = submit(List.of("step-1", "step-2"));
        when(runtime.execute(any(), eq("step-1"), any())).thenAnswer(invocation -> {
            jobs.requestCancel(jobId, "actor-1");
            return success("attempt-1");
        });

        AiJobWorker.WorkerResult result = worker.processOne();

        assertThat(result.outcome()).isEqualTo("cancelled");
        assertThat(jobs.find(jobId).orElseThrow().status()).isEqualTo(AiJobService.STATUS_CANCELLED);
        verify(runtime, times(1)).execute(any(), eq("step-1"), any());
        assertThat(jobs.nodes(jobId)).extracting(AiJobService.AiJobNode::status)
            .containsExactly(AiJobService.NODE_SUCCEEDED, AiJobService.NODE_PENDING);
    }

    @Test
    void nodeFailureFailsFastAndMarksTheJobFailed() {
        String jobId = submit(List.of("step-1", "step-2"));
        when(runtime.execute(any(), eq("step-1"), any())).thenReturn(new AiRuntime.RuntimeExecution(
            jobId, "step-1", "run-x", "attempt-f", "failed", "UPSTREAM_ERROR", null, "", null));

        AiJobWorker.WorkerResult result = worker.processOne();

        assertThat(result.outcome()).isEqualTo("failed");
        assertThat(jobs.find(jobId).orElseThrow().status()).isEqualTo(AiJobService.STATUS_FAILED);
        verify(runtime, times(1)).execute(any(), eq("step-1"), any());
        assertThat(jobs.nodes(jobId)).extracting(AiJobService.AiJobNode::nodeKey, AiJobService.AiJobNode::status)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("step-1", AiJobService.NODE_FAILED),
                org.assertj.core.groups.Tuple.tuple("step-2", AiJobService.NODE_PENDING));
    }

    @Test
    void recoveryBlocksUnknownSideEffectNodesButRequeuesSafeOnes() {
        String blocked = submit(List.of("step-1"));
        long fenceCrashed = jobs.claim("worker-crashed", Duration.ofSeconds(30)).orElseThrow().fencingToken();
        jobs.startNode(blocked, "step-1", "attempt-crashed", AiJobService.SIDE_EFFECT_UNKNOWN, fenceCrashed);
        clock.advance(Duration.ofSeconds(31));
        List<String> reclaimed = worker.recoverExpiredJobs();

        assertThat(reclaimed).contains(blocked);
        assertThat(jobs.find(blocked).orElseThrow().status()).isEqualTo(AiJobService.STATUS_BLOCKED);
        verify(runtime, org.mockito.Mockito.never()).execute(any(), anyString(), any());

        String retryable = submit(List.of("step-1"));
        long fenceCrashed2 = jobs.claim("worker-crashed-2", Duration.ofSeconds(30)).orElseThrow().fencingToken();
        jobs.startNode(retryable, "step-1", "attempt-crashed", AiJobService.SIDE_EFFECT_NONE, fenceCrashed2);
        clock.advance(Duration.ofSeconds(31));
        worker.recoverExpiredJobs();
        assertThat(jobs.find(retryable).orElseThrow().status()).isEqualTo(AiJobService.STATUS_QUEUED);
        assertThat(jobs.nodes(retryable)).extracting(AiJobService.AiJobNode::status)
            .containsExactly(AiJobService.NODE_PENDING);
    }

    @Test
    void unknownTaskTypesDeclareUnknownSideEffectsSoInterruptionsBlockInsteadOfReplay() {
        // C-8 锁测：非纯模型任务（未知 taskType）的工作节点一律声明 unknown 副作用——
        // 中断后由恢复对账置 BLOCKED，绝不盲重放。
        String jobId = jobs.submit("web_case_generation", PROJECT_ID, "actor-1",
            "idem-" + java.util.UUID.randomUUID(), "hash-" + java.util.UUID.randomUUID(),
            List.of("browser-step"), "rev-1");
        when(runtime.execute(any(), eq("browser-step"), any())).thenReturn(success("attempt-x"));
        worker.processOne();
        assertThat(jobs.nodes(jobId).get(0).sideEffect()).isEqualTo(AiJobService.SIDE_EFFECT_UNKNOWN);

        // 模拟执行中崩溃 ⇒ 恢复必须 BLOCKED 而非重排队。
        String crashed = jobs.submit("web_case_generation", PROJECT_ID, "actor-1",
            "idem-" + java.util.UUID.randomUUID(), "hash-" + java.util.UUID.randomUUID(),
            List.of("browser-step"), "rev-1");
        long fence = jobs.claim("worker-crash", Duration.ofSeconds(30)).orElseThrow().fencingToken();
        jobs.startNode(crashed, "browser-step", "", AiJobService.SIDE_EFFECT_UNKNOWN, fence);
        clock.advance(Duration.ofSeconds(31));
        worker.recoverExpiredJobs();
        assertThat(jobs.find(crashed).orElseThrow().status()).isEqualTo(AiJobService.STATUS_BLOCKED);
        // 纯模型任务则按可重放重排。
        String pure = submit(List.of("step-1"));
        long fencePure = jobs.claim("worker-crash-2", Duration.ofSeconds(30)).orElseThrow().fencingToken();
        jobs.startNode(pure, "step-1", "", AiJobService.SIDE_EFFECT_NONE, fencePure);
        clock.advance(Duration.ofSeconds(31));
        worker.recoverExpiredJobs();
        assertThat(jobs.find(pure).orElseThrow().status()).isEqualTo(AiJobService.STATUS_QUEUED);
    }

    @Test
    void recoveryKeepsCancelledJobsCancelled() {
        // C-4（worker 视角）：运行中取消 + 崩溃 ⇒ 恢复保持取消终态，不再领取。
        String jobId = submit(List.of("step-1"));
        jobs.claim("worker-crash", Duration.ofSeconds(30)).orElseThrow();
        jobs.requestCancel(jobId, "actor-1");
        clock.advance(Duration.ofSeconds(31));
        worker.recoverExpiredJobs();
        assertThat(jobs.find(jobId).orElseThrow().status()).isEqualTo(AiJobService.STATUS_CANCELLED);
        assertThat(worker.processOne().outcome()).isEqualTo("none");
    }

    @Test
    void staleWorkerNeverEmitsPhantomSuccessAfterTakeover() {
        // F4 锁测：执行中发生接管 ⇒ 旧持有者 complete/completeNode 全被围栏拒 ⇒
        // 必须报 lost 且不发任何成功事件（节点与 job 状态属于新持有者）。
        String jobId = submit(List.of("step-1"));
        when(runtime.execute(any(), eq("step-1"), any())).thenAnswer(invocation -> {
            // 执行中途接管：租约（worker 120s）过期 + 新持有者领取。
            clock.advance(Duration.ofSeconds(121));
            jobs.reclaimExpired();
            jobs.reconcileNodesAfterRecovery(jobId);
            jobs.claim("worker-b", Duration.ofSeconds(30)).orElseThrow();
            return success("attempt-stale");
        });

        AiJobWorker.WorkerResult result = worker.processOne();

        assertThat(result.outcome()).isEqualTo("lost");
        Integer succeededEvents = jdbcTemplate.queryForObject(
            "select count(*) from ai_run_events where run_id = ? and stage = 'runtime_job_succeeded'",
            Integer.class, jobId);
        assertThat(succeededEvents).isZero();
        Integer lostEvents = jdbcTemplate.queryForObject(
            "select count(*) from ai_run_events where run_id = ? and stage = 'runtime_job_ownership_lost'",
            Integer.class, jobId);
        assertThat(lostEvents).isEqualTo(1);
        // 新持有者仍持锁，节点未被旧持有者伪写。
        assertThat(jobs.find(jobId).orElseThrow().status()).isEqualTo(AiJobService.STATUS_RUNNING);
    }

    @Test
    void enabledWorkerRefusesOutOfBoundsLeaseConfigurationAtConstruction() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new AiJobWorker(jobs, runtime, eventService, job -> null, true, 0))
            .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new AiJobWorker(jobs, runtime, eventService, job -> null, true, 3601))
            .isInstanceOf(IllegalArgumentException.class);
        // disabled 时不校验（CI/测试上下文安心）。
        new AiJobWorker(jobs, runtime, eventService, job -> null, false, 0);
    }

    @Test
    void staleWorkerNeverEmitsPhantomFailureAfterTakeover() {
        // R3-1 锁测：接管后旧 worker 的失败分支写全被围栏拒 ⇒ 报 lost、零 runtime_job_failed。
        String jobId = submit(List.of("step-1"));
        when(runtime.execute(any(), eq("step-1"), any())).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(121));
            jobs.reclaimExpired();
            jobs.reconcileNodesAfterRecovery(jobId);
            jobs.claim("worker-b", Duration.ofSeconds(30)).orElseThrow();
            return new AiRuntime.RuntimeExecution(jobId, "step-1", "run-x", "attempt-f",
                "failed", "UPSTREAM_ERROR", null, "", null);
        });

        AiJobWorker.WorkerResult result = worker.processOne();

        assertThat(result.outcome()).isEqualTo("lost");
        Integer failedEvents = jdbcTemplate.queryForObject(
            "select count(*) from ai_run_events where run_id = ? and stage = 'runtime_job_failed'",
            Integer.class, jobId);
        assertThat(failedEvents).isZero();
        Integer lostEvents = jdbcTemplate.queryForObject(
            "select count(*) from ai_run_events where run_id = ? and stage = 'runtime_job_ownership_lost'",
            Integer.class, jobId);
        assertThat(lostEvents).isEqualTo(1);
        assertThat(jobs.find(jobId).orElseThrow().status()).isEqualTo(AiJobService.STATUS_RUNNING);
    }

    @Test
    void staleWorkerNeverEmitsPhantomCancelAfterTakeover() {
        // R3-1 锁测（取消分支变体）：接管后旧 worker 的取消写被拒 ⇒ 报 lost、零 runtime_job_cancelled。
        String jobId = submit(List.of("step-1"));
        when(runtime.execute(any(), eq("step-1"), any())).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(121));
            jobs.reclaimExpired();
            jobs.reconcileNodesAfterRecovery(jobId);
            jobs.claim("worker-b", Duration.ofSeconds(30)).orElseThrow();
            return new AiRuntime.RuntimeExecution(jobId, "step-1", "run-x", "attempt-c",
                "cancelled", "CANCELLED", null, "", null);
        });

        AiJobWorker.WorkerResult result = worker.processOne();

        assertThat(result.outcome()).isEqualTo("lost");
        Integer cancelEvents = jdbcTemplate.queryForObject(
            "select count(*) from ai_run_events where run_id = ? and stage = 'runtime_job_cancelled'",
            Integer.class, jobId);
        assertThat(cancelEvents).isZero();
        Integer lostEvents = jdbcTemplate.queryForObject(
            "select count(*) from ai_run_events where run_id = ? and stage = 'runtime_job_ownership_lost'",
            Integer.class, jobId);
        assertThat(lostEvents).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private String submit(List<String> nodePlan) {
        return jobs.submit("requirement_analysis", PROJECT_ID, "actor-1",
            "idem-" + java.util.UUID.randomUUID(), "hash-" + java.util.UUID.randomUUID(), nodePlan, "rev-1");
    }

    private static AiRuntime.RuntimeExecution success(String attemptId) {
        return new AiRuntime.RuntimeExecution("", "", "", attemptId, "succeeded", "", "{\"ok\":true}", "", null);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration by) {
            instant = instant.plus(by);
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
